package com.zju.courtgrab.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zju.courtgrab.a11y.VenueAccessibilityService
import com.zju.courtgrab.core.ConfigRepo
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.core.Prefs
import com.zju.courtgrab.databinding.ActivityCodeRequestBinding
import com.zju.courtgrab.flow.InviteCodeGate
import com.zju.courtgrab.service.GrabForegroundService
import com.zju.courtgrab.util.Notify
import com.zju.courtgrab.util.Perms
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 到点后要求输入「本次 5 位邀请码」的全屏页。
 *
 * 两种情况：
 *   · 流程正卡在「填邀请码」这一步 → 输入后交给 [InviteCodeGate]，流程继续点确定
 *   · 到点触发但流程还没开始   → 输入后直接启动流程
 *
 * 页面带 showWhenLocked/turnScreenOn，所以手机锁屏时也会亮屏弹出来（但解锁密码仍需你自己按）。
 */
class CodeRequestActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "CodeRequest"
    }

    private lateinit var b: ActivityCodeRequestBinding
    private lateinit var prefs: Prefs
    private var countdownJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCodeRequestBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs.get(this)
        showOverLockScreen()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val cfg = ConfigRepo.load(this)
        b.etCode.setText(prefs.inviteCode.filter { it.isDigit() }.take(5))
        startCountdown(cfg.inviteCodeWaitMs)

        b.btnOk.setOnClickListener { submit() }
        b.btnSkip.setOnClickListener { skipAndFinish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = skipAndFinish()
        })

        b.etCode.requestFocus()
        b.etCode.post { showKeyboard() }
        Logs.i(TAG, "到点输入页已展示")
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            runCatching {
                (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                    .requestDismissKeyguard(this, null)
            }
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun showKeyboard() {
        runCatching {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(b.etCode, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun startCountdown(totalMs: Long) {
        countdownJob?.cancel()
        countdownJob = lifecycleScope.launch {
            var left = (totalMs / 1000).toInt()
            while (left > 0) {
                b.tvCountdown.text = "将在 $left 秒后自动关闭并跳过本次预约"
                delay(1000)
                left--
            }
            if (!isFinishing) {
                Logs.w(TAG, "等待邀请码超时，跳过")
                InviteCodeGate.skip()
                finish()
            }
        }
    }

    private fun submit() {
        val code = b.etCode.text.toString().trim()
        if (code.length != 5 || code.any { !it.isDigit() }) {
            b.tvError.text = "请输入 5 位数字邀请码"
            return
        }
        b.tvError.text = ""
        prefs.inviteCode = code
        prefs.lastCode = code
        val delivered = InviteCodeGate.provide(code)
        Logs.i(TAG, "已输入邀请码（5 位）；当前有流程在等=$delivered")
        countdownJob?.cancel()

        if (!delivered) {
            // 不是流程在等 → 说明是「到点直接拉起」的入口，这里手动开抢
            if (!Perms.isA11yEnabled(this)) {
                Notify.showResult(this, false, "无障碍服务未开启，无法自动预约")
            } else {
                GrabForegroundService.startFire(this)
            }
        }
        finish()
    }

    private fun skipAndFinish() {
        Logs.w(TAG, "用户跳过了邀请码输入")
        countdownJob?.cancel()
        InviteCodeGate.skip()
        finish()
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        super.onDestroy()
    }
}
