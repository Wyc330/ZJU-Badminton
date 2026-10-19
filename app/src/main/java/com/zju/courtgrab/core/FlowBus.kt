package com.zju.courtgrab.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 一次抢场地流程的状态快照 */
data class FlowState(
    val running: Boolean = false,
    /** null = 还没跑过；true/false = 上次结果 */
    val success: Boolean? = null,
    val message: String = "",
    /** 结束时间戳（毫秒），用来避免重复弹提示 */
    val finishedAt: Long = 0L,
)

/**
 * 流程状态总线：让主界面能实时显示「进行中 / 成功 / 失败」，
 * 并在流程结束的那一刻给用户一个明确提示（对话框 + 通知 + 震动）。
 */
object FlowBus {

    private val _state = MutableStateFlow(FlowState())
    val state: StateFlow<FlowState> = _state

    fun running(message: String = "正在自动预约…") {
        _state.value = FlowState(running = true, message = message)
    }

    fun done(success: Boolean, message: String) {
        _state.value = FlowState(
            running = false,
            success = success,
            message = message,
            finishedAt = System.currentTimeMillis(),
        )
    }
}
