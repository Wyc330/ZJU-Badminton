package com.zju.courtgrab.direct

import com.zju.courtgrab.core.DatePlanner
import com.zju.courtgrab.core.GrabConfig
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.core.Prefs
import com.zju.courtgrab.core.SlotGridGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    /** 同伴码相关拒绝：一次性码已失效/填错，重试也没用，必须让用户找同伴重新要码 */
    private val BUDDY_CODE_HINTS = listOf("同伴码", "同伴信息", "同伴", "buddy")
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
        val tokenExp = com.zju.courtgrab.alarm.JwtUtil.expiryMs(prefs.venueToken)
        if (tokenExp != null && tokenExp <= System.currentTimeMillis()) {
            Logs.w(TAG, "登录态已过期，需要重新登录后再直连；本次回退界面流程")
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

        val buddy = runCatching { pickBuddy(client, cfg, prefs.companionName) }.getOrElse {
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
                val detail = "$date ${row.first} 场地#$spaceId"
                prefs.lastDirectDetail = detail
                Logs.i(TAG, "✅ 直连下单成功（第 $attempt 轮，$detail）：${resp.toString().take(300)}")
                client.moduleAction("venue", "submit")
                return true
            } catch (e: VenueUnauthorized) {
                Logs.w(TAG, "登录态失效，回退界面流程：${e.message}")
                return false
            } catch (e: VenueApiError) {
                val msg = e.message ?: ""
                lastNote = "服务端拒绝（code=${e.code}）：$msg"
                when {
                    msg.containsAny(BUDDY_CODE_HINTS) -> {
                        Logs.w(TAG, "同伴码校验失败（$msg）：同伴码是一次性的，请让同伴重新生成后填入，本次直连停止")
                        return false
                    }
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

    private fun pickBuddy(client: VenueClient, cfg: GrabConfig, prefsHint: String): Pair<Long, Long?>? {
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
            val want = prefsHint.ifBlank { cfg.companionName }
            if (want.isNotBlank() && name.contains(want)) fallback = pair
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

    /**
     * 「直连自检」：在界面上一键验证 登录态 / 场馆 id / day/info 解析 / 同伴，返回人话结果。
     * 不提交订单，可以随时点。
     */
    suspend fun selfCheck(prefs: Prefs, cfg: GrabConfig): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val client = VenueClient(prefs)
        if (!client.isLoggedIn()) {
            return@withContext "❌ 未登录场馆系统：请先点「登录场馆系统」登录一次"
        }
        sb.appendLine("① 登录态：已保存（${prefs.venueToken.take(6)}…${prefs.venueToken.takeLast(4)}）")
        val now = LocalDateTime.now()
        val date = DatePlanner.resolveTargetDate(now, cfg.dateCfg, cfg.releaseHour, cfg.releaseMinute)
        sb.appendLine("② 目标日期：${date ?: "算不出（检查 dateCfg）"}")
        val venueSiteId = try {
            resolveVenueSiteId(client, cfg)
        } catch (e: VenueUnauthorized) {
            return@withContext "❌ 登录态已失效，请重新登录场馆系统：${e.message}"
        } catch (e: Throwable) {
            return@withContext "❌ 场馆信息接口失败：${e.message}"
        }
        if (venueSiteId == null) {
            return@withContext "❌ 没找到「${cfg.directVenueName}」的场馆 id：可在配置里填 directVenueSiteId"
        }
        sb.appendLine("③ 场馆 id：$venueSiteId（${cfg.directVenueName}）")
        val day = try {
            client.dayInfo(venueSiteId, date.toString())
        } catch (e: VenueUnauthorized) {
            return@withContext "❌ 登录态已失效，请重新登录场馆系统：${e.message}"
        } catch (e: Throwable) {
            return@withContext "❌ day/info 接口失败（可能还没到放号时间）：${e.message}"
        }
        val preferredMinute = SlotGridGeometry.parseStartMinute(prefs.preferredSlotStart.ifBlank { cfg.preferredSlotStart })
        val row = pickTimeRow(day, preferredMinute)
        val spaces = spaceIds(day)
        val timeCount = day.optJSONArray("spaceTimeInfo")?.length() ?: 0
        sb.appendLine("④ 时段数：$timeCount，选中 timeId：${row?.first ?: "未解析出"}")
        sb.appendLine("⑤ 场地数：${spaces.size}")
        val buddy = runCatching { pickBuddy(client, cfg, prefs.companionName) }.getOrNull()
        sb.appendLine("⑥ 同伴：${buddy?.first ?: "未找到（界面可填同伴姓名）"}")
        sb.appendLine("⑦ 同伴码：${if (prefs.inviteCode.length == 5) "已填 5 位" else "未填"}")
        sb.appendLine("⑧ 演练模式：${if (prefs.dryRun) "开（不会真下单）" else "关（9:00 会真下单）"}")
        sb.appendLine()
        sb.appendLine(
            if (row != null && spaces.isNotEmpty()) "✅ 直连链路可用，09:00:00 会直接下单"
            else "⚠️ 解析异常：放号前 day/info 可能还没数据，9:00 后点自检更准"
        )
        sb.toString()
    }

    /**
     * 拉取同伴列表（需要登录态），返回 (id, 名字)，供界面「选择同伴」用。
     * 只读接口，不修改任何数据。
     */
    suspend fun buddyList(prefs: Prefs): List<Pair<Long, String>> = withContext(Dispatchers.IO) {
        val client = VenueClient(prefs)
        if (!client.isLoggedIn()) return@withContext emptyList()
        val out = LinkedHashMap<Long, String>()
        val root = client.buddies()
        walk(root) { obj ->
            val id = obj.optLong("id", -1L)
            if (id <= 0) return@walk
            var name = obj.optString("name", "")
            if (name.isBlank()) name = obj.optString("nickName", "")
            if (name.isBlank()) name = obj.optString("userName", "")
            if (name.isBlank()) name = obj.optString("buddyName", "")
            if (name.isNotBlank()) out.putIfAbsent(id, name)
        }
        out.map { it.key to it.value }
    }
}
