package com.zju.courtgrab.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.zju.courtgrab.core.ConfigRepo
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.flow.BookingFlow
import com.zju.courtgrab.flow.FlowException
import com.zju.courtgrab.flow.Trigger
import com.zju.courtgrab.util.Notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无障碍服务本体。
 *
 * 它只做三件事：
 *   1. 在被系统连上时把 [A11yOps] 准备好，供流程使用
 *   2. 承载 [BookingFlow] 的协程（流程是「轮询界面 + 动作」的循环，不依赖事件回调）
 *   3. 对外提供「抓取当前界面」能力
 *
 * 为什么不用事件驱动？H5/WebView 的节点树会不停重建，事件里拿到的节点经常已经失效，
 * 轮询 + 每轮重新抓根节点最稳。
 */
class VenueAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "A11yService"

        @Volatile
        var instance: VenueAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }

    lateinit var ops: A11yOps
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var flowJob: Job? = null
    private val running = AtomicBoolean(false)

    @Volatile
    private var lastEventPackage: String? = null

    val isFlowRunning: Boolean get() = running.get()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ops = A11yOps(this)
        Logs.init(applicationContext)
        Logs.i(TAG, "无障碍服务已连接")
        runCatching {
            val info = serviceInfo
            Logs.i(
                TAG,
                "服务配置：flags=0x${Integer.toHexString(info?.flags ?: 0)} " +
                    "canRetrieveWindowContent=${info?.canRetrieveWindowContent} " +
                    "eventTypes=0x${Integer.toHexString(info?.eventTypes ?: 0)}",
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只记录，不驱动流程
        runCatching {
            val pkg = event?.packageName?.toString()
            if (!pkg.isNullOrEmpty() && pkg != lastEventPackage) {
                lastEventPackage = pkg
            }
        }
    }

    override fun onInterrupt() {
        Logs.w(TAG, "无障碍服务被打断（onInterrupt）")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Logs.w(TAG, "无障碍服务被解绑")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Logs.w(TAG, "无障碍服务销毁")
        flowJob?.cancel()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    /** 启动一次完整流程；已有流程在跑时返回 false */
    fun startFlow(trigger: Trigger): Boolean {
        if (!running.compareAndSet(false, true)) {
            Logs.w(TAG, "已有流程正在运行，忽略本次启动请求")
            return false
        }
        val startAt = System.currentTimeMillis()
        flowJob = scope.launch {
            var success = false
            var message = ""
            try {
                Logs.i(TAG, "===== 流程开始（$trigger）=====")
                Notify.showFlowRunning(applicationContext, "正在自动预约…")
                BookingFlow(this@VenueAccessibilityService, trigger).run()
                success = true
                message = "预约流程执行完成，请到「浙大体艺-我的预约」确认结果"
            } catch (e: FlowException) {
                message = "流程失败于「${e.step}」：${e.message}"
                Logs.e(TAG, "流程失败：$message", e.cause)
                dumpUi("fail-${e.step}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                message = "流程被手动停止"
                Logs.w(TAG, message)
            } catch (e: Throwable) {
                message = "流程异常：${e.javaClass.simpleName}: ${e.message}"
                Logs.e(TAG, message, e)
                dumpUi("fail-exception")
            } finally {
                val cost = System.currentTimeMillis() - startAt
                Logs.i(TAG, "===== 流程结束：${if (success) "成功" else "未成功"}，耗时 ${cost}ms =====")
                Notify.showResult(applicationContext, success, "$message（耗时 ${cost / 1000}s）")
                Notify.cancelFlowNotification(applicationContext)
                running.set(false)
            }
        }
        return true
    }

    fun stopFlow(reason: String) {
        Logs.w(TAG, "请求停止流程：$reason")
        flowJob?.cancel()
        running.set(false)
    }

    /** 抓取当前界面节点树（同时会刷新配置） */
    fun dumpUi(tag: String): Dump.Result? {
        if (!isConnected) return null
        val cfg = ConfigRepo.current
        return Dump.save(applicationContext, ops, cfg, tag)
    }

    /**
     * 延时抓取：无障碍只能看到「当前可见」的窗口，
     * 所以给用户 N 秒时间切到目标 App 再抓（否则抓到的只有状态栏）。
     */
    fun dumpUiAfter(delayMs: Long, tag: String) {
        scope.launch {
            Logs.i(TAG, "${delayMs / 1000} 秒后抓取界面：请立刻切到目标页面")
            kotlinx.coroutines.delay(delayMs)
            val result = dumpUi(tag)
            if (result != null) {
                com.zju.courtgrab.core.Prefs.get(applicationContext).lastDumpPath = result.json.absolutePath
                Notify.showDumpDone(applicationContext, result.nodeCount, result.json.absolutePath)
            } else {
                Notify.showResult(applicationContext, false, "抓取失败，详见日志")
                Logs.e(TAG, "延时抓取失败")
            }
        }
    }
}
