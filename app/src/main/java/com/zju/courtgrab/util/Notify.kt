package com.zju.courtgrab.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zju.courtgrab.MainActivity
import com.zju.courtgrab.R
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.ui.CodeRequestActivity

/**
 * 通知统一入口。
 *  - 流程前台通知（保活）
 *  - 到点「输入同伴码」全屏通知
 *  - 结果通知
 */
object Notify {

    private const val TAG = "Notify"
    private const val CH_FLOW = "ch_flow"
    private const val CH_ALARM = "ch_alarm"
    private const val CH_RESULT = "ch_result"
    private const val CH_RESULT_HIGH = "ch_result_high"

    const val ID_FOREGROUND = 1001
    const val ID_CODE_REQUEST = 1002
    const val ID_RESULT = 1003

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(CH_FLOW, "抢场地流程", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "抢场地进行中的常驻通知"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_ALARM, "到点提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "到点提醒你输入同伴码"
                    enableVibration(true)
                    setBypassDnd(true)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_RESULT, "结果通知", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "预约结果"
                }
            )
            // 抢场地结束时的强提醒（声音+震动+弹横幅）
            nm.createNotificationChannel(
                NotificationChannel(CH_RESULT_HIGH, "抢场地结束提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "抢场地流程结束时的明确提示"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 150, 300, 150, 500)
                    enableLights(true)
                }
            )
        }.onFailure { Logs.w(TAG, "创建通知渠道失败：${it.message}") }
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun mainIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun codeIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            1,
            Intent(context, CodeRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun buildForeground(context: Context, title: String, text: String) =
        NotificationCompat.Builder(context, CH_FLOW)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainIntent(context))
            .build()

    fun showFlowRunning(context: Context, text: String) {
        if (!canNotify(context)) return
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(ID_FOREGROUND, buildForeground(context, "抢场地进行中", text))
        }.onFailure { Logs.w(TAG, "showFlowRunning: ${it.message}") }
    }

    /** 到点提醒：全屏 Intent（用户点了直接进输入同伴码页），同时带一个普通的点按入口 */
    fun showCodeRequest(context: Context) {
        ensureChannels(context)
        if (!canNotify(context)) {
            Logs.w(TAG, "通知权限未开，无法弹「输入同伴码」通知")
            return
        }
        val builder = NotificationCompat.Builder(context, CH_ALARM)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("到点了：请输入本次 5 位同伴码")
            .setContentText("点这里输入同伴码，App 会自动继续预约流程")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(codeIntent(context))
            .setFullScreenIntent(codeIntent(context), true)
        runCatching {
            NotificationManagerCompat.from(context).notify(ID_CODE_REQUEST, builder.build())
        }.onFailure { Logs.w(TAG, "showCodeRequest: ${it.message}") }
    }

    fun showResult(context: Context, success: Boolean, text: String) {
        ensureChannels(context)
        if (!canNotify(context)) return
        val builder = NotificationCompat.Builder(context, CH_RESULT_HIGH)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (success) "✅ 抢场地完成" else "❌ 抢场地失败")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 300, 150, 300, 150, 500))
            .setAutoCancel(true)
            .setContentIntent(mainIntent(context))
        runCatching {
            NotificationManagerCompat.from(context).notify(ID_RESULT, builder.build())
        }.onFailure { Logs.w(TAG, "showResult: ${it.message}") }
    }

    /** 延时抓取完成通知（点一下回到 App 查看/分享文件） */
    fun showDumpDone(context: Context, nodeCount: Int, path: String) {
        ensureChannels(context)
        if (!canNotify(context)) return
        val builder = NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("界面抓取完成：$nodeCount 个节点")
            .setContentText("点这里回到「抢场地」，再点「分享最近一次抓取」把文件发出来")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("文件：$path\n\n回到 App 点「分享最近一次抓取」，把 JSON/TXT 发给开发者即可。")
            )
            .setAutoCancel(true)
            .setContentIntent(mainIntent(context))
        runCatching {
            NotificationManagerCompat.from(context).notify(ID_RESULT, builder.build())
        }.onFailure { Logs.w(TAG, "showDumpDone: ${it.message}") }
    }

    /** 结束震动：成功/失败不同节奏，抢场地结束时能立刻察觉 */
    fun vibrateDone(context: Context, success: Boolean) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return
            val pattern = if (success) longArrayOf(0, 300, 150, 300, 150, 500) else longArrayOf(0, 900)
            vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
        }.onFailure { Logs.w(TAG, "vibrateDone: ${it.message}") }
    }

    fun cancelFlowNotification(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_FOREGROUND) }
    }
}
