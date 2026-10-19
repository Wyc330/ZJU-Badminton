package com.zju.courtgrab.a11y

import android.content.Context
import com.zju.courtgrab.core.GrabConfig
import com.zju.courtgrab.core.Logs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把当前界面的节点树落盘成 JSON + 人眼可读的 TXT。
 * 这是「不知道对方界面长什么样」时最有效的工具：
 * 你在手机上点一下「抓取当前界面节点树」，把文件发给我/自己看，就能把
 * viewId / text / 位置 抄进配置。
 */
object Dump {

    private const val TAG = "Dump"
    private val stamp = SimpleDateFormat("MMdd-HHmmss", Locale.CHINA)

    data class Result(val json: File, val text: File, val nodeCount: Int)

    fun save(context: Context, ops: A11yOps, cfg: GrabConfig, tag: String): Result? {
        return runCatching {
            val nodes = ops.collect(includeOwn = false)
            // 放到「应用外部目录」，这样电脑上 adb pull 能直接拿到（不用 root、不用 run-as）
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "dumps").apply { mkdirs() }
            // 保留中文步骤名，方便一眼看出是哪一步失败（文件名只过滤非法字符）
            val safeTag = tag.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").take(40)
            val base = "dump-${stamp.format(Date())}-$safeTag"

            val arr = JSONArray()
            val txt = StringBuilder()
            txt.appendLine("# 抓取时间: ${Logs.fullTime()}")
            txt.appendLine("# 前台包名: ${ops.foregroundPackage() ?: "?"}")
            txt.appendLine("# 窗口包名: ${ops.currentWindowTitles().joinToString(",")}")
            txt.appendLine("# 目标包名(配置): ${cfg.targetPackage}")
            txt.appendLine("# 节点数: ${nodes.size}")
            txt.appendLine("# 界面文字: ${ops.visibleTextDigest(80)}")
            txt.appendLine("# 可点节点: ${ops.clickableDigest(30)}")
            txt.appendLine()
            txt.appendLine("# depth | class | viewId | text | desc | bounds | flags")

            for (h in nodes) {
                val s = h.snap
                arr.put(
                    JSONObject().apply {
                        put("depth", s.depth)
                        put("class", s.className)
                        put("viewId", s.viewId ?: "")
                        put("text", s.text ?: "")
                        put("desc", s.desc ?: "")
                        put("bounds", s.bounds.toString())
                        put("visible", s.visible)
                        put("clickable", s.clickable)
                        put("enabled", s.enabled)
                        put("scrollable", s.scrollable)
                        put("editable", s.editable)
                    }
                )
                txt.append("  ".repeat(minOf(s.depth, 20)))
                txt.append(s.className.substringAfterLast('.'))
                if (!s.viewId.isNullOrEmpty()) txt.append("  id=").append(s.viewId)
                if (!s.text.isNullOrEmpty()) txt.append("  text=\"").append(s.text.replace('\n', ' ')).append('"')
                if (!s.desc.isNullOrEmpty()) txt.append("  desc=\"").append(s.desc.replace('\n', ' ')).append('"')
                txt.append("  ").append(s.bounds)
                if (s.clickable) txt.append(" CLICK")
                if (s.editable) txt.append(" EDIT")
                if (!s.enabled) txt.append(" DISABLED")
                if (!s.visible) txt.append(" INVISIBLE")
                txt.appendLine()
            }

            val root = JSONObject().apply {
                put("time", Logs.fullTime())
                put("tag", tag)
                put("foregroundPackage", ops.foregroundPackage() ?: "")
                put("textDigest", ops.visibleTextDigest(80))
                put("clickableDigest", ops.clickableDigest(30))
                put("nodeCount", nodes.size)
                put("nodes", arr)
            }

            val jsonFile = File(dir, "$base.json")
            val txtFile = File(dir, "$base.txt")
            jsonFile.writeText(root.toString(2))
            txtFile.writeText(txt.toString())
            Logs.i(TAG, "已抓取界面节点树（${nodes.size} 个节点）：${jsonFile.absolutePath}")
            Result(jsonFile, txtFile, nodes.size)
        }.getOrElse {
            Logs.e(TAG, "抓取界面失败", it)
            null
        }
    }
}
