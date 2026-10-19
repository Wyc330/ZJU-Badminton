package com.zju.courtgrab

import com.zju.courtgrab.core.SlotCandidate
import com.zju.courtgrab.core.SlotConfig
import com.zju.courtgrab.core.SlotPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotPickerTest {

    private val cfg = SlotConfig()

    private fun slots(vararg texts: String, topStep: Int = 100): List<SlotCandidate> =
        texts.mapIndexed { i, t ->
            SlotCandidate(
                index = i,
                text = t,
                enabled = true,
                clickable = true,
                bounds = com.zju.courtgrab.core.RectI(0, i * topStep, 500, i * topStep + 90),
            )
        }

    @Test
    fun `excludes first and last then picks middle`() {
        val list = slots(
            "06:00-07:00", "07:00-08:00", "08:00-09:00",
            "09:00-10:00", "10:00-11:00", "11:00-12:00",
        )
        val d = SlotPicker.decide(list, cfg, fridayAfternoonOnly = false)!!
        // 6 个可约 → 去掉首尾剩 [07-08, 08-09, 09-10, 10-11]，取 size/2=2 → 09:00-10:00
        assertEquals("09:00-10:00", d.chosen.text)
    }

    @Test
    fun `friday only keeps afternoon and takes middle`() {
        val list = slots(
            "06:00-07:00", "07:00-08:00", "08:00-09:00", "09:00-10:00",
            "14:00-15:00", "15:00-16:00", "16:00-17:00", "17:00-18:00",
        )
        val d = SlotPicker.decide(list, cfg, fridayAfternoonOnly = true)!!
        // 去掉首尾后 6 个，周五过滤掉上午 → [14-15, 15-16, 16-17]，中间 = 15:00-16:00
        assertEquals("15:00-16:00", d.chosen.text)
    }

    @Test
    fun `chinese am pm text is recognised`() {
        val list = slots(
            "上午 08:00-09:00", "上午 09:00-10:00", "上午 10:00-11:00",
            "下午 14:00-15:00", "下午 15:00-16:00", "下午 16:00-17:00",
        )
        val d = SlotPicker.decide(list, cfg, fridayAfternoonOnly = true)!!
        assertTrue(d.chosen.text!!.startsWith("下午"))
    }

    @Test
    fun `unavailable and disabled slots are excluded`() {
        val base = slots(
            "06:00-07:00", "07:00-08:00 已满", "08:00-09:00",
            "09:00-10:00", "10:00-11:00", "11:00-12:00",
        )
        val list = base.toMutableList()
        list[4] = list[4].copy(enabled = false)
        val d = SlotPicker.decide(list, cfg, fridayAfternoonOnly = false)!!
        assertTrue(d.excluded.any { it.first.text!!.contains("已满") })
        assertTrue(d.excluded.any { it.first.text == "10:00-11:00" })
        // 可约：06-07,08-09,09-10,11-12 → 去首尾 → [08-09,09-10] → 中间取 size/2=1 → 09:00-10:00
        assertEquals("09:00-10:00", d.chosen.text)
    }

    @Test
    fun `too few slots returns null`() {
        val list = slots("06:00-07:00", "07:00-08:00")
        assertNull(SlotPicker.decide(list, cfg, fridayAfternoonOnly = false))
    }

    @Test
    fun `unsorted input is sorted by position`() {
        val list = listOf(
            SlotCandidate(0, "10:00-11:00", true, true, com.zju.courtgrab.core.RectI(0, 200, 500, 290)),
            SlotCandidate(1, "06:00-07:00", true, true, com.zju.courtgrab.core.RectI(0, 0, 500, 90)),
            SlotCandidate(2, "08:00-09:00", true, true, com.zju.courtgrab.core.RectI(0, 100, 500, 190)),
            SlotCandidate(3, "12:00-13:00", true, true, com.zju.courtgrab.core.RectI(0, 300, 500, 390)),
        )
        val d = SlotPicker.decide(list, cfg, fridayAfternoonOnly = false)!!
        // 排序后 06,08,10,12 → 去首尾 → [08,10] → 中间 size/2=1 → 10:00-11:00
        assertEquals("10:00-11:00", d.chosen.text)
    }

    @Test
    fun `strict afternoon filter falls back when nothing matches`() {
        val list = slots("A", "B", "C", "D", "E")
        val d = SlotPicker.decide(list, cfg, fridayAfternoonOnly = true)!!
        assertTrue(d.chosen.text in listOf("C", "D"))
    }
}
