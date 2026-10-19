package com.zju.courtgrab.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * 敏感信息（邀请码）走 EncryptedSharedPreferences；
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

    /** 本次要用的邀请码（用户提前填的，或上次运行中确认过的） */
    var inviteCode: String
        get() = sp.getString("invite_code", "").orEmpty()
        set(v) = sp.edit().putString("invite_code", v).apply()

    /** 是否记住邀请码（默认不记，因为用户说每次不同） */
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
    /** 上一次成功使用过的邀请码，仅用于界面回显 */
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
