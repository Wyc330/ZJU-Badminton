package com.zju.courtgrab.core

import java.time.DayOfWeek

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  界面事实集中在这里。标「实测」的默认值来自 2026-10-19 的真机截图；          ║
 * ║  标「★ 待核对」的需要抓一次节点树确认。                                     ║
 * ║                                                                          ║
 * ║  改配置不用重装：改 grab-config.json → App 里点「重新载入配置文件」         ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */
data class GrabConfig(

    // ① 目标 App ─────────────────────────────────────────────────────────────
    /** 实测：浙大体艺包名（来自安装包 AndroidManifest.xml） */
    val targetPackage: String = "net.crigh.mysport",
    /** 实测：启动页（manifest 里唯一的 MAIN/LAUNCHER） */
    val targetActivity: String = "net.crigh.cgsport.ui.activity.ZdSplashActivity",
    val launchTimeoutMs: Long = 20_000,

    // ② 弹窗 ─────────────────────────────────────────────────────────────────
    /**
     * 实测：进「场馆服务」/「预约场地」时会弹「预约须知」（底部有「关闭」，19 秒后自动关）。
     * 这里按顺序找，命中就点；每次页面跳转后都会再试一轮。
     */
    val popupClosers: List<Locator> = listOf(
        Locator(textContains = "关闭", note = "实测：预约须知弹窗的关闭按钮"),
        Locator(textEquals = "×", note = "右上角叉号"),
        Locator(textEquals = "X", note = "右上角叉号（大写）"),
        Locator(descContains = "关闭", note = "无文字的关闭图标"),
        Locator(textContains = "我知道了"),
        Locator(textContains = "以后再说"),
        Locator(textContains = "暂不"),
        Locator(textContains = "跳过"),
    ),
    /**
     * 预约须知弹窗识别词。H5 弹窗的「关闭」按钮有时不暴露成无障碍节点，
     * 检测到这些词时用相对坐标兜底点击（实测按钮在屏幕 86.8% / 67.1% 处）。
     */
    val noticeTexts: List<String> = listOf("预约须知", "温馨提示", "弹窗将在", "秒后关闭"),
    val noticeCloseXRatio: Float = 0.868f,
    val noticeCloseYRatio: Float = 0.671f,
    val popupRounds: Int = 3,

    // ③ 首页 / 登录态 ────────────────────────────────────────────────────────
    /** 实测：首页九宫格里第三行第四个是「场馆服务」；标题 id 来自安装包 */
    val homeReady: List<Locator> = listOf(
        Locator(id = "tv_home_model_title", note = "首页九宫格标题（APK 内确认存在）"),
        Locator(textContains = "场馆服务"),
    ),
    val loginHints: List<Locator> = listOf(
        Locator(textContains = "账号登录"),
        Locator(textContains = "密码登录"),
        Locator(textContains = "学号/工号"),
        Locator(id = "btn_login"),
    ),

    // ④ 入口 ─────────────────────────────────────────────────────────────────
    /** 实测：首页 →「场馆服务」 */
    val entryService: List<Locator> = listOf(
        Locator(id = "tv_home_model_title", textContains = "场馆服务", note = "首选：九宫格标题 id + 文字"),
        Locator(textContains = "场馆服务", note = "兜底：任意含「场馆服务」的节点"),
    ),

    /**
     * 实测：点「场馆服务」后是六宫格页（场馆预约 / 艺术中心 / 预约记录 / 套餐中心 / 同伴管理 / 培训课程）。
     * 注意页面标题也叫「场馆预约」，会命中多个节点 —— 流程会依次尝试点击直到界面真的变化。
     */
    val entryBooking: List<Locator> = listOf(
        Locator(textContains = "场馆预约", note = "六宫格里的「场馆预约」"),
    ),

    /** 实测：「预约场地」页就绪标志（能看到预约日期/校区/我要预约 任一） */
    val bookingPageReady: List<Locator> = listOf(
        Locator(textContains = "预约日期", note = "实测：左上角标签"),
        Locator(textContains = "我要预约", note = "实测：底部橙色按钮"),
        Locator(textContains = "校区", note = "实测：校区选择行"),
    ),

    // ⑤ 校区 ─────────────────────────────────────────────────────────────────
    /**
     * 实测：校区是横向 tab 行（紫金港校区 / 玉泉校区 / 西溪校区 / 华家池校区），
     * 默认就选中「紫金港校区」。点它幂等，用来确保选区正确。
     */
    val campusTarget: String = "紫金港校区",
    val campusSelect: List<Locator> = listOf(
        Locator(textContains = "紫金港校区", note = "实测：默认选中，点一下确保"),
    ),
    val campusOptional: Boolean = true,
    /**
     * 是否真的点一下校区 tab。
     * 实测默认就选中紫金港，而点它会让整个时段表重新加载 → 拖慢抢场地，所以默认不点。
     */
    val campusClick: Boolean = false,

    // ⑥⑦ 场馆 / 场地 ─────────────────────────────────────────────────────────
    /** 实测：场馆行里的「风雨操场」 */
    val venueFengYu: List<Locator> = listOf(
        Locator(textContains = "风雨操场", note = "实测：场馆 tab"),
    ),
    /** 实测：场地行里的「羽毛球场」 */
    val venueBadminton: List<Locator> = listOf(
        Locator(textContains = "羽毛球场", note = "实测：场地按钮"),
        Locator(textContains = "羽毛球", note = "兜底"),
    ),
    /**
     * 点完「风雨操场」后，如果时段表已经出现，就跳过点「羽毛球场」
     * （实测该场地只有一个选项且默认已选中）。
     */
    val skipCourtClickIfGridReady: Boolean = true,

    // ⑧ 日期（实测：左右箭头 + 中间日期框，不是日期格子）─────────────────────
    /** 实测：日期框显示 2026-10-19 这种格式；`<` / `>` 按钮各移动一天 */
    val dateDisplay: Locator = Locator(
        textRegex = "\\d{4}\\s*[-/]\\s*\\d{1,2}\\s*[-/]\\s*\\d{1,2}",
        note = "实测：预约日期框里的 2026-10-19",
    ),
    /** 找不到可点的箭头节点时，按「日期框高度的倍数」左右偏移点击 */
    val dateArrowFallbackRatio: Float = 1.15f,

    /**
     * 日期策略。
     * 实测：日期选择器里只有「今天 + 2 天」可点（2026-10-19 那天看，22 号起是灰的），
     * 所以 maxDaysAhead = 2；窗口内没有周五/六/日时这次就不抢。
     */
    val dateCfg: DatePlanConfig = DatePlanConfig(
        weekdayPriority = listOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
        includeToday = true,
        maxDaysAhead = 0,
        useNextReleaseDate = true,
        releaseDaysAhead = 2,
        fridayAfternoonOnly = false,
        datePatterns = listOf(
            "(\\d{4})\\s*[-/\\.年]\\s*(\\d{1,2})\\s*[-/\\.月]\\s*(\\d{1,2})",
            "(\\d{1,2})\\s*[-/\\.月]\\s*(\\d{1,2})\\s*日?",
        ),
    ),
    /**
     * 窗口内没有优先级里的星期时，是否直接放弃。
     * 默认 false —— 你说过「后面几天有场地」，所以窗口内的其它日子也抢（后天优先）。
     */
    val requirePreferredWeekday: Boolean = false,
    /** 日期候选是否优先「后天」（每天 09:00 刚放出来的那天，最有货） */
    val preferFreshDates: Boolean = true,

    // ⑧½ 放号时间 ───────────────────────────────────────────────────────────
    /**
     * 实测：每天早上 09:00 刷新，从这一刻起才能约「后天」的场地。
     * 流程会提前就位，到点先点一次刷新再抢。
     */
    val releaseHour: Int = 9,
    val releaseMinute: Int = 0,
    val releaseSecond: Int = 0,
    /** 是否等在放号点再抢（false = 一到现场就抢，手动试跑时有用） */
    val waitForRelease: Boolean = true,
    /** 「刷新」按钮的坐标比例（相对日期框高度，位于日期框右侧） */
    val refreshTapRatio: Float = 2.5f,
    /** 放号后没看到绿色格时，刷新重试的最长时间 */
    val slotRetryMs: Long = 20_000,

    // ⑨ 时段表（实测：纯色块，只能截图取色）────────────────────────────────
    /** 实测：左列时间标题，如 07:30-08:30 */
    val slotRowLabel: Locator = Locator(
        textRegex = "\\d{1,2}\\s*[:：]\\s*\\d{2}\\s*[-~—－–]\\s*\\d{1,2}\\s*[:：]\\s*\\d{2}",
        order = MatchOrder.TOP_LEFT,
        note = "实测：时间列",
    ),
    /** 实测：表头场地列，如 1号 / 2号（横向可能还有更多列） */
    val slotColumnHeader: Locator = Locator(
        textRegex = "^\\s*\\d+\\s*号\\s*$",
        order = MatchOrder.LEFT_TOP,
        note = "实测：1号 / 2号 列标题",
    ),
    /**
     * 用「格子节点」直接还原时段表（真机 dump 确认：格子是可点 View，约 354x123，
     * 行标题是 TextView "07:30-08:30"）。比按表头交叉猜测准得多。
     */
    val useCellNodes: Boolean = true,
    /** 格子最小宽/高（过滤掉 0 宽度的屏幕外节点） */
    val cellMinWidth: Int = 60,
    val cellMinHeight: Int = 60,
    /** 可见区域没找到绿格时：向下滚动再扫的次数 */
    val maxVerticalScrollPasses: Int = 2,
    /** 还没找到：向右滚动看更多场地再扫的次数 */
    val maxHorizontalScrollPasses: Int = 1,

    /** 取色采样半径（像素），越大越抗噪但可能越过格子边界 */
    val slotSampleRadius: Int = 5,
    /** 颜色 → 状态 映射与选中规则（绿=可约 / 红=已约 / 灰=不可约） */
    val slotGridCfg: SlotGridConfig = SlotGridConfig(),
    /** 时段格的上午/下午识别（周五只看下午） */
    val slotCfg: SlotConfig = SlotConfig(),
    /** 点完是否再截一次图确认格子变成「已选择」色 */
    val slotVerifyByRecapture: Boolean = false,
    /**
     * 想预约的时间段开始时间，如 "18:30"（随便哪个场地都行）。
     * 留空 = 取中间偏后的空闲时段。主界面下拉框里可以选，无需改这里。
     */
    val preferredSlotStart: String = "",
    /** 直连下单的轮询总时长（毫秒）：从放号时刻起最多重试这么久 */
    val directMaxWaitMs: Long = 120_000,
    /** 直连重试时每轮之间的最小间隔（毫秒） */
    val directPollMinMs: Long = 250,
    /** 直连重试的随机抖动上限（毫秒），实际间隔 = directPollMinMs + random(0..jitter) */
    val directPollJitterMs: Long = 400,
    /** 直连最多尝试轮数（与 directMaxWaitMs 一起兜底，避免无限请求） */
    val directMaxAttempts: Int = 240,
    /** 直连下单时匹配的场馆名称（接口里按名字找 id） */
    val directVenueName: String = "风雨操场",
    /** 直连下单时直接指定场馆 id（0=自动按名称匹配） */
    val directVenueSiteId: Long = 0L,
    /**
     * 极速模式：提前把「已阅读并同意」勾好；放号瞬间只做
     * 刷新 → 切日期 → 点格子 → 我要预约 这四件事。
     */
    val turboMode: Boolean = true,

    // ⑩ 底部勾选 ─────────────────────────────────────────────────────────────
    /** 实测：底部「☑ 已阅读并同意 预约须知」 */
    val agreeCheckbox: List<Locator> = listOf(
        Locator(textContains = "已阅读并同意", note = "实测：底部勾选项"),
    ),

    /** 实测：底部橙色按钮「我要预约」 */
    val btnBookNow: List<Locator> = listOf(
        Locator(textContains = "我要预约"),
    ),

    // ⑪ 同伴信息（你原话里的「通报信息」应该就是这里）────────────────────────
    /** 实测：点完「我要预约」进入「同伴信息( 最少1人最多7人 )」页，底部「取消预约 / 提交订单」 */
    val companionPageReady: List<Locator> = listOf(
        Locator(textContains = "同伴信息", note = "实测：页面标题"),
        Locator(textContains = "提交订单", note = "实测：底部橙色按钮"),
        Locator(textContains = "取消预约"),
    ),
    /**
     * 要勾选的同伴名字。留空 = 点第一个可勾选的同伴。
     * ★ 如果手上的 5 位同伴码属于某个固定的人，把名字填这里（如「宋佳荟」）。
     */
    val companionName: String = "",
    /** 同伴勾选项（H5 里通常暴露为 CheckBox；点它会弹「5位数字同伴码」对话框） */
    val companionCandidates: List<Locator> = listOf(
        Locator(classNameEndsWith = "CheckBox", index = 0, note = "第一个同伴勾选框"),
    ),
    /** 没有任何同伴时的添加入口，同样会弹同伴码对话框 */
    val companionFallback: List<Locator> = listOf(
        Locator(textContains = "点击这里", note = "实测：点击这里 添加同伴"),
        Locator(textContains = "添加同伴"),
    ),
    /** 实测：同伴码弹窗（标题「选择同伴XXX」+ 说明「同伴码需要同伴登录系统在同伴管理里获取」） */
    val companionCodeHint: List<Locator> = listOf(
        Locator(textContains = "同伴码", note = "实测：弹窗说明文字"),
        Locator(textContains = "5位数字同伴码", note = "实测：输入框 hint"),
    ),

    // ⑫⑬⑭ 填码 / 确定 / 提交 ───────────────────────────────────────────────
    /** 实机是 H5，输入框通常是 EditText；先点一下再 ACTION_SET_TEXT */
    val codeInput: List<Locator> = listOf(
        Locator(classNameEndsWith = "EditText", note = "首选"),
        Locator(classNameEndsWith = "AutoCompleteTextView"),
    ),
    /** 实测：弹窗右下角蓝色「确定」（左边是「取消」） */
    val btnConfirm: List<Locator> = listOf(
        Locator(textEquals = "确定", note = "实测"),
        Locator(textEquals = "确认"),
    ),
    /** 实测：底部橙色按钮「提交订单」（左边是「取消预约」，别点错） */
    val btnSubmitOrder: List<Locator> = listOf(
        Locator(textContains = "提交订单", note = "实测"),
    ),
    /** 实测：提交后弹「请完成安全验证」（按顺序点选字符），应用无法自动完成，需要人工接力 */
    val captchaHints: List<Locator> = listOf(
        Locator(textContains = "安全验证", note = "实测：请完成安全验证"),
        Locator(textContains = "请依次点击", note = "实测：验证码操作说明"),
        Locator(textContains = "完成验证"),
    ),
    /** 实测：订单页底部橙色「支付」，金额 ¥0 也必须点，否则 3 分钟内自动取消 */
    val btnPay: List<Locator> = listOf(
        Locator(textEquals = "支付", note = "实测：¥0 订单底部按钮"),
        Locator(textContains = "立即支付"),
    ),
    val payConfirm: List<Locator> = listOf(
        Locator(textEquals = "确认支付"),
        Locator(textContains = "确认支付"),
        Locator(textEquals = "确认"),
    ),

    // ⑮ 结果判定 ─────────────────────────────────────────────────────────────
    val successSigns: List<Locator> = listOf(
        Locator(textContains = "提交成功"),
        Locator(textContains = "预约成功"),
        Locator(textContains = "待支付", note = "实测：提交后一般进入待支付，按你的要求不支付"),
        Locator(textContains = "预约记录"),
        Locator(textContains = "我的预约"),
    ),
    val failureSigns: List<Locator> = listOf(
        Locator(textContains = "预约失败"),
        Locator(textContains = "人数已满"),
        Locator(textContains = "已被预约"),
        Locator(textContains = "请选择时间段"),
        Locator(textContains = "请勾选"),
        Locator(textContains = "重复预约"),
    ),

    // ⏱ 节奏参数 ─────────────────────────────────────────────────────────────
    val stepTimeoutMs: Long = 12_000,
    val navTimeoutMs: Long = 20_000,
    val pollIntervalMs: Long = 80,
    val afterClickDelayMs: Long = 110,
    val afterNavDelayMs: Long = 250,
    /** 截图最小间隔（系统限制约 333ms） */
    val screenshotIntervalMs: Long = 330,
    val inviteCodeWaitMs: Long = 120_000,
    val submitVerifyMs: Long = 8_000,
    /** 提交后自动点「支付」（¥0 也必须点，否则订单 3 分钟内取消） */
    val autoPay: Boolean = true,
    /** 等人工完成验证码的最长时间 */
    val captchaWaitMs: Long = 120_000,
    /** 支付后等「预约成功」页的最长时间 */
    val payWaitMs: Long = 25_000,
    /** 单步看门狗：任何一步超过该时长直接判失败（避免静默卡死） */
    val stepWatchdogMs: Long = 90_000,
) {

    fun summary(): String = buildString {
        appendLine("targetPackage        = $targetPackage")
        appendLine("entryService         = ${entryService.joinToString { it.describe() }}")
        appendLine("entryBooking         = ${entryBooking.joinToString { it.describe() }}")
        appendLine("bookingPageReady     = ${bookingPageReady.joinToString { it.describe() }}")
        appendLine("campusTarget         = $campusTarget")
        appendLine("venueFengYu          = ${venueFengYu.joinToString { it.describe() }}")
        appendLine("venueBadminton       = ${venueBadminton.joinToString { it.describe() }}")
        appendLine("dateDisplay          = ${dateDisplay.describe()}")
        appendLine("放号时间             = ${releaseHour}:%02d:%02d".format(releaseMinute, releaseSecond) + "，等到点=${waitForRelease}，优先后天=${preferFreshDates}")
        appendLine(
            if (dateCfg.useNextReleaseDate)
                "dateCfg              = 待开票的最近一天（9:00 前=今天+${dateCfg.releaseDaysAhead}，9:00 后=明天+${dateCfg.releaseDaysAhead}）"
            else
                "dateCfg              = 优先级=${dateCfg.weekdayPriority.joinToString { DatePlanner.weekdayCn(it) }}，" +
                    "窗口=今天+${dateCfg.maxDaysAhead}天，周五只看下午=${dateCfg.fridayAfternoonOnly}",
        )
        appendLine("slotRowLabel         = ${slotRowLabel.describe()}")
        appendLine("slotColumnHeader     = ${slotColumnHeader.describe()}")
        appendLine("时段格识别           = 格子节点=${useCellNodes}（±${cellMinWidth}x${cellMinHeight}以上），滚动扫描 竖${maxVerticalScrollPasses}/横${maxHorizontalScrollPasses}")
        appendLine("slotGridColors       = 可约=${slotGridCfg.availableColors.first()} 已约=${slotGridCfg.bookedColors.first()} 不可约=${slotGridCfg.closedColors.first()}")
        appendLine("agreeCheckbox        = ${agreeCheckbox.joinToString { it.describe() }}")
        appendLine("btnBookNow           = ${btnBookNow.joinToString { it.describe() }}")
        appendLine("companionName        = ${if (companionName.isBlank()) "(留空=第一个同伴)" else companionName}")
        appendLine("btnSubmitOrder       = ${btnSubmitOrder.joinToString { it.describe() }}")
        appendLine("btnPay               = ${btnPay.joinToString { it.describe() }}")
        appendLine("captchaHints         = ${captchaHints.joinToString { it.describe() }}")
        appendLine("autoPay              = $autoPay（¥0 也必须点，否则 3 分钟取消）")
        appendLine("stepWatchdogMs       = $stepWatchdogMs")
        appendLine("stepTimeoutMs        = $stepTimeoutMs")
    }

    companion object {
        val DEFAULT = GrabConfig()
    }
}
