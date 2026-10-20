package com.zju.courtgrab.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zju.courtgrab.MainActivity
import com.zju.courtgrab.core.Logs

/**
 * 仅 debug 构建：允许通过 adb 广播触发调试动作，便于在真机上无人值守地跑流程。
 * adb shell am broadcast -n com.zju.courtgrab/.debug.DebugTriggerReceiver \
 *   -a com.zju.courtgrab.debug.TRIGGER --es action selfcheck|start|login|dryrun_on|dryrun_off
 */
class DebugTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action").orEmpty()
        Logs.init(context)
        Logs.i("DebugTrigger", "收到调试动作：$action")

        // 开关类调试动作：直接在 receiver 内完成，不拉起界面
        if (action in setOf("enable_on", "enable_off", "dryrun_on", "dryrun_off")) {
            runCatching {
                val prefs = com.zju.courtgrab.core.Prefs.get(context)
                when (action) {
                    "enable_on" -> {
                        prefs.enabled = true
                        val ok = com.zju.courtgrab.alarm.AlarmScheduler.schedule(context, prefs)
                        Logs.i("DebugTrigger", "已启用每日自动，排闹钟=$ok")
                    }
                    "enable_off" -> {
                        prefs.enabled = false
                        com.zju.courtgrab.alarm.AlarmScheduler.cancel(context)
                        Logs.i("DebugTrigger", "已关闭每日自动")
                    }
                    "dryrun_on" -> {
                        prefs.dryRun = true
                        Logs.i("DebugTrigger", "演练模式=开")
                    }
                    "dryrun_off" -> {
                        prefs.dryRun = false
                        Logs.i("DebugTrigger", "演练模式=关（会真实下单）")
                    }
                }
            }.onFailure { Logs.w("DebugTrigger", "开关调试失败：${it.message}") }
            return
        }

        // 不打扰用户的通知类调试：直接在本 receiver 里完成，不拉起界面
        if (action == "prep_notify" || action == "prep_notify_check") {
            runCatching {
                val prefs = com.zju.courtgrab.core.Prefs.get(context)
                val cfg = com.zju.courtgrab.core.ConfigRepo.load(context)
                val date = com.zju.courtgrab.core.DatePlanner.resolveTargetDate(
                    java.time.LocalDateTime.now(), cfg.dateCfg, cfg.releaseHour, cfg.releaseMinute,
                )
                val dateText = if (date != null) {
                    "${date.monthValue}/${date.dayOfMonth} ${com.zju.courtgrab.core.DatePlanner.weekdayCn(date.dayOfWeek)}"
                } else "?"
                val exp = if (prefs.venueToken.isBlank()) null
                else com.zju.courtgrab.alarm.JwtUtil.expiryMs(prefs.venueToken)
                val tokenText = com.zju.courtgrab.core.PrepReminder.tokenText(
                    prefs.venueToken, exp, System.currentTimeMillis(),
                )
                com.zju.courtgrab.util.Notify.showPrepReminder(
                    context,
                    com.zju.courtgrab.core.PrepReminder.title(dateText),
                    com.zju.courtgrab.core.PrepReminder.body(
                        dateText, tokenText, prefs.inviteCode.length == 5, prefs.directMode,
                    ),
                )
                Logs.i("DebugTrigger", "已发送准备提醒（调试）：$dateText / $tokenText")
            }.onFailure { Logs.w("DebugTrigger", "准备提醒失败：${it.message}") }
            return
        }
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("debug_action", action)
        }
        runCatching { context.startActivity(launch) }
    }
}
