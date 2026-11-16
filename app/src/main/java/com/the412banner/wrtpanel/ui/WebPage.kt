package com.the412banner.wrtpanel.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.the412banner.wrtpanel.core.PinningTrustManager

/**
 * Shows a LuCI page the app has no native screen for (plugins / luci-apps), signed in with
 * the app's session and without LuCI's own header, so every menu entry stays reachable.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPage(ctx: PageContext, node: MenuNode) {
    val client = ctx.client
    val url = client.luciUrl(*node.path.split('/').toTypedArray())
    var loading by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                client.sid?.let { sid ->
                    val name = if (ctx.session.router.isHttps) "sysauth_https" else "sysauth_http"
                    cm.setCookie(client.baseUrl, "$name=$sid; path=/")
                }
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true }
                        override fun onPageFinished(view: WebView, url: String?) {
                            loading = false
                            view.evaluateJavascript(
                                "(function(){var s=document.createElement('style');s.textContent='header,footer,#tabmenu,.main-left{display:none!important}.main-right{width:100%!important;margin:0!important}body{padding-top:0!important}#maincontent{padding:8px!important}';document.head.appendChild(s);})();",
                                null,
                            )
                        }
                        @SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                            val pin = ctx.session.router.pinnedCertSha256
                            val cert = if (Build.VERSION.SDK_INT >= 29) error.certificate.x509Certificate else null
                            if (pin != null && cert != null && PinningTrustManager.sha256(cert.encoded).equals(pin, true)) handler.proceed()
                            else handler.cancel()
                        }
                    }
                    loadUrl(url)
                }
            },
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}
