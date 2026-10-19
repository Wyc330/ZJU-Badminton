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
 * 直连下单：放号瞬间直接调用场馆系统接口，并像真实用户一样「刷新 → 选场 → 提交」重试。
 *
 * 拟真设计（避免出现异常请求）：
 *  - 请求头与 H5 完全一致（UA/Origin/Referer/app-key/timestamp/sign/cgAuthorization）
 *  - 每轮重试都重新拉 day/info（等同用户刷新页面，orderToken 也是新的）
 *  - 请求串行、低频（250~650ms 一次）+ 随机抖动；不做并发、不做递增轰炸
 *  - 只在放号窗口内工作：放号前不发请求；窗口默认 120 秒，用完就停并回退界面流程
 *  - 服务端明确业务拒绝（已约满）→ 换一片场地继续；未开放/网络错误/5xx → 下一轮
 *
 * 返回：true = 已成功提交；false = 未处理/失败（调用方回退 UI 自动化）
 */
object DirectBookingFlow {

    private const val TAG = "Direct"

    private val NOT_OPEN_HINTS = listOf(
        "未开放", "未开始", "不在开放", "未到", "时间未到", "暂未", "未开启", "不可预约时间",
    )
    private val FULL_HINTS = listOf(
        "已满", "约满", "无可用", "已被预约", "已订满", "已被约", "无可预约", "库存不足",
    )

    suspend fun execute(prefs: Prefs, cfg: GrabConfig): Boolean {
        if (!prefs.directMode) return false
        val client = VenueClient(prefs)
        if (!client.isLoggedIn()) {
            Logs.w(TAG, "未登录场馆系统（token 为空），回退界面流程")
            return false
        }

        val now = LocalDateTime.now()
        val date = DatePlanner.resolveTargetDate(now, cfg.dateCfg, cfg.releaseHour, cfg.releaseMinute)
        if (date == null) {
            Logs.w(TAG, "预约窗口内没有目标日期，回退界面流程")
            return false
        }
        Logs.i(TAG, "直连目标日期：$date，时间窗 ${cfg.directMaxWaitMs / 1000}s")

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
                .onFailure { Logs.w(TAG, "同伴码预校验未通过：${it.message}") }
        }
        client.moduleAction("venue", "open")

        // ── 2) 等到放号时刻（提前 0.3s 就位，避免调度延迟）──
        val release = now.toLocalDate().atTime(cfg.releaseHour, cfg.releaseMinute)
        val waitMs = ChronoUnit.MILLIS.between(LocalDateTime.now(), release) - 300
        if (waitMs > 0) {
            Logs.i(TAG, "等到放号时刻：${cfg.releaseHour}:%02d（还有 ${waitMs}ms）".format(cfg.releaseMinute))
            delay(waitMs)
        }

        // ── 3) 放号后：轮询「刷新 day/info → 提交」，直到成功/超时 ──
        val deadline = System.currentTimeMillis() + cfg.directMaxWaitMs
        val preferredMinute = SlotGridGeometry.parseStartMinute(
            prefs.preferredSlotStart.ifBlank { cfg.preferredSlotStart },
        )
        var attempt = 0
        var spaceIndex = 0
        var lastNote = ""
        var unknownRejects = 0

        while (attempt < cfg.directMaxAttempts && System.currentTimeMillis() < deadline) {
            attempt++
            val day = try {
                client.dayInfo(venueSiteId, date.toString())
            } catch (e: VenueUnauthorized) {
                Logs.w(TAG, "登录态失效，回退界面流程：${e.message}")
                return false
            } catch (e: Throwable) {
                lastNote = "拉时段表失败：${e.message}"
                Logs.w(TAG, "第 $attempt 轮：$lastNote，稍后重试")
                jitterSleep(cfg)
                continue
            }

            val orderToken = day.optString("token", "")
            val row = pickTimeRow(day, preferredMinute)
            if (row == null) {
                lastNote = "day/info 里没解析出时段"
                Logs.w(TAG, "第 $attempt 轮：$lastNote，稍后重试")
                jitterSleep(cfg)
                continue
            }
            val spaces = spaceIds(day)
            if (spaces.isEmpty()) {
                lastNote = "day/info 里没解析出场地"
                Logs.w(TAG, "第 $attempt 轮：$lastNote，稍后重试")
                jitterSleep(cfg)
                continue
            }
            val spaceId = spaces[spaceIndex % spaces.size]
            val orderJson = JSONArray().put(
                JSONObject()
                    .put("spaceId", spaceId)
                    .put("timeId", row.first)
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
                Logs.i(TAG, "【演练】第 $attempt 轮已拿到 timeId=${row.first} spaceId=$spaceId，不下单")
                Logs.i(TAG, "【演练】payload=$payload")
                return false
            }

            try {
                val resp = client.submit(payload)
                Logs.i(TAG, "✅ 直连下单成功（第 $attempt 轮）：${resp.toString().take(300)}")
                client.moduleAction("venue", "submit")
                return true
            } catch (e: VenueUnauthorized) {
                Logs.w(TAG, "登录态失效，回退界面流程：${e.message}")
                return false
            } catch (e: VenueApiError) {
                val msg = e.message ?: ""
                lastNote = "服务端拒绝（code=${e.code}）：$msg"
                when {
                    msg.containsAny(FULL_HINTS) -> {
                        spaceIndex++
                        Logs.i(TAG, "第 $attempt 轮：该场地已满，换下一片（${spaceIndex % spaces.size + 1}/${spaces.size}）")
                    }
                    msg.containsAny(NOT_OPEN_HINTS) -> {
                        Logs.i(TAG, "第 $attempt 轮：还没开放（$msg），继续等")
                    }
                    else -> {
                        unknownRejects++
                        Logs.w(TAG, "第 $attempt 轮：$lastNote")
                        if (unknownRejects >= 3) spaceIndex++
                    }
                }
            } catch (e: Throwable) {
                lastNote = "网络异常：${e.message}"
                Logs.w(TAG, "第 $attempt 轮：$lastNote，稍后重试")
            }
            jitterSleep(cfg)
        }

        Logs.w(TAG, "直连未成功（已试 $attempt 轮，$lastNote），回退界面流程")
        return false
    }

    // ───────────────────────── 轮询节奏 ─────────────────────────

    private suspend fun jitterSleep(cfg: GrabConfig) {
        delay(cfg.directPollMinMs + Random.nextLong(cfg.directPollJitterMs.coerceAtLeast(1)))
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

    /** 时段行：(timeId, 分钟数)；优先用户指定时段，否则去掉首尾取中间 */
    private fun pickTimeRow(day: JSONObject, preferredMinute: Int?): Pair<String, Int>? {
        val rows = day.optJSONArray("spaceTimeInfo") ?: return null
        val parsed = ArrayList<Pair<String, Int>>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            var timeId = row.optString("id", "")
            if (timeId.isBlank()) timeId = row.optString("timeId", "")
            if (timeId.isBlank()) continue
            parsed += timeId to (minuteOf(row) ?: -1)
        }
        if (parsed.isEmpty()) return null
        return when {
            preferredMinute != null -> parsed.minByOrNull { kotlin.math.abs(it.second - preferredMinute) }
            else -> {
                val inner = parsed.filterIndexed { i, _ -> i > 0 && i < parsed.size - 1 }.ifEmpty { parsed }
                inner[inner.size / 2]
            }
        }
    }

    /** 该日期下所有可尝试的场地 id（用于「已约满就换一片」） */
    private fun spaceIds(day: JSONObject): List<String> {
        val out = LinkedHashSet<String>()
        val byDate = day.optJSONObject("reservationDateSpaceInfo")
        if (byDate != null) {
            for (key in byDate.keys().asSequence().toList()) {
                val arr = byDate.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val space = arr.optJSONObject(i) ?: continue
                    val id = space.optString("id", "").ifBlank { space.optString("spaceId", "") }
                    if (id.isNotBlank()) out += id
                }
            }
        }
        return out.toList()
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

    private fun String.containsAny(hints: List<String>): Boolean = hints.any { this.contains(it) }
}
