package com.zju.courtgrab

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.zju.courtgrab.direct.VenueLoginActivity
import android.view.ViewGroup
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
import com.zju.courtgrab.service.GrabForegroundService
import com.zju.courtgrab.util.Perms
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime

/**
 * 主界面：
 *  · 权限一览 + 一键跳转（无障碍 / 精确闹钟 / 通知 / 悬浮窗 / 电池 / MIUI 自启动）
 *  · 每日触发时间、开关、本次邀请码
 *  · 立即试跑 / 停止
 *  · 调试工具：抓取当前界面节点树、分享、导出/重载配置、日期预览
 *  · 实时日志
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "Main"
    }

    private lateinit var b: ActivityMainBinding
    /** 已经弹过提示的流程结束时间，避免重复弹 */
    private var lastShownFinish = 0L
    private lateinit var prefs: Prefs
    private var cfg: GrabConfig = GrabConfig.DEFAULT

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
    }

    override fun onResume() {
        super.onResume()
        b.cbDirectMode.isChecked = prefs.directMode
        renderDirectStatus()
        refreshStatus()
        refreshNextRun()
        refreshConfigPath()
    }

    // ───────────────────────── UI 绑定 ─────────────────────────

    private fun bindUi() {
        // 邀请码
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
            }
        })

        b.cbRememberCode.isChecked = prefs.rememberCode
        b.cbRememberCode.setOnCheckedChangeListener { _, checked -> prefs.rememberCode = checked }

        b.cbDryRun.isChecked = prefs.dryRun
        b.cbDryRun.setOnCheckedChangeListener { _, checked -> prefs.dryRun = checked }

        // 「想预约的时间段」下拉框（任意场地，按时段优先）
        setupSlotTimeSpinner()

        // 直连下单模式（极速；失败自动回退界面流程）
        b.cbDirectMode.isChecked = prefs.directMode
        b.cbDirectMode.setOnCheckedChangeListener { _, checked ->
            prefs.directMode = checked
            Logs.i(TAG, "直连下单模式 = $checked")
            renderDirectStatus()
        }
        b.btnVenueLogin.setOnClickListener {
            startActivity(android.content.Intent(this, VenueLoginActivity::class.java))
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

        // 调试
        b.btnDump.setOnClickListener { dumpCurrentUi() }
        b.btnDumpDelayed.setOnClickListener { dumpDelayed() }
        b.btnShareDump.setOnClickListener { shareLastDump() }
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

    /** 显示直连模式登录态（脱敏） */
    private fun renderDirectStatus() {
        val token = prefs.venueToken
        val whenText = if (prefs.venueTokenAt > 0) {
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date(prefs.venueTokenAt))
        } else ""
        b.tvDirectStatus.text = when {
            token.isBlank() -> "直连：未登录场馆系统（点下方按钮登录一次）"
            else -> "直连：已登录（${token.take(6)}…${token.takeLast(4)}，$whenText）"
        }
    }

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

    /**
     * 打开无障碍设置前先弹一次手动路径。
     * 实测部分 HyperOS 机型上直接跳 ACTION_ACCESSIBILITY_SETTINGS 会落到 WLAN 设置页，
     * 所以把「设置 → 无障碍 → 已下载的服务」这条路径明确写给用户，跳错了也能自己走。
     */
    private fun openA11yWithGuide() {
        if (Perms.isA11yEnabled(this)) {
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
                    "请回到桌面按上面的路径手动进入；开启后回到本页会自动刷新状态。"
            )
            .setPositiveButton("去设置") { _, _ -> Perms.openA11ySettings(this) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ───────────────────────── 状态刷新 ─────────────────────────

    private fun refreshStatus() {
        setStatus(b.tvA11yStatus, Perms.isA11yEnabled(this))
        setStatus(b.tvAlarmPermStatus, Perms.canScheduleExact(this))
        setStatus(b.tvNotifyStatus, Perms.areNotificationsEnabled(this))
        setStatus(b.tvOverlayStatus, Perms.canDrawOverlay(this))
        setStatus(b.tvBatteryStatus, Perms.isIgnoringBattery(this))
    }

    private fun setStatus(tv: TextView, ok: Boolean) {
        tv.text = if (ok) "已开启" else "未开启"
        tv.setTextColor(getColorCompat(if (ok) R.color.ok else R.color.bad))
    }

    private fun getColorCompat(id: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getColor(id)
        else resources.getColor(id, theme)

    private fun refreshNextRun() {
        b.tvAlarmTime.text = String.format("%02d:%02d:%02d", prefs.alarmHour, prefs.alarmMinute, prefs.alarmSecond)
        b.tvNextRun.text = "下次触发：" + AlarmScheduler.describeNext(prefs)
    }

    private fun refreshConfigPath() {
        runCatching {
            b.tvConfigPath.text = "配置文件：${ConfigRepo.externalConfigFile(this).absolutePath}\n" +
                "日志文件：${Logs.filePath() ?: "-"}"
        }
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
        if (!Perms.isA11yEnabled(this)) missing += "无障碍服务"
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
                gravity = android.view.Gravity.CENTER_HORIZONTAL
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
                    gravity = android.view.Gravity.CENTER
                }
            )
            container.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return np
        }

        val hourP = picker(23, prefs.alarmHour, "时")
        val minP = picker(59, prefs.alarmMinute, "分")
        val secP = picker(59, prefs.alarmSecond, "秒")

        AlertDialog.Builder(this)
            .setTitle("每日触发时间（建议 07:59:55，抢整点前几秒）")
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
        if (!Perms.isA11yEnabled(this)) {
            toast("请先开启无障碍服务")
            Perms.openA11ySettings(this)
            return
        }
        val code = b.etInviteCode.text.toString().trim()
        if (code.isNotEmpty() && code.length != 5) {
            toast("邀请码需要 5 位数字")
            return
        }
        if (code.length == 5) prefs.inviteCode = code
        Logs.i(TAG, "手动试跑（邀请码=${if (code.length == 5) "已填" else "未填，流程中会弹窗要"}）")
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
                    "可点节点：\n${svc.ops.clickableDigest(20)}\n\n" +
                    "把 TXT 发给我，就能把 viewId / text 抄进配置。",
            )
        }
    }

    /**
     * 延时抓取：无障碍只能看到「当前可见」的窗口。
     * 所以先点这个按钮，然后 5 秒内切到浙大体艺的目标页面，服务会在那边抓。
     */
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
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "分享抓取结果"))
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
                        "· timeSlots —— 时段节点的正则\n" +
                        "· btnBookNow / noticeItem / btnSubmitOrder —— 我要预约、通报信息、提交订单\n" +
                        "· dateCfg.weekdayPriority —— 日期优先级（默认 FRI→SAT→SUN）\n" +
                        "· codeInput / btnConfirm —— 邀请码输入框与确定按钮",
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
        val text = DatePlanner.preview(LocalDateTime.now(), cfg.dateCfg)
        showTextDialog(
            "目标日期优先级（按今天算）",
            "会按这个顺序尝试：\n\n$text\n\n" +
                "说明：预约页用左右箭头切日期，窗口实测是「今天 + 2 天」。" +
                "App 会从今天开始按上面的优先级挑，切不过去就说明超出窗口。\n\n" +
                "周五会只挑下午时段（dateCfg.fridayAfternoonOnly=true）；" +
                "窗口内没有周五/六/日时本次不抢（requirePreferredWeekday）。",
        )
    }

    // ───────────────────────── 流程状态 / 完成提示 ─────────────────────────

    private fun collectFlowState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                FlowBus.state.collect { st -> renderFlowState(st) }
            }
        }
    }

    private fun renderFlowState(st: FlowState) {
        b.tvFlowState.text = when {
            st.running -> "⏳ 流程运行中…（请不要切走；切走会自动把浙大体艺拉回来）"
            st.success == null -> "上次结果：还没跑过"
            st.success -> "✅ 上次成功：${st.message}"
            else -> "❌ 上次失败：${st.message}"
        }
        if (!st.running && st.success != null && st.finishedAt > lastShownFinish) {
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
