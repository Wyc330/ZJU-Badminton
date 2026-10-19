package com.zju.courtgrab.direct

import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.core.Prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 登录态失效（需要重新在 App 内登录场馆系统） */
class VenueUnauthorized(message: String) : Exception(message)

/** 接口返回了非 200 的业务码 */
class VenueApiError(val code: Int, message: String, val payload: String) : Exception(message)

/**
 * 场馆系统 HTTP 客户端：完全模仿 H5 的请求头与签名，走明文 HTTP。
 * 所有接口都是「查询/提交预约」，不涉及任何第三方数据。
 */
class VenueClient(private val prefs: Prefs) {

    companion object {
        const val BASE = "http://tyys.zju.edu.cn/venue-server"
        const val ORIGIN = "http://www.tyys.zju.edu.cn"
        const val REFERER = "$ORIGIN/venue/login"
        const val TAG = "VenueApi"

        private const val FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 17; CP2A.260605.016 Build/CP2A.260605.016; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/140.0.0.0 Mobile Safari/537.36"

        private fun enc(s: String): String =
            URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }

    fun isLoggedIn(): Boolean = prefs.venueToken.isNotBlank()

    /** 统一请求入口；返回已拆掉 {code,data} 外壳的业务数据 */
    fun call(method: String, path: String, data: Map<String, Any?> = emptyMap()): JSONObject {
        val token = prefs.venueToken
        if (token.isBlank()) throw VenueUnauthorized("还没有登录场馆系统")
        val ts = System.currentTimeMillis()
        val headers = HashMap<String, String>()
        val body: String
        val query: String
        if (method == "GET") {
            val params = HashMap<String, Any?>(data)
            params["nocache"] = ts
            headers["sign"] = Sign.sign(path, params, ts)
            query = params.entries.joinToString("&") { (k, v) ->
                "${enc(k)}=${enc(v?.toString() ?: "")}"
            }
            body = ""
        } else {
            val json = JSONObject()
            data.forEach { (k, v) -> if (v != null) json.put(k, v) }
            body = json.toString()
            headers["sign"] = Sign.sign(path, data, ts)
            query = ""
        }
        headers["app-key"] = Sign.APP_KEY
        headers["timestamp"] = ts.toString()
        headers["cgAuthorization"] = token
        headers["cgappauthorization"] = token
        headers["Content-Type"] = "application/json;charset=UTF-8"
        headers["User-Agent"] = prefs.webviewUa.ifBlank { FALLBACK_UA }
        headers["Accept"] = "application/json, text/plain, */*"
        headers["Accept-Encoding"] = "identity"
        headers["Origin"] = ORIGIN
        headers["Referer"] = REFERER
        headers["Connection"] = "keep-alive"

        val url = URL(BASE + path + if (query.isNotEmpty()) "?$query" else "")
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8000
                readTimeout = 12000
                instanceFollowRedirects = false
                for ((k, v) in headers) setRequestProperty(k, v)
            }
            if (body.isNotEmpty()) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (code == 401 || json?.optInt("code", 0) == 401) {
                throw VenueUnauthorized("登录态失效（HTTP $code）：$text")
            }
            if (json == null) throw VenueApiError(code, "返回不是 JSON（HTTP $code）", text.take(300))
            val bizCode = json.optInt("code", 200)
            if (bizCode != 200) {
                throw VenueApiError(bizCode, json.optString("message", "接口错误 $bizCode"), text.take(500))
            }
            return json.optJSONObject("data") ?: json
        } finally {
            conn?.disconnect()
        }
    }

    // ───────────────────────── 业务接口 ─────────────────────────

    /** 校区/场馆/场地信息（含场馆 id，用于选风雨操场） */
    fun campusVenueInfo(): JSONObject = call("GET", "/api/reservation/campus/venue/info")

    /** 某天的时段表 + 场地列表 + 下单 token */
    fun dayInfo(venueSiteId: Long, date: String): JSONObject = call(
        "GET", "/api/reservation/day/info",
        mapOf(
            "venueSiteId" to venueSiteId,
            "searchDate" to date,
            "reservationType" to null,
            "schoolType" to null,
        ),
    )

    fun buddies(): JSONObject = call("GET", "/api/buddies")

    fun checkBuddyNo(buddyUserId: Long, buddyRoleId: Long?, buddyNo: String): JSONObject = call(
        "POST", "/api/vip/check/buddy_no",
        mapOf("buddyUserId" to buddyUserId, "buddyRoleId" to buddyRoleId, "buddyNo" to buddyNo),
    )

    /** 提交预约（结构对齐 H5 的 /api/reservation/order/submit） */
    fun submit(payload: Map<String, Any?>): JSONObject = call("POST", "/api/reservation/order/submit", payload)

    /** 模拟 H5 的模块操作埋点，让行为轨迹与真实客户端一致（可选） */
    fun moduleAction(moduleName: String, action: String): Boolean = runCatching {
        call("POST", "/api/logs/module_action", mapOf("moduleName" to moduleName, "action" to action))
        true
    }.getOrElse {
        Logs.w(TAG, "埋点上报失败（忽略）：${it.message}")
        false
    }
}
