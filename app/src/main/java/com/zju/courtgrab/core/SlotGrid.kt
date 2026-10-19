package com.zju.courtgrab.core

import kotlin.math.abs

/**
 * 时段格状态。
 *
 * 「浙大体艺」的预约页（H5）时段表是**纯色块**：没有文字、没有 contentDescription，
 * 颜色就是状态 —— 图例：空闲(绿) / 未支付(黄) / 已预约(红) / 已选择(浅蓝) / 不开放(灰)。
 * 所以可用性只能从截图像素判断，这个文件负责「取色 → 分类 → 按规则选格」的纯逻辑。
 */
enum class SlotState(val cn: String) {
    AVAILABLE("空闲"),
    BOOKED("已预约"),
    UNPAID("未支付"),
    SELECTED("已选择"),
    CLOSED("不开放"),
    UNKNOWN("未知"),
}

data class Rgb(val r: Int, val g: Int, val b: Int) {
    fun distance(o: Rgb): Int = abs(r - o.r) + abs(g - o.g) + abs(b - o.b)

    fun isNearWhite(): Boolean = r >= 245 && g >= 245 && b >= 245

    fun hex(): String = "#%02X%02X%02X".format(r, g, b)

    companion object {
        fun parse(hex: String): Rgb? {
            val s = hex.trim().removePrefix("#")
            if (s.length != 6) return null
            val v = s.toIntOrNull(16) ?: return null
            return Rgb((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
        }

        fun ofArgb(argb: Int): Rgb = Rgb((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }
}

/**
 * 取色阈值与选中规则。
 * 颜色默认值是从你给的真机截图上采样的（已预约格实测 #FE4B37）。
 */
data class SlotGridConfig(
    /** 空闲（绿）—— 图例「空闲」文字实测 #8ECF75，取几个常见绿 */
    val availableColors: List<String> = listOf("#67C23A", "#52C41A", "#4CAF50", "#6BCB77", "#8ECF75"),
    /** 已预约（红）—— 截图上格子底色实测 #FE4B37 */
    val bookedColors: List<String> = listOf("#FE4B37", "#EB5E54", "#F5333F", "#FF4D3A"),
    /** 未支付（黄） */
    val unpaidColors: List<String> = listOf("#FFB800", "#FAAD14", "#FEC43F", "#FFC107"),
    /** 已选择（浅蓝） */
    val selectedColors: List<String> = listOf("#40A9FF", "#75BEF3", "#1890FF"),
    /** 不开放（灰） */
    val closedColors: List<String> = listOf("#D9D9D9", "#BFBFBF", "#EEEEEE", "#E0E0E0"),
    /** 颜色容差（RGB 曼哈顿距离），超过就判 UNKNOWN */
    val tolerance: Int = 120,
    /** 排除最前面 N 个时间段 */
    val skipFirstRows: Int = 1,
    /** 排除最后面 N 个时间段 */
    val skipLastRows: Int = 1,
    /** 周五是否跳过上午的时段 */
    val fridaySkipMorning: Boolean = true,
    /** 采样点相对格心的偏移（有些界面格子中间有文字，可微调） */
    val sampleOffsetX: Int = 0,
    val sampleOffsetY: Int = 0,
)

data class SlotCellSample(
    val rowIndex: Int,
    val colIndex: Int,
    val rowLabel: String?,
    val colLabel: String?,
    val rgb: Rgb,
    val x: Int,
    val y: Int,
)

data class SlotRowSamples(
    val rowIndex: Int,
    val rowLabel: String?,
    val cells: List<SlotCellSample>,
)

data class SlotPick(
    val cell: SlotCellSample,
    val state: SlotState,
    val reason: String,
    /** 供日志输出的整张表 */
    val table: String,
)

object SlotGrid {

    private fun colors(list: List<String>): List<Rgb> =
        list.mapNotNull { Rgb.parse(it) }

    fun classify(rgb: Rgb, cfg: SlotGridConfig): SlotState {
        if (rgb.isNearWhite()) return SlotState.UNKNOWN
        val candidates = listOf(
            SlotState.AVAILABLE to colors(cfg.availableColors),
            SlotState.BOOKED to colors(cfg.bookedColors),
            SlotState.UNPAID to colors(cfg.unpaidColors),
            SlotState.SELECTED to colors(cfg.selectedColors),
            SlotState.CLOSED to colors(cfg.closedColors),
        )
        var best: SlotState = SlotState.UNKNOWN
        var bestDist = Int.MAX_VALUE
        for ((state, palettes) in candidates) {
            for (p in palettes) {
                val d = rgb.distance(p)
                if (d < bestDist) {
                    bestDist = d
                    best = state
                }
            }
        }
        return if (bestDist <= cfg.tolerance) best else SlotState.UNKNOWN
    }

    /** 把「整个时段表」的状态渲染成一行行文字，方便日志里直接看 */
    fun renderTable(rows: List<SlotRowSamples>): String {
        if (rows.isEmpty()) return "(空)"
        val colCount = rows.maxOf { it.cells.size }
        val sb = StringBuilder()
        val header = StringBuilder("时间段".padEnd(14))
        for (c in 0 until colCount) {
            val label = rows.firstNotNullOfOrNull { r -> r.cells.getOrNull(c)?.colLabel } ?: "#${c + 1}"
            header.append(label.padEnd(8))
        }
        sb.append(header).append('\n')
        for (r in rows) {
            sb.append((r.rowLabel ?: "#${r.rowIndex}").padEnd(14))
            for (c in 0 until colCount) {
                val cell = r.cells.getOrNull(c)
                sb.append((cell?.let { classifyCache[it] ?: "?" } ?: "-").padEnd(8))
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** 只用于 renderTable 时复用分类结果，避免重复算 */
    private val classifyCache = HashMap<SlotCellSample, String>()

    fun cacheStates(rows: List<SlotRowSamples>, cfg: SlotGridConfig) {
        classifyCache.clear()
        for (r in rows) for (c in r.cells) classifyCache[c] = classify(c.rgb, cfg).cn
    }

    /**
     * 按规则挑一格：
     *  1. 排除最前/最后的时间段
     *  2. 周五只留下午
     *  3. 在剩下的里从「最接近中间」的行开始找
     *  4. 找到第一个「空闲」格就选它（同一行优先最左边的场地）
     */
    fun decide(
        rows: List<SlotRowSamples>,
        cfg: SlotGridConfig,
        slotCfg: SlotConfig,
        fridayAfternoonOnly: Boolean,
        log: (String) -> Unit = {},
    ): SlotPick? {
        if (rows.isEmpty()) {
            log("没有采到任何时段行（检查 slotRowLabel / 列标题定位）")
            return null
        }
        cacheStates(rows, cfg)
        val table = renderTable(rows)
        log("时段表（行=时间段，列=场地）：\n$table")

        var considered = rows.sortedBy { it.rowIndex }
        if (cfg.skipFirstRows > 0) considered = considered.drop(cfg.skipFirstRows)
        if (cfg.skipLastRows > 0) considered = considered.dropLast(cfg.skipLastRows)
        if (considered.isEmpty()) {
            log("去掉首尾后没有剩余时间段（可调 skipFirstRows/skipLastRows）")
            return null
        }
        log("去掉首尾后剩 ${considered.size} 行：${considered.mapNotNull { it.rowLabel }.joinToString(" ")}")

        if (fridayAfternoonOnly) {
            val pm = considered.filter { row ->
                val morning = SlotPicker.isMorning(row.rowLabel, slotCfg)
                !morning
            }
            if (pm.isNotEmpty()) {
                considered = pm
                log("周五只看下午，剩 ${pm.size} 行")
            } else {
                log("周五过滤后没有可识别为下午的时段，退回全部候选")
            }
        }

        // 最接近中间优先
        val mid = (considered.size - 1) / 2.0
        val ordered = considered.sortedBy { abs(considered.indexOf(it) - mid) }
        log("尝试顺序（从中间向两边）：${ordered.mapNotNull { it.rowLabel }.joinToString(" ")}")

        for (row in ordered) {
            val available = row.cells
                .sortedBy { it.colIndex }
                .firstOrNull { classify(it.rgb, cfg) == SlotState.AVAILABLE }
            if (available != null) {
                val reason = "选中 ${row.rowLabel} / ${available.colLabel ?: "场地${available.colIndex + 1}"} " +
                    "(颜色 ${available.rgb.hex()} → 空闲)"
                return SlotPick(available, SlotState.AVAILABLE, reason, table)
            }
        }
        log("所有候选行都没有「空闲」格")
        return null
    }
}
