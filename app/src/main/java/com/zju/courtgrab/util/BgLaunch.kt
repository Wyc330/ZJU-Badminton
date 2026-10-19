package com.zju.courtgrab.util

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.zju.courtgrab.core.GrabConfig
import com.zju.courtgrab.core.Logs

/**
 * 「从后台把目标 App 拉到前台 + 唤醒屏幕」。
 *
 * Android 10+ 默认禁止后台启动 Activity，常见两条合法途径（本 App 都用了）：
 *   1) 用户授予「显示在其他应用上层」(SYSTEM_ALERT_WINDOW) —— 本 App 已要求
 *   2) 先启动前台服务（精确闹钟触发的 BroadcastReceiver 允许启动前台服务）
 */
object BgLaunch {

    private const val TAG = "BgLaunch"

    fun openTarget(context: Context, cfg: GrabConfig): Boolean {
        val pkg = cfg.targetPackage
        val pm = context.packageManager
        val intent: Intent = runCatching {
            if (cfg.targetActivity.isNotBlank()) {
                Intent().setClassName(pkg, cfg.targetActivity)
            } else {
                pm.getLaunchIntentForPackage(pkg) ?: Intent().setPackage(pkg)
            }
        }.getOrElse { Intent().setPackage(pkg) }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            Logs.i(TAG, "已请求拉起目标 App：$pkg")
            true
        }.getOrElse {
            Logs.e(TAG, "拉起目标 App 失败（多半是没给「显示在其他应用上层」权限）", it)
            false
        }
    }

    /** 拿到一个能唤醒屏幕的 WakeLock（用完记得 release） */
    fun acquireWakeLock(context: Context, timeoutMs: Long = 10 * 60 * 1000L): PowerManager.WakeLock? =
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java)
            val wl = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "CourtGrab:flow",
            )
            wl.setReferenceCounted(false)
            wl.acquire(timeoutMs)
            wl
        }.getOrNull()

    /**
     * 尽力点亮屏幕。真正的亮屏动作由展示在前台的 Activity 完成
     * （本 App 的 CodeRequestActivity 带 turnScreenOn/showWhenLocked）。
     */
    @Suppress("DEPRECATION")
    fun wakeScreen(context: Context, timeoutMs: Long = 15_000L) {
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java)
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "CourtGrab:screen",
            )
            wl.setReferenceCounted(false)
            wl.acquire(timeoutMs)
        }.onFailure { Logs.w(TAG, "wakeScreen 失败：${it.message}") }
    }
}
