package com.zju.courtgrab

import com.zju.courtgrab.core.ConfigJson
import com.zju.courtgrab.core.DatePlanner
import com.zju.courtgrab.core.GrabConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * 配置层是最容易出错的地方（JSON 键写错、缺键、空数组语义），单独测一遍。
 */
class ConfigJsonTest {

    @Test
    fun `round trip keeps every locator and scalar`() {
        val cfg = GrabConfig.DEFAULT
        val json = ConfigJson.toJson(cfg)
        val back = ConfigJson.merge(GrabConfig.DEFAULT, json)

        assertEquals(cfg.entryService.size, back.entryService.size)
        assertEquals(cfg.entryService[0].id, back.entryService[0].id)
        assertEquals(cfg.entryService[0].textContains, back.entryService[0].textContains)
        assertEquals(cfg.entryBooking.size, back.entryBooking.size)
        assertEquals(cfg.slotRowLabel.textRegex, back.slotRowLabel.textRegex)
        assertEquals(cfg.slotColumnHeader.textRegex, back.slotColumnHeader.textRegex)
        assertEquals(cfg.dateCfg.weekdayPriority, back.dateCfg.weekdayPriority)
        assertEquals(cfg.dateCfg.datePatterns, back.dateCfg.datePatterns)
        assertEquals(cfg.slotCfg.skipFirst, back.slotCfg.skipFirst)
        assertEquals(cfg.slotGridCfg.availableColors, back.slotGridCfg.availableColors)
        assertEquals(cfg.slotGridCfg.bookedColors.first(), back.slotGridCfg.bookedColors.first())
        assertEquals(cfg.companionName, back.companionName)
        assertEquals(cfg.agreeCheckbox.size, back.agreeCheckbox.size)
        assertEquals(cfg.slotCfg.unavailableKeywords, back.slotCfg.unavailableKeywords)
        assertEquals(cfg.stepTimeoutMs, back.stepTimeoutMs)
        assertEquals(cfg.targetPackage, back.targetPackage)
    }

    @Test
    fun `partial json overrides only the given keys`() {
        val json = JSONObject(
            """
            {
              "venueFengYu": [ { "contains": "风雨操场" } ],
              "slotCfg": { "skipFirst": 0 },
              "dateCfg": { "weekdayPriority": ["SATURDAY"] }
            }
            """.trimIndent()
        )
        val cfg = ConfigJson.merge(GrabConfig.DEFAULT, json)

        assertEquals(1, cfg.venueFengYu.size)
        assertEquals("风雨操场", cfg.venueFengYu[0].textContains)
        assertEquals(0, cfg.slotCfg.skipFirst)
        assertEquals(1, cfg.slotCfg.skipLast) // 没写的键保持默认
        assertEquals(listOf(DayOfWeek.SATURDAY), cfg.dateCfg.weekdayPriority)
        assertEquals(GrabConfig.DEFAULT.dateCfg.datePatterns, cfg.dateCfg.datePatterns)
        assertEquals(GrabConfig.DEFAULT.dateCfg.maxDaysAhead, cfg.dateCfg.maxDaysAhead)
        assertEquals(GrabConfig.DEFAULT.entryService.size, cfg.entryService.size)
    }

    @Test
    fun `empty array disables a step`() {
        val cfg = ConfigJson.merge(GrabConfig.DEFAULT, JSONObject("""{"popupClosers":[]}"""))
        assertTrue(cfg.popupClosers.isEmpty())
    }

    @Test
    fun `locator supports regex text class and index`() {
        val loc = ConfigJson.locatorFromJson(
            JSONObject("""{"regex":"\\d{2}:\\d{2}","class":"EditText","index":-1,"order":"LEFT_TOP","note":"x"}""")
        )
        assertEquals("EditText", loc.classNameEndsWith)
        assertEquals(-1, loc.index)
        assertEquals("\\d{2}:\\d{2}", loc.textRegex)
        assertEquals(com.zju.courtgrab.core.MatchOrder.LEFT_TOP, loc.order)
    }

    @Test
    fun `legacy config (no schema) ignores changed default of requirePreferredWeekday`() {
        // v1 时代的导出文件里写着 true，但这批用户从没主动改过它 → 必须按新默认 false 处理
        val legacy = JSONObject("""{"targetPackage":"net.crigh.mysport","requirePreferredWeekday":true}""")
        val cfg = ConfigJson.merge(GrabConfig.DEFAULT, legacy)
        assertEquals(false, cfg.requirePreferredWeekday)
        assertEquals("net.crigh.mysport", cfg.targetPackage)
    }

    @Test
    fun `schema 2 config honors requirePreferredWeekday`() {
        val current = JSONObject("""{"_schemaVersion":2,"requirePreferredWeekday":true}""")
        val cfg = ConfigJson.merge(GrabConfig.DEFAULT, current)
        assertEquals(true, cfg.requirePreferredWeekday)
    }

    @Test
    fun `exported config carries schema version`() {
        val json = ConfigJson.toJson(GrabConfig.DEFAULT)
        assertEquals(ConfigJson.SCHEMA_VERSION, json.optInt("_schemaVersion", -1))
        // 导出一份再读回来，语义不变
        val back = ConfigJson.merge(GrabConfig.DEFAULT, json)
        assertEquals(GrabConfig.DEFAULT.requirePreferredWeekday, back.requirePreferredWeekday)
    }

    @Test
    fun `preview lists next friday saturday sunday`() {
        val monday = LocalDateTime.of(2026, 10, 19, 8, 0)
        val s = DatePlanner.preview(monday, GrabConfig.DEFAULT.dateCfg)
        assertTrue(s, s.contains("周五 10-23"))
        assertTrue(s, s.contains("周六 10-24"))
        assertTrue(s, s.contains("周日 10-25"))
    }

    @Test
    fun `date cfg defaults to next release day strategy`() {
        val cfg = GrabConfig.DEFAULT
        assertTrue(cfg.dateCfg.useNextReleaseDate)
        assertEquals(2, cfg.dateCfg.releaseDaysAhead)
        assertEquals(false, cfg.dateCfg.fridayAfternoonOnly)
    }

    @Test
    fun `direct polling config round trips`() {
        val cfg = GrabConfig.DEFAULT
        val back = ConfigJson.merge(GrabConfig.DEFAULT, ConfigJson.toJson(cfg))
        assertEquals(cfg.directVenueName, back.directVenueName)
        assertEquals(cfg.directMaxWaitMs, back.directMaxWaitMs)
        assertEquals(cfg.directPollMinMs, back.directPollMinMs)
        assertEquals(cfg.directPollJitterMs, back.directPollJitterMs)
        assertEquals(cfg.directMaxAttempts, back.directMaxAttempts)
        assertEquals(cfg.dateCfg.useNextReleaseDate, back.dateCfg.useNextReleaseDate)
        assertEquals(cfg.dateCfg.releaseDaysAhead, back.dateCfg.releaseDaysAhead)
    }
}
