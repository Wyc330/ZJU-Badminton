package com.zju.courtgrab.util

import android.content.Context
import android.os.Build
import com.zju.courtgrab.alarm.JwtUtil
import com.zju.courtgrab.core.ConfigJson
import com.zju.courtgrab.core.ConfigRepo
import com.zju.courtgrab.core.DatePlanner
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.core.Prefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.LocalDateTime

/**
 * 「分享诊断信息」：把排查所需的一切打成一个文本文件，方便出问题时一次性发出来。
 *
 * 包含：版本/设备、开关状态、登录态（脱敏+到期时间）、闹钟状态、上次结果、
 * 目标日期、配置 JSON、最近日志尾部。**不包含**学号密码、完整 token、同伴码明文。
 */
object Diag {

    fun build(context: Context, prefs: Prefs): File {
        val cfg = ConfigRepo.load(context)
        val sb = StringBuilder()
        val now = LocalDateTime.now()
        fun line(k: String, v: Any?) = sb.append("$k: ${v ?: "-"}\n")

        sb.appendLine("===== 抢场地 诊断信息 =====")
        line("时间", Logs.fullTime())
        line("版本", versionName(context))
        line("设备", "${Build.BRAND} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")

        sb.appendLine()
        sb.appendLine("--- 开关 ---")
        line("每日自动", prefs.enabled)
        line("演练模式", prefs.dryRun)
        line("直连模式", prefs.directMode)
        line("触发时间", String.format(Locale.CHINA, "%02d:%02d:%02d", prefs.alarmHour, prefs.alarmMinute, prefs.alarmSecond))
        line("上次静默续期", if (prefs.lastSilentRefreshAt > 0) fmt(prefs.lastSilentRefreshAt) else "从未")
        line("目标日期", DatePlanner.resolveTargetDate(now, cfg.dateCfg, cfg.releaseHour, cfg.releaseMinute)?.toString() ?: "?")

        sb.appendLine()
        sb.appendLine("--- 登录态（脱敏） ---")
        val token = prefs.venueToken
        if (token.isBlank()) {
            line("token", "未登录")
        } else {
            line("token", "${token.take(6)}…${token.takeLast(4)}（长度 ${token.length}）")
            line("登录时间", if (prefs.venueTokenAt > 0) fmt(prefs.venueTokenAt) else "?")
            val exp = JwtUtil.expiryMs(token)
            line("到期时间", exp?.let { "${fmt(it)}（剩余 ${(it - System.currentTimeMillis()) / 60000} 分钟）" } ?: "非 JWT")
        }
        line("同伴姓名", prefs.companionName.ifBlank { "(空=第一个同伴)" })
        line("同伴码", if (prefs.inviteCode.length == 5) "已填（${prefs.inviteCode.length} 位）" else "未填")
        line("同伴码上次失败", if (prefs.buddyCodeInvalidAt > 0) fmt(prefs.buddyCodeInvalidAt) else "无")

        sb.appendLine()
        sb.appendLine("--- 上次结果 ---")
        line("成功", prefs.lastRunSuccess)
        line("时间", if (prefs.lastRunAt > 0) fmt(prefs.lastRunAt) else "无")
        line("消息", prefs.lastRunMessage.ifBlank { "无" })
        line("直连详情", prefs.lastDirectDetail.ifBlank { "无" })

        sb.appendLine()
        sb.appendLine("--- 配置 ---")
        runCatching { sb.appendLine(ConfigJson.toJson(cfg).toString(2)) }
            .onFailure { sb.appendLine("配置序列化失败：${it.message}") }

        sb.appendLine()
        sb.appendLine("--- 最近日志（尾部 120 行） ---")
        val logPath = Logs.filePath()
        if (logPath != null) {
            runCatching {
                val tail = File(logPath).readLines().takeLast(120)
                sb.appendLine("($logPath)")
                tail.forEach { sb.appendLine(it) }
            }.onFailure { sb.appendLine("读取日志失败：${it.message}") }
        } else {
            sb.appendLine("无日志文件")
        }

        val dir = File(context.filesDir, "diag").apply { mkdirs() }
        val f = File(dir, "diag-${SimpleDateFormat("MMdd-HHmm", Locale.CHINA).format(Date())}.txt")
        f.writeText(sb.toString(), Charsets.UTF_8)
        Logs.i("Diag", "诊断包已生成：${f.absolutePath}")
        return f
    }

    private fun fmt(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))

    @Suppress("DEPRECATION")
    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
