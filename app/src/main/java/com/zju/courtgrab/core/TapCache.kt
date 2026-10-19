package com.zju.courtgrab.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「学习式点击缓存」。
 *
 * 同一台手机、同一个 App 版本下，「同意勾选 / 我要预约 / 同伴勾选 / 确定 / 提交订单 /
 * 日期箭头 / 刷新」这些控件的位置是固定的。第一次跑到时把坐标记下来，
 * 之后每次抢场地直接按坐标点击，**完全跳过遍历无障碍节点树**（每次 50~150ms），
 * 这是放号瞬间最值钱的一段时间。
 */
object TapCache {

    fun load(prefs: Prefs): MutableMap<String, Pair<Int, Int>> {
        val raw = prefs.tapCacheJson
        if (raw.isBlank()) return mutableMapOf()
        return runCatching {
            val obj = JSONObject(raw)
            val out = mutableMapOf<String, Pair<Int, Int>>()
            for (key in obj.keys()) {
                val arr = obj.optJSONArray(key) ?: continue
                if (arr.length() == 2) out[key] = arr.getInt(0) to arr.getInt(1)
            }
            out
        }.getOrElse { mutableMapOf() }
    }

    fun save(prefs: Prefs, map: Map<String, Pair<Int, Int>>) {
        runCatching {
            val obj = JSONObject()
            map.forEach { (k, v) ->
                obj.put(k, JSONArray().put(v.first).put(v.second))
            }
            prefs.tapCacheJson = obj.toString()
        }.onFailure { Logs.w("TapCache", "保存失败：${it.message}") }
    }
}
