package com.zju.courtgrab.core

/**
 * 时段挑选规则：
 *  1. 先剔除「不可预约」的时段（文案关键词 / 不可点击 / disabled）
 *  2. 排除第一个和最后一个
 *  3. 在剩下的里取最接近中间的
 *  4. 周五只看下午（识别方式用正则，不假设时间段文案格式）
 */
data class SlotConfig(
    /** 文案里出现这些词就当作「不可约」 */
    val unavailableKeywords: List<String> = listOf(
        "已满", "已约满", "约满", "不可约", "不可预约", "已预约", "无可用", "已结束", "未开放", "停用",
    ),
    /** 上午时段的识别正则（英文/中文/24小时制都能认出来就写上） */
    val morningPattern: String = "(上午|早上|早晨|AM|\\b0?[6-9]\\s*[:：]|\\b1[01]\\s*[:：])",
    /** 下午时段的识别正则 */
    val afternoonPattern: String = "(下午|傍晚|晚上|PM|\\b1[2-9]\\s*[:：]|\\b2[0-3]\\s*[:：])",
    /** 排除第一个可约时段 */
    val skipFirst: Int = 1,
    /** 排除最后一个可约时段 */
    val skipLast: Int = 1,
    /** 可约时段少于这个数就不硬选（配置里可调小） */
    val minSlots: Int = 3,
    /**
     * true  = 周五严格只认「能正识别为下午」的时段（正则都不匹配的会被排除）
     * false = 只要不能被识别为上午就算候选
     */
    val fridayStrictAfternoon: Boolean = true,
)

data class SlotCandidate(
    /** 在本次匹配到的原始节点列表里的下标（回点用） */
    val index: Int,
    val text: String?,
    val enabled: Boolean,
    val clickable: Boolean,
    val bounds: RectI,
)

data class SlotDecision(
    val chosen: SlotCandidate,
    val considered: List<SlotCandidate>,
    val all: List<SlotCandidate>,
    val excluded: List<Pair<SlotCandidate, String>>,
    val reason: String,
)

object SlotPicker {

    private fun regex(pattern: String): Regex? =
        runCatching { Regex(pattern) }.getOrNull()

    fun isMorning(text: String?, cfg: SlotConfig): Boolean {
        if (text.isNullOrBlank()) return false
        val r = regex(cfg.morningPattern) ?: return false
        return r.containsMatchIn(text)
    }

    fun isAfternoon(text: String?, cfg: SlotConfig): Boolean {
        if (text.isNullOrBlank()) return false
        val r = regex(cfg.afternoonPattern) ?: return false
        return r.containsMatchIn(text)
    }

    fun isUnavailable(text: String?, cfg: SlotConfig): Boolean {
        if (text.isNullOrBlank()) return false
        return cfg.unavailableKeywords.any { text.contains(it) }
    }

    /**
     * @param fridayAfternoonOnly 当前选中的日期是不是周五且要求只约下午
     */
    fun decide(
        raw: List<SlotCandidate>,
        cfg: SlotConfig,
        fridayAfternoonOnly: Boolean,
        log: (String) -> Unit = {},
    ): SlotDecision? {
        val sorted = raw.sortedWith(
            compareBy({ it.bounds.top }, { it.bounds.left })
        )
        if (sorted.isEmpty()) {
            log("没有匹配到任何时段节点（检查 timeSlot 定位条件）")
            return null
        }

        val excluded = ArrayList<Pair<SlotCandidate, String>>()
        val available = ArrayList<SlotCandidate>()
        for (s in sorted) {
            when {
                !s.enabled -> excluded += s to "disabled"
                isUnavailable(s.text, cfg) -> excluded += s to "文案含不可约关键词(${s.text})"
                else -> available += s
            }
        }
        log("时段原始 ${sorted.size} 个，可约 ${available.size} 个，剔除 ${excluded.size} 个")

        if (available.size < maxOf(cfg.minSlots, cfg.skipFirst + cfg.skipLast + 1)) {
            log("可约时段不足（需要≥${maxOf(cfg.minSlots, cfg.skipFirst + cfg.skipLast + 1)}，实际 ${available.size}）")
            return null
        }

        var considered = available.toList()
        if (cfg.skipFirst > 0) considered = considered.drop(cfg.skipFirst)
        if (cfg.skipLast > 0) considered = considered.dropLast(cfg.skipLast)
        log("去掉首尾后剩 ${considered.size} 个：${considered.joinToString(" | ") { it.text ?: "?" }}")

        if (fridayAfternoonOnly) {
            val pm = considered.filter { isAfternoon(it.text, cfg) }
            if (pm.isNotEmpty()) {
                considered = pm
                log("周五只看下午，剩 ${pm.size} 个")
            } else {
                log("周五过滤后没有可识别为「下午」的时段，退回全部候选（检查 afternoonPattern 配置）")
            }
        }

        if (considered.isEmpty()) {
            log("过滤后没有候选时段")
            return null
        }

        // 取最接近中间的：下标 size/2
        val mid = considered.size / 2
        val chosen = considered[mid]
        val reason = buildString {
            append("时段共${available.size}个可约，去掉首尾后中间候选=${considered.size}，")
            append("取第${mid + 1}个：")
            append(chosen.text ?: "[无文字, ${chosen.bounds}]")
            if (fridayAfternoonOnly) append("（周五·下午）")
        }
        return SlotDecision(
            chosen = chosen,
            considered = considered,
            all = sorted,
            excluded = excluded,
            reason = reason,
        )
    }
}
