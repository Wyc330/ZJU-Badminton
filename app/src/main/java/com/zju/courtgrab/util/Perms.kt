package com.zju.courtgrab.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.app.NotificationManagerCompat
import com.zju.courtgrab.a11y.VenueAccessibilityService
import com.zju.courtgrab.core.Prefs

/** 权限 / 系统设置检查与跳转（含 MIUI 的几个私人入口） */
object Perms {

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

    fun openA11ySettings(context: Context) {
        safeStart(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
