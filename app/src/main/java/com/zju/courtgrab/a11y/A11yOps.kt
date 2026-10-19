package com.zju.courtgrab.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.zju.courtgrab.core.Locator
import com.zju.courtgrab.core.LocatorMatcher
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.core.NodeSnapshot
import com.zju.courtgrab.core.RectI
import kotlinx.coroutines.delay

/** 活节点 + 其快照（快照用于纯逻辑匹配，活节点用于执行动作） */
class HNode(val node: AccessibilityNodeInfo, val snap: NodeSnapshot) {
    override fun toString(): String = snap.summary()
}

/**
 * 对 AccessibilityService 的一层封装：
 *  - 抓取所有窗口的节点树并拍成 NodeSnapshot 列表（跨 App / 跨 WebView）
 *  - 等待控件出现、点击、输入、滑动、返回
 *  - 点击失败自动「坐标点击」兜底（H5 里很关键）
 */
class A11yOps(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "A11y"
        private const val MAX_NODES = 4000
        private const val MAX_DEPTH = 48
    }

    private val ownPackage: String = service.packageName

    // ───────────────────────── 节点抓取 ─────────────────────────

    private fun roots(): List<AccessibilityNodeInfo> {
        val list = ArrayList<AccessibilityNodeInfo>(4)
        runCatching {
            service.windows?.forEach { w ->
                runCatching { w.root }.getOrNull()?.let { list += it }
            }
        }
        if (list.isEmpty()) {
            runCatching { service.rootInActiveWindow }.getOrNull()?.let { list += it }
        }
        return list
    }

    /**
     * 抓取当前所有窗口的节点。
     * @param includeOwn 是否包含本 App 自己的窗口（默认不包含，避免点到自己）
     */
    fun collect(includeOwn: Boolean = false): List<HNode> {
        val out = ArrayList<HNode>(256)
        val seenWindow = HashSet<Int>()
        for (root in roots()) {
            val wid = runCatching { root.windowId }.getOrDefault(0)
            if (!seenWindow.add(wid)) continue
            val pkg = runCatching { root.packageName?.toString() }.getOrNull()
            if (!includeOwn && pkg == ownPackage) continue
            collectInto(root, out, 0, "0")
            if (out.size >= MAX_NODES) break
        }
        return out
    }

    private fun collectInto(
        node: AccessibilityNodeInfo,
        out: MutableList<HNode>,
        depth: Int,
        path: String,
    ) {
        if (out.size >= MAX_NODES || depth > MAX_DEPTH) return
        out.add(HNode(node, snapshotOf(node, depth, path)))
        val count = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until count) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectInto(child, out, depth + 1, "$path/$i")
            if (out.size >= MAX_NODES) return
        }
    }

    private fun snapshotOf(node: AccessibilityNodeInfo, depth: Int, path: String): NodeSnapshot {
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        return NodeSnapshot(
            depth = depth,
            className = runCatching { node.className?.toString() }.getOrNull().orEmpty(),
            viewId = runCatching { node.viewIdResourceName }.getOrNull(),
            text = runCatching { node.text?.toString() }.getOrNull(),
            desc = runCatching { node.contentDescription?.toString() }.getOrNull(),
            bounds = RectI(rect.left, rect.top, rect.right, rect.bottom),
            clickable = runCatching { node.isClickable }.getOrDefault(false),
            enabled = runCatching { node.isEnabled }.getOrDefault(true),
            visible = runCatching { node.isVisibleToUser }.getOrDefault(true),
            scrollable = runCatching { node.isScrollable }.getOrDefault(false),
            editable = runCatching { node.isEditable }.getOrDefault(false),
            checkable = runCatching { node.isCheckable }.getOrDefault(false),
            checked = runCatching { node.isChecked }.getOrDefault(false),
            path = path,
        )
    }

    fun foregroundPackage(): String? = runCatching {
        service.rootInActiveWindow?.packageName?.toString()
    }.getOrNull()

    fun currentWindowTitles(): List<String> = roots().mapNotNull {
        runCatching { it.packageName?.toString() }.getOrNull()
    }.distinct()

    // ───────────────────────── 等待 ─────────────────────────

    /**
     * 等待 [locs] 里任意一个条件命中，返回命中的节点。
     * H5 页面要反复重新抓取（树会重建），所以这里是轮询而不是监听事件。
     */
    suspend fun waitFor(
        locs: List<Locator>,
        timeoutMs: Long,
        pollMs: Long = 220,
        includeOwn: Boolean = false,
    ): HNode? {
        val usable = locs.filter { !it.isEmpty }
        if (usable.isEmpty()) return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            val nodes = collect(includeOwn)
            if (nodes.isNotEmpty()) {
                val snaps = nodes.map { it.snap }
                for (loc in usable) {
                    val idx = LocatorMatcher.pick(snaps, loc)
                    if (idx != null) return nodes[idx]
                }
            }
            delay(pollMs)
        } while (SystemClock.elapsedRealtime() < deadline)
        return null
    }

    /** 等到这些条件全部消失（关闭弹窗后确认它真的没了） */
    suspend fun waitGone(locs: List<Locator>, timeoutMs: Long, pollMs: Long = 150): Boolean {
        val usable = locs.filter { !it.isEmpty }
        if (usable.isEmpty()) return true
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            val snaps = collect().map { it.snap }
            val stillThere = usable.any { LocatorMatcher.pick(snaps, it) != null }
            if (!stillThere) return true
            delay(pollMs)
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }

    /** 在当前界面里把某个条件命中的节点全部取出来（保持排序） */
    fun findAll(loc: Locator, includeOwn: Boolean = false): List<HNode> {
        if (loc.isEmpty) return emptyList()
        val nodes = collect(includeOwn)
        val snaps = nodes.map { it.snap }
        return LocatorMatcher.candidateIndexes(snaps, loc).map { nodes[it] }
    }

    /** 依次尝试多个条件，返回第一个有命中的条件的全部命中结果 */
    fun findAll(locs: List<Locator>, includeOwn: Boolean = false): List<HNode> {
        for (loc in locs) {
            if (loc.isEmpty) continue
            val hit = findAll(loc, includeOwn)
            if (hit.isNotEmpty()) return hit
        }
        return emptyList()
    }

    // ───────────────────────── 点击 ─────────────────────────

    /**
     * 点击一个节点：
     *  1. 先尝试节点自身 / 最近的可点击祖先的 ACTION_CLICK
     *  2. 都不行就按节点屏幕坐标做手势点击（H5 里很常见）
     */
    suspend fun click(h: HNode, settleMs: Long = 0): Boolean {
        var n: AccessibilityNodeInfo? = h.node
        var depth = 0
        while (n != null && depth <= 6) {
            val clickable = runCatching { n!!.isClickable }.getOrDefault(false)
            if (clickable) {
                val ok = runCatching { n!!.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    .getOrDefault(false)
                if (ok) {
                    if (settleMs > 0) delay(settleMs)
                    return true
                }
            }
            n = runCatching { n!!.parent }.getOrNull()
            depth++
        }
        Logs.w(TAG, "ACTION_CLICK 不可用，改用坐标点击：${h.snap.summary()}")
        val ok = tapCenter(h.snap.bounds)
        if (ok && settleMs > 0) delay(settleMs)
        return ok
    }

    /** 手势点击屏幕坐标 */
    suspend fun tapCenter(r: RectI): Boolean = tap(r.centerX.toFloat(), r.centerY.toFloat())

    suspend fun tap(x: Float, y: Float): Boolean {
        if (x <= 0f || y <= 0f) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatch(gesture, 1500L)
    }

    /** 从 (x1,y1) 滑到 (x2,y2) */
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 320L): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatch(gesture, durationMs + 1200L)
    }

    /** 派发手势并等待结果；有的 ROM 回调不来，超时按「已执行」处理 */
    private suspend fun dispatch(gesture: GestureDescription, timeoutMs: Long): Boolean {
        val result = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result.set(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                result.set(false)
            }
        }
        val dispatched = runCatching { service.dispatchGesture(gesture, callback, null) }
            .getOrDefault(false)
        if (!dispatched) return false
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (result.get() == null && SystemClock.elapsedRealtime() < deadline) {
            delay(25)
        }
        return result.get() ?: true
    }

    // ───────────────────────── 输入 ─────────────────────────

    /** 往节点里写文字：ACTION_SET_TEXT → 可编辑子节点 → 剪贴板粘贴 三级兜底 */
    fun setText(h: HNode, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (trySetText(h.node, args)) return true
        findEditableDescendant(h.node, 0)?.let { if (trySetText(it, args)) return true }
        Logs.w(TAG, "ACTION_SET_TEXT 失败，尝试剪贴板粘贴")
        return pasteText(h, text)
    }

    private fun trySetText(node: AccessibilityNodeInfo, args: Bundle): Boolean {
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
            .getOrDefault(false)
        if (ok) return true
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
            .getOrDefault(false)
    }

    private fun findEditableDescendant(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 8) return null
        val count = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until count) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val editable = runCatching { child.isEditable }.getOrDefault(false)
            if (editable) return child
            findEditableDescendant(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun pasteText(h: HNode, text: String): Boolean = runCatching {
        val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("courtgrab", text))
        h.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        h.node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }.getOrDefault(false)

    /** 读出节点当前文字（用于验证输入是否成功） */
    fun textOf(h: HNode): String? = runCatching {
        h.node.refresh()
        h.node.text?.toString()
    }.getOrNull()

    // ───────────────────────── 其他动作 ─────────────────────────

    fun back(): Boolean = runCatching {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }.getOrDefault(false)

    fun home(): Boolean = runCatching {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }.getOrDefault(false)

    suspend fun scrollForward(h: HNode? = null): Boolean {
        val target = h?.node ?: collect().firstOrNull { it.snap.scrollable }?.node ?: return false
        return runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            .getOrDefault(false)
    }

    // ───────────────────────── 调试辅助 ─────────────────────────

    /** 当前界面所有文字（去重、截断），失败时打进日志方便你对照 */
    fun visibleTextDigest(limit: Int = 40): String {
        val texts = LinkedHashSet<String>()
        for (h in collect()) {
            val t = h.snap.text ?: h.snap.desc
            if (!t.isNullOrBlank()) texts += t.take(30)
            if (texts.size >= limit) break
        }
        return if (texts.isEmpty()) "(界面上没有可读文字)"
        else texts.joinToString(" | ")
    }

    /** 当前界面所有可点的节点摘要 */
    fun clickableDigest(limit: Int = 25): String {
        val items = ArrayList<String>()
        for (h in collect()) {
            if (!h.snap.clickable) continue
            val t = h.snap.text ?: h.snap.desc ?: h.snap.viewId?.substringAfterLast('/') ?: h.snap.className.substringAfterLast('.')
            items += t.take(24)
            if (items.size >= limit) break
        }
        return if (items.isEmpty()) "(没有可点节点)" else items.joinToString(" | ")
    }
}
