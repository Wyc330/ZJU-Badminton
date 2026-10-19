package com.zju.courtgrab

import com.zju.courtgrab.core.Locator
import com.zju.courtgrab.core.LocatorMatcher
import com.zju.courtgrab.core.MatchOrder
import com.zju.courtgrab.core.NodeSnapshot
import com.zju.courtgrab.core.RectI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocatorMatcherTest {

    private fun node(
        text: String? = null,
        id: String? = null,
        cls: String = "android.widget.TextView",
        x: Int = 0,
        y: Int = 0,
        clickable: Boolean = true,
        enabled: Boolean = true,
        desc: String? = null,
    ) = NodeSnapshot(
        className = cls,
        viewId = id,
        text = text,
        desc = desc,
        bounds = RectI(x, y, x + 100, y + 50),
        clickable = clickable,
        enabled = enabled,
    )

    @Test
    fun `empty locator matches nothing`() {
        assertFalse(LocatorMatcher.matches(node(text = "场馆服务"), Locator()))
        assertNull(LocatorMatcher.pick(listOf(node(text = "任何")), Locator()))
    }

    @Test
    fun `viewId can be written in full or short form`() {
        val n = node(id = "net.crigh.mysport:id/tv_home_model_title")
        assertTrue(LocatorMatcher.matches(n, Locator(id = "tv_home_model_title")))
        assertTrue(LocatorMatcher.matches(n, Locator(id = "net.crigh.mysport:id/tv_home_model_title")))
        assertFalse(LocatorMatcher.matches(n, Locator(id = "other_id")))
    }

    @Test
    fun `text equals and contains`() {
        val n = node(text = "场馆服务")
        assertTrue(LocatorMatcher.matches(n, Locator(textEquals = "场馆服务")))
        assertFalse(LocatorMatcher.matches(n, Locator(textEquals = "场馆")))
        assertTrue(LocatorMatcher.matches(n, Locator(textContains = "场馆")))
        assertTrue(LocatorMatcher.matches(n, Locator(textRegex = "场馆.*")))
    }

    @Test
    fun `content description is also searched`() {
        val n = node(desc = "关闭")
        assertTrue(LocatorMatcher.matches(n, Locator(textContains = "关闭")))
        assertTrue(LocatorMatcher.matches(n, Locator(descContains = "关闭")))
    }

    @Test
    fun `h5 nodes without viewId are matched by text`() {
        val n = node(text = "我要预约", cls = "android.view.View", id = null)
        assertTrue(LocatorMatcher.matches(n, Locator(textContains = "我要预约")))
    }

    @Test
    fun `clickableOnly and enabledOnly filters`() {
        val notClickable = node(text = "提交订单", clickable = false)
        assertFalse(LocatorMatcher.matches(notClickable, Locator(textContains = "提交订单", clickableOnly = true)))
        assertTrue(LocatorMatcher.matches(notClickable, Locator(textContains = "提交订单")))

        val disabled = node(text = "提交订单", enabled = false)
        assertFalse(LocatorMatcher.matches(disabled, Locator(textContains = "提交订单")))
        assertTrue(LocatorMatcher.matches(disabled, Locator(textContains = "提交订单", enabledOnly = false)))
    }

    @Test
    fun `top_left ordering and index selection`() {
        val nodes = listOf(
            node(text = "A", y = 300),
            node(text = "B", y = 100),
            node(text = "C", y = 200),
        )
        val loc = Locator(textRegex = "[ABC]")
        assertEquals(listOf(1, 2, 0), LocatorMatcher.candidateIndexes(nodes, loc))
        assertEquals(1, LocatorMatcher.pick(nodes, loc))
        assertEquals(2, LocatorMatcher.pick(nodes, loc.copy(index = 1)))
        assertEquals(0, LocatorMatcher.pick(nodes, loc.copy(index = -1)))
    }

    @Test
    fun `area desc picks the biggest container`() {
        val nodes = listOf(
            NodeSnapshot(className = "V", text = "预约", bounds = RectI(0, 0, 200, 100)),
            NodeSnapshot(className = "V", text = "预约", bounds = RectI(0, 0, 1000, 800)),
        )
        assertEquals(1, LocatorMatcher.pick(nodes, Locator(textContains = "预约", order = MatchOrder.AREA_DESC)))
    }

    @Test
    fun `pickFirst walks the locator chain`() {
        val nodes = listOf(node(text = "场馆预约"), node(id = "x:id/venue_entry", text = "场馆服务"))
        val chain = listOf(Locator(id = "not_exist"), Locator(textEquals = "场馆预约"))
        assertEquals(0, LocatorMatcher.pickFirst(nodes, chain))
    }

    @Test
    fun `invalid regex does not crash`() {
        val n = node(text = "任何")
        assertFalse(LocatorMatcher.matches(n, Locator(textRegex = "([unclosed")))
    }
}
