package com.zju.courtgrab.core

/**
 * 「放号前准备提醒」的文案生成（纯函数，便于单测）。
 *
 * 通知里要让用户一眼看清：目标日期、登录态还剩多久、同伴码填没填、直连开没开。
 */
object PrepReminder {

    fun tokenText(token: String, expMs: Long?, nowMs: Long): String = when {
        token.isBlank() -> "未登录（直连不可用）"
        expMs != null && expMs <= nowMs -> "已过期（需重新登录）"
        expMs != null -> "正常（剩余 ${(expMs - nowMs) / 60_000} 分钟）"
        else -> "已登录"
    }

    fun title(dateText: String): String = "9:00 放号提醒：$dateText"

    fun body(dateText: String, tokenText: String, codeFilled: Boolean, directOn: Boolean): String =
        "目标日期：$dateText\n" +
            "登录态：$tokenText\n" +
            "同伴码：${if (codeFilled) "已填" else "未填（到点会弹窗问）"}\n" +
            "直连：${if (directOn) "开" else "关"}；点击打开 App 检查"
}
