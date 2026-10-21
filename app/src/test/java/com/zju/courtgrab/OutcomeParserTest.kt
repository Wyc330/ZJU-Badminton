package com.zju.courtgrab

import com.zju.courtgrab.core.OutcomeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutcomeParserTest {

    // 真机 2026-10-21 提交订单后的页面文字（节选）
    private val realOrderPage = """
        订单提交后 3 分钟内未支付将会自动取消 ，179秒后关闭
        预约信息
        订单号 D261021000598
        订场信息 紫金港校区 风雨操场 羽毛球场 2026-10-23 9号 14:30-15:30
        场地使用者 吴承泽 /3260102826 学生
        同伴信息 邬奕辰/3260100590 学生
        下单时间 2026-10-21 13:10:27
        优惠方式
    """.trimIndent()

    @Test
    fun `captcha dialog is detected`() {
        assertTrue(OutcomeParser.isCaptcha("请完成安全验证"))
        assertTrue(OutcomeParser.isCaptcha("请依次点击【研,何,形】"))
        assertTrue(OutcomeParser.isCaptcha("场馆预约 验证码"))
        assertFalse(OutcomeParser.isCaptcha("预约成功，请准时入场"))
        assertFalse(OutcomeParser.isCaptcha("订单号 D261021000598"))
    }

    @Test
    fun `success page is detected`() {
        assertTrue(OutcomeParser.isSuccess("预约成功，请准时入场。"))
        assertTrue(OutcomeParser.isSuccess("提交成功"))
        assertTrue(OutcomeParser.isSuccess("您已成功预约…可使用校园卡和预约码入场"))
        assertFalse(OutcomeParser.isSuccess("订单提交后 3 分钟内未支付将会自动取消"))
        assertFalse(OutcomeParser.isSuccess("人数已满"))
    }

    @Test
    fun `pay button presence is detected`() {
        assertTrue(OutcomeParser.hasPayButton("取消订单 支付"))
        assertFalse(OutcomeParser.hasPayButton("待支付"))
        assertFalse(OutcomeParser.hasPayButton("预约成功"))
    }

    @Test
    fun `order info parsed from real order page`() {
        val info = OutcomeParser.parseOrder(realOrderPage)
        assertEquals("D261021000598", info.orderNo)
        assertEquals("2026-10-23", info.date)
        assertEquals("14:30-15:30", info.timeRange)
        assertEquals("9", info.courtNo)
        val d = info.describe()
        assertTrue(d.contains("D261021000598"))
        assertTrue(d.contains("2026-10-23"))
        assertTrue(d.contains("14:30-15:30"))
        assertTrue(d.contains("9号场"))
    }

    @Test
    fun `order info tolerant to tilde and fullwidth dash`() {
        val info = OutcomeParser.parseOrder("订单号 ABC123456 2026-10-24 10:00〜11:00 3号")
        assertEquals("ABC123456", info.orderNo)
        assertEquals("2026-10-24", info.date)
        assertEquals("10:00-11:00", info.timeRange)
        assertEquals("3", info.courtNo)
    }

    @Test
    fun `empty order describe falls back`() {
        val info = OutcomeParser.parseOrder("预约成功")
        assertEquals(null, info.orderNo)
        assertEquals("已提交", info.describe())
    }
}
