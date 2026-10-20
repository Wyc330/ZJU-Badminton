package com.zju.courtgrab.ui

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.zju.courtgrab.core.Logs

/**
 * 透明解锁页：用于无人值守时把「非安全锁屏」（滑动解锁）自动划掉。
 *
 * 注意：如果用户设置了 PIN/图案/密码（安全锁屏），系统不会允许应用代为解锁，
 * 这里只会记录错误并退出，此时需要用户本人手动解锁。
 */
class UnlockActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "Unlock"
    }

    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) {
            Logs.i(TAG, "屏幕已解锁，无需处理")
            finish()
            return
        }
        Logs.i(TAG, "尝试解除锁屏（仅对非安全锁屏有效）")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        Logs.i(TAG, "锁屏已解除")
                        finish()
                    }

                    override fun onDismissError() {
                        Logs.w(TAG, "无法自动解锁（多半是安全锁屏），需要用户本人解锁")
                        finish()
                    }

                    override fun onDismissCancelled() {
                        Logs.w(TAG, "解锁被取消，需要用户本人解锁")
                        finish()
                    }
                },
            )
        } else {
            finish()
        }
        // 兜底：最多停留 4 秒
        ui.postDelayed({ if (!isFinishing) finish() }, 4_000)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
