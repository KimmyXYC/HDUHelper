package moe.nepnep.hduhelper.data.island.xposed

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import dalvik.system.BaseDexClassLoader
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import moe.nepnep.hduhelper.data.island.IslandWire
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** No authentication, timetable, or UI classes are initialized in the host process. */
@android.annotation.SuppressLint("PrivateApi", "DiscouragedPrivateApi") // Intentional, scoped hooks inside HyperOS SystemUI; signature-checked before use.
class IslandModule : XposedModule() {
    private val installed = ConcurrentHashMap.newKeySet<java.lang.reflect.Method>()
    private val loaders = Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var host: IslandSystemHost? = null
    private var baseReady = false
    private var pluginReady = false
    private var authReady = false
    private var bridgeInstalled = false
    private var processName = ""

    override fun onModuleLoaded(param: ModuleLoadedParam) { processName = param.processName }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (processName != IslandWire.SYSTEM_UI) return
        if (param.packageName !in setOf(IslandWire.SYSTEM_UI, IslandWire.PLUGIN)) return
        install(param.defaultClassLoader)
        if (bridgeInstalled) return
        bridgeInstalled = true
        runCatching {
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            hook(attach).intercept { chain ->
                val result = chain.proceed()
                val app = chain.thisObject as? Application
                if (app?.packageName == IslandWire.SYSTEM_UI) handler.post { startHost(app) }
                result
            }
        }.onFailure { log(android.util.Log.WARN, "HDUIsland", "Application bridge unavailable", it) }
        // HyperOS loads the plugin with a separate class loader after Application.attach.
        BaseDexClassLoader::class.java.declaredConstructors.forEach { constructor ->
            runCatching { hook(constructor).intercept { chain ->
                val result = chain.proceed()
                val loader = chain.thisObject as? ClassLoader
                if (loader != null) handler.post { install(loader) }
                result
            } }
        }
        handler.post {
            runCatching {
                val app = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication").invoke(null) as? Application
                if (app?.packageName == IslandWire.SYSTEM_UI) startHost(app)
            }
        }
    }

    private fun startHost(context: Context) {
        if (host == null) host = IslandSystemHost(context, moduleApplicationInfo.sourceDir) { baseReady && pluginReady && authReady }
        host?.register()
    }

    @Synchronized private fun install(loader: ClassLoader) {
        if (!loaders.add(loader)) return
        val names = listOf(
            "com.miui.systemui.notification.NotificationSettingsManager",
            "miui.systemui.notification.NotificationSettingsManager",
            "miui.systemui.notification.focus.FocusNotifUtils",
        )
        for (name in names) {
            val type = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
            for (method in type.declaredMethods) {
                val parameters = method.parameterTypes
                val authGate = name == "miui.systemui.notification.NotificationSettingsManager" &&
                    method.name == "canPassXMSPermission" && parameters.contentEquals(arrayOf(String::class.java))
                val knownName = authGate || method.name in setOf("canShowFocus", "canShowFocusState", "canShowFocusStateApp")
                val knownParameters = authGate || parameters.contentEquals(arrayOf(Context::class.java, String::class.java)) ||
                    name.endsWith("FocusNotifUtils") && parameters.contentEquals(arrayOf(Context::class.java, String::class.java, Int::class.javaPrimitiveType))
                val returnsBoolean = method.returnType == Boolean::class.javaPrimitiveType
                val returnsState = method.name != "canShowFocus" && method.returnType == Int::class.javaPrimitiveType
                if (!knownName || !knownParameters || !(returnsBoolean || returnsState) || !installed.add(method)) continue
                runCatching {
                    hook(method).intercept { chain ->
                        if (chain.args.getOrNull(if (authGate) 0 else 1) == IslandWire.APP) {
                            if (returnsBoolean) true else 1
                        } else chain.proceed()
                    }
                    if (authGate) authReady = true else if (name.startsWith("com.miui.")) baseReady = true else pluginReady = true
                    log(android.util.Log.INFO, "HDUIsland", "Installed own-package gate: $name.${method.name}")
                    handler.post { host?.register() }
                }.onFailure { installed.remove(method); log(android.util.Log.WARN, "HDUIsland", "Unsupported focus gate", it) }
            }
        }
    }
}
