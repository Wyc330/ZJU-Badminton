package com.zju.courtgrab

import com.zju.courtgrab.core.PrepReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepReminderTest {

    @Test
    fun `未登录时提示直连不可用`() {
        assertEquals("未登录（直连不可用）", PrepReminder.tokenText("", null, 0L))
    }

    @Test
    fun `已过期时提示重新登录`() {
        val now = 1_000_000L
        assertEquals("已过期（需重新登录）", PrepReminder.tokenText("tok", now - 1, now))
    }

    @Test
    fun `有效时显示剩余分钟`() {
        val now = 1_000_000L
        assertEquals("正常（剩余 30 分钟）", PrepReminder.tokenText("tok", now + 30 * 60_000L, now))
    }

    @Test
    fun `通知正文包含日期登录态同伴码与直连状态`() {
        val body = PrepReminder.body("10/23 周五", "正常（剩余 30 分钟）", codeFilled = false, directOn = true)
        assertTrue(body.contains("目标日期：10/23 周五"))
        assertTrue(body.contains("登录态：正常"))
        assertTrue(body.contains("同伴码：未填"))
        assertTrue(body.contains("直连：开"))
        assertEquals("9:00 放号提醒：10/23 周五", PrepReminder.title("10/23 周五"))
    }
}
