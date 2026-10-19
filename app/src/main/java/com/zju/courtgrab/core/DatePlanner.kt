package com.zju.courtgrab.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 日期策略配置。
 *
 * 这里不假设界面上日期长什么样：界面上所有「日期格」的文字会被 [DatePlanner.plan]
 * 用 [datePatterns] 解析成真实日期，再按星期几优先级排序，最后挑第一个可用的。
 * 如果你们预约页的日期格文案不是常见格式，只要在配置里加一条正则即可。
 */
data class DatePlanConfig(
    /** 优先级：先周五，再周六，再周日。顺序可改（例如 ["SATURDAY","SUNDAY"]） */
    val weekdayPriority: List<DayOfWeek> = listOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
    /** 是否允许选今天（周五当天上午抢当天下午的场） */
    val includeToday: Boolean = true,
    /** 只考虑未来 N 天内；0 = 不限制（交给界面里实际存在哪些日期） */
    val maxDaysAhead: Int = 0,
    /** 选中周五时是否只挑下午时段 */
    val fridayAfternoonOnly: Boolean = true,
    /** 日期格文案的正则，必须用「2 组」或「3 组」捕获分别表示 月/日 或 年/月/日 */
    val datePatterns: List<String> = listOf(
        "(\\d{4})\\s*[-/\\.年]\\s*(\\d{1,2})\\s*[-/\\.月]\\s*(\\d{1,2})",
        "(\\d{1,2})\\s*[-/\\.月]\\s*(\\d{1,2})\\s*日?",
        "(\\d{1,2})\\s*月\\s*(\\d{1,2})",
    ),
    val todayWords: List<String> = listOf("今天", "今日"),
    val tomorrowWords: List<String> = listOf("明天", "明日"),
)

/** 界面上一个解析成功的日期格 */
data class VisibleDate(
    /** 在传入的日期格列表里的下标（用于回点） */
    val cellIndex: Int,
    val date: LocalDate,
    val rawText: String,
    /** 相对「今天」的天数，0 = 今天 */
    val daysAhead: Long = 0,
) {
    val weekday: DayOfWeek get() = date.dayOfWeek
    val isFriday: Boolean get() = date.dayOfWeek == DayOfWeek.FRIDAY
}

data class DatePlan(
    val ranked: List<VisibleDate>,
    val chosen: VisibleDate?,
    val reason: String,
)

object DatePlanner {

    private val patternsCache = HashMap<String, Regex?>()

    private fun regexOf(pattern: String): Regex? =
        patternsCache.getOrPut(pattern) { runCatching { Regex(pattern) }.getOrNull() }

    /** 把一段日期格文字解析成具体日期；解析不出来返回 null */
    fun parseCell(
        raw: String,
        now: LocalDateTime,
        cfg: DatePlanConfig,
    ): LocalDate? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val today = now.toLocalDate()

        if (cfg.todayWords.any { text.contains(it) }) return today
        if (cfg.tomorrowWords.any { text.contains(it) }) return today.plusDays(1)

        for (pattern in cfg.datePatterns) {
            val regex = regexOf(pattern) ?: continue
            val m = regex.find(text) ?: continue
            val groups = m.groupValues.drop(1).mapNotNull { it.trim().toIntOrNull() }
            val (month, day) = when (groups.size) {
                3 -> groups[1] to groups[2] // 年/月/日
                2 -> groups[0] to groups[1] // 月/日
                else -> continue
            }
            if (month !in 1..12 || day !in 1..31) continue
            val resolved = resolveYear(month, day, today) ?: continue
            return resolved
        }
        return null
    }

    /** 只有月/日时推断年份：取落在今天前后合理窗口内的那一年 */
    private fun resolveYear(month: Int, day: Int, today: LocalDate): LocalDate? {
        for (year in listOf(today.year, today.year + 1)) {
            val d = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: continue
            val diff = ChronoUnit.DAYS.between(today, d)
            if (diff >= -45 && diff <= 400) return d
        }
        return null
    }

    /**
     * 输入界面上所有日期格的文字（顺序 = 界面顺序），输出按优先级排序的候选日期。
     * 排序规则：星期优先级 → 日期先后 → 界面顺序。
     */
    fun plan(
        cellTexts: List<String?>,
        now: LocalDateTime,
        cfg: DatePlanConfig,
    ): DatePlan {
        val today = now.toLocalDate()
        val parsed = ArrayList<VisibleDate>()
        for ((idx, text) in cellTexts.withIndex()) {
            if (text.isNullOrBlank()) continue
            val date = parseCell(text, now, cfg) ?: continue
            val ahead = ChronoUnit.DAYS.between(today, date)
            if (ahead < 0) continue
            if (!cfg.includeToday && ahead == 0L) continue
            if (cfg.maxDaysAhead > 0 && ahead > cfg.maxDaysAhead) continue
            parsed += VisibleDate(cellIndex = idx, date = date, rawText = text, daysAhead = ahead)
        }

        if (parsed.isEmpty()) {
            return DatePlan(emptyList(), null, "界面上没有解析出任何日期（检查 datePatterns 正则）")
        }

        val priority = cfg.weekdayPriority
        val ranked = if (priority.isEmpty()) {
            parsed.sortedWith(compareBy({ it.date }, { it.cellIndex }))
        } else {
            parsed.sortedWith(
                compareBy<VisibleDate> { vd ->
                    val i = priority.indexOf(vd.weekday)
                    if (i < 0) priority.size + vd.date.dayOfWeek.value else i
                }.thenBy { it.date }.thenBy { it.cellIndex }
            )
        }

        // 如果优先级里的星期在界面上一个都没有，退化为「按日期最早」
        val inPriority = if (priority.isEmpty()) ranked
        else ranked.filter { priority.contains(it.weekday) }
        val finalRanked = inPriority.ifEmpty { parsed.sortedWith(compareBy({ it.date }, { it.cellIndex })) }

        val chosen = finalRanked.firstOrNull()
        val reason = if (chosen == null) "无可用日期" else
            "选中 ${chosen.date} (${weekdayCn(chosen.weekday)}) 文案=\"${chosen.rawText}\"" +
                if (priority.isEmpty()) "" else "；优先级=${priority.joinToString(",") { weekdayCn(it) }}"
        return DatePlan(finalRanked, chosen, reason)
    }

    fun weekdayCn(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "周一"
        DayOfWeek.TUESDAY -> "周二"
        DayOfWeek.WEDNESDAY -> "周三"
        DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"
        DayOfWeek.SATURDAY -> "周六"
        DayOfWeek.SUNDAY -> "周日"
    }
    /** 解析「预约日期」框里的字符串（如 2026-10-19） */
    fun parseDisplayed(text: String?, cfg: DatePlanConfig, now: LocalDateTime = LocalDateTime.now()): LocalDate? {
        if (text.isNullOrBlank()) return null
        return parseCell(text, now, cfg)
    }

    /**
     * 按「预约窗口」算出候选日期。
     *
     * 实测该 H5 的窗口是「今天 + 之后 2 天」（日期选择器里 22 号以后是灰的），所以窗口内
     * 能抢到的周五/六/日取决于今天是星期几：
     *   周三~周五 → 周五；周四~周六 → 周六；周五~周日 → 周日。
     *
     * @param requirePreferred true 时，窗口内若没有优先级里的星期就返回空（这次不抢）
     */
    fun windowCandidates(
        now: LocalDateTime,
        cfg: DatePlanConfig,
        maxDaysAhead: Int,
        requirePreferred: Boolean = true,
        preferFresh: Boolean = true,
    ): List<LocalDate> {
        val today = now.toLocalDate()
        val days = (0..maxDaysAhead.coerceAtLeast(0)).map { today.plusDays(it.toLong()) }
        val priorityIndex = { d: LocalDate ->
            val i = cfg.weekdayPriority.indexOf(d.dayOfWeek)
            if (i < 0) cfg.weekdayPriority.size else i
        }
        // 每天 09:00 放「后天」的号：后天才是有货的那天，优先试它（preferFresh）
        val ranked = if (preferFresh) {
            days.sortedByDescending { it }
        } else {
            days.sortedWith(compareBy(priorityIndex).thenBy { it })
        }
        val inPriority = ranked.filter { cfg.weekdayPriority.contains(it.dayOfWeek) }
        return when {
            inPriority.isNotEmpty() -> inPriority
            requirePreferred -> emptyList()
            else -> ranked
        }
    }

    /**
     * 给界面用的一句话预览：按优先级算出接下来会优先尝试的日期。
     * 注意：真正的日期选择以界面上实际存在的日期格为准（本函数只是让你确认优先级配置）。
     */
    fun preview(now: LocalDateTime, cfg: DatePlanConfig): String {
        if (cfg.weekdayPriority.isEmpty()) return "（未配置优先级，按界面上最早可约日期）"
        val today = now.toLocalDate()
        return cfg.weekdayPriority.joinToString(" → ") { wd ->
            var ahead = (wd.value - today.dayOfWeek.value + 7) % 7
            if (ahead == 0 && !cfg.includeToday) ahead = 7
            val d = today.plusDays(ahead.toLong())
            "${weekdayCn(wd)} ${d.monthValue}-${d.dayOfMonth}"
        }
    }
}