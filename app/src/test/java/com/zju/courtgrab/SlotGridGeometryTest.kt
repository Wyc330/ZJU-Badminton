package com.zju.courtgrab

import com.zju.courtgrab.core.Box
import com.zju.courtgrab.core.CellBox
import com.zju.courtgrab.core.LabelBox
import com.zju.courtgrab.core.SlotGridGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真机 dump 的真实坐标验证「格子 ↔ 行标题」的配对逻辑。
 */
class SlotGridGeometryTest {

    // 真机坐标（1200x2608 屏，截图 dump 2026-10-19 22:00）
    private val labels = listOf(
        LabelBox("07:30-08:30", Box(66, 1725, 351, 1851)),
        LabelBox("08:30-09:30", Box(66, 1848, 351, 1974)),
        LabelBox("09:30-10:30", Box(66, 1971, 351, 2097)),
        LabelBox("13:30-14:30", Box(66, 2340, 351, 2466)),
    )

    // 每行 3 个可见格子（1号 354 宽、2号 354 宽、3号被屏幕边缘裁剩 144）
    private val cells = buildList {
        listOf(1725, 1848, 1971, 2340).forEach { y ->
            add(CellBox(Box(348, y, 702, y + 123)))
            add(CellBox(Box(702, y, 1056, y + 123)))
            add(CellBox(Box(1056, y, 1200, y + 123)))
        }
    }

    @Test
    fun `parse start minute`() {
        assertEquals(450, SlotGridGeometry.parseStartMinute("07:30-08:30"))
        assertEquals(1350, SlotGridGeometry.parseStartMinute("22:30-23:30"))
        assertEquals(830, SlotGridGeometry.parseStartMinute("13：50-14:50"))
        assertNull(SlotGridGeometry.parseStartMinute("时间段"))
    }

    @Test
    fun `groups real cells into rows by vertical overlap`() {
        val rows = SlotGridGeometry.group(cells, labels)
        assertEquals(4, rows.size)
        assertEquals(listOf("07:30-08:30", "08:30-09:30", "09:30-10:30", "13:30-14:30"), rows.map { it.label })
        rows.forEach { assertEquals(3, it.cells.size) }
        // 每行内按 x 排序
        assertTrue(rows[0].cells[0].box.left < rows[0].cells[1].box.left)
        assertEquals(450, rows[0].startMinute)
    }

    @Test
    fun `ignores cells that do not overlap any row label`() {
        val stray = listOf(CellBox(Box(348, 3000, 702, 3123)))
        val rows = SlotGridGeometry.group(cells + stray, labels)
        assertEquals(4, rows.size)
        assertEquals(12, rows.sumOf { it.cells.size })
    }

    @Test
    fun `finds date arrows and refresh button by geometry`() {
        // 真机日期行：EditText [396,357][729,456]，左箭头 [249,360][393,456]，右箭头 [732,360][876,456]，刷新 [885,360][1029,456]
        val editor = Box(396, 357, 729, 456)
        val candidates = listOf(
            Box(249, 360, 393, 456),   // 上一天
            Box(732, 360, 876, 456),   // 下一天
            Box(885, 360, 1029, 456),  // 刷新
            Box(66, 357, 246, 456),    // "预约日期"标签（不应该被选中）
        )
        assertEquals(Box(732, 360, 876, 456), SlotGridGeometry.findNeighbor(editor, candidates, forward = true, index = 0))
        assertEquals(Box(885, 360, 1029, 456), SlotGridGeometry.findNeighbor(editor, candidates, forward = true, index = 1))
        assertEquals(Box(249, 360, 393, 456), SlotGridGeometry.findNeighbor(editor, candidates, forward = false, index = 0))
    }

    @Test
    fun `findNeighbor returns null when nothing on the same band`() {
        val editor = Box(396, 357, 729, 456)
        val far = listOf(Box(732, 1200, 876, 1300))
        assertNull(SlotGridGeometry.findNeighbor(editor, far, forward = true))
    }
}
