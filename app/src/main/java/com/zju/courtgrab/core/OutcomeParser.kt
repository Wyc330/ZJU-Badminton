package com.zju.courtgrab.core

/**
 * 提交订单后的结果识别与解析（纯函数，方便单测）。
 *
 * 实测（2026-10-21 真机）：
 *  · 点「提交订单」后先弹「请完成安全验证」（按顺序点选字符）
 *  · 验证通过后进入订单页：订单号 / 订场信息 / 场地使用者 / 同伴信息
 *  · 底部有「支付」（金额 ¥0），不点支付 3 分钟内自动取消
 *  · 支付成功后显示「预约成功，请准时入场」
 */
object OutcomeParser {

    private val ORDER_NO = Regex("""订单号[：:\s]*([A-Za-z0-9]{5,})""")
    private val DATE = Regex("""(\d{4}-\d{1,2}-\d{1,2})""")
    private val RANGE = Regex("""(\d{1,2}:\d{2}\s*[-~—－–〜～]\s*\d{1,2}:\d{2})""")
    private val COURT = Regex("""(?<!订单)(\d{1,2})\s*号""")

    private val CAPTCHA_WORDS = listOf(
        "安全验证", "请依次点击", "请完成验证", "完成安全验证", "拖动滑块", "验证码",
    )

    fun isCaptcha(text: String): Boolean = CAPTCHA_WORDS.any { text.contains(it) }

    fun isSuccess(text: String): Boolean =
        text.contains("预约成功") || text.contains("提交成功") || text.contains("成功预约") || text.contains("请准时入场")

    fun hasPayButton(text: String): Boolean =
        text.contains("支付") && !text.contains("待支付") && !text.contains("无需支付")

    fun parseOrder(text: String): OrderInfo = OrderInfo(
        orderNo = ORDER_NO.find(text)?.groupValues?.get(1),
        date = DATE.find(text)?.value,
        timeRange = RANGE.find(text)?.value?.let { raw ->
            raw.replace('—', '-').replace('－', '-').replace('–', '-').replace('~', '-')
                .replace('〜', '-').replace('～', '-')
                .replace(" ", "")
        },
        courtNo = COURT.find(text)?.groupValues?.get(1),
    )

    data class OrderInfo(
        val orderNo: String?,
        val date: String?,
        val timeRange: String?,
        val courtNo: String?,
    ) {
        /** 落盘/通知用的一行摘要，例如「订单号 D261021000598 · 2026-10-23 14:30-15:30 · 9号场」 */
        fun describe(): String = buildString {
            orderNo?.let { append("订单号 ").append(it).append(" · ") }
            date?.let { append(it).append(' ') }
            timeRange?.let { append(it) }
            courtNo?.let { append(" · ").append(it).append("号场") }
        }.trim().trimEnd('·').trim().ifBlank { "已提交" }
    }
}
