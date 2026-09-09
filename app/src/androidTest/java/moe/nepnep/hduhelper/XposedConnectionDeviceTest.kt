package moe.nepnep.hduhelper

import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in read-only check of the real same-signed companion, including vendor startup policy. */
class XposedConnectionDeviceTest {
    @Test fun installedModuleStatusIsReachable() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("xposedConnection") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val uri = Uri.parse("content://moe.nepnep.hduhelper.xposed.bridge")
        var status: Bundle? = null
        // The framework binder may arrive asynchronously after the provider's cold start.
        for (attempt in 0 until 20) {
            status = resolver.call(uri, "status", null, null)
            if (status?.getBoolean("active") == true) break
            Thread.sleep(250)
        }
        assertNotNull("Module provider is blocked or missing", status)
        assertEquals(1, status!!.getInt("protocol"))
        assertTrue("Framework service has not connected", status.getBoolean("active"))
        assertTrue(status.getInt("api") >= 101)
        assertTrue(status.getBoolean("remote"))
        assertTrue(status.getStringArrayList("scope").orEmpty().containsAll(
            listOf("system", "com.android.systemui", "miui.systemui.plugin")))
        instrumentation.sendStatus(0, Bundle().apply {
            putBoolean("xposed_connected", true)
            putInt("xposed_api", status.getInt("api"))
            putStringArrayList("xposed_scope", status.getStringArrayList("scope"))
        })
    }
}
