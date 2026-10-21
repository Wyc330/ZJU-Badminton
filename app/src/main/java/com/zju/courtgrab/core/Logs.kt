package com.zju.courtgrab.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内存环形日志 + 落盘日志。
 * 界面上实时滚动显示，方便你在真机上对照每一步到底卡在哪。
 */
object Logs {

    private const val MAX_LINES = 600

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    private var logFile: File? = null
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)
    private val dayStamp = SimpleDateFormat("yyyyMMdd", Locale.CHINA)
    private val fullStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)

    fun init(context: Context) {
        runCatching {
            // 优先外部私有目录（/sdcard/Android/data/<pkg>/files/logs），方便 adb pull 排查
            val ext = context.getExternalFilesDir("logs")
            val dir = (ext ?: File(context.filesDir, "logs")).apply { mkdirs() }
            logFile = File(dir, "run-${dayStamp.format(Date())}.log")
        }
    }

    fun i(tag: String, message: String) = append("I", tag, message)

    fun w(tag: String, message: String) = append("W", tag, message)

    fun e(tag: String, message: String, tr: Throwable? = null) {
        append("E", tag, message)
        tr?.let { append("E", tag, "  ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun append(level: String, tag: String, message: String) {
        val line = "${stamp.format(Date())} $level/$tag $message"
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
        runCatching {
            val f = logFile ?: return
            f.appendText(line + "\n")
        }
        when (level) {
            "E" -> android.util.Log.e("CourtGrab.$tag", message)
            "W" -> android.util.Log.w("CourtGrab.$tag", message)
            else -> android.util.Log.i("CourtGrab.$tag", message)
        }
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun asText(): String = _lines.value.joinToString("\n")

    fun filePath(): String? = logFile?.absolutePath

    fun fullTime(): String = fullStamp.format(Date())
}
