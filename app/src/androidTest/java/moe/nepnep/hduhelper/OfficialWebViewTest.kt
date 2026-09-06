package moe.nepnep.hduhelper

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import moe.nepnep.hduhelper.data.auth.AuthEndpoints
import moe.nepnep.hduhelper.data.auth.VerificationSession
import moe.nepnep.hduhelper.ui.screens.VerificationScreen
import moe.nepnep.hduhelper.ui.theme.HDUHelperTheme
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar

/** Opt-in public-site test using the real Android frame clock. Never enters or reads credentials. */
class OfficialWebViewTest {
    @Test(timeout = 60_000)
    fun officialPageUsesPhoneLayoutAndKeyboardDoesNotResizeIt() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("officialWebViewTest") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val error = AtomicReference<String?>(null)
        val metrics = AtomicReference<JSONObject?>(null)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var root: View? = null
            scenario.onActivity { activity ->
                root = activity.window.decorView
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
                activity.setContent {
                    HDUHelperTheme {
                        Scaffold(topBar = { TopAppBar("官方登录") }) { padding ->
                            VerificationScreen(
                                VerificationSession(0, AuthEndpoints().login.toString(), emptyList()), null,
                                { _, _ -> }, { error.set(it) }, Modifier.fillMaxSize().padding(padding),
                            )
                        }
                    }
                }
            }
            var webView: WebView? = null
            await(10_000) {
                instrumentation.runOnMainSync { webView = root?.let(::findWebView) }
                webView != null
            }
            fun sample() {
                instrumentation.runOnMainSync {
                    webView!!.evaluateJavascript(
                        """JSON.stringify({width:innerWidth,height:innerHeight,mobile:/Android.*Mobile/.test(navigator.userAgent),device:typeof device==='string'?device:'',ready:!!window.neworientation})""",
                    ) { value -> runCatching { metrics.set(JSONObject(JSONTokener(value).nextValue() as String)) } }
                }
            }
            await(30_000) {
                sample()
                metrics.get()?.optBoolean("ready") == true && metrics.get()?.optString("device") == "PHONE"
            }
            assertNull(error.get())
            val before = metrics.get()!!
            assertTrue(before.getBoolean("mobile"))
            assertTrue(before.getInt("height") > before.getInt("width"))
            assertTrue(before.getInt("width") <= 480)
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "PHONE viewport: ${before.getInt("width")} x ${before.getInt("height")}\n") })
            instrumentation.runOnMainSync {
                webView!!.requestFocus()
                webView!!.evaluateJavascript(
                    """(()=>{let roots=[document,...Array.from(document.querySelectorAll('*')).map(e=>e.shadowRoot).filter(Boolean)];for(let r of roots){let i=Array.from(r.querySelectorAll('input')).find(e=>e.type!=='hidden'&&e.getBoundingClientRect().width>0);if(i){i.focus();return true;}}return false;})()""",
                ) {
                    @Suppress("DEPRECATION")
                    (webView!!.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            await(10_000) {
                var visible = false
                instrumentation.runOnMainSync { visible = ViewCompat.getRootWindowInsets(root!!)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                visible
            }
            // Let the real IME insets animation finish before comparing the layout.
            Thread.sleep(500)
            metrics.set(null)
            await(5_000) { sample(); metrics.get() != null }
            val after = metrics.get()!!
            assertEquals(before.getInt("width"), after.getInt("width"))
            assertEquals(before.getInt("height"), after.getInt("height"))
            assertEquals("PHONE", after.getString("device"))
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "Keyboard shown: viewport unchanged\n") })
        }
    }

    private fun await(timeout: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for public WebView layout" }
            Thread.sleep(100)
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) repeat(view.childCount) { findWebView(view.getChildAt(it))?.let { return it } }
        return null
    }
}
