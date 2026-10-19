package com.zju.courtgrab.direct

import java.security.MessageDigest

/**
 * 复刻场馆 H5 的请求签名。
 *
 * 算法来自场馆系统前端公开下发的 JS（http://www.tyys.zju.edu.cn/venue/js/chingo.*.js）：
 *   sign   = MD5(secret + path + 按 key 排序拼接的 "key+value" + timestamp + " " + secret)
 *   appkey = base64(reverse(常量串))
 *
 * 目的：让 App 发出的请求与真实 H5 客户端**完全一致**（这是"拟真"而不是"绕过"）。
 * 注意：这里没有存储任何个人凭据；token 由用户本人登录后从 WebView 取得。
 */
object Sign {

    /** 服务端 JS 里携带的签名常量（拼接后 = 32 位十六进制字符串） */
    private const val SECRET = "c640ca392cd45fb3a55b00a63a86c618"

    /** app-key：由 JS 常量串反转 + base64 解码得到 */
    const val APP_KEY = "8fceb735082b5a529312040b58ea780b"

    fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /**
     * @param path 以 "/" 开头的接口路径
     * @param data 参与签名的参数（GET 时必须已包含 nocache=timestamp）
     * @param timestamp 毫秒时间戳
     */
    fun sign(path: String, data: Map<String, Any?>, timestamp: Long): String {
        val sb = StringBuilder(SECRET).append(path)
        for (key in data.keys.sorted()) {
            val v = data[key] ?: continue
            if (v is Map<*, *> || v is Collection<*>) continue
            val s = v.toString()
            if (s.isEmpty()) continue
            sb.append(key).append(s)
        }
        sb.append(timestamp).append(' ').append(SECRET)
        return md5(sb.toString())
    }
}
