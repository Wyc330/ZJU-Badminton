package com.zju.courtgrab.core

/**
 * 屏幕矩形（像素）。
 */
data class RectI(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Int get() = width * height

    override fun toString(): String = "[$left,$top][$right,$bottom]"

    companion object {
        val EMPTY = RectI(0, 0, 0, 0)
    }
}

/**
 * 某一时刻界面上一个节点的「快照」。
 * 纯数据、不持有 AccessibilityNodeInfo，方便在 JVM 单元测试里构造。
 */
data class NodeSnapshot(
    val depth: Int = 0,
    val className: String = "",
    /** 完整 viewIdResourceName，例如 net.crigh.mysport:id/tv_home_model_title；H5 里通常为 null */
    val viewId: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val bounds: RectI = RectI.EMPTY,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val indexInParent: Int = 0,
    /** 调试用：从根到该节点的下标路径，如 "0/2/1" */
    val path: String = "",
) {
    fun summary(): String {
        val sb = StringBuilder()
        sb.append(className.substringAfterLast('.'))
        if (!viewId.isNullOrEmpty()) sb.append(" id=").append(viewId.substringAfter('/'))
        if (!text.isNullOrEmpty()) sb.append(" text=\"").append(text.take(40)).append('"')
        if (!desc.isNullOrEmpty()) sb.append(" desc=\"").append(desc.take(40)).append('"')
        sb.append(' ').append(bounds)
        if (clickable) sb.append(" clickable")
        if (editable) sb.append(" editable")
        if (checkable) sb.append(if (checked) " checked" else " unchecked")
        if (!enabled) sb.append(" disabled")
        return sb.toString()
    }
}

/** 多个命中结果时的排序方式 */
enum class MatchOrder {
    /** 先上后下、先左后右（列表类步骤默认） */
    TOP_LEFT,
    /** 先左后右、先上后下（横向日期条默认） */
    LEFT_TOP,
    /** 面积从大到小（取最大的容器） */
    AREA_DESC,
    /** 保持文档树顺序 */
    DOCUMENT,
}

/**
 * 控件定位条件。所有非 null 的条件是「与」关系，全部满足才算命中。
 *
 * 用法示例：
 *   Locator(textContains = "场馆服务")          // H5 里最常用
 *   Locator(id = "tv_home_model_title", textContains = "场馆服务")  // 原生界面最稳
 *   Locator(classNameEndsWith = "EditText")     // 找输入框
 */
data class Locator(
    val id: String? = null,
    val textEquals: String? = null,
    val textContains: String? = null,
    val textRegex: String? = null,
    val descContains: String? = null,
    val classNameEndsWith: String? = null,
    val clickableOnly: Boolean = false,
    val enabledOnly: Boolean = true,
    val order: MatchOrder = MatchOrder.TOP_LEFT,
    /** 在排序后的命中列表里取第几个；负数表示从后往前（-1 = 最后一个） */
    val index: Int = 0,
    /** 仅用于说明，方便对照界面 */
    val note: String = "",
) {
    val isEmpty: Boolean
        get() = id == null && textEquals == null && textContains == null && textRegex == null &&
            descContains == null && classNameEndsWith == null

    /** 文本类条件至少有一个时才做文本匹配 */
    val hasTextRule: Boolean
        get() = textEquals != null || textContains != null || textRegex != null || descContains != null

    fun describe(): String {
        val parts = ArrayList<String>()
        id?.let { parts += "id≈$it" }
        textEquals?.let { parts += "text=$it" }
        textContains?.let { parts += "text⊃$it" }
        textRegex?.let { parts += "text~/$it/" }
        descContains?.let { parts += "desc⊃$it" }
        classNameEndsWith?.let { parts += "class≈$it" }
        if (clickableOnly) parts += "clickable"
        if (index != 0) parts += "index=$index"
        if (note.isNotEmpty()) parts += "($note)"
        return if (parts.isEmpty()) "<空条件>" else parts.joinToString(" & ")
    }

    init {
        // 让空条件在配置出错时也匹配不到任何东西，避免误点
    }
}

/** 便于在配置里精简书写 */
object Loc {
    fun id(id: String): Locator = Locator(id = id)

    fun id(id: String, textContains: String): Locator = Locator(id = id, textContains = textContains)

    fun text(text: String): Locator = Locator(textEquals = text)

    fun contains(text: String): Locator = Locator(textContains = text)

    fun regex(pattern: String): Locator = Locator(textRegex = pattern)

    fun desc(text: String): Locator = Locator(descContains = text)

    fun editable(): Locator = Locator(classNameEndsWith = "EditText")

    fun clickableContains(text: String): Locator = Locator(textContains = text, clickableOnly = true)
}
