package com.zju.courtgrab.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zju.courtgrab.core.ConfigRepo
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.core.Prefs
import com.zju.courtgrab.service.GrabForegroundService
import com.zju.courtgrab.ui.CodeRequestActivity
import com.zju.courtgrab.util.BgLaunch
import com.zju.courtgrab.util.Notify
import com.zju.courtgrab.util.Perms

/**
 * 到点收口。
 *
 * 顺序：
 *   1. 先把「明天这一次」排上（即使后面失败也不会断掉每天循环）
 *   2. 检查无障碍服务（没开会直接通知并终止）
 *   3. 唤醒屏幕 + 起前台服务保活
 *   4. 有同伴码 → 直接开抢；没有 → 通知/全屏页让你输入（最多等 inviteCodeWaitMs）
 *
 * 说明：精确闹钟触发的 BroadcastReceiver 允许启动前台服务，这是 Android 12+ 上
 * 从后台把流程拉起来的合法路径。
 */
class BookingAlarmReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "AlarmRx"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Logs.init(context)
        val prefs = Prefs.get(context)
        Logs.i(TAG, "⏰ 收到定时触发：${intent?.action}")

        // 1. 先排下一次
        runCatching { AlarmScheduler.schedule(context, prefs) }
            .onFailure { Logs.e(TAG, "排下一次失败", it) }

        // 1.5) 锁屏处理：安全锁屏无法由应用代解，排队等解锁（每分钟重试，最多 6 次）
        val attempt = intent?.getIntExtra("attempt", 0) ?: 0
        val km = context.getSystemService(android.app.KeyguardManager::class.java)
        if (km != null && km.isKeyguardLocked) {
            Logs.w(TAG, "手机锁屏中（第 ${attempt + 1} 次检查）")
            runCatching {
                context.startActivity(
                    Intent(context, com.zju.courtgrab.ui.UnlockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            }
            if (attempt < 6) {
                Notify.showPrepReminder(
                    context,
                    "手机锁屏中，等待解锁",
                    "9:00 放号需要解锁手机。解锁后会自动继续（每分钟自动检查一次，最多等 6 分钟）。",
                )
                AlarmScheduler.scheduleRetry(context, 60_000, attempt + 1)
                return
            }
            Logs.e(TAG, "等待解锁超时（6 分钟），本次放弃")
            Notify.showResult(context, false, "手机一直锁屏，本次自动抢场地未能执行（请解锁后再等下一次放号）")
            return
        }
        if (intent?.action == AlarmScheduler.ACTION_RETRY) {
            Logs.i(TAG, "解锁重试：手机已解锁，继续执行")
        }
        AlarmScheduler.cancelRetry(context)

        // 2. 无障碍必须开着
        if (!Perms.isA11yEnabled(context)) {
            Logs.e(TAG, "无障碍服务未开启，无法执行自动预约")
            Notify.showResult(context, false, "无障碍服务未开启：请打开「抢场地」→ 无障碍服务 后再试")
            return
        }

        // 3. 唤醒 + 保活
        BgLaunch.wakeScreen(context)
        runCatching { GrabForegroundService.startArm(context) }
            .onFailure { Logs.e(TAG, "启动前台服务失败", it) }

        val cfg = ConfigRepo.load(context)
        val code = prefs.inviteCode.trim().filter { it.isDigit() }

        // 4. 有码直接开抢；没码让用户输入
        if (code.length == 5) {
            Logs.i(TAG, "已有 5 位同伴码，直接开抢")
            runCatching { GrabForegroundService.startFire(context) }
                .onFailure { Logs.e(TAG, "启动抢场地流程失败", it) }
        } else {
            Logs.i(TAG, "没有本次同伴码，弹出输入页")
            Notify.showCodeRequest(context)
            runCatching {
                context.startActivity(
                    Intent(context, CodeRequestActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }.onFailure { Logs.w(TAG, "直接拉起输入页失败（受限），请从通知进入：${it.message}") }
        }
        Logs.i(TAG, "目标包名=${cfg.targetPackage}，触发时间=${Logs.fullTime()}")
    }
}
