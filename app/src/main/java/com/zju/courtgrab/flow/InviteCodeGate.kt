package com.zju.courtgrab.flow

import android.content.Context
import android.content.Intent
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.ui.CodeRequestActivity
import com.zju.courtgrab.util.Notify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「本次邀请码」闸门。
 *
 * 你说邀请码每次都不一样，所以流程走到「填邀请码」这一步时：
 *   · 如果主界面 / 到点弹窗里已经填过 → 直接用
 *   · 否则弹通知 + 全屏输入页，流程原地挂起等你输入（最长 inviteCodeWaitMs）
 *
 * 输入页把结果通过 [provide] 交回这里，流程继续往下点「确定」。
 */
object InviteCodeGate {

    private const val TAG = "CodeGate"

    @Volatile
    private var waiter: CompletableDeferred<String?>? = null

    @Volatile
    var isWaiting: Boolean = false
        private set

    /** 流程侧调用：挂起等待用户输入；超时/跳过返回 null */
    suspend fun requestCode(context: Context, timeoutMs: Long): String? {
        val app = context.applicationContext
        val deferred = CompletableDeferred<String?>()
        waiter = deferred
        isWaiting = true
        Logs.i(TAG, "等待用户输入邀请码（最长 ${timeoutMs / 1000}s）")

        Notify.showCodeRequest(app)
        runCatching {
            app.startActivity(
                Intent(app, CodeRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }.onFailure {
            Logs.w(TAG, "直接拉起输入页失败（后台启动限制），请从通知点进去：${it.message}")
        }

        return try {
            val code = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (code.isNullOrBlank()) {
                Logs.w(TAG, "没有拿到邀请码（超时或用户跳过）")
            } else {
                Logs.i(TAG, "已拿到邀请码（${code.length} 位）")
            }
            code
        } finally {
            isWaiting = false
            if (waiter === deferred) waiter = null
        }
    }

    /** 输入页调用：把码交给等待中的流程；返回 false 表示当前没有流程在等 */
    fun provide(code: String): Boolean {
        val deferred = waiter ?: return false
        if (deferred.isCompleted) return false
        deferred.complete(code)
        return true
    }

    /** 输入页调用：用户选择跳过 */
    fun skip(): Boolean {
        val deferred = waiter ?: return false
        if (deferred.isCompleted) return false
        deferred.complete(null)
        return true
    }
}
