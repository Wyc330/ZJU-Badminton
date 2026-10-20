package com.zju.courtgrab.flow

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.zju.courtgrab.a11y.HNode
import com.zju.courtgrab.a11y.ScreenCap
import com.zju.courtgrab.a11y.VenueAccessibilityService
import com.zju.courtgrab.core.ConfigRepo
import com.zju.courtgrab.core.DatePlanner
import com.zju.courtgrab.core.FlowBus
import com.zju.courtgrab.core.GrabConfig
import com.zju.courtgrab.core.Locator
import com.zju.courtgrab.core.LocatorMatcher
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.core.Prefs
import com.zju.courtgrab.core.Rgb
import com.zju.courtgrab.core.Box
import com.zju.courtgrab.core.CellBox
import com.zju.courtgrab.core.LabelBox
import com.zju.courtgrab.core.SlotCellSample
import com.zju.courtgrab.core.SlotGridGeometry
import com.zju.courtgrab.core.toBox
import com.zju.courtgrab.core.SlotGrid
import com.zju.courtgrab.core.SlotPick
import com.zju.courtgrab.core.SlotRowSamples
import com.zju.courtgrab.core.SlotState
import com.zju.courtgrab.core.TapCache
import com.zju.courtgrab.direct.DirectBookingFlow
import com.zju.courtgrab.util.BgLaunch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** 流程触发来源 */
enum class Trigger { ALARM, MANUAL }

/** 流程在某个步骤失败；step 是中文步骤名，方便定位 */
class FlowException(val step: String, message: String, cause: Throwable? = null) :
    Exception("[$step] $message", cause)

/**
 * 完整的预约流程（按 2026-10-19 真机截图的真实界面写的）。
 *
 *   ① 启动/切到浙大体艺
 *   ②~③ 关弹窗、等首页
 *   ④ 首页九宫格 →「场馆服务」
 *   ⑤~⑦ 关弹窗（实测这里会弹「预约须知」）→ 六宫格 →「场馆预约」
 *   ⑧ 等「预约场地」页
 *   ⑨ 确认校区（紫金港校区，默认已选中，点一下确保）
 *   ⑩ 场馆 →「风雨操场」
 *   ⑪ 场地 →「羽毛球场」
 *   ⑫ 日期：用 `<`/`>` 箭头移到目标日期（窗口=今天+2 天，优先周五→周六→周日）
 *   ⑬ 时段：截图取色（绿=可约/红=已约/灰=不可约），去掉首尾行、从中间开始找绿色格，
 *        周五只看下午
 *   ⑭ 勾选「已阅读并同意」
 *   ⑮ 点「我要预约」→ 关掉预约须知弹窗
 *   ⑯ 「同伴信息」页：勾第一个同伴 → 弹「5位数字同伴码」→ 填码 → 确定
 *   ⑰ 点「提交订单」
 *   ⑱ 记录结果并停止（不支付）
 *
 * 任何一步定位失败：写日志 + 抓取节点树 + 停止，不盲目往下点。
 */
class BookingFlow(
    private val svc: VenueAccessibilityService,
    private val trigger: Trigger,
) {
    private companion object {
        const val TAG = "Flow"
    }

    private val ops = svc.ops
    private val cap = ScreenCap(svc)
    private val ctx: Context = svc.applicationContext
    private var cfg: GrabConfig = GrabConfig.DEFAULT
    private val prefs: Prefs = Prefs.get(ctx)
    private val screenH: Int = svc.resources.displayMetrics.heightPixels
    private val screenW: Int = svc.resources.displayMetrics.widthPixels
    private var lastShotAt = 0L
    /** 学习到的点击坐标（第一次跑到就记住，下次直接点） */
    private val tapCache: MutableMap<String, Pair<Int, Int>> = TapCache.load(prefs)

    private fun rememberPoint(key: String, x: Int, y: Int) {
        val old = tapCache[key]
        if (old == null || old.first != x || old.second != y) {
            tapCache[key] = x to y
            TapCache.save(prefs, tapCache)
            Log("  学习坐标 $key=($x, $y)")
        }
    }

    /** 有缓存就按坐标快速点击；没有就走正常定位并学习 */
    private fun cachedPoint(key: String): Pair<Int, Int>? = tapCache[key]

    // ─────────────────────────── 主流程 ───────────────────────────

    /** 目标 App 当前停在哪个页面（用于跳过重复导航） */
    private enum class PageState { BOOKING, MODULE, HOME, UNKNOWN }

    private fun detectPageState(): PageState = runCatching {
        val booking = ops.findAll(cfg.bookingPageReady)
        if (booking.isNotEmpty()) {
            val usable = booking.any { it.snap.bounds.height > 5 && it.snap.bounds.width > 5 }
            if (usable) return@runCatching PageState.BOOKING
            Logs.w(TAG, "预约页节点高度为 0（页面没渲染好），按未知状态重新导航")
        }
        val moduleEntry = ops.findAll(cfg.entryBooking)
        val home = ops.findAll(cfg.entryService)
        if (moduleEntry.isNotEmpty() && home.isEmpty()) return@runCatching PageState.MODULE
        if (home.isNotEmpty()) return@runCatching PageState.HOME
        PageState.UNKNOWN
    }.getOrDefault(PageState.UNKNOWN)

    suspend fun run() {
        cfg = ConfigRepo.load(ctx)
        Logs.i(TAG, "配置摘要:\n${cfg.summary()}")
        Logs.i(TAG, "触发来源=$trigger，时间=${Logs.fullTime()}")

        // 直连模式：能直连就直连（失败自动回退下面的界面流程）
        if (prefs.directMode) {
            var directOk = false
            step("⚡ 直连下单（极速模式）") {
                directOk = DirectBookingFlow.execute(prefs, cfg)
                if (!directOk) Log("直连未成功，回退界面流程")
            }
            if (directOk) {
                val detail = prefs.lastDirectDetail.ifBlank { "已下单" }
                FlowBus.done(true, "直连下单成功：$detail")
                return
            }
        }

        // 智能识别当前停在哪个页面：已经到预约页就不用再跑 ①–⑧（预热/上次失败后常停在这里）
        var pageState = detectPageState()
        if (pageState == PageState.UNKNOWN && ops.foregroundPackage() == cfg.targetPackage) {
            Log("  页面状态未知（可能是空白页/WebView 没渲染），回退两层后重新识别")
            repeat(2) {
                ops.back()
                delay(600)
            }
            delay(400)
            pageState = detectPageState()
        }
        when (pageState) {
            PageState.BOOKING -> {
                Log("✔ 检测到已在「预约场地」页，跳过启动与导航（①–⑧）")
                ensureTargetForeground("确认预约页")
            }
            PageState.MODULE -> {
                Log("✔ 检测到已在「场馆服务」页，只需点一次「场馆预约」")
                ensureTargetForeground("进入场馆预约")
                step("⑥ 进入场馆预约") { clickNav("进入场馆预约", cfg.entryBooking, expected = cfg.bookingPageReady) }
                step("⑦ 关闭弹窗") { dismissPopups() }
                step("⑧ 等待预约场地页") { waitBookingPage() }
            }
            PageState.HOME, PageState.UNKNOWN -> {
                step("① 启动目标 App") { launchTarget() }
                step("② 关闭弹窗") { dismissPopups() }
                step("③ 等待首页") { waitHome() }
                step("④ 进入场馆服务") { clickNav("进入场馆服务", cfg.entryService, expected = cfg.entryBooking + cfg.bookingPageReady) }
                step("⑤ 关闭弹窗（预约须知）") { dismissPopups() }
                step("⑥ 进入场馆预约") { clickNav("进入场馆预约", cfg.entryBooking, expected = cfg.bookingPageReady) }
                step("⑦ 关闭弹窗") { dismissPopups() }
                step("⑧ 等待预约场地页") { waitBookingPage() }
            }
        }
        step("⑨ 确认校区") { selectCampus() }
        step("⑩ 选择风雨操场") { clickSelect("选择风雨操场", cfg.venueFengYu) }
        step("⑪ 选择羽毛球场") {
                    if (cfg.skipCourtClickIfGridReady && ops.findAll(cfg.slotRowLabel).isNotEmpty()) {
                        Log("时段表已就绪，跳过点「羽毛球场」（默认已选中）")
                    } else {
                        clickSelect("选择羽毛球场", cfg.venueBadminton)
                    }
                }
        step("⑫ 关闭弹窗") { dismissPopups() }
        step("⑫¼ 预热（提前勾好「已阅读并同意」）") {
            if (cfg.turboMode) {
                ensureAgreeChecked()
                Log("预热完成：同意勾选已就绪，放号后只做 刷新→切日期→点格子→我要预约")
            }
        }
        // 预热 2：放号前先试着把日期切到目标（有些时段窗口提前就允许切，
        // 这样 09:00:00 之后只剩「刷新 → 点格子」两件事）
        if (cfg.turboMode) {
            val warmDate = DatePlanner.resolveTargetDate(
                LocalDateTime.now(), cfg.dateCfg, cfg.releaseHour, cfg.releaseMinute,
            )
            if (warmDate != null) {
                step("⑫⅜ 预热（提前切日期）") {
                    val reached = navigateToDate(warmDate)
                    if (reached == warmDate) {
                        Log("预热成功：日期已在 $warmDate，放号后不用再切")
                    } else {
                        Log("预热未生效（当前 $reached，放号前可能不允许切），放号后会重试")
                    }
                }
            }
        }
        step("⑫½ 等到 ${cfg.releaseHour}:${"%02d".format(cfg.releaseMinute)} 放号") { waitForRelease() }

        val now = LocalDateTime.now()
        val target = DatePlanner.resolveTargetDate(now, cfg.dateCfg, cfg.releaseHour, cfg.releaseMinute)
            ?: throw FlowException("选择日期", "算不出目标日期（检查 dateCfg 配置）")
        val candidates = listOf(target)
        Log("目标日期（待开票的最近一天）：$target（${DatePlanner.weekdayCn(target.dayOfWeek)}）")
        Notify("开始抢 ${candidates.first()} 的场")

        var lastError: FlowException? = null
        for ((idx, target) in candidates.withIndex()) {
            Log("—— 候选 ${idx + 1}/${candidates.size}：$target ——")
            try {
                val actual = stepWithResult("⑬ 切到日期 $target") { navigateToDate(target) }
                if (actual == null) {
                    throw FlowException(
                        "切到日期",
                        "读不出日期框内容（页面可能被滚走或还没到放号时间）；目标 $target",
                    )
                }
                Log("实际日期 = $actual")
                if (actual != target) Logs.w(TAG, "期望 $target，实际停在 $actual，按实际日期判断")

                val fridayOnly = actual.dayOfWeek == DayOfWeek.FRIDAY && cfg.dateCfg.fridayAfternoonOnly
                val pick = stepWithResult("⑭ 选择时段") { pickSlotWithRetry(actual, fridayOnly) }
                if (pick == null) {
                    Logs.w(TAG, "$actual 没有空闲时段，换下一个候选日期")
                    lastError = FlowException("选择时段", "$actual 没有可预约的绿色时段")
                    continue
                }

                step("⑮ 勾选同意") { ensureAgreeChecked() }
                step("⑯ 点击我要预约") {
                    tapCached("book", cfg.btnBookNow, "点击我要预约", expectText = "同伴信息")
                    delay(cfg.afterNavDelayMs)
                    dismissPopups()
                }
                step("⑰ 等待同伴信息页") { waitCompanionPage() }
                step("⑱ 勾选同伴并填同伴码") { selectCompanionAndCode() }
                if (prefs.dryRun) {
                    Log("🧪 演练模式：已经走到「提交订单」前，按设置不实际提交")
                    prefs.lastResult = "演练完成 ${Logs.fullTime()}（$actual ${pick.cell.rowLabel}）"
                    Notify("演练完成：已到提交订单前，未下单")
                    return
                }
                step("⑲ 提交订单") {
                    tapCached("submit", cfg.btnSubmitOrder, "提交订单")
                    delay(cfg.afterNavDelayMs)
                }
                step("⑳ 确认结果") { verifyResult() }

                prefs.lastResult = "成功 ${Logs.fullTime()}（$actual ${pick.cell.rowLabel}）"
                Notify("预约流程完成：$actual ${pick.cell.rowLabel}")
                return
            } catch (e: FlowException) {
                lastError = e
                Logs.e(TAG, "候选日期 $target 失败：${e.message}")
                dumpQuietly("date-$target")
                rewindToBookingPage()
            }
        }

        prefs.lastResult = "失败 ${Logs.fullTime()}：${lastError?.message ?: "未知"}"
        throw lastError ?: FlowException("流程", "所有候选日期都没有可约时段")
    }

    // ─────────────────────────── 前置 ───────────────────────────

    private suspend fun launchTarget() {
        // 锁屏时先把非安全锁屏解除，否则目标 App 起不来（安全锁屏只能用户本人解锁）
        runCatching {
            ctx.startActivity(
                android.content.Intent(ctx, com.zju.courtgrab.ui.UnlockActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
            delay(900)
        }
        if (ops.foregroundPackage() == cfg.targetPackage) {
            Log("目标 App 已在前台")
            return
        }
        if (!BgLaunch.openTarget(ctx, cfg)) {
            throw FlowException(
                "启动目标 App",
                "startActivity 被拦截：请到「设置-应用-抢场地-显示在其他应用上层」打开悬浮窗权限",
            )
        }
        val deadline = SystemClock.elapsedRealtime() + cfg.navTimeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ops.foregroundPackage() == cfg.targetPackage) {
                Log("目标 App 已到前台")
                return
            }
            delay(cfg.pollIntervalMs)
        }
        Log("未确认到前台（当前=${ops.foregroundPackage() ?: "?"}），继续等首页")
    }

    /** 关弹窗：按候选依次尝试，命中就点，点完再来一轮 */
    private suspend fun dismissPopups() {
        repeat(cfg.popupRounds) { round ->
            val nodes = ops.collect()
            val snaps = nodes.map { it.snap }
            var hit: HNode? = null
            var hitLocator: Locator? = null
            for (loc in cfg.popupClosers) {
                if (loc.isEmpty) continue
                val idx = LocatorMatcher.pick(snaps, loc) ?: continue
                hit = nodes[idx]
                hitLocator = loc
                break
            }
            if (hit == null) return
            Log("关闭弹窗（匹配 ${hitLocator?.describe()}）：${hit.snap.summary()}")
            ops.click(hit, 250)
            delay(250)
        }
    }

    private suspend fun waitHome() {
        val node = ops.waitFor(cfg.homeReady, cfg.navTimeoutMs, cfg.pollIntervalMs)
        if (node == null) {
            if (isLoginPage()) {
                throw FlowException("等待首页", "登录态疑似失效，请先手动打开浙大体艺登录一次")
            }
            throw FlowException(
                "等待首页",
                "等不到首页：${cfg.homeReady.joinToString(" || ") { it.describe() }}；界面文字=${ops.visibleTextDigest()}",
            )
        }
        Log("首页就绪：${node.snap.summary()}")
    }

    private fun isLoginPage(): Boolean {
        val snaps = ops.collect().map { it.snap }
        return cfg.loginHints.any { !it.isEmpty && LocatorMatcher.pick(snaps, it) != null }
    }

    private suspend fun waitBookingPage() {
        ensureTargetForeground("等待预约场地页")
        val node = ops.waitFor(cfg.bookingPageReady, cfg.navTimeoutMs, cfg.pollIntervalMs)
        if (node == null) {
            dumpQuietly("booking-page")
            throw FlowException(
                "等待预约场地页",
                "等不到预约页（${cfg.bookingPageReady.joinToString(" || ") { it.describe() }}）；" +
                    "界面文字=${ops.visibleTextDigest()}",
            )
        }
        Log("预约页就绪：${node.snap.summary()}")
    }

    // ─────────────────────────── 通用点击 ───────────────────────────

    /**
     * 导航型点击：可能命中多个节点（比如页面标题和九宫格图标都叫「场馆预约」），
     * 依次点，直到界面文字真的变化。
     */
    private suspend fun clickNav(
        name: String,
        locs: List<Locator>,
        expected: List<Locator> = emptyList(),
        timeoutMs: Long = cfg.stepTimeoutMs,
    ): Boolean {
        ensureTargetForeground(name)
        // 学习坐标：第二次起直接按坐标点，省掉遍历节点树找入口
        val cacheKey = "nav_$name"
        cachedPoint(cacheKey)?.let { p ->
            Log("  $name：用学习坐标 (${p.first}, ${p.second})")
            ops.tapFast(p.first.toFloat(), p.second.toFloat())
            if (expected.isEmpty()) {
                delay(cfg.afterNavDelayMs)
                return true
            }
            val ready = ops.waitFor(expected, 2600, 120)
            if (ready != null) {
                Log("  ✔ 学习坐标生效 → ${ready.snap.summary()}")
                return true
            }
            Logs.w(TAG, "  $name：学习坐标没生效，回退正常定位")
        }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            val nodes = ops.collect()
            val snaps = nodes.map { it.snap }
            for (loc in locs) {
                if (loc.isEmpty) continue
                val idxs = LocatorMatcher.candidateIndexes(snaps, loc)
                if (idxs.isEmpty()) continue
                val ordered = idxs.sortedByDescending { nodes[it].snap.clickable }
                for (idx in ordered.take(4)) {
                    val h = nodes[idx]
                    if (!ops.click(h, cfg.afterClickDelayMs)) continue
                    if (expected.isEmpty()) {
                        Log("  ✔ 已点击：${h.snap.summary()}")
                        rememberPoint(cacheKey, h.snap.bounds.centerX, h.snap.bounds.centerY)
                        delay(cfg.afterNavDelayMs)
                        return true
                    }
                    val ready = ops.waitFor(expected, 2600, 120)
                    if (ready != null) {
                        Log("  ✔ 点击生效：${h.snap.summary()} → ${ready.snap.summary()}")
                        rememberPoint(cacheKey, h.snap.bounds.centerX, h.snap.bounds.centerY)
                        return true
                    }
                    Logs.w(TAG, "  点了 ${h.snap.summary()} 但目标页未出现，试下一个候选")
                }
            }
            delay(120)
        } while (SystemClock.elapsedRealtime() < deadline)
        dumpQuietly("click-$name")
        throw FlowException(
            name,
            "等待 ${timeoutMs}ms 没点到能生效的控件；界面文字=${ops.visibleTextDigest()}",
        )
    }

    /**
     * 热路径点击：有学习坐标就直接点（跳过节点树遍历）；没有就正常定位、学习并点击。
     * 点完可以用 [expectText] 快速确认页面真的变了（findAccessibilityNodeInfosByText，10~30ms）。
     */
    private suspend fun tapCached(
        key: String,
        locs: List<Locator>,
        what: String,
        expectText: String? = null,
        expectTimeoutMs: Long = 2500,
    ): Boolean {
        cachedPoint(key)?.let { p ->
            Log("  $what：用学习坐标 (${p.first}, ${p.second})")
            ops.tapFast(p.first.toFloat(), p.second.toFloat())
            if (expectText == null) {
                delay(100)
                return true
            }
            if (ops.waitTextFast(expectText, expectTimeoutMs) != null) return true
            Logs.w(TAG, "  $what：学习坐标没生效，回退到正常定位")
        }
        val node = ops.waitFor(locs, cfg.stepTimeoutMs, cfg.pollIntervalMs)
            ?: throw FlowException(what, "没找到控件；界面文字=${ops.visibleTextDigest()}")
        val c = node.snap.bounds
        rememberPoint(key, c.centerX, c.centerY)
        Log("  $what：命中 ${node.snap.summary()}")
        ops.tapFast(c.centerX.toFloat(), c.centerY.toFloat())
        if (expectText != null && ops.waitTextFast(expectText, expectTimeoutMs) == null) {
            throw FlowException(what, "点击后没出现「$expectText」；界面文字=${ops.visibleTextDigest()}")
        }
        return true
    }

    /** 选择型点击：点一下就行（校区/场馆/场地/按钮），不要求界面文字变化 */
    private suspend fun clickSelect(name: String, locs: List<Locator>): HNode {
        ensureTargetForeground(name)
        Log("  定位：${locs.joinToString(" || ") { it.describe() }}")
        var last: HNode? = null
        for (round in 0..1) {
            val node = ops.waitFor(locs, if (round == 0) cfg.stepTimeoutMs else 1800, cfg.pollIntervalMs)
            if (node == null) {
                if (round == 0) {
                    throw FlowException(
                        name,
                        "没找到控件；界面文字=${ops.visibleTextDigest()}；可点节点=${ops.clickableDigest()}",
                    )
                }
                break
            }
            last = node
            val b = node.snap.bounds
            if (b.height <= 5 || b.width <= 5) {
                Logs.w(TAG, "  控件尺寸异常（$b），页面可能没渲染好，刷新后重试")
                tapRefresh()
                delay(900)
                continue
            }
            Log("  命中：${node.snap.summary()}")
            if (ops.click(node, cfg.afterClickDelayMs)) {
                delay(200)
                return node
            }
            Logs.w(TAG, "  点击失败：${node.snap.summary()}，刷新后重试")
            tapRefresh()
            delay(900)
        }
        val h = last
        throw FlowException(name, "点击失败：${h?.snap?.summary() ?: "没找到控件"}（页面可能卡在空白，刷新后仍失败）")
    }

    /**
     * 确保目标 App 在前台。
     *
     * 实测踩到的坑：用户中途切回「抢场地」看日志（或从通知点进来）时，
     * 目标 App 会退到后台，无障碍就看不到它的窗口 → 「选择时段」直接报
     * 「还原不出时段表」。现在遇到这种情况会主动重新拉起目标 App 再继续。
     */
    private var pullAwayCount = 0

    private suspend fun ensureTargetForeground(where: String): Boolean {
        val fg = ops.foregroundPackage()
        if (fg == cfg.targetPackage) {
            pullAwayCount = 0
            return true
        }
        pullAwayCount++
        if (pullAwayCount > 4) {
            throw FlowException(
                where,
                "检测到你在用其它 App，已停止流程避免打扰（被切走 $pullAwayCount 次）；要抢场地请保持屏幕亮着别操作",
            )
        }
        Logs.w(TAG, "  [$where] 目标 App 不在前台（当前=${fg ?: "?"}），自动重新拉起（第 $pullAwayCount 次）")
        BgLaunch.openTarget(ctx, cfg)
        val deadline = SystemClock.elapsedRealtime() + cfg.navTimeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ops.foregroundPackage() == cfg.targetPackage) {
                delay(700)
                Log("  [$where] 已回到目标 App，继续执行")
                return true
            }
            delay(cfg.pollIntervalMs)
        }
        Logs.e(TAG, "  [$where] 拉不起目标 App，当前前台=${ops.foregroundPackage() ?: "?"}")
        return false
    }

    private fun screenSig(): Int =
        ops.collect().joinToString("|") { (it.snap.text ?: it.snap.desc ?: "") }.hashCode()

    // ─────────────────────────── 校区 / 场馆 / 场地 ───────────────────────────

    private suspend fun selectCampus() {
        val node = ops.waitFor(cfg.campusSelect, 2500, 150)
        if (node == null) {
            if (cfg.campusOptional) {
                Logs.w(TAG, "没看到校区选择，跳过")
                return
            }
            throw FlowException("确认校区", "找不到校区「${cfg.campusTarget}」")
        }
        if (!cfg.campusClick) {
            Log("校区已是「${cfg.campusTarget}」（默认选中，跳过点击避免整页重刷）：${node.snap.summary()}")
            return
        }
        Log("点校区：${node.snap.summary()}")
        ops.click(node, cfg.afterClickDelayMs)
        delay(300)
    }

    // ─────────────────────────── 日期 ───────────────────────────

    private suspend fun readDisplayedDate(): LocalDate? {
        val node = ops.waitFor(listOf(cfg.dateDisplay), 1500, 120)
        node?.snap?.text?.let { txt ->
            DatePlanner.parseDisplayed(txt, cfg.dateCfg)?.let { return it }
        }
        // 兜底：整页找「2026-10-20」样式的文本（WebView 里日期框偶尔不在可访问区）
        val regex = Regex("\\d{4}\\s*[-/]\\s*\\d{1,2}\\s*[-/]\\s*\\d{1,2}")
        for (h in ops.collect()) {
            val m = regex.find(h.snap.text ?: "") ?: continue
            DatePlanner.parseDisplayed(m.value, cfg.dateCfg)?.let {
                Log("  日期框兜底命中：${m.value}")
                return it
            }
        }
        return null
    }

    /** 页面被滚到下面（比如刚勾过同意）时，先滚回顶部让日期框可见 */
    private suspend fun scrollPageToTop(times: Int = 2) {
        repeat(times) {
            ops.swipe(screenW * 0.5f, screenH * 0.30f, screenW * 0.5f, screenH * 0.80f, 260)
            delay(280)
        }
    }

    /** 用 `<`/`>` 箭头把日期移动到 target；返回最终停在哪天 */
    private suspend fun navigateToDate(target: LocalDate): LocalDate? {
        ensureTargetForeground("切换日期")
        var current = readDisplayedDate()
        if (current == null) {
            Log("  日期框不在可视区，先滚回顶部")
            scrollPageToTop()
            current = readDisplayedDate()
        }
        if (current == null) return null
        if (current == target) {
            Log("日期已经是 $target")
            return current
        }
        var guard = 0
        while (current != target && guard++ < 8) {
            val delta = ChronoUnit.DAYS.between(current, target)
            val forward = delta > 0
            val clicks = kotlin.math.abs(delta).toInt().coerceIn(1, 3)
            // 快速连点，不逐次回读；点完统一校验一次
            repeat(clicks) {
                tapDateArrow(forward, 0)
                delay(90)
            }
            delay(120)
            val after = readDisplayedDate()
            if (after != null && after != current) {
                current = after
                Log("  日期 → $current（本轮连点 $clicks 次${if (forward) "下一天" else "上一天"}）")
                continue
            }
            // 连点无效：换坐标比例补点（布局差异兜底）
            var fixed = false
            for (attempt in 1..2) {
                tapDateArrow(forward, attempt)
                delay(cfg.afterClickDelayMs)
                val a2 = readDisplayedDate()
                if (a2 != null && a2 != current) {
                    current = a2
                    fixed = true
                    break
                }
            }
            if (!fixed) {
                scrollPageToTop(1)
                val a3 = readDisplayedDate()
                if (a3 != null && a3 != current) {
                    current = a3
                    continue
                }
                Log("箭头没生效（当前 $current），可能已到预约窗口边界")
                break
            }
        }
        delay(300)
        return current
    }

    /**
     * 点日期框左边/右边的箭头。
     *
     * 真机 dump 确认：日期框是 EditText，左箭头/右箭头/刷新都是它同一水平带上的 Button
     * （左 [249,360][393,456]，右 [732,360][876,456]，刷新 [885,360][1029,456]）。
     * 所以先按几何找同高度带的按钮（前进取最左的那个，后退取最右的那个），
     * 找不到再按比例做坐标点击兜底。
     */
    private suspend fun tapDateArrow(forward: Boolean, attempt: Int): Boolean {
        val cacheKey = if (forward) "date_next" else "date_prev"
        cachedPoint(cacheKey)?.let { p ->
            ops.tapFast(p.first.toFloat(), p.second.toFloat())
            return true
        }
        val anchor = ops.waitFor(listOf(cfg.dateDisplay), 1200, 100) ?: return false
        val anchorBox = anchor.snap.bounds.toBox()
        if (attempt == 0) {
            val onBand = clickableBoxesOnScreen()
            val neighbor = SlotGridGeometry.findNeighbor(anchorBox, onBand, forward = forward, index = 0)
            if (neighbor != null) {
                Log("  日期箭头（${if (forward) "下一天" else "上一天"}）坐标 (${neighbor.centerX}, ${neighbor.centerY})")
                rememberPoint(cacheKey, neighbor.centerX, neighbor.centerY)
                return ops.tapFast(neighbor.centerX.toFloat(), neighbor.centerY.toFloat())
            }
        }
        val ratio = when (attempt) {
            0 -> cfg.dateArrowFallbackRatio
            1 -> 0.75f
            else -> 1.15f
        }
        val offset = (anchorBox.height * ratio).toInt().coerceAtLeast(14)
        val x = if (forward) anchorBox.right + offset else anchorBox.left - offset
        if (x <= 0) return false
        Log("  箭头坐标点击 ($x, ${anchorBox.centerY}) ratio=$ratio")
        return ops.tap(x.toFloat(), anchorBox.centerY.toFloat())
    }

    /** 界面里选的目标时间段（如 18:30）→ 分钟数；留空返回 null（走中间优先） */
    private fun preferredSlotMinute(): Int? {
        val raw = prefs.preferredSlotStart.ifBlank { cfg.preferredSlotStart }.trim()
        if (raw.isEmpty()) return null
        val minute = SlotGridGeometry.parseStartMinute(raw)
        if (minute == null) Logs.w(TAG, "  preferredSlotStart=「$raw」解析不出时间，改用中间优先")
        return minute
    }

    /** 当前屏幕上「可点击且够大」的节点矩形，用于几何找邻居 */
    private fun clickableBoxesOnScreen(): List<Box> = ops.collect()
        .asSequence()
        .filter { n ->
            n.snap.clickable && n.snap.visible &&
                n.snap.bounds.width >= 20 && n.snap.bounds.height >= 20 &&
                n.snap.bounds.centerX in 0..screenW && n.snap.bounds.centerY in 0..screenH
        }
        .map { it.snap.bounds.toBox() }
        .distinctBy { listOf(it.left, it.top, it.right, it.bottom) }
        .toList()

    // ─────────────────────────── 放号 ───────────────────────────

    /** 等到每天 09:00 放号点：提前就位，到点刷新一次再抢 */
    private suspend fun waitForRelease() {
        if (!cfg.waitForRelease) {
            Log("不等待放号，直接抢")
            return
        }
        val now = LocalDateTime.now()
        val release = now.toLocalDate().atTime(cfg.releaseHour, cfg.releaseMinute, cfg.releaseSecond)
        if (now.isAfter(release.plusMinutes(30))) {
            Log("已过放号时间 $release，直接抢")
            return
        }
        val waitMs = java.time.Duration.between(now, release).toMillis()
        if (waitMs > 0) {
            Log("提前就位，等待 ${waitMs}ms 到 $release")
            delay(waitMs)
        }
        Log("到放号点，刷新页面（等新数据回来）")
        tapRefresh()
        delay(if (cfg.turboMode) 350 else 900)
        dismissPopups()
    }

    /**
     * 点日期行右侧的「刷新」按钮。
     * 真机结构：日期框右边第一个 Button 是「下一天」，第二个才是刷新（[885,360][1029,456]）。
     * 如果页面被滚动过导致日期行不在屏幕内，先把页面滑回顶部。
     */
    private suspend fun tapRefresh(): Boolean {
        var anchor = ops.waitFor(listOf(cfg.dateDisplay), 1500, 100)
        if (anchor != null) {
            val cy = anchor.snap.bounds.centerY
            if (cy < 0 || cy > screenH) {
                repeat(2) {
                    ops.swipe(screenW * 0.5f, screenH * 0.25f, screenW * 0.5f, screenH * 0.85f, 300)
                    delay(450)
                }
                anchor = ops.waitFor(listOf(cfg.dateDisplay), 1500, 100)
            }
        }
        if (anchor == null) {
            Logs.w(TAG, "  找不到日期框，无法刷新")
            return false
        }
        val box = anchor.snap.bounds.toBox()
        cachedPoint("refresh")?.let { p ->
            Log("  刷新：用学习坐标 (${p.first}, ${p.second})")
            ops.tapFast(p.first.toFloat(), p.second.toFloat())
            delay(150)
            return true
        }
        val refresh = SlotGridGeometry.findNeighbor(box, clickableBoxesOnScreen(), forward = true, index = 1)
        if (refresh != null) {
            Log("  点刷新按钮坐标 (${refresh.centerX}, ${refresh.centerY})")
            rememberPoint("refresh", refresh.centerX, refresh.centerY)
            ops.tapFast(refresh.centerX.toFloat(), refresh.centerY.toFloat())
            delay(150)
            return true
        }
        val x = box.right + (box.height * cfg.refreshTapRatio).toInt().coerceAtLeast(24)
        Log("  没找到刷新按钮节点，坐标点击 ($x, ${box.centerY})")
        ops.tap(x.toFloat(), box.centerY.toFloat())
        delay(700)
        return true
    }

    /**
     * 选时段（带重试）：没看到绿色格就刷新页面、重新切日期再看，
     * 直到 slotRetryMs 超时 —— 放号瞬间服务端可能有 1~3 秒延迟。
     */
    private suspend fun pickSlotWithRetry(target: LocalDate, fridayOnly: Boolean): SlotPick? {
        val deadline = SystemClock.elapsedRealtime() + cfg.slotRetryMs
        var attempt = 0
        while (true) {
            attempt++
            // ★ 只有日期真的切到 target 才允许点格子，否则可能误约到今天/明天
            val cur = navigateToDate(target)
            if (cur != target) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    Logs.w(TAG, "  始终切不到 $target（停在 $cur），放弃（可能是放号前或已过窗口）")
                    return null
                }
                Log("  还切不到 $target（停在 $cur），刷新后重试（第 $attempt 次）")
                tapRefresh()
                delay(1200)
                dismissPopups()
                continue
            }
            val pick = scanForSlot(fridayOnly)
            if (pick != null) return pick
            if (SystemClock.elapsedRealtime() >= deadline) {
                Logs.w(TAG, "  $target 重试 ${attempt} 次仍无绿色格，放弃")
                return null
            }
            Log("  第 $attempt 次没看到绿色格，刷新后重试")
            tapRefresh()
            delay(700)
            dismissPopups()
        }
    }

    // ─────────────────────────── 时段（截图取色） ───────────────────────────

    /** 截图（带最小间隔，避免触发系统的截图频率限制） */
    private suspend fun capture(): Bitmap? {
        val gap = cfg.screenshotIntervalMs - (SystemClock.elapsedRealtime() - lastShotAt)
        if (gap > 0) delay(gap)
        val bmp = cap.capture()
        lastShotAt = SystemClock.elapsedRealtime()
        return bmp
    }
    private suspend fun tryPickSlot(fridayOnly: Boolean): SlotPick? =
        if (cfg.useCellNodes) tryPickSlotByCells(fridayOnly) else tryPickSlotByHeaders(fridayOnly)

    /**
     * 用真机 dump 出来的结构还原时段表：
     *   行标题 = TextView "07:30-08:30"（垂直范围 ~126px）
     *   格子   = 可点击、无文字的 View（354x123，一列 20 个场地）
     * 把格子和行标题按垂直重叠配对 → 得到 (行, 列) → 截图上取每格中心颜色 → 判状态。
     */
    private suspend fun tryPickSlotByCells(fridayOnly: Boolean): SlotPick? {
        ensureTargetForeground("选择时段")
        val nodes = ops.collect()
        val labelRegex = cfg.slotRowLabel.textRegex?.let { runCatching { Regex(it) }.getOrNull() }
        val labels = nodes
            .filter { n ->
                val t = n.snap.text?.trim() ?: return@filter false
                n.snap.bounds.height >= 40 && (labelRegex?.containsMatchIn(t) ?: false)
            }
            .groupBy { it.snap.text!!.trim() }
            .mapNotNull { (text, list) ->
                val best = list.maxByOrNull { it.snap.bounds.height } ?: return@mapNotNull null
                LabelBox(text, best.snap.bounds.toBox())
            }
        val cells = nodes.filter { n ->
            n.snap.clickable &&
                n.snap.text.isNullOrBlank() && n.snap.desc.isNullOrBlank() &&
                n.snap.bounds.width >= cfg.cellMinWidth &&
                n.snap.bounds.height >= cfg.cellMinHeight &&
                n.snap.visible
        }
        if (labels.isEmpty() || cells.isEmpty()) {
            Log("  行标题=${labels.size} 格子=${cells.size}，还原不出时段表（当前前台=${ops.foregroundPackage() ?: "?"}）")
            return null
        }
        val rows = SlotGridGeometry.group(cells.map { CellBox(it.snap.bounds.toBox()) }, labels)
        if (rows.isEmpty()) {
            Log("  格子与行标题对不上（行标题=${labels.size}，格子=${cells.size}）")
            return null
        }
        Log("  扫描到 ${rows.size} 行 / 每行最多 ${rows.maxOf { it.cells.size }} 列：${rows.joinToString(" ") { it.label }}")

        val bitmap = capture()
            ?: throw FlowException(
                "选择时段",
                "截图失败（页面 FLAG_SECURE 或系统限制），无法判断格子颜色",
            )
        val samples = rows.map { row ->
            SlotRowSamples(
                rowIndex = row.startMinute,
                rowLabel = row.label,
                cells = row.cells.mapIndexed { idx, cell ->
                    val x = cell.centerX + cfg.slotGridCfg.sampleOffsetX
                    val y = cell.centerY + cfg.slotGridCfg.sampleOffsetY
                    val rgb = sampleCellRgb(bitmap, cell.box)
                    SlotCellSample(
                        rowIndex = row.startMinute,
                        colIndex = idx,
                        rowLabel = row.label,
                        colLabel = null,
                        rgb = rgb,
                        x = x,
                        y = y,
                    )
                },
            )
        }
        cap.recycle(bitmap)

        val pick = SlotGrid.decide(samples, cfg.slotGridCfg, cfg.slotCfg, fridayOnly, preferredSlotMinute()) { Log("  $it") }
            ?: return null
        Log("  ✔ ${pick.reason}")
        ops.tap(pick.cell.x.toFloat(), pick.cell.y.toFloat())
        delay(cfg.afterClickDelayMs)
        if (cfg.slotVerifyByRecapture) verifySlotSelected(pick)
        return pick
    }

    /** 老方案：用「时间列文字 × 场地表头」交叉估算格子位置（没有格子节点时兜底） */
    private suspend fun tryPickSlotByHeaders(fridayOnly: Boolean): SlotPick? {
        val rowNodes = ops.findAll(cfg.slotRowLabel)
        val colNodes = ops.findAll(cfg.slotColumnHeader)
        Log("  时段行 ${rowNodes.size} 个，场地列 ${colNodes.size} 个")
        if (rowNodes.isEmpty() || colNodes.isEmpty()) {
            dumpQuietly("slot-geometry")
            throw FlowException(
                "选择时段",
                "找不到时间列或场地列（行=${rowNodes.size} 列=${colNodes.size}）；" +
                    "界面文字=${ops.visibleTextDigest()}",
            )
        }
        val bitmap = capture()
            ?: throw FlowException("选择时段", "截图失败（FLAG_SECURE 或系统限制），无法判断时段颜色")
        val uniqueCols = colNodes
            .filter { it.snap.bounds.width >= 40 }
            .distinctBy { it.snap.bounds.centerX }
            .sortedBy { it.snap.bounds.centerX }
        val rows = rowNodes
            .filter { it.snap.bounds.height >= 40 }
            .distinctBy { it.snap.bounds.centerY }
            .sortedBy { it.snap.bounds.top }
            .mapIndexed { i, h ->
                SlotRowSamples(
                    rowIndex = i,
                    rowLabel = h.snap.text?.trim(),
                    cells = uniqueCols.mapIndexed { j, c ->
                        val cellBox = Box(
                            left = c.snap.bounds.left,
                            top = h.snap.bounds.top,
                            right = c.snap.bounds.right,
                            bottom = h.snap.bounds.bottom,
                        )
                        val x = cellBox.centerX + cfg.slotGridCfg.sampleOffsetX
                        val y = cellBox.centerY + cfg.slotGridCfg.sampleOffsetY
                        val rgb = sampleCellRgb(bitmap, cellBox)
                        SlotCellSample(i, j, h.snap.text?.trim(), c.snap.text?.trim(), rgb, x, y)
                    },
                )
            }
        cap.recycle(bitmap)
        val pick = SlotGrid.decide(rows, cfg.slotGridCfg, cfg.slotCfg, fridayOnly, preferredSlotMinute()) { Log("  $it") }
            ?: return null
        Log("  ✔ ${pick.reason}")
        ops.tap(pick.cell.x.toFloat(), pick.cell.y.toFloat())
        delay(cfg.afterClickDelayMs)
        if (cfg.slotVerifyByRecapture) verifySlotSelected(pick)
        return pick
    }

    /**
     * 扫描 + 滚动：先在当前可见区域找绿格；没有就向下滚（后面的时间段），
     * 再没有就向右滚（后面的场地号）。最多几轮，避免抢的时候磨蹭太久。
     */
    /**
     * 多点采样：格子中心 + 四个偏移点各取一次颜色，按 SlotState 投票。
     * 解决「边缘/渐变/动画中/被遮挡」导致单点采样判成未知的问题（之前第 1 列常被判未知）。
     */
    private fun sampleCellRgb(bitmap: Bitmap, cell: Box): Rgb {
        val dx = (cell.width * 0.15f).toInt().coerceAtLeast(4)
        val dy = (cell.height * 0.15f).toInt().coerceAtLeast(3)
        // 格子可能贴边/部分在屏幕外：把采样中心夹回屏幕内，避免第一列整列判「未知」
        val cx = cell.centerX.coerceIn(3, (bitmap.width - 4).coerceAtLeast(3))
        val cy = cell.centerY.coerceIn(3, (bitmap.height - 4).coerceAtLeast(3))
        val points = listOf(
            (cx + cfg.slotGridCfg.sampleOffsetX) to (cy + cfg.slotGridCfg.sampleOffsetY),
            (cx - dx) to cy,
            (cx + dx) to cy,
            cx to (cy - dy),
            cx to (cy + dy),
        )
        val rgbs = points.mapNotNull { (x, y) -> cap.sampleRegion(bitmap, x, y, cfg.slotSampleRadius) }
        if (rgbs.isEmpty()) return Rgb(255, 255, 255)
        val bestState = SlotGrid.classifyMost(rgbs, cfg.slotGridCfg)
        return rgbs.firstOrNull { SlotGrid.classify(it, cfg.slotGridCfg) == bestState } ?: rgbs.first()
    }

    private suspend fun scanForSlot(fridayOnly: Boolean): SlotPick? {
        var vertical = 0
        var horizontal = 0
        while (true) {
            tryPickSlot(fridayOnly)?.let { return it }
            if (vertical < cfg.maxVerticalScrollPasses) {
                vertical++
                scrollGridDown()
                continue
            }
            if (horizontal < cfg.maxHorizontalScrollPasses) {
                horizontal++
                scrollGridRight()
                continue
            }
            Log("  已扫描完可见区域（竖向 $vertical 次、横向 $horizontal 次），没有绿格")
            return null
        }
    }

    private suspend fun scrollGridDown() {
        Log("  向下滚动时段表（竖向第 ${cfg.maxVerticalScrollPasses} 次上限内）")
        ops.swipe(screenW * 0.5f, screenH * 0.78f, screenW * 0.5f, screenH * 0.30f, 380)
        delay(900)
    }

    private suspend fun scrollGridRight() {
        Log("  向右滚动看更多场地号")
        ops.swipe(screenW * 0.85f, screenH * 0.62f, screenW * 0.18f, screenH * 0.62f, 380)
        delay(900)
    }

    private suspend fun verifySlotSelected(pick: SlotPick) {
        val bmp = capture() ?: return
        val rgb = cap.sampleRegion(bmp, pick.cell.x, pick.cell.y, cfg.slotSampleRadius)
        cap.recycle(bmp)
        val state = rgb?.let { SlotGrid.classify(it, cfg.slotGridCfg) }
        if (state == SlotState.SELECTED) {
            Log("  复核：格子已变「已选择」色（${rgb?.hex()}）")
        } else {
            Logs.w(TAG, "  复核：格子颜色 ${rgb?.hex()} → ${state?.cn}，可能没选中（可调 slotSampleRadius）")
        }
    }

    // ─────────────────────────── 勾选 / 下单 / 同伴 ───────────────────────────

    private suspend fun ensureAgreeChecked() {
        cachedPoint("agree")?.let { p ->
            Log("  同意勾选：用学习坐标 (${p.first}, ${p.second})")
            ops.tapFast(p.first.toFloat(), p.second.toFloat())
            delay(80)
            return
        }
        val bottomY = (screenH * 0.55f).toInt()
        val checkables = ops.collect().filter {
            it.snap.checkable && it.snap.visible && it.snap.bounds.centerY >= bottomY
        }
        if (checkables.isNotEmpty()) {
            val target = checkables.first()
            Log("  勾选框：${target.snap.summary()}")
            if (target.snap.checked) {
                Log("  已经勾选，跳过")
                return
            }
            rememberPoint("agree", target.snap.bounds.centerX, target.snap.bounds.centerY)
            ops.tapFast(target.snap.bounds.centerX.toFloat(), target.snap.bounds.centerY.toFloat())
            delay(250)
            val after = ops.collect().firstOrNull {
                it.snap.checkable && it.snap.bounds.centerY >= bottomY
            }
            Log("  勾选后状态：${if (after?.snap?.checked == true) "已勾选" else "未确认"}")
            return
        }
        val node = ops.waitFor(cfg.agreeCheckbox, 2000, 150)
        if (node == null) {
            Logs.w(TAG, "  没找到「已阅读并同意」勾选框，继续（若提交时提示请勾选，请把 agreeCheckbox 改成实际文案）")
            return
        }
        Log("  点同意勾选：${node.snap.summary()}")
        ops.click(node, 300)
        delay(250)
    }

    private suspend fun waitCompanionPage() {
        ensureTargetForeground("等待同伴信息页")
        val node = ops.waitFor(cfg.companionPageReady, cfg.navTimeoutMs, cfg.pollIntervalMs)
        if (node == null) {
            dumpQuietly("companion-page")
            throw FlowException(
                "等待同伴信息页",
                "等不到同伴页；界面文字=${ops.visibleTextDigest()}",
            )
        }
        Log("同伴页就绪：${node.snap.summary()}")
        delay(cfg.afterNavDelayMs)
    }

    private suspend fun selectCompanionAndCode() {
        var code = prefs.inviteCode.trim().filter { it.isDigit() }
        if (code.length != 5) {
            Log("  还没有本次 5 位同伴码，弹窗等你输入…")
            code = InviteCodeGate.requestCode(svc, cfg.inviteCodeWaitMs)?.trim().orEmpty()
        }
        if (code.length != 5) {
            throw FlowException("填写同伴码", "没有拿到 5 位同伴码（超时或被跳过）")
        }

        // 1) 选同伴
        val wantName = prefs.companionName.ifBlank { cfg.companionName }
        val explicit = if (wantName.isNotBlank()) {
            ops.waitFor(listOf(Locator(textContains = wantName)), 2500, 150)
        } else null

        cachedPoint("companion")?.let { p ->
            Log("  同伴勾选：用学习坐标 (${p.first}, ${p.second})")
            ops.tapFast(p.first.toFloat(), p.second.toFloat())
            delay(120)
            return fillCompanionCode(code)
        }
        val checkbox = ops.findAll(cfg.companionCandidates).firstOrNull()
        val target = explicit ?: checkbox
        if (target == null) {
            Log("  没找到已添加的同伴，尝试「添加同伴」入口")
            val add = ops.waitFor(cfg.companionFallback, 2000, 150)
                ?: throw FlowException(
                    "选择同伴",
                    "找不到同伴勾选框，也找不到添加入口；界面文字=${ops.visibleTextDigest()}",
                )
            ops.click(add, cfg.afterClickDelayMs)
        } else {
            if (target.snap.checkable && target.snap.checked) {
                Log("  同伴已是勾选状态（${target.snap.text ?: "无名"}），跳过填码")
                return
            }
            Log("  勾选同伴：${target.snap.summary()}")
            rememberPoint("companion", target.snap.bounds.centerX, target.snap.bounds.centerY)
            ops.tapFast(target.snap.bounds.centerX.toFloat(), target.snap.bounds.centerY.toFloat())
        }
        delay(400)
        fillCompanionCode(code)
    }

    /**
     * 填 5 位同伴码：优先「学习坐标点一下 → 直接往焦点输入框写文本」，
     * 完全不遍历节点树；失败再回退到正常定位。
     */
    private suspend fun fillCompanionCode(code: String) {
        var ok = false
        val cachedInput = cachedPoint("code_input")
        if (cachedInput != null) {
            ops.tapFast(cachedInput.first.toFloat(), cachedInput.second.toFloat())
            delay(80)
            val focused = ops.focusedEditable()
            if (focused != null) {
                ok = ops.setText(focused, code)
                Log("  填码（学习坐标 + 焦点输入框）：${if (ok) "成功" else "失败"}")
            }
        }
        if (!ok) {
            val input = ops.waitFor(cfg.codeInput, cfg.stepTimeoutMs, cfg.pollIntervalMs)
                ?: throw FlowException(
                    "填写同伴码",
                    "点了同伴后没出现输入框；界面文字=${ops.visibleTextDigest()}",
                )
            val c = input.snap.bounds
            rememberPoint("code_input", c.centerX, c.centerY)
            Log("  输入框：${input.snap.summary()}")
            ops.click(input, 120)
            delay(80)
            ok = ops.setText(input, code)
            Log("  填码：${if (ok) "成功" else "失败"}")
        }
        delay(120)
        prefs.lastCode = code

        // 确定（坐标缓存优先）
        tapCached("confirm", cfg.btnConfirm, "点击确定")
        delay(250)
    }

    private suspend fun verifyResult() {
        val failNode = ops.waitFor(cfg.failureSigns, 900, 150)
        if (failNode != null) {
            throw FlowException("提交订单", "出现失败提示：${failNode.snap.text ?: failNode.snap.desc}")
        }
        val okNode = ops.waitFor(cfg.successSigns, cfg.submitVerifyMs, 250)
        if (okNode != null) {
            Log("✅ 成功标志：${okNode.snap.text ?: okNode.snap.desc}")
        } else {
            Logs.w(TAG, "⚠ 没看到明确成功标志，请到「我的预约」确认；界面文字=${ops.visibleTextDigest()}")
        }
        Log("按你的要求：不进行支付，流程到此结束")
    }

    // ─────────────────────────── 恢复 / 工具 ───────────────────────────

    /** 失败后回到「预约场地」页（能读日期就行） */
    private suspend fun rewindToBookingPage(): Boolean {
        repeat(4) {
            if (ops.waitFor(listOf(cfg.dateDisplay), 400, 120) != null) {
                Log("已回到预约场地页")
                return true
            }
            ops.back()
            delay(700)
        }
        Log("后退没用，重启目标 App 重新进入")
        return try {
            launchTarget()
            delay(1200)
            dismissPopups()
            waitHome()
            clickNav("进入场馆服务", cfg.entryService)
            dismissPopups()
            clickNav("进入场馆预约", cfg.entryBooking)
            dismissPopups()
            waitBookingPage()
            true
        } catch (e: FlowException) {
            Logs.e(TAG, "重新进入失败：${e.message}")
            false
        }
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        stepWithResult(name) { block() }
    }

    private suspend fun <T> stepWithResult(name: String, block: suspend () -> T): T {
        Log("▶ $name")
        val t0 = SystemClock.elapsedRealtime()
        val result = try {
            block()
        } catch (e: FlowException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw FlowException(name, e.message ?: e.javaClass.simpleName, e)
        }
        Log("✔ $name（${SystemClock.elapsedRealtime() - t0}ms）")
        return result
    }

    private fun dumpQuietly(tag: String) {
        runCatching { svc.dumpUi("flow-$tag") }
    }

    private fun Log(message: String) {
        Logs.i(TAG, message)
    }

    private fun Notify(message: String) {
        Logs.i(TAG, message)
        com.zju.courtgrab.util.Notify.showFlowRunning(ctx, message)
    }
}
