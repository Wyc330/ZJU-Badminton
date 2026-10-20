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

        // ② 锁屏检查：安全锁屏无法由应用代解，9:00 流程会卡在拉起目标 App
        runCatching {
            val km = context.getSystemService(android.app.KeyguardManager::class.java)
            if (km != null && km.isKeyguardLocked) {
                Logs.w(TAG, "手机处于锁屏状态，尝试自动解锁（非安全锁屏可解）")
                runCatching {
                    context.startActivity(
                        Intent(context, com.zju.courtgrab.ui.UnlockActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                Notify.showPrepReminder(
                    context,
                    "请解锁手机",
                    "9:00 自动抢场地需要手机处于解锁状态（安全锁屏应用无法代解）。" +
                        "请现在解锁一次；解锁后到 9 点之间不会再自动锁回去。",
                )
            } else {
                Logs.i(TAG, "屏幕已解锁")
            }
        }.onFailure { Logs.w(TAG, "锁屏检查失败：${it.message}") }

        // ③ 无障碍检查：夜间被系统/手机管家杀掉的话，早上流程会直接失败，提前提醒用户重开
        runCatching {
            if (!com.zju.courtgrab.util.Perms.isA11yEnabled(context)) {
                Logs.w(TAG, "无障碍服务未开启，提醒用户")
                Notify.showPrepReminder(
                    context,
                    "无障碍服务未开启",
                    "今天的自动抢场地需要无障碍服务：打开「抢场地」→「运行状态」→「去开启」，开好后回来即可。",
                )
            } else {
                Logs.i(TAG, "无障碍服务状态正常")
            }
        }.onFailure { Logs.w(TAG, "无障碍检查失败：${it.message}") }

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
