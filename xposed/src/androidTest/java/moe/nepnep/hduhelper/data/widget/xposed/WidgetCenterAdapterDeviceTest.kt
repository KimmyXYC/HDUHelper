package moe.nepnep.hduhelper.data.widget.xposed

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** Tests the real installed Xiaomi model classes, without requiring live hooks or changing its databases. */
class WidgetCenterAdapterDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val host by lazy { context.createPackageContext(WidgetCenterAdapter.HOST, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY) }
    private val loader get() = host.classLoader
    private fun adapter(path: String = context.applicationInfo.sourceDir) = WidgetCenterAdapter(loader, path)
    private fun invoke(value: Any, name: String): Any? = value.javaClass.getMethod(name).invoke(value)
    private fun decode(name: String, json: String): Any {
        val gson = loader.loadClass("com.google.gson.Gson")
        return gson.getMethod("fromJson", String::class.java, Class::class.java)
            .invoke(gson.getConstructor().newInstance(), json, loader.loadClass(name))
    }

    @Test fun localRowsKeepOtherAppsAndDoNotDuplicateOnReload() {
        val adapter = adapter()
        val foreign = Any()
        val merged = adapter.mergeRows(context, listOf(foreign))
        assertEquals(2, merged.size)
        assertSame(foreign, merged[1])
        assertTrue(adapter.isOwnRow(merged[0]))
        assertEquals(3, invoke(merged[0]!!, "getAppWidgetAmount"))
        val repeated = adapter.mergeRows(context, merged)
        assertEquals(2, repeated.size)
        assertSame(foreign, repeated[1])
    }

    @Test fun unsignedOrDifferentSignedIdentityCannotInjectRows() {
        val original = listOf(Any())
        val adapter = adapter(host.applicationInfo.sourceDir)
        assertTrue(adapter.installed(context).isEmpty())
        assertSame(original, adapter.mergeRows(context, original))
    }

    @Test fun actualXiaomiModelsContainOnlyTheThreeInstalledProvidersAndNativeSizes() {
        val details = adapter().details(context)
        assertEquals(3, details.size)
        assertEquals(listOf(1, 2, 4), details.map { invoke(it, "getOriginStyle") })
        assertEquals(WidgetCenterAdapter.providers.keys.toList(), details.map { invoke(invoke(it, "getWidgetImplInfo")!!, "getWidgetProviderName") })
        details.forEach {
            assertEquals(WidgetCenterAdapter.APP, invoke(it, "getAppPackage"))
            assertEquals(false, invoke(it, "isPay"))
            val icon = android.net.Uri.parse(invoke(it, "getAppIcon") as String)
            context.contentResolver.openInputStream(icon)!!.use { stream ->
                assertNotNull(android.graphics.BitmapFactory.decodeStream(stream))
            }
            val widget = invoke(it, "getWidgetImplInfo")!!
            assertEquals(1, invoke(widget, "getInstallStatus"))
            for (method in listOf("getLightPreviewUrl", "getDarkPreviewUrl")) {
                val uri = android.net.Uri.parse(invoke(widget, method) as String)
                assertEquals("android.resource", uri.scheme)
                assertEquals(WidgetCenterAdapter.APP, uri.authority)
                context.contentResolver.openInputStream(uri)!!.use { assertTrue(it.readBytes().size > 100) }
            }
        }
    }

    @Test fun detailServiceOnlyInterceptsOwnPackageAndPreservesOtherResults() {
        val adapter = adapter()
        val service = loader.loadClass("com.miui.personalassistant.picker.business.detail.service.PickerDetailService")
        val method = service.declaredMethods.single { it.name == "getPickerDetail" }
        var calls = 0
        val marker = Any()
        val original = Proxy.newProxyInstance(loader, arrayOf(service)) { _, _, _ -> calls++; marker }
        val wrapped = adapter.wrapService(context, original)
        fun query(packageName: String) = decode("com.miui.personalassistant.picker.business.detail.bean.PickerDetailQueryParam",
            """{"info":{"appPackage":"$packageName"}}""")
        val local = method.invoke(wrapped, query(WidgetCenterAdapter.APP), null) as List<*>
        assertEquals(3, local.size)
        assertEquals(0, calls)
        assertSame(marker, method.invoke(wrapped, query("com.example.unrelated"), null))
        assertEquals(1, calls)
    }
}
