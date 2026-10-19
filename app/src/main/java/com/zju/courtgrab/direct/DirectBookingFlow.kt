package com.zju.courtgrab.direct

import com.zju.courtgrab.core.DatePlanner
import com.zju.courtgrab.core.GrabConfig
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.core.Prefs
import com.zju.courtgrab.core.SlotGridGeometry
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * 直连下单：09:00:00 直接调用场馆系统接口完成预约。
 *
 * 拟真设计（避免出现"异常请求"）：
 *  - 请求头与 H5 完全一致（UA/Origin/Referer/app-key/timestamp/sign/cgAuthorization）
 *  - 请求顺序与 H5 一致：campus→day→buddies→check_buddy_no→submit，并补一次 module_action
 *  - 只在放号窗口内动作；提交成功即停，失败最多带 200~600ms 抖动重试 1 次，绝不轮询
 *  - 放号前的查询（场馆/同伴/同伴码校验）提前做，属于正常页面加载行为
 *
 * 返回：true = 已成功提交；false = 未处理/失败（调用方回退 UI 自动化）
 */
object DirectBookingFlow {

    private const val TAG = "Direct"

    suspend fun execute(prefs: Prefs, cfg: GrabConfig): Boolean {
        if (!prefs.directMode) return false
        val client = VenueClient(prefs)
        if (!client.isLoggedIn()) {
            Logs.w(TAG, "未登录场馆系统（token 为空），回退界面流程")
            return false
        }

        val now = LocalDateTime.now()
        val date = DatePlanner.windowCandidates(
            now, cfg.dateCfg, cfg.dateCfg.maxDaysAhead,
            cfg.requirePreferredWeekday, cfg.preferFreshDates,
        ).firstOrNull()
        if (date == null) {
            Logs.w(TAG, "预约窗口内没有目标日期，回退界面流程")
            return false
        }
        Logs.i(TAG, "直连目标日期：$date")

        // ── 1) 放号前的准备（等同 H5 打开页面时的查询）──
        val venueSiteId = runCatching { resolveVenueSiteId(client, cfg) }.getOrElse {
            Logs.w(TAG, "解析风雨操场 id 失败：${it.message}，回退界面流程")
            return false
        } ?: run {
            Logs.w(TAG, "没找到「${cfg.directVenueName}」的场馆 id，回退界面流程")
            return false
        }
        Logs.i(TAG, "场馆 id = $venueSiteId")

        val buddy = runCatching { pickBuddy(client, cfg) }.getOrElse {
            Logs.w(TAG, "查询同伴失败：${it.message}")
            null
        }
        val buddyNo = prefs.inviteCode.trim().filter { it.isDigit() }
        if (buddy != null && buddyNo.length == 5) {
            runCatching { client.checkBuddyNo(buddy.first, buddy.second, buddyNo) }
                .onSuccess { Logs.i(TAG, "同伴码预校验通过（${buddy.first}）") }
                .onFailure { Logs.w(TAG, "同伴码预校验未通过（继续按 H5 流程提交时会再校验）：${it.message}") }
        }
        client.moduleAction("venue", "open")

        // ── 2) 等到放号瞬间 ──
        val release = now.toLocalDate().atTime(cfg.releaseHour, cfg.releaseMinute)
        val waitMs = ChronoUnit.MILLIS.between(LocalDateTime.now(), release)
        if (waitMs > 0) {
            Logs.i(TAG, "等到放号时刻：${cfg.releaseHour}:%02d（还有 ${waitMs}ms）".format(cfg.releaseMinute))
            delay(waitMs)
        }
        val shotAt = System.currentTimeMillis()

        // ── 3) 放号后：拉当天时段表 → 提交 ──
        var lastError: Throwable? = null
        for (attempt in 1..2) {
            try {
                val day = client.dayInfo(venueSiteId, date.toString())
                val orderToken = day.optString("token", "")
                val (timeId, spaceId) = pickCell(day, prefs.preferredSlotStart, cfg)
                    ?: throw IllegalStateException("day/info 里没解析出时段/场地（结构与你之前发我的一致吗？）")
                Logs.i(TAG, "选中 timeId=$timeId spaceId=$spaceId，下单 token=${orderToken.take(8)}…")

                val orderJson = JSONArray().put(
                    JSONObject()
                        .put("spaceId", spaceId)
                        .put("timeId", timeId)
                        .put("venueSpaceGroupId", JSONObject.NULL),
                ).toString()

                val payload = linkedMapOf<String, Any?>(
                    "venueSiteId" to venueSiteId,
                    "reservationDate" to date.toString(),
                    "reservationOrderJson" to orderJson,
                    "phone" to null,
                    "buddyIds" to buddy?.first?.toString(),
                    "weekStartDate" to null,
                    "reservationType" to null,
                    "isOfflineTicket" to null,
                    "schoolType" to null,
                    "tradeNo" to null,
                    "token" to orderToken,
                )
                if (buddyNo.length == 5) payload["buddyNo"] = buddyNo

                if (prefs.dryRun) {
                    Logs.i(TAG, "【演练】不下单。payload=${JSONObject(payload as Map<*, *>? ?: emptyMap<String, Any?>())}")
                    return false
                }

                val resp = client.submit(payload)
                val cost = System.currentTimeMillis() - shotAt
                Logs.i(TAG, "✅ 直连下单成功（${cost}ms）：${resp.toString().take(300)}")
                client.moduleAction("venue", "submit")
                return true
            } catch (e: VenueUnauthorized) {
                Logs.w(TAG, "登录态失效，回退界面流程：${e.message}")
                return false
            } catch (e: VenueApiError) {
                // 业务拒绝（例如已被约满/规则限制）：不重试，直接回退 UI，避免异常请求
                Logs.w(TAG, "服务端拒绝（code=${e.code}）：${e.message}")
                return false
            } catch (e: Throwable) {
                lastError = e
                Logs.w(TAG, "第 $attempt 次直连失败：${e.message}")
                if (attempt == 1) delay(200L + Random.nextLong(400))
            }
        }
        Logs.w(TAG, "直连未成功（${lastError?.message}），回退界面流程")
        return false
    }

    // ───────────────────────── 解析 ─────────────────────────

    private fun resolveVenueSiteId(client: VenueClient, cfg: GrabConfig): Long? {
        if (cfg.directVenueSiteId > 0) return cfg.directVenueSiteId
        val root = client.campusVenueInfo()
        var best: Long? = null
        walk(root) { obj ->
            if (best != null) return@walk
            val name = nameOf(obj) ?: return@walk
            if (!name.contains(cfg.directVenueName)) return@walk
            for (key in listOf("venueSiteId", "id", "venueId", "siteId")) {
                val v = obj.optLong(key, -1L)
                if (v > 0) {
                    best = v
                    return@walk
                }
            }
        }
        return best
    }

    private fun pickBuddy(client: VenueClient, cfg: GrabConfig): Pair<Long, Long?>? {
        val root = client.buddies()
        var fallback: Pair<Long, Long?>? = null
        walk(root) { obj ->
            val id = obj.optLong("id", -1L)
            if (id <= 0) return@walk
            val name = obj.optString("name", "") + obj.optString("nickName", "") +
                obj.optString("userName", "") + obj.optString("buddyName", "")
            val roleId = if (obj.has("buddyRoleId")) obj.optLong("buddyRoleId") else
                if (obj.has("roleId")) obj.optLong("roleId") else null
            val pair = id to roleId
            if (fallback == null) fallback = pair
            if (cfg.companionName.isNotBlank() && name.contains(cfg.companionName)) fallback = pair
        }
        return fallback
    }

    /** 返回 (timeId, spaceId)：优先用户指定时段，否则去掉首尾取中间 */
    private fun pickCell(day: JSONObject, preferred: String, cfg: GrabConfig): Pair<String, String>? {
        val rows = day.optJSONArray("spaceTimeInfo") ?: return null
        if (rows.length() == 0) return null

        val preferredMinute = SlotGridGeometry.parseStartMinute(preferred)
        val parsed = ArrayList<Triple<Int, String, String?>>() // minute, timeId, rawId
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            var timeId = row.optString("id", "")
            if (timeId.isBlank()) timeId = row.optString("timeId", "")
            if (timeId.isBlank()) continue
            val minute = minuteOf(row) ?: -1
            parsed += Triple(minute, timeId, null)
        }
        if (parsed.isEmpty()) return null
        val chosen = when {
            preferredMinute != null -> parsed.minByOrNull { kotlin.math.abs(it.first - preferredMinute) }
            else -> {
                val inner = parsed.filterIndexed { i, _ -> i > 0 && i < parsed.size - 1 }.ifEmpty { parsed }
                inner[inner.size / 2]
            }
        } ?: return null

        // 场地：当天该日期下的第一个场地（放号瞬间全部空闲，任取一片）
        val spaceId = firstSpaceId(day) ?: return null
        return chosen.second to spaceId
    }

    private fun firstSpaceId(day: JSONObject): String? {
        val byDate = day.optJSONObject("reservationDateSpaceInfo") ?: return null
        val dateKeys = byDate.keys().asSequence().toList()
        for (key in dateKeys) {
            val arr = byDate.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val space = arr.optJSONObject(i) ?: continue
                val id = space.optString("id", "").ifBlank { space.optString("spaceId", "") }
                if (id.isNotBlank()) return id
            }
        }
        return null
    }

    private fun minuteOf(row: JSONObject): Int? {
        for (key in listOf("beginTime", "startTime", "timeBegin", "begin", "start", "time")) {
            val s = row.optString(key, "")
            val m = Regex("(\\d{1,2}):(\\d{2})").find(s) ?: continue
            return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        }
        for (key in listOf("beginMinute", "startMinute", "timeMinute")) {
            if (row.has(key)) return row.optInt(key)
        }
        return null
    }

    private fun nameOf(obj: JSONObject): String? {
        for (key in listOf("name", "venueName", "siteName", "venueSiteName", "spaceName", "title", "modelName")) {
            val v = obj.optString(key, "")
            if (v.isNotBlank()) return v
        }
        return null
    }

    private fun walk(node: Any?, visit: (JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> {
                visit(node)
                for (key in node.keys()) walk(node.opt(key), visit)
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), visit)
        }
    }
}
