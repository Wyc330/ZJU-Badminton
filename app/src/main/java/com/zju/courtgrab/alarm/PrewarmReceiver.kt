package com.zju.courtgrab.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.core.Prefs
import com.zju.courtgrab.direct.VenueLoginActivity
import com.zju.courtgrab.util.Notify

/**
 * 放号前 12 分钟的预热广播：
 *  · 直连模式下登录态快过期（<45 分钟）→ 尝试静默续期（点一次「统一身份认证登录」，
 *    CAS 会话还在的话免密拿到新 token；不在则通知用户手动登录）
 *  · 登录态还健康 → 什么都不做
 */
class PrewarmReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "Prewarm"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Logs.init(context)
        val prefs = Prefs.get(context)
        Logs.i(TAG, "⏰ 预热触发（放号前 12 分钟）")
        if (!prefs.enabled) {
            Logs.i(TAG, "每日自动未启用，跳过预热")
            return
        }

        // ① 先发一条「准备提醒」：目标日期 + 登录态 + 同伴码状态，一眼看清要不要动手
        runCatching {
            val cfg = com.zju.courtgrab.core.ConfigRepo.load(context)
            val date = com.zju.courtgrab.core.DatePlanner.resolveTargetDate(
                java.time.LocalDateTime.now(), cfg.dateCfg, cfg.releaseHour, cfg.releaseMinute,
            )
            val dateText = if (date != null) {
                "${date.monthValue}/${date.dayOfMonth} ${com.zju.courtgrab.core.DatePlanner.weekdayCn(date.dayOfWeek)}"
            } else "?"
            val token = prefs.venueToken
            val exp = if (token.isBlank()) null else JwtUtil.expiryMs(token)
            val tokenText = com.zju.courtgrab.core.PrepReminder.tokenText(
                token, exp, System.currentTimeMillis(),
            )
            com.zju.courtgrab.util.Notify.showPrepReminder(
                context,
                com.zju.courtgrab.core.PrepReminder.title(dateText),
                com.zju.courtgrab.core.PrepReminder.body(
                    dateText, tokenText, prefs.inviteCode.length == 5, prefs.directMode,
                ),
            )
        }.onFailure { Logs.w(TAG, "准备提醒失败：${it.message}") }

        if (!prefs.directMode) {
            Logs.i(TAG, "没开直连模式，只发提醒不续期")
            return
        }
        val token = prefs.venueToken
        if (token.isBlank()) {
            Logs.w(TAG, "还没有登录态，通知用户手动登录")
            Notify.showResult(
                context, false,
                "直连模式需要先登录场馆系统：打开「抢场地」→「登录场馆系统」登录一次（登录态 4 小时有效）",
            )
            return
        }
        val exp = JwtUtil.expiryMs(token)
        val remainMin = if (exp != null) (exp - System.currentTimeMillis()) / 60000 else Long.MAX_VALUE
        Logs.i(TAG, "当前登录态剩余 ${if (remainMin == Long.MAX_VALUE) "未知" else "$remainMin 分钟"}")
        if (exp == null || remainMin >= 45) {
            Logs.i(TAG, "登录态还够用，不需要续期")
            return
        }
        Logs.i(TAG, "登录态即将过期，尝试静默续期")
        runCatching {
            context.startActivity(
                Intent(context, VenueLoginActivity::class.java)
                    .putExtra(VenueLoginActivity.EXTRA_SILENT, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }.onFailure {
            Logs.w(TAG, "后台拉起登录页失败：${it.message}")
            Notify.showResult(context, false, "登录态即将过期，请打开「抢场地」手动续期")
        }
    }
}

/** 轻量 JWT 到期解析（不做签名校验，仅用于本地提示） */
object JwtUtil {
    fun expiryMs(token: String): Long? = runCatching {
        val parts = token.split(".")
        if (parts.size < 2) return@runCatching null
        val payload = android.util.Base64.decode(
            parts[1],
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        )
        val exp = org.json.JSONObject(String(payload, Charsets.UTF_8)).optLong("exp", 0L)
        if (exp > 0) exp * 1000 else null
    }.getOrNull()
}
