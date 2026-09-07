package moe.nepnep.hduhelper.data.background

import android.content.Context
import android.content.Intent
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.nepnep.hduhelper.data.settings.SettingsRepository

class BackgroundAccess(private val context: Context, private val settings: SettingsRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(BackgroundStatus())
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            combine(XposedFramework.service, BackgroundHostConnection.revision,
                settings.state.map { it.backgroundEnhancement }.distinctUntilChanged()) { _, _, _ -> Unit }
                .collect { refreshNow() }
        }
    }

    fun refresh() { scope.launch { refreshNow() } }

    @android.annotation.SuppressLint("UseKtx") // Verify remote preference persistence before reporting success.
    suspend fun refreshNow(): BackgroundStatus = withContext(Dispatchers.IO) { mutex.withLock {
        var result = BackgroundDetector.read(context)
        val service = XposedFramework.service.value
        val host = BackgroundHostConnection.binder.value
        fun readHost() = if (host != null) runCatching { BackgroundWire.status(host) }.getOrNull() else null
        var response = readHost()
        val currentHost = response?.getString("modulePath") == context.applicationInfo.sourceDir
        val detected = response
        if (detected?.getInt("version") == BackgroundWire.VERSION) {
            fun permission(key: String) = PermissionState.entries.firstOrNull { it.name == detected.getString(key) } ?: PermissionState.UNKNOWN
            result = result.copy(autostart = permission("autostart"), vendorBattery = permission("vendorBattery"))
        }
        val desired = settings.state.value.backgroundEnhancement
        val remoteSupported = runCatching { service != null && service.frameworkProperties and XposedService.PROP_CAP_REMOTE != 0L }.getOrDefault(false)
        // Synchronize OFF even if a user removed the scope while its old hooks are still loaded.
        val synced = remoteSupported && runCatching {
            val prefs = service!!.getRemotePreferences(BackgroundWire.GROUP)
            val needsWrite = prefs.getBoolean(BackgroundWire.ENABLED, false) != desired ||
                (currentHost && response?.getBoolean("enabled") != desired)
            !needsWrite || prefs.edit().putBoolean(BackgroundWire.ENABLED, desired).commit()
        }.getOrDefault(false)
        // A persisted value is not an acknowledgement from system_server. Allow its asynchronous
        // preference callback to apply the change before showing a failure or rescheduling alarms.
        if (synced && currentHost && response?.getBoolean("ready") == true) {
            for (attempt in 0 until 10) {
                response = readHost()
                if (response == null || response?.getBoolean("enabled") == desired) break
                delay(100)
            }
        }
        val hook = when {
            !BackgroundDetector.isXiaomi -> BackgroundHookState.UNSUPPORTED
            service == null -> BackgroundHookState.INACTIVE
            !runCatching { service.scope.contains("system") }.getOrDefault(false) -> BackgroundHookState.SCOPE_REQUIRED
            !remoteSupported -> BackgroundHookState.UNSUPPORTED
            else -> {
                when {
                    !synced -> BackgroundHookState.SYNC_FAILED
                    response == null || !currentHost -> BackgroundHookState.RESTART_REQUIRED
                    response?.getInt("version") != BackgroundWire.VERSION || response?.getBoolean("ready") != true -> BackgroundHookState.UNSUPPORTED
                    response?.getBoolean("enabled") != desired -> BackgroundHookState.SYNC_FAILED
                    desired -> BackgroundHookState.ACTIVE
                    else -> BackgroundHookState.READY
                }
            }
        }
        mutableState.value = result.copy(hook = hook)
        if (service != null && host == null) context.sendBroadcast(Intent(BackgroundWire.REQUEST).setPackage("android"))
        mutableState.value
    } }
}
