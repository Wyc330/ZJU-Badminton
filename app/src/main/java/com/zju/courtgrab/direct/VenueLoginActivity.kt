package com.zju.courtgrab.direct

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zju.courtgrab.alarm.JwtUtil
import com.zju.courtgrab.core.Logs
import com.zju.courtgrab.core.Prefs
import com.zju.courtgrab.databinding.ActivityVenueLoginBinding

/**
 * 场馆系统登录页（内嵌 WebView）。
 *
 * 流程：用户自己输入学号密码 → H5 登录成功把 access_token 写进 localStorage.dataSix
 *      → 本页在同源 WebView 内读出来 → 加密保存 → 供直连下单使用。
 *
 * 说明：不记录、不上传任何凭据；token 只存本机 EncryptedSharedPreferences。
 */
class VenueLoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityVenueLoginBinding
    private lateinit var prefs: Prefs
    private var checkRounds = 0
    private var silent = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityVenueLoginBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs.get(this)
        silent = intent.getBooleanExtra(EXTRA_SILENT, false)

        b.btnReadToken.setOnClickListener { readToken(showToast = true) }
        b.btnBack.setOnClickListener { finish() }

        with(b.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = userAgentString // 保持系统 WebView 默认 UA（与真实 H5 一致）
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(b.webView, true)

        b.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                b.tvStatus.text = "正在加载：${url ?: ""}"
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                val desc = error?.description?.toString() ?: "?"
                Logs.w(TAG, "WebView 加载失败：${request?.url} code=${error?.errorCode} $desc")
                b.tvStatus.text = "加载失败（${error?.errorCode}）：$desc"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                b.tvStatus.text = "页面已加载，登录后会自动读取登录态…"
                prefs.webviewUa = view?.settings?.userAgentString.orEmpty()
                readToken(showToast = false)
            }
        }

        b.webView.loadUrl("http://www.tyys.zju.edu.cn/venue/login")
        Logs.i(TAG, if (silent) "打开场馆系统登录页（静默续期）" else "打开场馆系统登录页")
        if (silent) {
            b.tvStatus.text = "正在尝试自动续期…（CAS 会话还在就能续上）"
            b.webView.postDelayed({ clickSsoLogin() }, 2500)
            b.webView.postDelayed({ finishSilentFail() }, 35_000)
        }
    }

    /** 静默续期：自动点一下「统一身份认证登录」，不输入任何凭据 */
    private fun clickSsoLogin() {
        val js = "(function(){try{var ns=Array.from(document.querySelectorAll('button,div,span,a'));var t=ns.find(function(e){return e.textContent&&e.textContent.trim()==='统一身份认证登录';})||ns.find(function(e){return e.textContent&&e.textContent.indexOf('统一身份认证')>=0&&e.getBoundingClientRect().height>20;});if(t){t.click();return 'clicked';}return 'not-found';}catch(e){return 'err:'+e;}})()"
        b.webView.evaluateJavascript(js) { result ->
            Logs.i(TAG, "静默续期点击 SSO 结果：$result")
        }
    }

    /** 静默续期没拿到新 token：通知用户手动登录，然后退出 */
    private fun finishSilentFail() {
        if (!silent || isFinishing) return
        val exp = JwtUtil.expiryMs(prefs.venueToken)
        if (exp != null && exp > System.currentTimeMillis()) {
            Logs.i(TAG, "静默续期完成（登录态仍有效）")
            finish()
            return
        }
        Logs.w(TAG, "静默续期失败：登录态仍不可用，需要用户手动登录")
        com.zju.courtgrab.util.Notify.showResult(
            this, false,
            "直连登录态即将过期，自动续期没成功：请打开「抢场地」→「登录场馆系统」手动登录一次",
        )
        finish()
    }

    /** 读取 localStorage.dataSix（H5 的 access_token）并保存 */
    private fun readToken(showToast: Boolean) {
        val web = b.webView
        web.evaluateJavascript("(function(){try{return localStorage.getItem('dataSix')||''}catch(e){return ''}})()") { raw ->
            val token = raw?.trim()?.trim('"')?.replace("\\u003d", "=") ?: ""
            if (token.length >= 16 && token != "null") {
                prefs.venueToken = token
                prefs.venueTokenAt = System.currentTimeMillis()
                prefs.webviewUa = web.settings.userAgentString.orEmpty()
                val masked = token.take(6) + "…" + token.takeLast(4)
                b.tvStatus.text = "✅ 已获取登录态（$masked），可以返回了"
                Logs.i(TAG, if (silent) "静默续期成功：已保存新登录态 $masked" else "已保存场馆登录态 $masked")
                if (!silent) {
                    Toast.makeText(this, "登录态已保存，可以开始直连预约", Toast.LENGTH_SHORT).show()
                }
                finish()
            } else {
                checkRounds++
                if (checkRounds % 5 == 0) {
                    b.tvStatus.text = "还没有拿到登录态：请在页面上完成登录（第 $checkRounds 次检查）"
                }
                if (showToast) Toast.makeText(this, "还没检测到登录态，请先在页面上登录", Toast.LENGTH_SHORT).show()
                // 登录页内部跳转多次，持续轮询 40 次 × 3s = 2 分钟
                if (checkRounds < 40) web.postDelayed({ readToken(showToast = false) }, 3000)
            }
        }
    }

    override fun onDestroy() {
        b.webView.stopLoading()
        b.webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VenueLogin"

        /** true = 静默续期模式（自动点一次统一身份认证登录，不弹键盘不打扰用户） */
        const val EXTRA_SILENT = "silent"
    }
}
