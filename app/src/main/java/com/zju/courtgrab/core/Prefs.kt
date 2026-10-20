package com.zju.courtgrab.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * 敏感信息（同伴码）走 EncryptedSharedPreferences；
 * 万一某些 ROM 上 Keystore 异常，自动降级到普通 SharedPreferences，保证 App 不会因此崩溃。
 */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences = runCatching {
        val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            FILE_ENCRYPTED,
            alias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        Logs.w(TAG, "EncryptedSharedPreferences 不可用，降级为普通存储：${it.message}")
        context.getSharedPreferences(FILE_PLAIN, Context.MODE_PRIVATE)
    }

    var alarmHour: Int
        get() = sp.getInt("alarm_hour", 8)
        set(v) = sp.edit().putInt("alarm_hour", v).apply()

    var alarmMinute: Int
        get() = sp.getInt("alarm_minute", 59)
        set(v) = sp.edit().putInt("alarm_minute", v).apply()

    var alarmSecond: Int
        get() = sp.getInt("alarm_second", 45)
        set(v) = sp.edit().putInt("alarm_second", v).apply()

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** 本次要用的同伴码（用户提前填的，或上次运行中确认过的） */
    var inviteCode: String
        get() = sp.getString("invite_code", "").orEmpty()
        set(v) = sp.edit().putString("invite_code", v).apply()

    /** 是否记住同伴码（默认不记，因为用户说每次不同） */
    var rememberCode: Boolean
        get() = sp.getBoolean("remember_code", false)
        set(v) = sp.edit().putBoolean("remember_code", v).apply()

    /** 想预约的时间段（界面下拉框选择），如 "18:30"；空 = 取中间 */
    var preferredSlotStart: String
        get() = sp.getString("preferred_slot_start", "").orEmpty()
        set(v) = sp.edit().putString("preferred_slot_start", v).apply()

    /** 学习到的点击坐标缓存（JSON），见 TapCache */
    var tapCacheJson: String
        get() = sp.getString("tap_cache_json", "").orEmpty()
        set(v) = sp.edit().putString("tap_cache_json", v).apply()

    /** 想带的同伴姓名（留空 = 第一个同伴）；界面里可改，优先于配置文件 */
    var companionName: String
        get() = sp.getString("companion_name", "").orEmpty()
        set(v) = sp.edit().putString("companion_name", v).apply()

    /** 上次流程结果（持久化，重启 App 也能看到） */
    var lastRunSuccess: Boolean
        get() = sp.getBoolean("last_run_success", false)
        set(v) = sp.edit().putBoolean("last_run_success", v).apply()

    var lastRunMessage: String
        get() = sp.getString("last_run_message", "").orEmpty()
        set(v) = sp.edit().putString("last_run_message", v).apply()

    var lastRunAt: Long
        get() = sp.getLong("last_run_at", 0L)
        set(v) = sp.edit().putLong("last_run_at", v).apply()

    /** 直连成功时的详情（日期/时段/场地），用于结果展示与通知 */
    var lastDirectDetail: String
        get() = sp.getString("last_direct_detail", "").orEmpty()
        set(v) = sp.edit().putString("last_direct_detail", v).apply()

    /** 最近一次同伴码校验失败的时间（>0 表示需要重新要码） */
    var buddyCodeInvalidAt: Long
        get() = sp.getLong("buddy_code_invalid_at", 0L)
        set(v) = sp.edit().putLong("buddy_code_invalid_at", v).apply()

    /** 抢到后是否自动关闭每日自动（避免第二天重复预约） */
    var autoStopAfterSuccess: Boolean
        get() = sp.getBoolean("auto_stop_after_success", false)
        set(v) = sp.edit().putBoolean("auto_stop_after_success", v).apply()

    /** 上次静默续期尝试时间（避免反复拉起） */
    var lastSilentRefreshAt: Long
        get() = sp.getLong("last_silent_refresh_at", 0L)
        set(v) = sp.edit().putLong("last_silent_refresh_at", v).apply()

    /** 直连下单模式（在 App 内登录场馆系统后，09:00:00 直接调接口，失败自动回退界面流程） */
    var directMode: Boolean
        get() = sp.getBoolean("direct_mode", false)
        set(v) = sp.edit().putBoolean("direct_mode", v).apply()

    /** 场馆系统登录态（H5 的 access_token），由内置登录页从同源 localStorage 读取 */
    var venueToken: String
        get() = sp.getString("venue_token", "").orEmpty()
        set(v) = sp.edit().putString("venue_token", v).apply()

    var venueTokenAt: Long
        get() = sp.getLong("venue_token_at", 0L)
        set(v) = sp.edit().putLong("venue_token_at", v).apply()

    /** 系统 WebView 的 UA（直连请求要完全一致，避免出现异常客户端指纹） */
    var webviewUa: String
        get() = sp.getString("webview_ua", "").orEmpty()
        set(v) = sp.edit().putString("webview_ua", v).apply()

    /** 演练模式：走到「提交订单」前就停，不真正下单（调试期强烈建议开着） */
    var dryRun: Boolean
        get() = sp.getBoolean("dry_run", true)
        set(v) = sp.edit().putBoolean("dry_run", v).apply()
    /** 上一次成功使用过的同伴码，仅用于界面回显 */
    var lastCode: String
        get() = sp.getString("last_code", "").orEmpty()
        set(v) = sp.edit().putString("last_code", v).apply()

    var lastDumpPath: String
        get() = sp.getString("last_dump", "").orEmpty()
        set(v) = sp.edit().putString("last_dump", v).apply()

    var lastResult: String
        get() = sp.getString("last_result", "").orEmpty()
        set(v) = sp.edit().putString("last_result", v).apply()

    fun clearCode() {
        sp.edit().remove("invite_code").apply()
    }

    companion object {
        private const val TAG = "Prefs"
        private const val FILE_ENCRYPTED = "courtgrab_secure"
        private const val FILE_PLAIN = "courtgrab_fallback"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }
    }
}
