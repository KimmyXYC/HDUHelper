package moe.nepnep.hduhelper.data.island.xposed

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.net.toUri
import android.os.*
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import moe.nepnep.hduhelper.data.island.*

/** Lives in SystemUI, so a closed island or killed module app cannot lose the restore timer. */
internal class IslandSystemHost(private val context: Context, private val gatesReady: () -> Boolean) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "hdu-island-bridge").apply { isDaemon = true } }
    private val audio = context.getSystemService(AudioManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("hduhelper_island_mute", Context.MODE_PRIVATE)
    private val getInternal = runCatching { AudioManager::class.java.getMethod("getRingerModeInternal") }.getOrNull()
    private val setInternal = runCatching { AudioManager::class.java.getMethod("setRingerModeInternal", Int::class.javaPrimitiveType) }.getOrNull()
    private var ownership = runCatching { Json.decodeFromString<MuteOwnership>(prefs.getString("ownership", "{}")!!) }.getOrDefault(MuteOwnership())
    private val expiry = AlarmManager.OnAlarmListener { expire() }
    private var expectedMode: Int? = null

    private fun mode(): Int = (getInternal?.invoke(audio) as? Int) ?: audio.ringerMode
    @android.annotation.SuppressLint("UseKtx") // The commit result is required before changing device sound state.
    private fun save() { check(prefs.edit().putString("ownership", Json.encodeToString(ownership)).commit()) }

    init {
        val filter = IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION).apply {
            addAction("android.media.INTERNAL_RINGER_MODE_CHANGED_ACTION")
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching {
                    if (intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED) expire()
                    else {
                        if (getInternal != null && intent.action == AudioManager.RINGER_MODE_CHANGED_ACTION) return
                        val current = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, mode())
                        if (expectedMode == current) expectedMode = null
                        else if (ownership.owned && current != MuteOwnership.SILENT) {
                            ownership = ownership.userChangedMode(); save(); schedule(); changed()
                        }
                    }
                }
            }
        }, filter, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (Build.VERSION.SDK_INT >= 34 && sentFromUid != appUid()) return
                register()
            }
        }, IntentFilter(IslandWire.REQUEST), ContextCompat.RECEIVER_EXPORTED)
        runCatching { expire() }
    }

    private fun appUid() = context.packageManager.getPackageUid(IslandWire.APP, 0)

    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in setOf(IslandWire.STATUS, IslandWire.TOGGLE_MUTE)) return super.onTransact(code, data, reply, flags)
            if (getCallingUid() != appUid()) throw SecurityException("Only HDUHelper may call its island host")
            data.enforceInterface(IslandWire.DESCRIPTOR)
            val key = data.readString().orEmpty()
            val end = data.readLong()
            data.enforceNoDataAvail()
            val identity = clearCallingIdentity()
            try {
                var result: Bundle? = null
                val completed = CountDownLatch(1)
                handler.post {
                    result = runCatching {
                        val success = if (code == IslandWire.TOGGLE_MUTE) toggle(key, end) else true
                        snapshot().apply { putBoolean("success", success) }
                    }.getOrElse { snapshot().apply { putBoolean("success", false) } }
                    completed.countDown()
                }
                check(completed.await(3, TimeUnit.SECONDS)) { "SystemUI island host busy" }
                reply!!.writeNoException()
                reply.writeBundle(result)
                return true
            } finally { restoreCallingIdentity(identity) }
        }
    }

    fun register() {
        worker.execute {
            runCatching { context.contentResolver.call("content://${IslandWire.AUTHORITY}".toUri(), "register", null,
                Bundle().apply { putInt("version", IslandWire.VERSION); putBinder("host", endpoint) }) }
        }
    }

    private fun snapshot() = Bundle().apply {
        putInt("version", IslandWire.VERSION)
        putBoolean("ready", gatesReady())
        putBoolean("muteSupported", getInternal != null && setInternal != null)
        putBoolean("owned", ownership.owned)
        putStringArrayList("keys", ArrayList(ownership.ends.keys))
        putBoolean("silent", runCatching { mode() == MuteOwnership.SILENT }.getOrDefault(false))
    }

    private fun toggle(key: String, end: Long): Boolean {
        if (!gatesReady() || setInternal == null || getInternal == null) return false
        require(key.length in 1..200)
        expire()
        if (ownership.owned) {
            // A second course adds its deadline; the same course's button releases all ownership.
            if (key in ownership.ends) restore()
            else {
                ownership = ownership.acquire(key, end, System.currentTimeMillis(), mode())
                save(); schedule(); changed()
            }
            return true
        }
        val current = mode()
        val next = ownership.acquire(key, end, System.currentTimeMillis(), current)
        if (!next.owned) return false
        ownership = next
        save() // Durable recovery record before changing the device sound state.
        try {
            expectedMode = MuteOwnership.SILENT
            setInternal.invoke(audio, MuteOwnership.SILENT)
            if (mode() != MuteOwnership.SILENT) { ownership = MuteOwnership(); save(); return false }
        } catch (e: Exception) { ownership = MuteOwnership(); save(); throw e }
        schedule(); changed()
        return true
    }

    private fun expire() {
        if (ownership.originalMode == null) return
        if (mode() != MuteOwnership.SILENT) {
            ownership = ownership.userChangedMode(); save(); schedule(); changed(); return
        }
        ownership = ownership.expire(System.currentTimeMillis())
        if (ownership.ends.isEmpty()) restore() else { save(); schedule() }
    }

    private fun restore() {
        val current = mode()
        if (ownership.shouldRestore(current) && setInternal != null) {
            expectedMode = ownership.originalMode
            setInternal.invoke(audio, ownership.originalMode!!)
        }
        ownership = MuteOwnership(); save(); schedule(); changed()
    }

    private fun schedule() {
        alarms.cancel(expiry)
        ownership.ends.values.minOrNull()?.let { at ->
            alarms.setExact(AlarmManager.RTC_WAKEUP, at, "hduhelper:mute-restore", expiry, handler)
        }
    }

    private fun changed() {
        worker.execute { runCatching {
            context.contentResolver.call("content://${IslandWire.AUTHORITY}".toUri(), "changed", null, null)
        } }
    }
}
