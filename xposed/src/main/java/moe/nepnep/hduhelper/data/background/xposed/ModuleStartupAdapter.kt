package moe.nepnep.hduhelper.data.background.xposed

import android.content.Intent
import android.content.pm.ComponentInfo
import android.content.pm.ProviderInfo
import io.github.libxposed.api.XposedInterface
import moe.nepnep.hduhelper.data.background.BackgroundIdentity
import moe.nepnep.hduhelper.data.background.BackgroundPolicy
import java.util.concurrent.atomic.AtomicLong

/** HyperOS provider startup gate, independent of the optional reminder enhancement preference. */
internal class ModuleStartupAdapter(loader: ClassLoader) {
    private val type = Class.forName("com.android.server.am.ActivityManagerServiceImpl", false, loader)
    private val callerType = Class.forName("miui.security.CallerInfo", false, loader)
    private val callerPackage = callerType.getDeclaredField("callerPkg").apply { isAccessible = true }
    private val callerUid = callerType.getDeclaredField("callerUid").apply { isAccessible = true }
    private val gate = type.getDeclaredMethod("checkWakePath",
        Class.forName("com.android.server.am.ActivityManagerService", false, loader), callerType,
        String::class.java, Intent::class.java, ComponentInfo::class.java,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply {
        check(returnType == Boolean::class.javaPrimitiveType)
    }
    val connections = AtomicLong()

    fun install(module: XposedInterface, identity: () -> BackgroundIdentity?) {
        module.hook(gate).intercept { chain ->
            val allowed = runCatching {
                val intent = chain.args[3] as? Intent ?: return@runCatching false
                val target = chain.args[4] as? ProviderInfo ?: return@runCatching false
                val caller = chain.args[1]
                chain.args[5] == 4 && caller != null &&
                    intent.component?.className == target.name &&
                    BackgroundPolicy.moduleConnection(identity(), callerPackage.get(caller) as? String,
                        callerUid.getInt(caller), target.packageName, target.applicationInfo.uid,
                        target.name, chain.args[6] as Int)
            }.getOrDefault(false)
            if (allowed) { connections.incrementAndGet(); true } else chain.proceed()
        }
        // Provider compatibility checks may have an AOT-inlined copy of checkWakePath.
        type.declaredMethods.filter { it.name == "checkRunningCompatibility" }.forEach(module::deoptimize)
    }
}
