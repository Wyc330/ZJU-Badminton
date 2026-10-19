package com.zju.courtgrab.core

import kotlin.math.abs

/**
 * 时段表的「几何还原」——纯函数，方便单测。
 *
 * 从真机 dump 里看到的实际结构（2026-10-19，1200x2608 屏）：
 *   行标题（时间）：       TextView  "07:30-08:30"   [66,1725][351,1851]
 *   每个时段格（可点）：   View      ""               [348,1725][702,1848]   ← 354x123
 *   列标题（场地）：       TextView  "1号"            [348,1605][705,1728]
 *   日期左边箭头：         Button    ""               [249,360][393,456]
 *   日期右边箭头：         Button    ""               [732,360][876,456]
 *   刷新按钮：             Button    ""               [885,360][1029,456]
 *
 * 所以不需要猜坐标：格子本身是可点节点，时间列文字就是行标题，
 * 把「格子的垂直范围」和「行标题的垂直范围」配对即可还原整张表。
 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun verticalOverlap(other: Box): Int =
        (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)

    override fun toString(): String = "[$left,$top][$right,$bottom]"
}

data class CellBox(val box: Box)

data class LabelBox(val text: String, val box: Box)

data class GroupedCell(val box: Box, val centerX: Int, val centerY: Int)

data class GroupedRow(
    val label: String,
    /** 该时间段的开始时间（分钟数，用于跨滚动位置稳定排序） */
    val startMinute: Int,
    val cells: List<GroupedCell>,
)

object SlotGridGeometry {

    /** 从 "07:30-08:30" 里取出开始时间（分钟） */
    fun parseStartMinute(label: String): Int? {
        val m = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})").find(label.trim()) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        return h * 60 + min
    }

    /**
     * 把格子按「行标题的垂直带」分组。
     * @param minOverlapRatio 格子与行标题垂直重叠占格子高度的比例下限
     */
    fun group(
        cells: List<CellBox>,
        labels: List<LabelBox>,
        minOverlapRatio: Double = 0.4,
    ): List<GroupedRow> {
        if (cells.isEmpty() || labels.isEmpty()) return emptyList()
        val rows = LinkedHashMap<Int, Pair<String, MutableList<GroupedCell>>>()
        for (cell in cells) {
            val c = cell.box
            // 找垂直重叠最多的行标题
            val best = labels.maxByOrNull { c.verticalOverlap(it.box) } ?: continue
            val overlap = c.verticalOverlap(best.box)
            val need = (c.height * minOverlapRatio).toInt().coerceAtLeast(1)
            if (overlap < need) continue
            val start = parseStartMinute(best.text) ?: continue
            rows.getOrPut(start) { best.text to ArrayList() }.second +=
                GroupedCell(box = c, centerX = c.centerX, centerY = c.centerY)
        }
        return rows.entries
            .sortedBy { it.key }
            .map { (start, pair) ->
                GroupedRow(
                    label = pair.first,
                    startMinute = start,
                    cells = pair.second.sortedBy { it.box.left },
                )
            }
    }

    /** 用于日志：把某次扫描到的表画出来 */
    fun render(rows: List<GroupedRow>, states: List<List<String>>? = null): String {
        if (rows.isEmpty()) return "(没有扫描到时段格)"
        val sb = StringBuilder()
        for ((i, row) in rows.withIndex()) {
            sb.append(row.label.padEnd(14))
            val states = states?.getOrNull(i)
            row.cells.forEachIndexed { j, _ ->
                sb.append((states?.getOrNull(j) ?: "#${j + 1}").padEnd(8))
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** 选中点尽量落在格子中心（供坐标点击） */
    fun tapPoint(cell: GroupedCell): Pair<Int, Int> = cell.centerX to cell.centerY

    /** 找同高度带、位于锚点左侧/右侧的可点节点（用于日期箭头和刷新按钮） */
    fun findNeighbor(
        anchor: Box,
        candidates: List<Box>,
        forward: Boolean,
        index: Int = 0,
        bandToleranceRatio: Double = 0.8,
    ): Box? {
        val tol = (anchor.height * bandToleranceRatio).toInt().coerceAtLeast(20)
        val onBand = candidates.filter { abs(it.centerY - anchor.centerY) <= tol }
        val sorted = if (forward) {
            onBand.filter { it.left >= anchor.right - 8 }.sortedBy { it.left }
        } else {
            onBand.filter { it.right <= anchor.left + 8 }.sortedByDescending { it.right }
        }
        return sorted.getOrNull(index)
    }
}

/** 方便把节点 bounds 转成几何 Box */
fun RectI.toBox(): Box = Box(left, top, right, bottom)
