package com.zju.courtgrab.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

/**
 * 配置 ⇄ JSON。JSON 里可以只写一部分键，没写的沿用默认值，
 * 所以你只需要在导出的文件里改你关心的那几行。
 */
object ConfigJson {

    /**
     * 配置 schema 版本。
     *
     * 每次「默认值语义发生变化」就 +1，并在 [merge] 里对旧 schema 的对应键忽略文件里的值、
     * 改用新默认值；否则用户手机上那份旧 grab-config.json 会把新默认值顶掉。
     *
     * v2：requirePreferredWeekday 默认从 true 改成 false（窗口里没有周五/六/日时也照抢）
     */
    const val SCHEMA_VERSION = 2

    // ───────────── Locator ⇄ JSON ─────────────

    fun locatorToJson(l: Locator): JSONObject = JSONObject().apply {
        l.id?.let { put("id", it) }
        l.textEquals?.let { put("text", it) }
        l.textContains?.let { put("contains", it) }
        l.textRegex?.let { put("regex", it) }
        l.descContains?.let { put("desc", it) }
        l.classNameEndsWith?.let { put("class", it) }
        if (l.clickableOnly) put("clickableOnly", true)
        if (!l.enabledOnly) put("enabledOnly", false)
        if (l.order != MatchOrder.TOP_LEFT) put("order", l.order.name)
        if (l.index != 0) put("index", l.index)
        if (l.note.isNotEmpty()) put("note", l.note)
    }

    fun locatorFromJson(o: JSONObject): Locator = Locator(
        id = o.strOrNull("id"),
        textEquals = o.strOrNull("text"),
        textContains = o.strOrNull("contains"),
        textRegex = o.strOrNull("regex"),
        descContains = o.strOrNull("desc"),
        classNameEndsWith = o.strOrNull("class"),
        clickableOnly = o.optBoolean("clickableOnly", false),
        enabledOnly = o.optBoolean("enabledOnly", true),
        order = o.strOrNull("order")?.let {
            runCatching { MatchOrder.valueOf(it.uppercase()) }.getOrNull()
        } ?: MatchOrder.TOP_LEFT,
        index = o.optInt("index", 0),
        note = o.optString("note", ""),
    )

    private fun locatorsToJson(list: List<Locator>): JSONArray = JSONArray().apply {
        list.forEach { put(locatorToJson(it)) }
    }

    private fun locatorsFromJson(arr: JSONArray?): List<Locator> {
        if (arr == null) return emptyList()
        val out = ArrayList<Locator>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out += locatorFromJson(it) }
        }
        return out
    }

    private fun JSONObject.strOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val s = optString(key, "")
        return if (s.isBlank()) null else s
    }

    // ───────────── 子配置 ⇄ JSON ─────────────

    private fun dateCfgToJson(c: DatePlanConfig): JSONObject = JSONObject().apply {
        put("weekdayPriority", JSONArray().apply { c.weekdayPriority.forEach { put(it.name) } })
        put("includeToday", c.includeToday)
        put("maxDaysAhead", c.maxDaysAhead)
        put("fridayAfternoonOnly", c.fridayAfternoonOnly)
        put("datePatterns", JSONArray().apply { c.datePatterns.forEach { put(it) } })
        put("todayWords", JSONArray().apply { c.todayWords.forEach { put(it) } })
        put("tomorrowWords", JSONArray().apply { c.tomorrowWords.forEach { put(it) } })
    }

    private fun dateCfgFromJson(base: DatePlanConfig, o: JSONObject): DatePlanConfig = base.copy(
        weekdayPriority = o.optJSONArray("weekdayPriority")?.let { arr ->
            val list = ArrayList<DayOfWeek>()
            for (i in 0 until arr.length()) {
                runCatching { DayOfWeek.valueOf(arr.optString(i).uppercase()) }.getOrNull()?.let { list += it }
            }
            list
        } ?: base.weekdayPriority,
        includeToday = o.bool("includeToday", base.includeToday),
        maxDaysAhead = o.int("maxDaysAhead", base.maxDaysAhead),
        fridayAfternoonOnly = o.bool("fridayAfternoonOnly", base.fridayAfternoonOnly),
        datePatterns = o.stringList("datePatterns") ?: base.datePatterns,
        todayWords = o.stringList("todayWords") ?: base.todayWords,
        tomorrowWords = o.stringList("tomorrowWords") ?: base.tomorrowWords,
    )

    private fun slotCfgToJson(c: SlotConfig): JSONObject = JSONObject().apply {
        put("unavailableKeywords", JSONArray().apply { c.unavailableKeywords.forEach { put(it) } })
        put("morningPattern", c.morningPattern)
        put("afternoonPattern", c.afternoonPattern)
        put("skipFirst", c.skipFirst)
        put("skipLast", c.skipLast)
        put("minSlots", c.minSlots)
        put("fridayStrictAfternoon", c.fridayStrictAfternoon)
    }

    private fun slotCfgFromJson(base: SlotConfig, o: JSONObject): SlotConfig = base.copy(
        unavailableKeywords = o.stringList("unavailableKeywords") ?: base.unavailableKeywords,
        morningPattern = o.optString("morningPattern", base.morningPattern),
        afternoonPattern = o.optString("afternoonPattern", base.afternoonPattern),
        skipFirst = o.int("skipFirst", base.skipFirst),
        skipLast = o.int("skipLast", base.skipLast),
        minSlots = o.int("minSlots", base.minSlots),
        fridayStrictAfternoon = o.bool("fridayStrictAfternoon", base.fridayStrictAfternoon),
    )

    private fun slotGridCfgToJson(c: SlotGridConfig): JSONObject = JSONObject().apply {
        put("availableColors", JSONArray().apply { c.availableColors.forEach { put(it) } })
        put("bookedColors", JSONArray().apply { c.bookedColors.forEach { put(it) } })
        put("unpaidColors", JSONArray().apply { c.unpaidColors.forEach { put(it) } })
        put("selectedColors", JSONArray().apply { c.selectedColors.forEach { put(it) } })
        put("closedColors", JSONArray().apply { c.closedColors.forEach { put(it) } })
        put("tolerance", c.tolerance)
        put("skipFirstRows", c.skipFirstRows)
        put("skipLastRows", c.skipLastRows)
        put("fridaySkipMorning", c.fridaySkipMorning)
        put("sampleOffsetX", c.sampleOffsetX)
        put("sampleOffsetY", c.sampleOffsetY)
    }

    private fun slotGridCfgFromJson(base: SlotGridConfig, o: JSONObject): SlotGridConfig = base.copy(
        availableColors = o.stringList("availableColors") ?: base.availableColors,
        bookedColors = o.stringList("bookedColors") ?: base.bookedColors,
        unpaidColors = o.stringList("unpaidColors") ?: base.unpaidColors,
        selectedColors = o.stringList("selectedColors") ?: base.selectedColors,
        closedColors = o.stringList("closedColors") ?: base.closedColors,
        tolerance = o.int("tolerance", base.tolerance),
        skipFirstRows = o.int("skipFirstRows", base.skipFirstRows),
        skipLastRows = o.int("skipLastRows", base.skipLastRows),
        fridaySkipMorning = o.bool("fridaySkipMorning", base.fridaySkipMorning),
        sampleOffsetX = o.int("sampleOffsetX", base.sampleOffsetX),
        sampleOffsetY = o.int("sampleOffsetY", base.sampleOffsetY),
    )

    private fun JSONObject.stringList(key: String): List<String>? {
        if (!has(key)) return null
        val arr = optJSONArray(key) ?: return null
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out += arr.optString(i)
        return out
    }

    private fun JSONObject.bool(key: String, def: Boolean) = if (has(key)) optBoolean(key, def) else def

    private fun JSONObject.int(key: String, def: Int) = if (has(key)) optInt(key, def) else def

    private fun JSONObject.lng(key: String, def: Long) = if (has(key)) optLong(key, def) else def

    private fun JSONObject.flt(key: String, def: Float) = if (has(key)) optDouble(key, def.toDouble()).toFloat() else def

    private fun JSONObject.str(key: String, def: String) = if (has(key)) optString(key, def) else def

    private fun JSONObject.locators(key: String, def: List<Locator>): List<Locator> =
        if (has(key) && !isNull(key)) locatorsFromJson(optJSONArray(key)) else def

    private fun JSONObject.locator(key: String, def: Locator): Locator =
        if (has(key) && !isNull(key)) optJSONObject(key)?.let { locatorFromJson(it) } ?: def else def

    // ───────────── 整份配置 ─────────────

    fun toJson(cfg: GrabConfig): JSONObject = JSONObject().apply {
        put(
            "_说明",
            "只改你关心的键即可；删除某个键 = 使用默认值。locator 支持 id/text/contains/regex/desc/class。" +
                "抓取界面请在「抢场地」里点「延时抓取」（先切到浙大体艺目标页）。",
        )
        put("targetPackage", cfg.targetPackage)
        put("targetActivity", cfg.targetActivity)
        put("launchTimeoutMs", cfg.launchTimeoutMs)
        put("popupClosers", locatorsToJson(cfg.popupClosers))
        put("popupRounds", cfg.popupRounds)
        put("homeReady", locatorsToJson(cfg.homeReady))
        put("loginHints", locatorsToJson(cfg.loginHints))
        put("entryService", locatorsToJson(cfg.entryService))
        put("entryBooking", locatorsToJson(cfg.entryBooking))
        put("bookingPageReady", locatorsToJson(cfg.bookingPageReady))
        put("campusTarget", cfg.campusTarget)
        put("campusSelect", locatorsToJson(cfg.campusSelect))
        put("campusOptional", cfg.campusOptional)
        put("campusClick", cfg.campusClick)
        put("venueFengYu", locatorsToJson(cfg.venueFengYu))
        put("venueBadminton", locatorsToJson(cfg.venueBadminton))
        put("skipCourtClickIfGridReady", cfg.skipCourtClickIfGridReady)
        put("dateDisplay", locatorToJson(cfg.dateDisplay))
        put("dateArrowFallbackRatio", cfg.dateArrowFallbackRatio)
        put("dateCfg", dateCfgToJson(cfg.dateCfg))
        put("_schemaVersion", SCHEMA_VERSION)
        put("requirePreferredWeekday", cfg.requirePreferredWeekday)
        put("preferFreshDates", cfg.preferFreshDates)
        put("releaseHour", cfg.releaseHour)
        put("releaseMinute", cfg.releaseMinute)
        put("releaseSecond", cfg.releaseSecond)
        put("waitForRelease", cfg.waitForRelease)
        put("refreshTapRatio", cfg.refreshTapRatio)
        put("slotRetryMs", cfg.slotRetryMs)
        put("slotRowLabel", locatorToJson(cfg.slotRowLabel))
        put("slotColumnHeader", locatorToJson(cfg.slotColumnHeader))
        put("useCellNodes", cfg.useCellNodes)
        put("cellMinWidth", cfg.cellMinWidth)
        put("cellMinHeight", cfg.cellMinHeight)
        put("maxVerticalScrollPasses", cfg.maxVerticalScrollPasses)
        put("maxHorizontalScrollPasses", cfg.maxHorizontalScrollPasses)
        put("slotSampleRadius", cfg.slotSampleRadius)
        put("slotGridCfg", slotGridCfgToJson(cfg.slotGridCfg))
        put("slotCfg", slotCfgToJson(cfg.slotCfg))
        put("slotVerifyByRecapture", cfg.slotVerifyByRecapture)
        put("agreeCheckbox", locatorsToJson(cfg.agreeCheckbox))
        put("btnBookNow", locatorsToJson(cfg.btnBookNow))
        put("companionPageReady", locatorsToJson(cfg.companionPageReady))
        put("companionName", cfg.companionName)
        put("companionCandidates", locatorsToJson(cfg.companionCandidates))
        put("companionFallback", locatorsToJson(cfg.companionFallback))
        put("companionCodeHint", locatorsToJson(cfg.companionCodeHint))
        put("codeInput", locatorsToJson(cfg.codeInput))
        put("btnConfirm", locatorsToJson(cfg.btnConfirm))
        put("btnSubmitOrder", locatorsToJson(cfg.btnSubmitOrder))
        put("successSigns", locatorsToJson(cfg.successSigns))
        put("failureSigns", locatorsToJson(cfg.failureSigns))
        put("stepTimeoutMs", cfg.stepTimeoutMs)
        put("navTimeoutMs", cfg.navTimeoutMs)
        put("pollIntervalMs", cfg.pollIntervalMs)
        put("afterClickDelayMs", cfg.afterClickDelayMs)
        put("afterNavDelayMs", cfg.afterNavDelayMs)
        put("screenshotIntervalMs", cfg.screenshotIntervalMs)
        put("inviteCodeWaitMs", cfg.inviteCodeWaitMs)
        put("submitVerifyMs", cfg.submitVerifyMs)
    }

    fun merge(base: GrabConfig, json: JSONObject): GrabConfig {
        // 旧版配置文件没有 _schemaVersion：视为 v1
        val schema = json.optInt("_schemaVersion", 1)
        return mergeWithSchema(base, json, schema)
    }

    private fun mergeWithSchema(base: GrabConfig, json: JSONObject, schema: Int): GrabConfig = base.copy(
        targetPackage = json.str("targetPackage", base.targetPackage),
        targetActivity = json.str("targetActivity", base.targetActivity),
        launchTimeoutMs = json.lng("launchTimeoutMs", base.launchTimeoutMs),
        popupClosers = json.locators("popupClosers", base.popupClosers),
        popupRounds = json.int("popupRounds", base.popupRounds),
        homeReady = json.locators("homeReady", base.homeReady),
        loginHints = json.locators("loginHints", base.loginHints),
        entryService = json.locators("entryService", base.entryService),
        entryBooking = json.locators("entryBooking", base.entryBooking),
        bookingPageReady = json.locators("bookingPageReady", base.bookingPageReady),
        campusTarget = json.str("campusTarget", base.campusTarget),
        campusSelect = json.locators("campusSelect", base.campusSelect),
        campusOptional = json.bool("campusOptional", base.campusOptional),
        campusClick = json.bool("campusClick", base.campusClick),
        venueFengYu = json.locators("venueFengYu", base.venueFengYu),
        venueBadminton = json.locators("venueBadminton", base.venueBadminton),
        skipCourtClickIfGridReady = json.bool("skipCourtClickIfGridReady", base.skipCourtClickIfGridReady),
        dateDisplay = json.locator("dateDisplay", base.dateDisplay),
        dateArrowFallbackRatio = json.flt("dateArrowFallbackRatio", base.dateArrowFallbackRatio),
        dateCfg = json.optJSONObject("dateCfg")?.let { dateCfgFromJson(base.dateCfg, it) } ?: base.dateCfg,
        // v2 改了默认值：旧配置里这个键不可信，直接用新默认值
        requirePreferredWeekday = if (schema >= 2) {
            json.bool("requirePreferredWeekday", base.requirePreferredWeekday)
        } else {
            base.requirePreferredWeekday
        },
        preferFreshDates = json.bool("preferFreshDates", base.preferFreshDates),
        releaseHour = json.int("releaseHour", base.releaseHour),
        releaseMinute = json.int("releaseMinute", base.releaseMinute),
        releaseSecond = json.int("releaseSecond", base.releaseSecond),
        waitForRelease = json.bool("waitForRelease", base.waitForRelease),
        refreshTapRatio = json.flt("refreshTapRatio", base.refreshTapRatio),
        slotRetryMs = json.lng("slotRetryMs", base.slotRetryMs),
        slotRowLabel = json.locator("slotRowLabel", base.slotRowLabel),
        slotColumnHeader = json.locator("slotColumnHeader", base.slotColumnHeader),
        useCellNodes = json.bool("useCellNodes", base.useCellNodes),
        cellMinWidth = json.int("cellMinWidth", base.cellMinWidth),
        cellMinHeight = json.int("cellMinHeight", base.cellMinHeight),
        maxVerticalScrollPasses = json.int("maxVerticalScrollPasses", base.maxVerticalScrollPasses),
        maxHorizontalScrollPasses = json.int("maxHorizontalScrollPasses", base.maxHorizontalScrollPasses),
        slotSampleRadius = json.int("slotSampleRadius", base.slotSampleRadius),
        slotGridCfg = json.optJSONObject("slotGridCfg")
            ?.let { slotGridCfgFromJson(base.slotGridCfg, it) } ?: base.slotGridCfg,
        slotCfg = json.optJSONObject("slotCfg")?.let { slotCfgFromJson(base.slotCfg, it) } ?: base.slotCfg,
        slotVerifyByRecapture = json.bool("slotVerifyByRecapture", base.slotVerifyByRecapture),
        agreeCheckbox = json.locators("agreeCheckbox", base.agreeCheckbox),
        btnBookNow = json.locators("btnBookNow", base.btnBookNow),
        companionPageReady = json.locators("companionPageReady", base.companionPageReady),
        companionName = json.str("companionName", base.companionName),
        companionCandidates = json.locators("companionCandidates", base.companionCandidates),
        companionFallback = json.locators("companionFallback", base.companionFallback),
        companionCodeHint = json.locators("companionCodeHint", base.companionCodeHint),
        codeInput = json.locators("codeInput", base.codeInput),
        btnConfirm = json.locators("btnConfirm", base.btnConfirm),
        btnSubmitOrder = json.locators("btnSubmitOrder", base.btnSubmitOrder),
        successSigns = json.locators("successSigns", base.successSigns),
        failureSigns = json.locators("failureSigns", base.failureSigns),
        stepTimeoutMs = json.lng("stepTimeoutMs", base.stepTimeoutMs),
        navTimeoutMs = json.lng("navTimeoutMs", base.navTimeoutMs),
        pollIntervalMs = json.lng("pollIntervalMs", base.pollIntervalMs),
        afterClickDelayMs = json.lng("afterClickDelayMs", base.afterClickDelayMs),
        afterNavDelayMs = json.lng("afterNavDelayMs", base.afterNavDelayMs),
        screenshotIntervalMs = json.lng("screenshotIntervalMs", base.screenshotIntervalMs),
        inviteCodeWaitMs = json.lng("inviteCodeWaitMs", base.inviteCodeWaitMs),
        submitVerifyMs = json.lng("submitVerifyMs", base.submitVerifyMs),
    )
}
