package com.zju.courtgrab

import com.zju.courtgrab.core.DatePlanConfig
import com.zju.courtgrab.core.DatePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

class DatePlannerTest {

    private val cfg = DatePlanConfig()

    // 2026-10-19 是周一
    private val monday = LocalDateTime.of(2026, 10, 19, 8, 0, 0)

    @Test
    fun `parses several common date cell formats`() {
        assertEquals(LocalDate.of(2026, 11, 7), DatePlanner.parseCell("11-07", monday, cfg))
        assertEquals(LocalDate.of(2026, 11, 7), DatePlanner.parseCell("11/7", monday, cfg))
        assertEquals(LocalDate.of(2026, 11, 7), DatePlanner.parseCell("11月7日", monday, cfg))
        assertEquals(LocalDate.of(2026, 11, 7), DatePlanner.parseCell("2026-11-07", monday, cfg))
        assertEquals(LocalDate.of(2026, 10, 19), DatePlanner.parseCell("今天", monday, cfg))
        assertEquals(LocalDate.of(2026, 10, 20), DatePlanner.parseCell("明天", monday, cfg))
        assertNull(DatePlanner.parseCell("无可预约", monday, cfg))
        assertNull(DatePlanner.parseCell("", monday, cfg))
    }

    @Test
    fun `friday wins over saturday and sunday`() {
        val cells = listOf("10-23", "10-24", "10-25") // 周五 周六 周日
        val plan = DatePlanner.plan(cells, monday, cfg)
        assertNotNull(plan.chosen)
        assertEquals(LocalDate.of(2026, 10, 23), plan.chosen!!.date)
        assertEquals(DayOfWeek.FRIDAY, plan.chosen!!.weekday)
        // 排序应是 周五 → 周六 → 周日
        assertEquals(
            listOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            plan.ranked.map { it.weekday }.take(3),
        )
    }

    @Test
    fun `falls back to saturday when friday absent`() {
        val cells = listOf("10-24", "10-25") // 周六 周日
        val plan = DatePlanner.plan(cells, monday, cfg)
        assertEquals(LocalDate.of(2026, 10, 24), plan.chosen!!.date)
    }

    @Test
    fun `falls back to earliest date when no preferred weekday present`() {
        val cells = listOf("10-20", "10-21", "10-22") // 周二 周三 周四
        val plan = DatePlanner.plan(cells, monday, cfg)
        assertEquals(LocalDate.of(2026, 10, 20), plan.chosen!!.date)
    }

    @Test
    fun `two fridays - the earlier one wins`() {
        val cells = listOf("10-23", "10-24", "10-25", "10-30", "10-31") // 周五...下一个周五
        val plan = DatePlanner.plan(cells, monday, cfg)
        assertEquals(LocalDate.of(2026, 10, 23), plan.chosen!!.date)
        // 两个周五都应排在周六周日之前
        assertEquals(DayOfWeek.FRIDAY, plan.ranked[0].weekday)
        assertEquals(DayOfWeek.FRIDAY, plan.ranked[1].weekday)
    }

    @Test
    fun `includeToday controls same-day selection`() {
        val friday = LocalDateTime.of(2026, 10, 23, 8, 0, 0)
        val cells = listOf("今天", "10-24")
        val withToday = DatePlanner.plan(cells, friday, DatePlanConfig(includeToday = true))
        assertEquals(LocalDate.of(2026, 10, 23), withToday.chosen!!.date)

        val withoutToday = DatePlanner.plan(cells, friday, DatePlanConfig(includeToday = false))
        assertEquals(LocalDate.of(2026, 10, 24), withoutToday.chosen!!.date)
    }

    @Test
    fun `year rollover is handled`() {
        val dec31 = LocalDateTime.of(2026, 12, 31, 8, 0, 0)
        // 12-31 是周四；1-1 下周是周五（2027-01-01）
        val cells = listOf("01-01", "01-02")
        val plan = DatePlanner.plan(cells, dec31, cfg)
        assertEquals(LocalDate.of(2027, 1, 1), plan.chosen!!.date)
        assertEquals(DayOfWeek.FRIDAY, plan.chosen!!.weekday)
    }

    @Test
    fun `empty cells produce null with reason`() {
        val plan = DatePlanner.plan(listOf(null, "", "  "), monday, cfg)
        assertNull(plan.chosen)
        assertTrue(plan.reason.isNotEmpty())
    }

    @Test
    fun `custom priority order is respected`() {
        val cells = listOf("10-23", "10-24") // 周五 周六
        val satFirst = DatePlanConfig(weekdayPriority = listOf(DayOfWeek.SATURDAY, DayOfWeek.FRIDAY))
        val plan = DatePlanner.plan(cells, monday, satFirst)
        assertEquals(LocalDate.of(2026, 10, 24), plan.chosen!!.date)
    }

    @Test
    fun `single-day window with maxDaysAhead filters`() {
        val cells = listOf("10-20", "10-23") // 周二, 周五(4天后)
        val plan = DatePlanner.plan(cells, monday, DatePlanConfig(maxDaysAhead = 2))
        assertEquals(LocalDate.of(2026, 10, 20), plan.chosen!!.date)
    }

    @Test
    fun `next release target is today plus two before 9am`() {
        val before = LocalDateTime.of(2026, 10, 19, 8, 59, 45)
        assertEquals(LocalDate.of(2026, 10, 21), DatePlanner.nextReleaseTarget(before, 9, 0, 2))
    }

    @Test
    fun `next release target rolls to tomorrow plus two after 9am`() {
        val after = LocalDateTime.of(2026, 10, 19, 9, 5, 0)
        assertEquals(LocalDate.of(2026, 10, 22), DatePlanner.nextReleaseTarget(after, 9, 0, 2))
    }

    @Test
    fun `resolve target date defaults to next release mode`() {
        val now = LocalDateTime.of(2026, 10, 19, 8, 0, 0)
        assertEquals(LocalDate.of(2026, 10, 21), DatePlanner.resolveTargetDate(now, DatePlanConfig(), 9, 0))
    }
}
