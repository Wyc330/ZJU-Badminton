package com.zju.courtgrab.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.zju.courtgrab.a11y.VenueAccessibilityService
import com.zju.courtgrab.core.ConfigRepo
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.flow.Trigger
import com.zju.courtgrab.util.BgLaunch
import com.zju.courtgrab.util.Notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 抢场地期间的前台服务：让进程在「拉起目标 App → 无障碍流程跑完」这段时间里不被冻结。
 *
 * 为什么需要它：Android 12+ 的后台限制会把刚被闹钟唤醒的进程很快冻结，
 * 而一次完整流程要 10~60 秒。精确闹钟唤醒的广播允许启动前台服务，于是流程有了合法的存活窗口。
 */
class GrabForegroundService : Service() {

    companion object {
        private const val TAG = "FgService"
        private const val ACTION_ARM = "com.zju.courtgrab.fgs.ARM"
        private const val ACTION_FIRE = "com.zju.courtgrab.fgs.FIRE"
        private const val ACTION_STOP = "com.zju.courtgrab.fgs.STOP"

        fun startArm(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GrabForegroundService::class.java).setAction(ACTION_ARM),
            )
        }

        fun startFire(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GrabForegroundService::class.java).setAction(ACTION_FIRE),
            )
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, GrabForegroundService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val text = when (action) {
            ACTION_FIRE -> "正在抢场地…"
            else -> "已就绪，等待到点"
        }
        startForeground(Notify.ID_FOREGROUND, Notify.buildForeground(this, "抢场地", text))
        Logs.i(TAG, "前台服务启动，action=$action")

        when (action) {
            ACTION_FIRE -> fire()
            ACTION_STOP -> stopSelf()
            else -> Unit
        }
        return START_NOT_STICKY
    }

    private fun fire() {
        val cfg = ConfigRepo.load(this)
        scope.launch {
            val svc = VenueAccessibilityService.instance
            if (svc == null) {
                Logs.e(TAG, "无障碍服务未连接，流程无法执行")
                Notify.showResult(this@GrabForegroundService, false, "无障碍服务未开启，无法自动预约")
                stopSelf()
                return@launch
            }
            if (svc.isFlowRunning) {
                Logs.w(TAG, "已有流程在跑，忽略本次 FIRE")
                stopSelf()
                return@launch
            }
            Notify.showFlowRunning(this@GrabForegroundService, "正在拉起浙大体艺…")
            BgLaunch.openTarget(this@GrabForegroundService, cfg)
            delay(700)
            val started = svc.startFlow(Trigger.ALARM)
            Logs.i(TAG, "startFlow=$started")
            // 流程期间保持前台；结束后退出
            val deadline = System.currentTimeMillis() + 5 * 60_000L
            while (svc.isFlowRunning && System.currentTimeMillis() < deadline) {
                delay(800)
            }
            Logs.i(TAG, "流程结束，前台服务退出")
            Notify.cancelFlowNotification(this@GrabForegroundService)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        Logs.i(TAG, "前台服务销毁")
        super.onDestroy()
    }
}
