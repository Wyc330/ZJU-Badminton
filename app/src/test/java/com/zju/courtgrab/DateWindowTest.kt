package com.zju.courtgrab

import com.zju.courtgrab.core.DatePlanConfig
import com.zju.courtgrab.core.DatePlanner
import com.zju.courtgrab.core.GrabConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 实测：预约窗口是「今天 + 2 天」，页面用左右箭头切日期。
 * 这里验证「今天该去抢哪一天」的推导。
 */
class DateWindowTest {

    private val cfg = GrabConfig.DEFAULT.dateCfg

    private fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 8, 0)

    @Test
    fun `parses the real date display format`() {
        assertEquals(LocalDate.of(2026, 10, 19), DatePlanner.parseDisplayed("2026-10-19", cfg, at(2026, 10, 19)))
        assertEquals(LocalDate.of(2026, 11, 7), DatePlanner.parseDisplayed("2026-11-07", cfg, at(2026, 10, 19)))
        assertNull(DatePlanner.parseDisplayed("预约日期", cfg, at(2026, 10, 19)))
    }

    @Test
    fun `friday is chosen when today is friday`() {
        // 2026-10-23 是周五 → 窗口五/六/日；按「后天优先」（放号那天最有货）排序
        val list = DatePlanner.windowCandidates(at(2026, 10, 23), cfg, cfg.maxDaysAhead)
        assertEquals(
            listOf(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 23)),
            list,
        )
        // 关掉 preferFresh 则回到「星期优先级优先」
        val byPriority = DatePlanner.windowCandidates(at(2026, 10, 23), cfg, cfg.maxDaysAhead, preferFresh = false)
        assertEquals(LocalDate.of(2026, 10, 23), byPriority.first())
    }

    @Test
    fun `thursday window contains friday and saturday`() {
        // 2026-10-22 周四 → 窗口 四/五/六；后天=周六先试，其次周五
        val list = DatePlanner.windowCandidates(at(2026, 10, 22), cfg, cfg.maxDaysAhead)
        assertEquals(listOf(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 23)), list)
        assertTrue(!list.contains(LocalDate.of(2026, 10, 22)))
    }

    @Test
    fun `wednesday window reaches friday`() {
        // 2026-10-21 周三 → 窗口 三/四/五
        val list = DatePlanner.windowCandidates(at(2026, 10, 21), cfg, cfg.maxDaysAhead)
        assertEquals(listOf(LocalDate.of(2026, 10, 23)), list)
    }

    @Test
    fun `monday and tuesday have no preferred weekday in window`() {
        // 2026-10-19 周一 → 窗口 一/二/三；2026-10-20 周二 → 二/三/四
        assertTrue(DatePlanner.windowCandidates(at(2026, 10, 19), cfg, cfg.maxDaysAhead).isEmpty())
        assertTrue(DatePlanner.windowCandidates(at(2026, 10, 20), cfg, cfg.maxDaysAhead).isEmpty())
    }

    @Test
    fun `saturday and sunday are chosen when they are in window`() {
        // 周六：窗口 六/日/一 → 后天(周一)不在优先级，先试明天(周日)，再今天(周六)
        val sat = DatePlanner.windowCandidates(at(2026, 10, 24), cfg, cfg.maxDaysAhead)
        assertEquals(LocalDate.of(2026, 10, 25), sat.first())
        // 周日：窗口 日/一/二 → 周日
        val sun = DatePlanner.windowCandidates(at(2026, 10, 25), cfg, cfg.maxDaysAhead)
        assertEquals(listOf(LocalDate.of(2026, 10, 25)), sun)
    }

    @Test
    fun `non-preferred fallback is opt-in`() {
        val list = DatePlanner.windowCandidates(
            at(2026, 10, 19), cfg, cfg.maxDaysAhead, requirePreferred = false, preferFresh = false,
        )
        assertEquals(3, list.size)
        assertEquals(LocalDate.of(2026, 10, 19), list.first())
    }

    @Test
    fun `custom window size is respected`() {
        val wide = DatePlanConfig(maxDaysAhead = 4)
        val list = DatePlanner.windowCandidates(at(2026, 10, 19), wide, wide.maxDaysAhead)
        // 周一 + 4 天 → 窗口 一/二/三/四/五，周五入选
        assertEquals(listOf(LocalDate.of(2026, 10, 23)), list)
    }
}
