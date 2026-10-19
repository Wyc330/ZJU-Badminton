package com.zju.courtgrab.core

/**
 * 纯函数式的控件匹配器：只依赖 NodeSnapshot，不依赖 Android，
 * 因此可以在 JVM 单元测试里直接验证匹配 / 排序 / 取第几个的逻辑。
 */
object LocatorMatcher {

    fun matches(node: NodeSnapshot, loc: Locator): Boolean {
        if (loc.isEmpty) return false
        if (loc.enabledOnly && !node.enabled) return false
        if (loc.clickableOnly && !node.clickable) return false

        loc.id?.let { want ->
            val vid = node.viewId
            if (vid.isNullOrEmpty()) return false
            // 允许写全名 "net.crigh.mysport:id/tv_home_model_title"，也允许只写 "tv_home_model_title"
            val ok = vid == want || vid.endsWith(":id/$want") || vid.endsWith("/$want")
            if (!ok) return false
        }

        loc.classNameEndsWith?.let { suffix ->
            if (!node.className.endsWith(suffix)) return false
        }

        val texts = buildList {
            node.text?.let { add(it) }
            node.desc?.let { add(it) }
        }

        loc.textEquals?.let { want ->
            if (texts.none { it.trim() == want.trim() }) return false
        }
        loc.textContains?.let { want ->
            if (texts.none { it.contains(want, ignoreCase = true) }) return false
        }
        loc.textRegex?.let { pattern ->
            val regex = runCatching { Regex(pattern) }.getOrNull() ?: return false
            if (texts.none { regex.containsMatchIn(it) }) return false
        }
        loc.descContains?.let { want ->
            val d = node.desc ?: return false
            if (!d.contains(want, ignoreCase = true)) return false
        }
        return true
    }

    /** 返回命中的下标（已按 order 排序） */
    fun candidateIndexes(nodes: List<NodeSnapshot>, loc: Locator): List<Int> {
        val hit = nodes.indices.filter { matches(nodes[it], loc) }
        if (hit.isEmpty()) return emptyList()
        return when (loc.order) {
            MatchOrder.TOP_LEFT -> hit.sortedWith(
                compareBy({ nodes[it].bounds.top }, { nodes[it].bounds.left })
            )

            MatchOrder.LEFT_TOP -> hit.sortedWith(
                compareBy({ nodes[it].bounds.left }, { nodes[it].bounds.top })
            )

            MatchOrder.AREA_DESC -> hit.sortedByDescending { nodes[it].bounds.area }
            MatchOrder.DOCUMENT -> hit
        }
    }

    /** 取最终要操作的那一个；null 表示没匹配到 */
    fun pick(nodes: List<NodeSnapshot>, loc: Locator): Int? {
        val hits = candidateIndexes(nodes, loc)
        if (hits.isEmpty()) return null
        val i = if (loc.index >= 0) loc.index else hits.size + loc.index
        return hits.getOrNull(i)
    }

    /** 依次尝试多个定位条件，返回第一个命中的下标 */
    fun pickFirst(nodes: List<NodeSnapshot>, locs: List<Locator>): Int? {
        for (loc in locs) {
            if (loc.isEmpty) continue
            pick(nodes, loc)?.let { return it }
        }
        return null
    }
}
