package moe.nepnep.hduhelper.data.widget.xposed

import android.app.Activity
import android.app.Application
import android.content.Context
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/** Adds only the signed HDUHelper providers to the local Xiaomi picker. */
@android.annotation.SuppressLint("DiscouragedPrivateApi") // Xposed requires the host Application attachment point.
class WidgetCenterModule : XposedModule() {
    private var processName = ""
    @Volatile private var context: Context? = null
    @Volatile private var ready = false

    override fun onModuleLoaded(param: ModuleLoadedParam) { processName = param.processName }
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != WidgetCenterAdapter.HOST || processName != WidgetCenterAdapter.HOST) return
        try {
            val loader = param.defaultClassLoader
            val adapter = WidgetCenterAdapter(loader, moduleApplicationInfo.sourceDir)
            val listType = Class.forName(WidgetCenterAdapter.APP_ADAPTER, false, loader)
            val baseType = Class.forName(WidgetCenterAdapter.BASE_ADAPTER, false, loader)
            val fragment = Class.forName(WidgetCenterAdapter.APP_FRAGMENT, false, loader)
            val repository = Class.forName(WidgetCenterAdapter.REPOSITORY, false, loader)
            val replace = baseType.getDeclaredMethod("setNewInstance", List::class.java)
            val click = fragment.declaredMethods.single { it.name == "onItemClick" && it.parameterCount == 2 &&
                it.parameterTypes[1].name.endsWith(".PickerListAppData") }
            val service = repository.getDeclaredMethod("getMPickerDetailService")
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            hook(attach).intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? Application)?.takeIf { it.packageName == WidgetCenterAdapter.HOST }?.let { context = it }
                result
            }
            hook(replace).intercept { chain ->
                val app = context
                if (!ready || app == null || !listType.isInstance(chain.thisObject)) chain.proceed()
                else {
                    val rows = runCatching { adapter.mergeRows(app, chain.args[0] as? List<*>) }.getOrElse {
                        log(android.util.Log.WARN, "HDUWidgetCenter", "Local widget listing unavailable", it)
                        return@intercept chain.proceed()
                    }
                    chain.proceed(arrayOf(rows))
                }
            }
            listType.declaredConstructors.forEach { constructor ->
                hook(constructor).intercept { chain ->
                    val result = chain.proceed()
                    if (ready && context != null) runCatching { replace.invoke(chain.thisObject, emptyList<Any>()) }
                    result
                }
            }
            hook(click).intercept { chain ->
                if (!ready || !adapter.isOwnRow(chain.args[1])) chain.proceed()
                else {
                    val activity = chain.thisObject!!.javaClass.getMethod("getActivity").invoke(chain.thisObject) as? Activity
                    if (activity == null) chain.proceed() else {
                        val source = runCatching { activity.javaClass.getMethod("getOpenSource").invoke(activity) as Int }.getOrDefault(2)
                        runCatching { adapter.open(activity, source) }.onFailure { log(android.util.Log.WARN, "HDUWidgetCenter", "Local widget detail unavailable", it) }
                        null
                    }
                }
            }
            hook(service).intercept { chain ->
                val original = chain.proceed()
                val app = context
                if (ready && original != null && app != null) adapter.wrapService(app, original) else original
            }
            ready = true
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    val app = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication").invoke(null) as? Application
                    if (app?.packageName == WidgetCenterAdapter.HOST) context = app
                }
            }
            log(android.util.Log.INFO, "HDUWidgetCenter", "Installed local app-list and widget-detail adapters")
        } catch (e: Exception) {
            ready = false
            log(android.util.Log.WARN, "HDUWidgetCenter", "Unsupported Xiaomi picker; original behavior retained", e)
        }
    }
}
