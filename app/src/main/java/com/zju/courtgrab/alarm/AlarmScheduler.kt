package com.zju.courtgrab.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.core.Prefs
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 每日定时。
 *
 * 用 setExactAndAllowWhileIdle（能穿透 Doze），需要 Android 12+ 的
 * 「闹钟和提醒 / SCHEDULE_EXACT_ALARM」权限；没授权时退化成
 * setAndAllowWhileIdle（可能被系统延迟几分钟），并在日志里写明。
 *
 * 每次触发后，[BookingAlarmReceiver] 会立刻把下一次排上（真·每天循环）。
 */
object AlarmScheduler {

    const val ACTION_DAILY_FIRE = "com.zju.courtgrab.action.DAILY_FIRE"

    private const val TAG = "Alarm"
    private const val REQUEST_CODE = 2001
    private val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun nextTrigger(prefs: Prefs, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val today = now.toLocalDate().atTime(prefs.alarmHour, prefs.alarmMinute, prefs.alarmSecond)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    fun schedule(context: Context, prefs: Prefs): Boolean {
        if (!prefs.enabled) {
            cancel(context)
            return false
        }
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        val next = nextTrigger(prefs)
        val millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
        val pi = pendingIntent(context)
        return runCatching {
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
                Logs.w(TAG, "未授予精确闹钟权限，使用非精确闹钟（可能被延迟）")
            }
            Logs.i(TAG, "已排下一次触发：${fmt.format(next)}（精确=$exact）")
            true
        }.getOrElse {
            Logs.e(TAG, "排闹钟失败", it)
            false
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            am.cancel(pendingIntent(context))
            Logs.i(TAG, "已取消定时")
        }
    }

    /** 给界面/日志用的一句话描述 */
    fun describeNext(prefs: Prefs): String {
        if (!prefs.enabled) return "每日自动：未启用"
        val next = nextTrigger(prefs)
        val dur = Duration.between(LocalDateTime.now(), next)
        val totalMin = dur.toMinutes()
        val remain = when {
            totalMin <= 0 -> "不到 1 分钟"
            totalMin < 60 -> "$totalMin 分钟"
            else -> "${totalMin / 60} 小时 ${totalMin % 60} 分"
        }
        return "${fmt.format(next)}（还有 $remain）"
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BookingAlarmReceiver::class.java)
            .setAction(ACTION_DAILY_FIRE)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
