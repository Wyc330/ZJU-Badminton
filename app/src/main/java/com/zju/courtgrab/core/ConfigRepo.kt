package com.zju.courtgrab.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 配置文件仓库。
 *
 * 查找顺序（后面的覆盖前面的）：
 *   1. GrabConfig.DEFAULT（代码里的默认值）
 *   2. 应用外部目录  /sdcard/Android/data/com.zju.courtgrab/files/config/grab-config.json
 *   3. 应用内部目录  /data/data/com.zju.courtgrab/files/config/grab-config.json
 *
 * 第一次启动会把默认配置写到外部目录，方便你用文件管理器 / adb pull 出来改。
 */
object ConfigRepo {

    private const val TAG = "Config"

    @Volatile
    var current: GrabConfig = GrabConfig.DEFAULT
        private set

    fun externalConfigFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "config")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "grab-config.json")
    }

    fun internalConfigFile(context: Context): File {
        val dir = File(context.filesDir, "config")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "grab-config.json")
    }

    /** 读取配置；没有文件则写出一份默认配置 */
    fun load(context: Context): GrabConfig {
        var cfg = GrabConfig.DEFAULT
        val candidates = listOf(internalConfigFile(context), externalConfigFile(context))
        var loadedFrom: File? = null
        var legacyFile: File? = null
        for (f in candidates) {
            if (!f.exists()) continue
            runCatching {
                val text = f.readText().removePrefix("\uFEFF").trim()
                if (text.isBlank()) return@runCatching
                val json = JSONObject(text)
                val schema = json.optInt("_schemaVersion", 1)
                if (schema < ConfigJson.SCHEMA_VERSION) {
                    legacyFile = f
                    Logs.w(TAG, "配置文件是旧版本（schema=$schema < ${ConfigJson.SCHEMA_VERSION}）：${f.absolutePath}")
                }
                cfg = ConfigJson.merge(cfg, json)
                loadedFrom = f
            }.onFailure {
                Logs.e(TAG, "配置文件解析失败：${f.absolutePath} - ${it.message}")
            }
        }
        if (loadedFrom == null) {
            Logs.i(TAG, "未找到配置文件，使用默认配置并导出一份到外部目录")
            runCatching { writeExternal(context, ConfigJson.toJson(cfg)) }
        } else {
            Logs.i(TAG, "已载入配置：${loadedFrom!!.absolutePath}（schema=${ConfigJson.SCHEMA_VERSION}）")
        }
        // 旧配置自动升级：写回新 schema，否则旧值会一直顶掉新默认值
        if (legacyFile != null) {
            runCatching {
                writeExternal(context, ConfigJson.toJson(cfg))
                Logs.i(TAG, "旧配置已升级到 schema=${ConfigJson.SCHEMA_VERSION} 并写回外部目录")
            }.onFailure { Logs.e(TAG, "旧配置升级写回失败", it) }
        }
        current = cfg
        return cfg
    }

    fun reload(context: Context): GrabConfig = load(context)

    /** 把当前生效配置写到外部目录，返回文件 */
    fun writeExternal(context: Context, json: JSONObject = ConfigJson.toJson(current)): File {
        val f = externalConfigFile(context)
        f.writeText(pretty(json))
        return f
    }

    /** 把当前生效配置写到内部目录（导入用） */
    fun writeInternal(context: Context, json: JSONObject = ConfigJson.toJson(current)): File {
        val f = internalConfigFile(context)
        f.writeText(pretty(json))
        return f
    }

    private fun pretty(json: JSONObject): String = runCatching { json.toString(2) }.getOrDefault(json.toString())

    /** 强制重新读取（外部文件优先） */
    fun forceReloadFromExternal(context: Context): GrabConfig {
        val ext = externalConfigFile(context)
        if (ext.exists()) {
            runCatching {
                val json = JSONObject(ext.readText().removePrefix("\uFEFF").trim())
                current = ConfigJson.merge(GrabConfig.DEFAULT, json)
                Logs.i(TAG, "已从外部配置文件重载：${ext.absolutePath}")
            }.onFailure {
                Logs.e(TAG, "重载失败：${it.message}")
            }
        }
        return current
    }
}
