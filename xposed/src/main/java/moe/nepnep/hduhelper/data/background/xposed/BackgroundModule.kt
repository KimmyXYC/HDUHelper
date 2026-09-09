package moe.nepnep.hduhelper.data.background.xposed

import android.content.*
import android.content.SharedPreferences
import android.os.*
import androidx.core.net.toUri
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import moe.nepnep.hduhelper.data.background.*
import java.util.concurrent.Executors

/** Owned reminder policies and module startup only; permissions and force-stop semantics remain. */
@android.annotation.SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class BackgroundModule : XposedModule() {
    @Volatile private var enabled = false
    @Volatile private var ready = false
    @Volatile private var identity: BackgroundIdentity? = null
    private var startup: ModuleStartupAdapter? = null
    private var policies: ReminderPolicyAdapter? = null
    private var preferences: SharedPreferences? = null
    private var modulePath: String? = null
    private var host: Host? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        enabled = prefs.getBoolean(BackgroundWire.ENABLED, false)
        host?.register()
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        if (param.isSystemServer) {
            android.util.Log.i("HDUBackground", "System module loaded")
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        modulePath = moduleApplicationInfo.sourceDir
        runCatching {
            ModuleStartupAdapter(param.classLoader).also {
                it.install(this) { identity }
                startup = it
            }
            android.util.Log.i("HDUBackground", "Module provider startup adapter ready")
        }.onFailure { android.util.Log.w("HDUBackground", "Module provider startup adapter unavailable", it) }
        runCatching {
            preferences = getRemotePreferences(BackgroundWire.GROUP).also {
                enabled = it.getBoolean(BackgroundWire.ENABLED, false)
                it.registerOnSharedPreferenceChangeListener(listener)
            }
            ReminderPolicyAdapter(param.classLoader).also {
                it.install(this, { identity }, { ready && enabled })
                policies = it
            }
            ready = true
            android.util.Log.i("HDUBackground", "Reminder startup, idle and vendor alarm adapters ready")
        }.onFailure { android.util.Log.w("HDUBackground", "Reminder adapters unavailable", it) }
        // SystemServer has prepared its Looper before this callback. Run after boot services return.
        Handler(Looper.getMainLooper()).post { startHost() }
    }

    private fun startHost() {
        if (host != null) return
        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getDeclaredMethod("currentActivityThread").invoke(null)
            val context = activityThread.getDeclaredMethod("getSystemContext").invoke(thread) as Context
            refreshIdentity(context)
            host = Host(context).also { it.registerReceiver() }
            android.util.Log.i("HDUBackground", "Background bridge ready")
        }.onFailure { android.util.Log.w("HDUBackground", "Background bridge unavailable", it) }
    }

    private fun refreshIdentity(context: Context) {
        identity = runCatching {
            val pm = context.packageManager
            check(pm.checkSignatures(BackgroundWire.APP, ModuleWire.PACKAGE) == android.content.pm.PackageManager.SIGNATURE_MATCH)
            BackgroundIdentity(pm.getPackageUid(BackgroundWire.APP, 0), pm.getPackageUid(ModuleWire.PACKAGE, 0))
        }.getOrNull()
    }

    private inner class Host(private val context: Context) {
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "hdu-background-bridge").apply { isDaemon = true } }
        private val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                check(Binder.getCallingUid() == identity?.appUid) { "Only HDUHelper may query its background host" }
                data.enforceInterface(BackgroundWire.DESCRIPTOR)
                require(code == BackgroundWire.STATUS && data.dataAvail() == 0)
                val callingIdentity = Binder.clearCallingIdentity()
                try {
                    val status = BackgroundDetector.read(context, requireNotNull(identity).appUid)
                    requireNotNull(reply).writeNoException()
                    reply.writeBundle(Bundle().apply {
                        putInt("version", BackgroundWire.VERSION)
                        putString("modulePath", modulePath)
                        putBoolean("ready", ready)
                        putBoolean("enabled", ready && enabled)
                        putInt("policyVersion", BackgroundWire.POLICY_VERSION)
                        putBoolean("moduleStartupReady", startup != null)
                        putLong("moduleConnections", startup?.connections?.get() ?: 0)
                        putLong("exemptions", policies?.exemptions?.get() ?: 0)
                        putLong("reminderStarts", policies?.starts?.get() ?: 0)
                        putLong("reminderThaws", policies?.thaws?.get() ?: 0)
                        putString("autostart", status.autostart.name)
                        putString("vendorBattery", status.vendorBattery.name)
                    })
                } finally { Binder.restoreCallingIdentity(callingIdentity) }
                return true
            }
        }

        fun registerReceiver() {
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { register() }
            }, IntentFilter(BackgroundWire.REQUEST), "${BackgroundWire.APP}.permission.REQUEST_BACKGROUND_HOST", null, Context.RECEIVER_EXPORTED)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.data?.schemeSpecificPart in setOf(BackgroundWire.APP, ModuleWire.PACKAGE)) refreshIdentity(context)
                }
            }, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }, Context.RECEIVER_NOT_EXPORTED)
            // Do not start the app at boot just to announce availability; it requests a host when opened.
        }

        fun register() {
            worker.execute {
                runCatching {
                    context.contentResolver.call("content://${BackgroundWire.AUTHORITY}".toUri(), "register", null,
                        Bundle().apply { putInt("version", BackgroundWire.VERSION); putBinder("host", binder) })
                }.onFailure { android.util.Log.w("HDUBackground", "Background host registration failed", it) }
            }
        }
    }
}
