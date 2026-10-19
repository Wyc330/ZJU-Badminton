package com.zju.courtgrab.direct

import org.junit.Assert.assertEquals
import org.junit.Test

/** 校验 Kotlin 的签名实现与按前端 JS 规则写出的参考实现完全一致 */
class SignTest {

    @Test
    fun `GET 请求签名与参考实现一致`() {
        val ts = 1792426320994L
        val data = mapOf<String, Any?>(
            "venueSiteId" to 19L,
            "searchDate" to "2026-10-22",
            "reservationType" to null,
            "schoolType" to null,
            "nocache" to ts,
        )
        assertEquals("f2e5ab8083ef86fae3b11c14bfa010cb", Sign.sign("/api/reservation/day/info", data, ts))
    }

    @Test
    fun `MD5 输出为 32 位小写十六进制`() {
        val h = Sign.md5("abc")
        assertEquals(32, h.length)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", h)
    }

    @Test
    fun `app-key 长度正确`() {
        assertEquals(32, Sign.APP_KEY.length)
    }
}
