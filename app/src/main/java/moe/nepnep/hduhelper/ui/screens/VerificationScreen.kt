package moe.nepnep.hduhelper.ui.screens

import android.annotation.SuppressLint
import android.os.Build
import androidx.core.view.doOnLayout
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.nepnep.hduhelper.data.auth.AuthEndpoints
import moe.nepnep.hduhelper.data.auth.StoredCookie
import moe.nepnep.hduhelper.data.auth.VerificationSession
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.coroutines.resume

object VerificationCookies {
    private val endpoints = AuthEndpoints()

    suspend fun seed(session: VerificationSession) {
        val manager = CookieManager.getInstance()
        suspendCancellableCoroutine { continuation ->
            manager.removeAllCookies { if (continuation.isActive) continuation.resume(Unit) }
        }
        manager.setAcceptCookie(true)
        session.cookies.forEach { cookie ->
            val origin = if (cookie.domain.trimStart('.') == endpoints.sso.host) endpoints.sso else endpoints.portal
            val url = origin.newBuilder().encodedPath(cookie.path).build()
            if (cookie.domain.trimStart('.') !in listOf(endpoints.sso.host, endpoints.portal.host)) return@forEach
            suspendCancellableCoroutine { continuation ->
                manager.setCookie(url.toString(), cookie.toCookie().toString()) { if (continuation.isActive) continuation.resume(Unit) }
            }
        }
    }

    fun read(session: VerificationSession): List<StoredCookie> {
        val manager = CookieManager.getInstance()
        val urls = buildSet {
            add(endpoints.sso); add(endpoints.portal)
            add(endpoints.portal.resolve("sopcb/")!!); add(endpoints.portal.resolve("sopplus/")!!)
            session.cookies.forEach { cookie ->
                val host = cookie.domain.trimStart('.')
                val origin = listOf(endpoints.sso, endpoints.portal).firstOrNull { it.host == host } ?: return@forEach
                add(origin.newBuilder().encodedPath(cookie.path).build())
            }
        }
        val imported = mutableListOf<StoredCookie>()
        urls.forEach { url ->
            manager.getCookie(url.toString()).orEmpty().split(';').forEach cookieLoop@{ pair ->
                val parsed = Cookie.parse(url, pair.trim()) ?: return@cookieLoop
                if (imported.any { it.name == parsed.name && it.value == parsed.value && it.toCookie().matches(url) }) return@cookieLoop
                val previous = session.cookies.firstOrNull { it.name == parsed.name && it.toCookie().matches(url) }
                // CookieManager exposes header values, not attributes. Preserve known scope; newly observed cookies
                // are conservatively host-only session cookies, then reconciled by the authenticated HTTP probe.
                imported += previous?.copy(value = parsed.value) ?: StoredCookie.from(parsed)
            }
        }
        return imported
    }

    fun clear() {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
    }
}

@SuppressLint("SetJavaScriptEnabled") // Required by the official SSO page; no JS bridge or password injection.
@Composable
fun VerificationScreen(
    session: VerificationSession?,
    error: String?,
    onCompleted: (VerificationSession, List<StoredCookie>) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (session == null) {
        Column(modifier.padding(20.dp)) { Text("验证已结束，请返回后重新打开") }
        return
    }
    val context = LocalContext.current
    val completed by rememberUpdatedState(onCompleted)
    val reportError by rememberUpdatedState(onError)
    var loading by remember(session.generation) { mutableStateOf(true) }
    val endpoints = remember { AuthEndpoints() }
    val webView = remember(session.generation) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            val defaultUa = WebSettings.getDefaultUserAgent(context)
            val chrome = Regex("Chrome/[0-9.]+").find(defaultUa)?.value.orEmpty()
            settings.userAgentString = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; Mobile) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 $chrome Mobile Safari/537.36"
            // SSO selects PC/PHONE using CSS viewport orientation, not just the UA.
            // Do not create or auto-fit a 980px desktop layout viewport.
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            settings.textZoom = 100
            setInitialScale(0)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Official challenges may use HTTPS subframes on the captcha provider's domain.
                    if (!request.isForMainFrame) return request.url.scheme != "https"
                    val url = request.url.toString().toHttpUrlOrNull()
                    val allowed = url != null && endpoints.accepts(url)
                    if (!allowed && request.isForMainFrame) reportError("请在学校官方验证页面内完成操作")
                    return !allowed
                }
                override fun onPageFinished(view: WebView, url: String) {
                    loading = false
                    val destination = url.toHttpUrlOrNull() ?: return
                    if (destination.host == endpoints.portal.host && endpoints.accepts(destination)) {
                        completed(session, VerificationCookies.read(session))
                    }
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    reportError("学校网站证书验证失败，已停止连接")
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        loading = false
                        reportError("官方页面加载失败，请检查网络后重试")
                    }
                }
            }
        }
    }
    LaunchedEffect(session.generation) {
        VerificationCookies.seed(session)
        webView.doOnLayout { view ->
            if (view.width > 0 && view.height > 0) webView.loadUrl(session.url)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.clearHistory()
            webView.clearCache(true)
            webView.destroy()
            VerificationCookies.clear()
        }
    }
    Column(modifier) {
        Text(
            "请在学校官方页面完成验证，成功后将自动返回",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (loading) Text("正在加载官方页面…", modifier = Modifier.padding(horizontal = 20.dp))
        if (error != null) {
            Text(error, modifier = Modifier.padding(horizontal = 20.dp))
            TextButton("重新加载", onClick = { loading = true; webView.loadUrl(session.url) }, modifier = Modifier.fillMaxWidth())
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            // The host uses adjustPan (no imePadding), so typing does not resize this viewport.
            val phoneWidth = minOf(420.dp, maxWidth, if (maxWidth >= maxHeight) maxHeight * 9 / 16 else maxWidth)
            AndroidView(factory = { webView }, modifier = Modifier.width(phoneWidth).fillMaxSize())
        }
    }
}
