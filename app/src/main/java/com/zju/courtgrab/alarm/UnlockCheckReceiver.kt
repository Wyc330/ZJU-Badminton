package com.zju.courtgrab.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zju.courtgrab.core.DatePlanner
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.core.Prefs
import com.zju.courtgrab.core.PrepReminder
import com.zju.courtgrab.util.Notify
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 「解锁即检查」：早上你解锁手机的那一刻，如果离放号不到 90 分钟，
 * 主动推一条清单通知（登录态 / 同伴码 / 演练模式 / 目标日期），不用等到 08:47。
 *
 * 只做提醒，不做任何操作；点通知打开 App。
 */
class UnlockCheckReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "UnlockCheck"
        /** 放号前多少分钟内才提示 */
        private const val WINDOW_MINUTES = 90L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_USER_PRESENT) return
        Logs.init(context)
        val prefs = Prefs.get(context)
        if (!prefs.enabled) {
            Logs.i(TAG, "每日自动未启用，跳过解锁检查")
            return
        }
        val trigger = LocalTime.of(prefs.alarmHour, prefs.alarmMinute)
        val now = LocalDateTime.now()
        val today = now.toLocalDate().atTime(trigger)
        if (now.isAfter(today)) {
            Logs.i(TAG, "今天放号时间已过，跳过解锁检查")
            return
        }
        val minutesLeft = Duration.between(now, today).toMinutes()
        if (minutesLeft > WINDOW_MINUTES) {
            Logs.i(TAG, "距离放号还有 $minutesLeft 分钟，稍后再提示")
            return
        }

        runCatching {
            val cfg = com.zju.courtgrab.core.ConfigRepo.load(context)
            val date = DatePlanner.resolveTargetDate(now, cfg.dateCfg, cfg.releaseHour, cfg.releaseMinute)
            val dateText = if (date != null) {
                "${date.monthValue}/${date.dayOfMonth} ${DatePlanner.weekdayCn(date.dayOfWeek)}"
            } else "?"
            val token = prefs.venueToken
            val exp = if (token.isBlank()) null else JwtUtil.expiryMs(token)
            val tokenText = PrepReminder.tokenText(token, exp, System.currentTimeMillis())
            val codeText = if (prefs.inviteCode.length == 5) "已填" else "未填"
            Notify.showPrepReminder(
                context,
                "放号前检查：$dateText（还剩 $minutesLeft 分钟）",
                "登录态：$tokenText\n同伴码：$codeText\n演练模式：${if (prefs.dryRun) "开（不会真下单）" else "关（会真下单）"}\n点击打开「抢场地」补齐后再等自动运行",
            )
            Logs.i(TAG, "已发解锁检查提醒：剩余 $minutesLeft 分钟，登录态=$tokenText，同伴码=$codeText")
        }.onFailure { Logs.w(TAG, "解锁检查提醒失败：${it.message}") }
    }
}
