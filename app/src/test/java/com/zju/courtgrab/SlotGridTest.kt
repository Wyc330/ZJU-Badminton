package com.zju.courtgrab

import com.zju.courtgrab.core.Rgb
import com.zju.courtgrab.core.SlotCellSample
import com.zju.courtgrab.core.SlotGrid
import com.zju.courtgrab.core.SlotGridConfig
import com.zju.courtgrab.core.SlotRowSamples
import com.zju.courtgrab.core.SlotConfig
import com.zju.courtgrab.core.SlotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 时段表是纯色块，可用性只能靠取色判断，所以这段逻辑必须可靠。
 * 颜色取自真机截图：已预约格实测 #FE4B37。
 */
class SlotGridTest {

    private val cfg = SlotGridConfig()
    private val slotCfg = SlotConfig()

    private fun rgb(hex: String) = Rgb.parse(hex)!!

    @Test
    fun `classifies the real palette`() {
        assertEquals(SlotState.BOOKED, SlotGrid.classify(rgb("#FE4B37"), cfg))
        assertEquals(SlotState.AVAILABLE, SlotGrid.classify(rgb("#67C23A"), cfg))
        assertEquals(SlotState.UNPAID, SlotGrid.classify(rgb("#FFB800"), cfg))
        assertEquals(SlotState.SELECTED, SlotGrid.classify(rgb("#40A9FF"), cfg))
        assertEquals(SlotState.CLOSED, SlotGrid.classify(rgb("#D9D9D9"), cfg))
        assertEquals(SlotState.UNKNOWN, SlotGrid.classify(rgb("#FFFFFF"), cfg))
    }

    @Test
    fun `unknown color is not mistaken for available`() {
        // 屏幕底色/白底绝不能被当成"空闲"
        assertEquals(SlotState.UNKNOWN, SlotGrid.classify(rgb("#F7F8FA"), cfg))
        assertEquals(SlotState.UNKNOWN, SlotGrid.classify(rgb("#123456"), cfg))
    }

    private fun rows(vararg states: List<SlotState>, labels: List<String>? = null): List<SlotRowSamples> =
        states.mapIndexed { ri, perCol ->
            SlotRowSamples(
                rowIndex = ri,
                rowLabel = labels?.getOrNull(ri) ?: "row$ri",
                cells = perCol.mapIndexed { ci, st ->
                    val hex = when (st) {
                        SlotState.AVAILABLE -> "#67C23A"
                        SlotState.BOOKED -> "#FE4B37"
                        SlotState.UNPAID -> "#FFB800"
                        SlotState.SELECTED -> "#40A9FF"
                        SlotState.CLOSED -> "#D9D9D9"
                        SlotState.UNKNOWN -> "#FFFFFF"
                    }
                    SlotCellSample(ri, ci, labels?.getOrNull(ri) ?: "row$ri", "${ci + 1}号", rgb(hex), 100 + ci * 100, 200 + ri * 100)
                },
            )
        }

    @Test
    fun `skips first and last rows and prefers the middle`() {
        val labels = listOf("07:30-08:30", "08:30-09:30", "09:30-10:30", "10:30-11:30", "11:50-12:50")
        val data = rows(
            listOf(SlotState.AVAILABLE),          // 第 1 行（应被排除）
            listOf(SlotState.BOOKED),             // 08:30
            listOf(SlotState.AVAILABLE),          // 09:30 ← 中间
            listOf(SlotState.AVAILABLE),          // 10:30
            listOf(SlotState.AVAILABLE),          // 最后一行（应被排除）
            labels = labels,
        )
        val pick = SlotGrid.decide(data, cfg, slotCfg, fridayAfternoonOnly = false)
        assertNotNull(pick)
        assertEquals("09:30-10:30", pick!!.cell.rowLabel)
        assertEquals(SlotState.AVAILABLE, pick.state)
    }

    @Test
    fun `returns null when everything is booked`() {
        val data = rows(
            listOf(SlotState.BOOKED, SlotState.BOOKED),
            listOf(SlotState.BOOKED, SlotState.BOOKED),
            listOf(SlotState.BOOKED, SlotState.BOOKED),
            listOf(SlotState.BOOKED, SlotState.BOOKED),
        )
        assertNull(SlotGrid.decide(data, cfg, slotCfg, fridayAfternoonOnly = false))
    }

    @Test
    fun `friday skips morning rows`() {
        val labels = listOf(
            "07:30-08:30", "08:30-09:30", "09:30-10:30", "10:30-11:30",
            "11:50-12:50", "13:30-14:30", "14:30-15:30", "15:30-16:30",
            "16:30-17:30", "17:30-18:30", "18:30-19:30",
        )
        // 上午全空闲，下午只有 15:30 空闲
        val data = rows(
            *Array(11) { listOf(SlotState.BOOKED) },
            labels = labels,
        ).mapIndexed { i, row ->
            val setAvailable = when (i) {
                0, 1, 2, 3, 4 -> true       // 上午都空闲
                7 -> true                    // 15:30-16:30
                else -> false
            }
            row.copy(cells = row.cells.map { it.copy(rgb = rgb(if (setAvailable) "#67C23A" else "#FE4B37")) })
        }
        val pick = SlotGrid.decide(data, cfg, slotCfg, fridayAfternoonOnly = true)
        assertNotNull(pick)
        assertEquals("15:30-16:30", pick!!.cell.rowLabel)
    }

    @Test
    fun `picks leftmost available court in the chosen row`() {
        val labels = listOf("07:30-08:30", "08:30-09:30", "09:30-10:30", "10:30-11:30")
        val data = rows(
            listOf(SlotState.BOOKED, SlotState.BOOKED),
            listOf(SlotState.BOOKED, SlotState.AVAILABLE),   // 2号空闲
            listOf(SlotState.AVAILABLE, SlotState.AVAILABLE), // 1号空闲
            listOf(SlotState.BOOKED, SlotState.BOOKED),
            labels = labels,
        )
        val pick = SlotGrid.decide(data, cfg, slotCfg, fridayAfternoonOnly = false)
        assertNotNull(pick)
        // 去掉首尾后只剩 08:30 / 09:30 两行，两行同样"中间"，按行的原顺序先试 08:30
        assertEquals("08:30-09:30", pick!!.cell.rowLabel)
        // 该行 1号已预约、2号空闲 → 取 2号
        assertEquals(1, pick.cell.colIndex)
        assertEquals(SlotState.AVAILABLE, pick.state)
    }
}
