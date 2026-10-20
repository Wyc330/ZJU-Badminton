package com.zju.courtgrab

import android.Manifest
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zju.courtgrab.a11y.VenueAccessibilityService
import com.zju.courtgrab.alarm.AlarmScheduler
import com.zju.courtgrab.core.ConfigJson
import com.zju.courtgrab.core.ConfigRepo
import com.zju.courtgrab.core.DatePlanner
import com.zju.courtgrab.core.FlowBus
import com.zju.courtgrab.core.FlowState
import com.zju.courtgrab.core.GrabConfig
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.core.Prefs
import com.zju.courtgrab.databinding.ActivityMainBinding
import com.zju.courtgrab.direct.DirectBookingFlow
import com.zju.courtgrab.direct.VenueLoginActivity
import com.zju.courtgrab.service.GrabForegroundService
import com.zju.courtgrab.util.Perms
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale

/**
 * 主界面：
 *  · 顶部：下次触发倒计时 + 目标日期
 *  · 权限一览 + 一键跳转（无障碍 / 精确闹钟 / 通知 / 悬浮窗 / 电池 / MIUI 自启动）
 *  · 本次任务：触发时间、开关、时间段、同伴姓名、同伴码、演练、直连
 *  · 立即试跑 / 停止；直连自检；调试工具（可折叠）；实时日志
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "Main"
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var cfg: GrabConfig = GrabConfig.DEFAULT
    private var lastShownFinish = 0L
    private var debugOpen = false
    /** 权限卡片展开状态：null = 自动（有缺失就展开、全绿就收起） */
    private var permsOpen: Boolean? = null

    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            runCatching { refreshLive() }
            ui.postDelayed(this, 1000L)
        }
    }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs.get(this)
        Logs.init(applicationContext)
        cfg = ConfigRepo.load(this)

        bindUi()
        observeLogs()
        collectFlowState()
        maybeAskNotificationPermission()
        Logs.i(TAG, "主界面已打开，无障碍已连接=${VenueAccessibilityService.isConnected}")
        handleDebugAction(intent)
        renderPersistedResult()
        renderBuddyHint()
        showFirstRunGuide()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugAction(intent)
    }

    /** 仅 debug 构建通过 adb 广播触发（见 DebugTriggerReceiver），方便无人值守真机调试 */
    private fun handleDebugAction(intent: Intent?) {
        val action = intent?.getStringExtra("debug_action").orEmpty()
        if (action.isEmpty()) return
        Logs.i(TAG, "调试动作：$action")
        ui.postDelayed({
            when (action) {
                "selfcheck" -> runSelfCheck()
                "start" -> startNow()
                "login" -> startActivity(Intent(this, VenueLoginActivity::class.java))
                "dryrun_on" -> { prefs.dryRun = true; b.cbDryRun.isChecked = true; toast("演练模式：开") }
                "dryrun_off" -> { prefs.dryRun = false; b.cbDryRun.isChecked = false; toast("演练模式：关") }
                "direct_on" -> { prefs.directMode = true; b.cbDirectMode.isChecked = true; renderDirectStatus() }
                "direct_off" -> { prefs.directMode = false; b.cbDirectMode.isChecked = false; renderDirectStatus() }
                "enable_on" -> {
                    prefs.enabled = true
                    b.swEnabled.isChecked = true
                    AlarmScheduler.schedule(this, prefs)
                    refreshNextRun()
                    toast("已启用每日自动")
                    Logs.i(TAG, "调试动作：启用每日自动")
                }
                "enable_off" -> {
                    prefs.enabled = false
                    b.swEnabled.isChecked = false
                    AlarmScheduler.cancel(this)
                    refreshNextRun()
                    toast("已关闭每日自动")
                    Logs.i(TAG, "调试动作：关闭每日自动")
                }
                "prewarm_silent" -> startActivity(
                    Intent(this, VenueLoginActivity::class.java)
                        .putExtra(VenueLoginActivity.EXTRA_SILENT, true),
                )
            }
        }, 800)
    }

    override fun onResume() {
        super.onResume()
        cfg = ConfigRepo.load(this)
        b.cbDirectMode.isChecked = prefs.directMode
        refreshStatus()
        refreshNextRun()
        refreshConfigPath()
        ui.removeCallbacks(ticker)
        ui.post(ticker)
        maybeSilentRefresh()
    }

    /**
     * 登录态只有 4 小时：直连模式下若剩余不足 45 分钟，悄悄拉一次静默续期
     * （每隔 30 分钟最多一次，不打扰用户；CAS 会话还在就能免密续上）。
     */
    private fun maybeSilentRefresh() {
        if (!prefs.directMode) return
        val token = prefs.venueToken
        if (token.isBlank()) return
        val exp = jwtExpiry(token)
        val remainMs = (exp ?: return) - System.currentTimeMillis()
        if (remainMs > 45 * 60_000L) return
        val now = System.currentTimeMillis()
        if (now - prefs.lastSilentRefreshAt < 30 * 60_000L) return
        prefs.lastSilentRefreshAt = now
        Logs.i(TAG, "登录态剩余 ${remainMs / 60000} 分钟，自动尝试静默续期")
        startActivity(
            Intent(this, VenueLoginActivity::class.java)
                .putExtra(VenueLoginActivity.EXTRA_SILENT, true),
        )
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        super.onDestroy()
    }

    // ───────────────────────── UI 绑定 ─────────────────────────

    private fun bindUi() {
        // 同伴码（一次性）
        b.etInviteCode.setText(prefs.inviteCode)
        b.etInviteCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, bb: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, bb: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val digits = s?.toString()?.filter { it.isDigit() }.orEmpty().take(5)
                if (digits != s?.toString()) {
                    b.etInviteCode.setText(digits)
                    b.etInviteCode.setSelection(digits.length)
                    return
                }
                prefs.inviteCode = digits
                if (digits.length == 5) {
                    prefs.buddyCodeInvalidAt = 0L
                    renderBuddyHint()
                }
            }
        })
        renderBuddyHint()
        prefs.rememberCode = false

        // 同伴姓名（留空=第一个同伴）
        b.etCompanionName.setText(prefs.companionName)
        b.etCompanionName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, bb: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, bb: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                prefs.companionName = s?.toString()?.trim().orEmpty()
            }
        })

        b.btnPickCompanion.setOnClickListener { pickCompanionFromApi() }
        b.btnDiagnosisAction.setOnClickListener { runPendingFix() }
        b.cbAutoStop.isChecked = prefs.autoStopAfterSuccess
        b.cbAutoStop.setOnCheckedChangeListener { _, checked ->
            prefs.autoStopAfterSuccess = checked
            Logs.i(TAG, "成功后自动关闭每日自动 = $checked")
        }

        b.cbDryRun.isChecked = prefs.dryRun
        b.cbDryRun.setOnCheckedChangeListener { _, checked -> prefs.dryRun = checked }

        setupSlotTimeSpinner()

        b.cbDirectMode.isChecked = prefs.directMode
        b.cbDirectMode.setOnCheckedChangeListener { _, checked ->
            prefs.directMode = checked
            Logs.i(TAG, "直连下单模式 = $checked")
            renderDirectStatus()
        }
        b.btnVenueLogin.setOnClickListener {
            startActivity(Intent(this, VenueLoginActivity::class.java))
        }
        b.btnRefreshToken.setOnClickListener {
            Logs.i(TAG, "手动触发静默续期")
            startActivity(
                Intent(this, VenueLoginActivity::class.java)
                    .putExtra(VenueLoginActivity.EXTRA_SILENT, true),
            )
        }
        b.btnClearToken.setOnClickListener {
            prefs.venueToken = ""
            prefs.venueTokenAt = 0L
            renderDirectStatus()
            toast("已清除场馆登录态")
        }
        renderDirectStatus()

        b.swEnabled.isChecked = prefs.enabled
        b.swEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.enabled = checked
            if (checked) {
                AlarmScheduler.schedule(this, prefs)
                warnIfPermissionsMissing()
            } else {
                AlarmScheduler.cancel(this)
            }
            refreshNextRun()
        }

        // 权限跳转
        b.btnA11y.setOnClickListener { openA11yWithGuide() }
        b.btnAlarmPerm.setOnClickListener { Perms.openExactAlarmSettings(this) }
        b.btnNotify.setOnClickListener { maybeAskNotificationPermission(true) }
        b.btnOverlay.setOnClickListener { Perms.openOverlaySettings(this) }
        b.btnBattery.setOnClickListener { Perms.openBatterySettings(this) }
        b.btnAutostart.setOnClickListener { Perms.openAutostart(this) }

        // 任务
        b.btnPickTime.setOnClickListener { pickTime() }
        b.btnStartNow.setOnClickListener { startNow() }
        b.btnStop.setOnClickListener { stopFlow() }

        // 页脚：版本 + 仓库链接
        runCatching {
            @Suppress("DEPRECATION")
            val version = packageManager.getPackageInfo(packageName, 0).versionName
            b.tvAbout.text = "抢场地 v$version · MIT · 点击查看源码"
        }
        b.tvAbout.setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Wyc330/ZJU-Badminton")),
                )
            }.onFailure { toast("打不开浏览器：${it.message}") }
        }

        // 调试
        b.btnTogglePerms.setOnClickListener {
            permsOpen = b.cardPerms.visibility != View.VISIBLE
            applyPermsVisibility()
        }

        b.btnToggleDebug.setOnClickListener { toggleDebug() }
        b.btnSelfCheck.setOnClickListener { runSelfCheck() }
        b.btnDump.setOnClickListener { dumpCurrentUi() }
        b.btnDumpDelayed.setOnClickListener { dumpDelayed() }
        b.btnShareDump.setOnClickListener { shareLastDump() }
        b.btnShareLog.setOnClickListener { shareLog() }
        b.btnExportConfig.setOnClickListener { exportConfig() }
        b.btnReloadConfig.setOnClickListener { reloadConfig() }
        b.btnTestDate.setOnClickListener { previewDates() }
        b.btnClearLog.setOnClickListener { Logs.clear() }
        b.btnClearTapCache.setOnClickListener {
            prefs.tapCacheJson = ""
            toast("点击缓存已清除，下次运行会重新学习坐标")
            Logs.i(TAG, "点击缓存已清除")
        }
    }

    /** 首次使用引导：一次说明清 4 件事 */
    private fun showFirstRunGuide() {
        if (prefs.guideShown) return
        prefs.guideShown = true
        AlertDialog.Builder(this)
            .setTitle("欢迎使用 抢场地")
            .setMessage(
                "第一次用，按这 4 步来：\n\n" +
                    "1. 把「运行状态」里的 5 项权限都打开（无障碍最关键）\n" +
                    "2. 点「登录场馆系统」登录一次（学号密码只在你这台手机上输入）\n" +
                    "3. 让同伴把「我的同伴码」发你，填到「同伴码」；再选好想约的时间段\n" +
                    "4. 打开「启用每日自动抢场地」，到 9:00 自动执行；建议先开「演练模式」试跑一次\n\n" +
                    "流程结束后不管成功失败都会弹提示/通知，不会让你猜。",
            )
            .setPositiveButton("开始设置", null)
            .show()
    }

    /** 同伴码失效提示（上次校验失败后显示，填了新码自动消失） */
    private fun renderBuddyHint() {
        val at = prefs.buddyCodeInvalidAt
        val fresh = at > 0 && System.currentTimeMillis() - at < 24 * 60 * 60 * 1000L
        b.tvBuddyHint.visibility = if (fresh) View.VISIBLE else View.GONE
    }

    private enum class FixAction { NONE, LOGIN, A11Y, RETRY, OPEN_TARGET, FOCUS_CODE }

    private var pendingFix: FixAction = FixAction.NONE

    /** 根据上次结果给出人话诊断 + 一键处理 */
    private fun renderDiagnosis(st: FlowState) {
        if (st.running) {
            b.cardDiagnosis.visibility = View.GONE
            return
        }
        val msg = st.message
        val text: String
        val action: FixAction
        val btn: String
        when {
            st.success == true -> {
                text = "🎉 已下单成功：$msg"
                action = FixAction.OPEN_TARGET
                btn = "打开浙大体艺"
            }
            msg.isBlank() -> {
                b.cardDiagnosis.visibility = View.GONE
                return
            }
            msg.contains("登录") || msg.contains("401") || msg.contains("过期") || msg.contains("未登录") -> {
                text = "登录态问题：场馆登录已失效，重新登录一次即可恢复直连"
                action = FixAction.LOGIN
                btn = "去登录"
            }
            msg.contains("同伴码") || msg.contains("同伴") -> {
                text = "同伴码问题：让同伴在「同伴管理 → 我的同伴码」重新生成，再填入下方输入框"
                action = FixAction.FOCUS_CODE
                btn = "去填写"
            }
            msg.contains("无障碍") -> {
                text = "无障碍服务未开启：打开后流程才能操作浙大体艺"
                action = FixAction.A11Y
                btn = "去开启"
            }
            msg.contains("没有可预约") || msg.contains("没有空闲") || msg.contains("已预约") -> {
                text = "这次没抢到：目标时段已被约完；保持每日自动，下次放号继续"
                action = FixAction.RETRY
                btn = "再试一次"
            }
            msg.contains("切不到") || msg.contains("窗口") || msg.contains("未到") -> {
                text = "还没到可预约窗口：每天 9:00 放后天的号，到点再试"
                action = FixAction.NONE
                btn = ""
            }
            msg.contains("空白") || msg.contains("没渲染") || msg.contains("拉不起") || msg.contains("页面异常") -> {
                text = "浙大体艺页面没渲染好：点「重试」即可，App 会自动重新导航"
                action = FixAction.RETRY
                btn = "重试"
            }
            else -> {
                text = "上次失败：$msg"
                action = FixAction.RETRY
                btn = "重试"
            }
        }
        pendingFix = action
        b.tvDiagnosis.text = text
        b.cardDiagnosis.visibility = View.VISIBLE
        b.btnDiagnosisAction.visibility = if (action == FixAction.NONE) View.GONE else View.VISIBLE
        b.btnDiagnosisAction.text = btn
    }

    private fun runPendingFix() {
        when (pendingFix) {
            FixAction.LOGIN -> startActivity(
                Intent(this, VenueLoginActivity::class.java)
                    .putExtra(VenueLoginActivity.EXTRA_SILENT, true),
            )
            FixAction.A11Y -> openA11yWithGuide()
            FixAction.RETRY -> startNow()
            FixAction.OPEN_TARGET -> runCatching {
                val i = packageManager.getLaunchIntentForPackage("net.crigh.mysport")
                if (i != null) startActivity(i) else toast("没找到浙大体艺")
            }.onFailure { toast("打开失败：${it.message}") }
            FixAction.FOCUS_CODE -> {
                b.etInviteCode.requestFocus()
                toast("把同伴新生成的 5 位码填到「同伴码」输入框")
            }
            FixAction.NONE -> Unit
        }
    }

    /** 从场馆接口拉同伴列表，弹窗选择后写进输入框（需要有效登录态） */
    private fun pickCompanionFromApi() {
        if (prefs.venueToken.isBlank()) {
            toast("先点「登录场馆系统」登录一次，再选择同伴")
            return
        }
        toast("正在拉取同伴列表…")
        lifecycleScope.launch {
            val list = runCatching { DirectBookingFlow.buddyList(prefs) }
                .getOrElse {
                    toast("拉取失败：${it.message}")
                    return@launch
                }
            if (list.isEmpty()) {
                toast("没拉到同伴（可能登录态过期，或还没添加过同伴）")
                return@launch
            }
            val names = list.map { it.second }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("选择同伴（共 ${list.size} 个）")
                .setItems(names) { _, which ->
                    val picked = list[which].second
                    prefs.companionName = picked
                    b.etCompanionName.setText(picked)
                    toast("已选择同伴：$picked")
                    Logs.i(TAG, "同伴选择：$picked（id=${list[which].first}）")
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 启动时把持久化的上次结果渲染出来（重启 App 也能看到） */
    private fun renderPersistedResult() {
        val st = FlowBus.state.value
        if (st.running || st.success != null) return
        val msg = prefs.lastRunMessage
        if (msg.isBlank() || prefs.lastRunAt == 0L) return
        lastShownFinish = prefs.lastRunAt
        renderFlowState(
            FlowState(running = false, success = prefs.lastRunSuccess, message = msg, finishedAt = prefs.lastRunAt),
            showDialog = false,
        )
    }

    /** 权限卡：默认「缺什么就展开，全绿就收起」，用户点过后尊重用户选择 */
    private fun applyPermsVisibility() {
        val open = permsOpen ?: true
        b.cardPerms.visibility = if (open) View.VISIBLE else View.GONE
        b.tvPermsArrow.text = if (open) "收起 ▴" else "展开 ▾"
    }

    private fun toggleDebug() {
        debugOpen = !debugOpen
        b.layoutDebug.visibility = if (debugOpen) View.VISIBLE else View.GONE
        b.tvDebugArrow.text = if (debugOpen) "收起 ▴" else "展开 ▾"
    }

    // ───────────────────────── 状态刷新 ─────────────────────────

    private fun refreshLive() {
        val now = LocalDateTime.now()
        val trigger = now.toLocalDate().atTime(prefs.alarmHour, prefs.alarmMinute, prefs.alarmSecond)
        val next = if (now.isBefore(trigger)) trigger else trigger.plusDays(1)
        val d = Duration.between(now, next)
        b.tvCountdown.text = String.format(
            Locale.CHINA, "%02d:%02d:%02d", d.toHours(), d.toMinutesPart(), d.toSecondsPart(),
        )
        b.tvNextRun.text = if (prefs.enabled) {
            "每日 %s · 到点自动运行".format(
                String.format(Locale.CHINA, "%02d:%02d:%02d", prefs.alarmHour, prefs.alarmMinute, prefs.alarmSecond),
            )
        } else {
            "每日自动：未启用"
        }

        val target = DatePlanner.resolveTargetDate(now, cfg.dateCfg, prefs.alarmHour, prefs.alarmMinute)
        b.tvTargetDate.text = if (target != null) {
            String.format(Locale.CHINA, "%d/%d ", target.monthValue, target.dayOfMonth) +
                DatePlanner.weekdayCn(target.dayOfWeek)
        } else {
            "--"
        }

        val a11y = Perms.isA11yEnabled(this) || VenueAccessibilityService.isConnected
        val missing = ArrayList<String>()
        if (!a11y) missing += "无障碍"
        if (!Perms.canScheduleExact(this)) missing += "精确闹钟"
        if (!Perms.areNotificationsEnabled(this)) missing += "通知"
        if (!Perms.canDrawOverlay(this)) missing += "悬浮窗"
        if (!Perms.isIgnoringBattery(this)) missing += "电池白名单"
        if (missing.isEmpty()) {
            b.tvPermsSummary.text = "5/5 已就绪"
            b.tvPermsSummary.setTextColor(getColorCompat(R.color.ok))
            if (permsOpen == null) permsOpen = false
        } else {
            b.tvPermsSummary.text = "还差 ${missing.size} 项：" + missing.joinToString("/")
            b.tvPermsSummary.setTextColor(getColorCompat(R.color.warn))
            if (permsOpen == null) permsOpen = true
        }
        applyPermsVisibility()

        setStatus(b.tvA11yStatus, a11y, b.btnA11y)
        setStatus(b.tvAlarmPermStatus, Perms.canScheduleExact(this), b.btnAlarmPerm)
        setStatus(b.tvNotifyStatus, Perms.areNotificationsEnabled(this), b.btnNotify)
        setStatus(b.tvOverlayStatus, Perms.canDrawOverlay(this), b.btnOverlay)
        setStatus(b.tvBatteryStatus, Perms.isIgnoringBattery(this), b.btnBattery)

        renderDirectStatus()
    }

    private fun refreshStatus() = refreshLive()

    private fun setStatus(tv: TextView, ok: Boolean, button: android.widget.Button? = null) {
        tv.text = if (ok) "已开启" else "未开启"
        tv.setTextColor(getColorCompat(if (ok) R.color.ok else R.color.bad))
        button?.text = if (ok) "去设置" else "去开启"
    }

    private fun getColorCompat(id: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getColor(id)
        else resources.getColor(id, theme)

    private fun refreshNextRun() {
        b.tvAlarmTime.text = String.format(
            Locale.CHINA, "%02d:%02d:%02d", prefs.alarmHour, prefs.alarmMinute, prefs.alarmSecond,
        )
        refreshLive()
    }

    private fun refreshConfigPath() {
        runCatching {
            b.tvConfigPath.text = "配置文件：${ConfigRepo.externalConfigFile(this).absolutePath}\n" +
                "日志文件：${Logs.filePath() ?: "-"}"
        }
    }

    /** 显示直连登录态：已登录 / 过期 / 未登录（脱敏） */
    private fun renderDirectStatus() {
        val token = prefs.venueToken
        if (token.isBlank()) {
            b.tvDirectStatus.text = "直连：未登录（点「登录场馆系统」登录一次）"
            b.tvDirectStatus.setTextColor(getColorCompat(R.color.text_secondary))
            return
        }
        val expMs = jwtExpiry(token)
        val loginAt = if (prefs.venueTokenAt > 0) {
            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(prefs.venueTokenAt))
        } else ""
        when {
            expMs != null && expMs < System.currentTimeMillis() -> {
                b.tvDirectStatus.text = "直连：⚠️ 登录态已过期（${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(expMs))}），请重新登录"
                b.tvDirectStatus.setTextColor(getColorCompat(R.color.bad))
            }
            expMs != null -> {
                val remainMin = (expMs - System.currentTimeMillis()) / 60_000
                val warn = if (remainMin < 45) "（即将过期，点「自动续期」）" else ""
                b.tvDirectStatus.text = "直连：已登录 · 有效至 " +
                    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(expMs)) +
                    "（剩余 ${remainMin} 分钟）$warn"
                b.tvDirectStatus.setTextColor(getColorCompat(if (remainMin < 45) R.color.warn else R.color.ok))
            }
            else -> {
                b.tvDirectStatus.text = "直连：已登录（${token.take(6)}…${token.takeLast(4)}，$loginAt）"
                b.tvDirectStatus.setTextColor(getColorCompat(R.color.ok))
            }
        }
    }

    /** 解析 JWT 的 exp（毫秒）；不是 JWT 时返回 null */
    private fun jwtExpiry(token: String): Long? = runCatching {
        val parts = token.split(".")
        if (parts.size < 2) return@runCatching null
        val payload = android.util.Base64.decode(
            parts[1],
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        )
        val exp = JSONObject(String(payload, Charsets.UTF_8)).optLong("exp", 0L)
        if (exp > 0) exp * 1000 else null
    }.getOrNull()

    /** 时间段下拉框：保存到 Prefs，抢场地时按它优先选那一行（场地号随意） */
    private fun setupSlotTimeSpinner() {
        val starts = listOf(
            "07:30", "08:30", "09:30", "10:30", "11:50", "13:30", "14:30",
            "15:30", "16:30", "17:30", "18:30", "19:30", "20:30", "21:30",
        )
        val options = listOf("不限（取中间）") + starts.map { "$it 开始" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        b.spSlotTime.adapter = adapter

        val saved = prefs.preferredSlotStart
        val index = if (saved.isBlank()) 0 else options.indexOfFirst { it.startsWith(saved) }.let { if (it < 0) 0 else it }
        b.spSlotTime.setSelection(index, false)

        b.spSlotTime.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.preferredSlotStart = if (position == 0) "" else options[position].substringBefore(" ")
                Logs.i(TAG, "目标时间段 = " + (prefs.preferredSlotStart.ifBlank { "不限（取中间）" }))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** 打开无障碍设置前先弹一次手动路径 */
    private fun openA11yWithGuide() {
        if (Perms.isA11yEnabled(this) || VenueAccessibilityService.isConnected) {
            toast("无障碍服务已开启")
            refreshStatus()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("开启无障碍服务")
            .setMessage(
                "手动路径：\n设置 → 更多设置 / 无障碍 → 已下载的服务（部分机型叫「已安装的服务」）" +
                    " → 「抢场地 · 自动预约助手」→ 开启\n\n" +
                    "如果点「去设置」跳到了别的页面（部分 HyperOS 会误跳到 WLAN），" +
                    "请回到桌面按上面的路径手动进入；开启后回到本页会自动刷新状态。",
            )
            .setPositiveButton("去设置") { _, _ -> Perms.openA11ySettings(this) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun maybeAskNotificationPermission(request: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (request) Perms.openNotificationSettings(this)
            return
        }
        if (Perms.areNotificationsEnabled(this)) {
            if (request) toast("通知权限已开启")
            return
        }
        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun warnIfPermissionsMissing() {
        val missing = ArrayList<String>()
        if (!Perms.isA11yEnabled(this) && !VenueAccessibilityService.isConnected) missing += "无障碍服务"
        if (!Perms.canScheduleExact(this)) missing += "精确闹钟"
        if (!Perms.areNotificationsEnabled(this)) missing += "通知"
        if (!Perms.canDrawOverlay(this)) missing += "悬浮窗（后台拉起需要）"
        if (missing.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("还差 ${missing.size} 项权限")
            .setMessage("开启每日自动前，请先打开：\n\n" + missing.joinToString("\n") { "· $it" } +
                "\n\n否则到点可能无法自动完成预约。")
            .setPositiveButton("知道了", null)
            .show()
    }

    // ───────────────────────── 时间选择 ─────────────────────────

    private fun pickTime() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 32, 48, 8)
        }

        fun picker(max: Int, value: Int, label: String): NumberPicker {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val np = NumberPicker(this).apply {
                minValue = 0
                maxValue = max
                this.value = value.coerceIn(0, max)
                wrapSelectorWheel = true
            }
            col.addView(np, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(
                TextView(this).apply {
                    text = label
                    textSize = 12f
                    gravity = Gravity.CENTER
                },
            )
            container.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return np
        }

        val hourP = picker(23, prefs.alarmHour, "时")
        val minP = picker(59, prefs.alarmMinute, "分")
        val secP = picker(59, prefs.alarmSecond, "秒")

        AlertDialog.Builder(this)
            .setTitle("每日触发时间（建议 08:59:45，放号前十几秒）")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                prefs.alarmHour = hourP.value
                prefs.alarmMinute = minP.value
                prefs.alarmSecond = secP.value
                refreshNextRun()
                if (prefs.enabled) {
                    val ok = AlarmScheduler.schedule(this, prefs)
                    toast(if (ok) "已重新排定" else "排闹钟失败，请检查精确闹钟权限")
                }
                Logs.i(TAG, "触发时间改为 ${hourP.value}:${minP.value}:${secP.value}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ───────────────────────── 任务操作 ─────────────────────────

    private fun startNow() {
        if (!Perms.isA11yEnabled(this) && !VenueAccessibilityService.isConnected) {
            toast("请先开启无障碍服务")
            Perms.openA11ySettings(this)
            return
        }
        val code = b.etInviteCode.text.toString().trim()
        if (code.isNotEmpty() && code.length != 5) {
            toast("同伴码需要 5 位数字")
            return
        }
        if (code.length == 5) prefs.inviteCode = code
        Logs.i(
            TAG,
            "手动试跑（同伴=${b.etCompanionName.text.ifBlank { "默认" }}，同伴码=${if (code.length == 5) "已填" else "未填，流程中会弹窗要"}，直连=${prefs.directMode}）",
        )
        toast("开始试跑：请保持屏幕常亮、不要操作手机")
        GrabForegroundService.startFire(this)
    }

    private fun stopFlow() {
        val svc = VenueAccessibilityService.instance
        if (svc == null) {
            toast("无障碍服务未连接")
            return
        }
        svc.stopFlow("主界面手动停止")
        toast("已请求停止")
    }

    /** 直连自检：验证登录态 / 场馆 / 解析 / 同伴 */
    private fun runSelfCheck() {
        toast("正在自检…")
        lifecycleScope.launch {
            val text = runCatching { DirectBookingFlow.selfCheck(prefs, cfg) }
                .getOrElse { "自检异常：${it.message}" }
            Logs.i(TAG, "直连自检：\n$text")
            showTextDialog("直连自检结果", text)
        }
    }

    // ───────────────────────── 调试工具 ─────────────────────────

    private fun dumpCurrentUi() {
        val svc = VenueAccessibilityService.instance
        if (svc == null) {
            toast("无障碍服务未连接：请先开启")
            Perms.openA11ySettings(this)
            return
        }
        toast("正在抓取…")
        lifecycleScope.launch {
            val result = svc.dumpUi("manual")
            if (result == null) {
                toast("抓取失败，详见日志")
                return@launch
            }
            prefs.lastDumpPath = result.json.absolutePath
            showTextDialog(
                "抓取完成（${result.nodeCount} 个节点）",
                "JSON：\n${result.json.absolutePath}\n\nTXT：\n${result.text.absolutePath}\n\n" +
                    "界面文字：\n${svc.ops.visibleTextDigest(40)}\n\n" +
                    "可点节点：\n${svc.ops.clickableDigest(20)}",
            )
        }
    }

    private fun dumpDelayed() {
        val svc = VenueAccessibilityService.instance
        if (svc == null) {
            toast("无障碍服务未连接：请先开启")
            Perms.openA11ySettings(this)
            return
        }
        toast("5 秒后抓取：请立刻切到「浙大体艺」的目标页面")
        svc.dumpUiAfter(5_000, "delayed")
    }

    private fun shareLastDump() {
        val path = prefs.lastDumpPath
        if (path.isEmpty() || !File(path).exists()) {
            toast("还没有抓取结果，先点「抓取当前界面节点树」")
            return
        }
        shareFile(File(path), "application/json", "分享抓取结果")
    }

    private fun shareLog() {
        val path = Logs.filePath()
        if (path.isNullOrEmpty() || !File(path).exists()) {
            toast("还没有日志文件")
            return
        }
        shareFile(File(path), "text/plain", "分享运行日志")
    }

    private fun shareFile(file: File, mime: String, title: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, title))
        }.onFailure { toast("分享失败：${it.message}") }
    }

    private fun exportConfig() {
        lifecycleScope.launch {
            runCatching {
                val f = ConfigRepo.writeExternal(this@MainActivity, ConfigJson.toJson(ConfigRepo.current))
                showTextDialog(
                    "配置已导出",
                    "路径：\n${f.absolutePath}\n\n" +
                        "改完点「重新载入配置文件」立即生效，不用重装。\n\n" +
                        "最可能要改的键：\n" +
                        "· entryService / entryBooking —— 场馆服务、场馆预约的入口文案\n" +
                        "· venueFengYu / venueBadminton —— 风雨操场、羽毛球场文案\n" +
                        "· dateCfg.useNextReleaseDate / releaseDaysAhead —— 目标日期策略（默认待开票最近一天）\n" +
                        "· preferredSlotStart —— 想预约的时间段\n" +
                        "· directVenueName / directVenueSiteId —— 直连时匹配的场馆\n" +
                        "· directMaxWaitMs / directPollMinMs —— 直连轮询时长与间隔\n" +
                        "· codeInput / btnConfirm —— 同伴码输入框与确定按钮",
                )
            }.onFailure { toast("导出失败：${it.message}") }
        }
    }

    private fun reloadConfig() {
        cfg = ConfigRepo.forceReloadFromExternal(this)
        refreshConfigPath()
        Logs.i(TAG, "已重载配置：\n${cfg.summary()}")
        toast("配置已重新载入")
    }

    private fun previewDates() {
        val now = LocalDateTime.now()
        val target = DatePlanner.resolveTargetDate(now, cfg.dateCfg, prefs.alarmHour, prefs.alarmMinute)
        val text = buildString {
            appendLine("现在：${Logs.fullTime()}")
            appendLine("目标日期：${target ?: "算不出"} ${target?.let { "（${DatePlanner.weekdayCn(it.dayOfWeek)}）" } ?: ""}")
            appendLine()
            appendLine("规则：每天 09:00 放「后天」的号")
            appendLine("· 09:00 前触发 → 今天+2")
            appendLine("· 09:00 后触发 → 明天+2")
            appendLine()
            appendLine("如需退回按星期优先级（旧逻辑），在配置里设 dateCfg.useNextReleaseDate=false")
        }
        showTextDialog("目标日期预览", text)
    }

    // ───────────────────────── 流程状态 / 完成提示 ─────────────────────────

    private fun collectFlowState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                FlowBus.state.collect { st -> renderFlowState(st) }
            }
        }
    }

    private fun renderFlowState(st: FlowState, showDialog: Boolean = true) {
        val (bgColor, textColor) = when {
            st.running -> R.color.brand_light to R.color.brand_dark
            st.success == true -> R.color.ok_light to R.color.ok
            st.success == false -> R.color.bad_light to R.color.bad
            else -> R.color.brand_light to R.color.text_secondary
        }
        renderDiagnosis(st)
        b.tvFlowState.text = when {
            st.running -> "⏳ 流程运行中…（不要切走；切走会自动把浙大体艺拉回来）"
            st.success == null -> "上次结果：还没跑过"
            st.success -> "✅ 上次成功${if (st.finishedAt > 0) "（" + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(st.finishedAt)) + "）" else ""}：${st.message}"
            else -> "❌ 上次失败${if (st.finishedAt > 0) "（" + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(st.finishedAt)) + "）" else ""}：${st.message}"
        }
        b.tvFlowState.background = GradientDrawable().apply {
            cornerRadius = 12f * resources.displayMetrics.density
            setColor(getColorCompat(bgColor))
        }
        b.tvFlowState.setTextColor(getColorCompat(textColor))

        if (!st.running && st.success != null) {
            // 持久化：重启 App 也能看到上次结果
            prefs.lastRunSuccess = st.success
            prefs.lastRunMessage = st.message
            prefs.lastRunAt = st.finishedAt
        }
        if (showDialog && !st.running && st.success != null && st.finishedAt > lastShownFinish) {
            lastShownFinish = st.finishedAt
            refreshStatus()
            refreshNextRun()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                AlertDialog.Builder(this)
                    .setTitle(if (st.success) "✅ 抢场地完成" else "❌ 抢场地失败")
                    .setMessage(st.message)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    // ───────────────────────── 日志 ─────────────────────────

    private fun observeLogs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Logs.lines.collect { lines ->
                    b.tvLog.text = if (lines.isEmpty()) "（暂无日志）" else lines.joinToString("\n")
                    b.scrollLog.post { b.scrollLog.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private fun showTextDialog(title: String, message: String) {
        val tv = TextView(this).apply {
            text = message
            setPadding(48, 24, 48, 8)
            textSize = 13f
            setTextIsSelectable(true)
            setTextColor(getColorCompat(R.color.text_primary))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(tv)
            .setPositiveButton("好", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
