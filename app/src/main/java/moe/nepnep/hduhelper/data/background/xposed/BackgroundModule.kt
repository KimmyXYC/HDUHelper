package moe.nepnep.hduhelper.data.background.xposed

import android.app.PendingIntent
import android.content.*
import android.content.SharedPreferences
import android.os.*
import androidx.core.net.toUri
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import moe.nepnep.hduhelper.data.background.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Only vendor alarm policy is changed; Android permission checks and force-stop semantics remain. */
@android.annotation.SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class BackgroundModule : XposedModule() {
    @Volatile private var enabled = false
    @Volatile private var ready = false
    @Volatile private var appUid: Int? = null
    private var preferences: SharedPreferences? = null
    private var modulePath: String? = null
    private var host: Host? = null
    private val exemptedAlarms = AtomicLong()
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
        android.util.Log.i("HDUBackground", "Installing vendor alarm adapter")
        modulePath = moduleApplicationInfo.sourceDir
        runCatching {
            preferences = getRemotePreferences(BackgroundWire.GROUP).also {
                enabled = it.getBoolean(BackgroundWire.ENABLED, false)
                it.registerOnSharedPreferenceChangeListener(listener)
            }
            val loader = param.classLoader
            val type = Class.forName("com.android.server.alarm.AlarmManagerServiceStubImpl", false, loader)
            val alarmType = Class.forName("com.android.server.alarm.Alarm", false, loader)
            val operation = alarmType.getDeclaredField("operation").apply { isAccessible = true }
            val getIntent = PendingIntent::class.java.getDeclaredMethod("getIntent").apply { isAccessible = true }
            val signatures = listOf(
                Triple("checkAlarmIsAllowedSend", arrayOf(Context::class.java, alarmType), true),
                Triple("alignAlarmLocked", arrayOf(alarmType), false),
                Triple("adjustAlarmLocked", arrayOf(alarmType), false),
                Triple("isExemptFromSsru", arrayOf(alarmType), true),
            )
            // Resolve every required member before installing anything. Partial installs stay inert.
            val methods = signatures.map { (name, parameters, _) ->
                type.getDeclaredMethod(name, *parameters).also { check(it.returnType == Boolean::class.javaPrimitiveType) }
            }
            methods.zip(signatures).forEach { (method, spec) ->
                hook(method).intercept { chain ->
                    val ownReminder = ready && enabled && runCatching {
                        val alarm = chain.args.lastOrNull() ?: return@runCatching false
                        val pending = operation.get(alarm) as? PendingIntent ?: return@runCatching false
                        val intent = getIntent.invoke(pending) as? Intent
                        matchesReminder(pending.creatorPackage, pending.creatorUid, appUid, intent?.action) &&
                            intent?.component?.packageName == BackgroundWire.APP
                    }.getOrDefault(false)
                    if (ownReminder) { exemptedAlarms.incrementAndGet(); spec.third } else chain.proceed()
                }
            }
            ready = true
            android.util.Log.i("HDUBackground", "Vendor alarm adapter ready")
        }.onFailure { android.util.Log.w("HDUBackground", "Vendor alarm adapter unavailable", it) }
        // SystemServer has prepared its Looper before this callback. Run after boot services return.
        Handler(Looper.getMainLooper()).post { startHost() }
    }

    private fun startHost() {
        if (host != null) return
        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getDeclaredMethod("currentActivityThread").invoke(null)
            val context = activityThread.getDeclaredMethod("getSystemContext").invoke(thread) as Context
            appUid = context.packageManager.getPackageUid(BackgroundWire.APP, 0)
            host = Host(context).also { it.registerReceiver() }
            android.util.Log.i("HDUBackground", "Background bridge ready")
        }.onFailure { android.util.Log.w("HDUBackground", "Background bridge unavailable", it) }
    }

    private inner class Host(private val context: Context) {
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "hdu-background-bridge").apply { isDaemon = true } }
        private val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                check(Binder.getCallingUid() == appUid) { "Only HDUHelper may query its background host" }
                data.enforceInterface(BackgroundWire.DESCRIPTOR)
                require(code == BackgroundWire.STATUS && data.dataAvail() == 0)
                val identity = Binder.clearCallingIdentity()
                try {
                    val status = BackgroundDetector.read(context, requireNotNull(appUid))
                    requireNotNull(reply).writeNoException()
                    reply.writeBundle(Bundle().apply {
                        putInt("version", BackgroundWire.VERSION)
                        putString("modulePath", modulePath)
                        putBoolean("ready", ready)
                        putBoolean("enabled", ready && enabled)
                        putLong("exemptions", exemptedAlarms.get())
                        putString("autostart", status.autostart.name)
                        putString("vendorBattery", status.vendorBattery.name)
                    })
                } finally { Binder.restoreCallingIdentity(identity) }
                return true
            }
        }

        fun registerReceiver() {
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { register() }
            }, IntentFilter(BackgroundWire.REQUEST), "${BackgroundWire.APP}.permission.REQUEST_BACKGROUND_HOST", null, Context.RECEIVER_EXPORTED)
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
