package com.zju.courtgrab.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.app.NotificationManagerCompat
import com.zju.courtgrab.a11y.VenueAccessibilityService
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.core.Prefs

/** 权限 / 系统设置检查与跳转（含 MIUI 的几个私人入口） */
object Perms {

    private const val TAG = "Perms"

    fun isA11yEnabled(context: Context): Boolean {
        if (VenueAccessibilityService.instance != null) return true
        val enabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull().orEmpty()
        if (TextUtils.isEmpty(enabled)) return false
        val me = "${context.packageName}/${VenueAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isIgnoringBattery(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun areNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * 打开系统「无障碍」设置。
     *
     * 部分 MIUI / HyperOS 机型上直接发 ACTION_ACCESSIBILITY_SETTINGS 会跳错页面
     * （实测有跳到 WLAN 设置的），所以这里：
     *   1) 先解析并打印真实会跳去的 Activity（日志里能看到）
     *   2) 解析不到 / 启动失败时，依次尝试 AOSP 的显式组件
     *   3) 还不行就退到「应用详情」页，并提示手动路径
     */
    fun openA11ySettings(context: Context) {
        val action = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved = runCatching { action.resolveActivity(context.packageManager) }.getOrNull()
        val target = resolved?.let { "${it.packageName}/${it.className}" } ?: "(未解析到)"
        Logs.i(TAG, "无障碍设置跳转目标：$target")
        if (runCatching { context.startActivity(action) }.isSuccess) return

        val components = listOf(
            ComponentName("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity"),
            ComponentName("com.android.settings", "com.android.settings.accessibility.AccessibilitySettingsActivity"),
            ComponentName("com.android.settings", "com.android.settings.MiuiAccessibilitySettingsActivity"),
        )
        for (cn in components) {
            val it = Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(it) }.isSuccess) {
                Logs.i(TAG, "改用显式组件打开无障碍设置：$cn")
                return
            }
        }
        Logs.w(TAG, "无法直接打开无障碍设置，退到应用详情页")
        safeStart(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}")),
        )
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:${context.packageName}"))
        safeStart(context, intent) {
            safeStart(context, Intent(Settings.ACTION_SETTINGS))
        }
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .setData(Uri.parse("package:${context.packageName}"))
        safeStart(context, intent) {
            safeStart(context, Intent(Settings.ACTION_SETTINGS))
        }
    }

    fun openBatterySettings(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        safeStart(context, intent) {
            safeStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        safeStart(context, intent) {
            safeStart(context, Intent(Settings.ACTION_SETTINGS))
        }
    }

    /**
     * MIUI / HyperOS 自启动管理。
     * 这些页面在 MIUI 上是私有 Activity，不同版本名字不一样，逐个试。
     */
    fun openAutostart(context: Context) {
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.securitycenter" to "com.miui.powercenter.PowerSettings",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        )
        for ((pkg, cls) in candidates) {
            val intent = Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                if (safeStart(context, intent)) return
            }
        }
        // 都打不开就退回应用详情页
        safeStart(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}")),
        )
    }

    private fun safeStart(context: Context, intent: Intent, fallback: (() -> Unit)? = null): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            fallback?.invoke()
            false
        }
    }

    /** 给界面显示用的一句话状态 */
    fun batteryHint(prefs: Prefs): String = ""
}
