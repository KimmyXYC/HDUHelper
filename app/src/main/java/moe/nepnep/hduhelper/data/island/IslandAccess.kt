package moe.nepnep.hduhelper.data.island

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import moe.nepnep.hduhelper.data.background.XposedFramework
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class IslandAccess(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(IslandCapability())
    val state = mutableState.asStateFlow()

    init {
        XposedFramework.initialize(context)
        scope.launch { XposedFramework.service.collect { refreshNow() } }
        scope.launch { IslandHostConnection.revision.collect { refreshNow() } }
    }

    fun refresh() { scope.launch { refreshNow() } }

    suspend fun refreshNow(): IslandCapability = withContext(Dispatchers.IO) {
        val supported = Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0) >= 3
        val service = XposedFramework.refresh(context)
        val framework = runCatching { service != null && service.apiVersion >= 101 }.getOrDefault(false)
        val scoped = framework && runCatching {
            service!!.scope.containsAll(listOf(IslandWire.SYSTEM_UI, IslandWire.PLUGIN))
        }.getOrDefault(false)
        val host = IslandHostConnection.binder.value
        val response = if (framework && scoped && supported && host != null) runCatching { IslandWire.call(host, IslandWire.STATUS) }.getOrNull() else null
        val result = IslandCapability(supported, framework, scoped,
            hookReady = response?.getInt("version") == IslandWire.VERSION && response.getBoolean("ready") &&
                response.getString("modulePath") == service?.modulePath,
            muteSupported = response?.getBoolean("muteSupported") == true,
            mutedByModule = response?.getBoolean("owned") == true,
            ringerSilent = response?.getBoolean("silent") == true,
            muteKeys = response?.getStringArrayList("keys")?.toSet().orEmpty())
        mutableState.value = result
        if (result.visible && host == null) {
            val intent = Intent(IslandWire.REQUEST).setPackage(IslandWire.SYSTEM_UI)
            if (Build.VERSION.SDK_INT >= 34) context.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
            else context.sendBroadcast(intent)
        }
        result
    }

    suspend fun toggleMute(key: String, end: Long): Boolean = withContext(Dispatchers.IO) {
        if (!refreshNow().ready) return@withContext false
        val host = IslandHostConnection.binder.value ?: return@withContext false
        val result = runCatching { IslandWire.call(host, IslandWire.TOGGLE_MUTE, key, end).getBoolean("success") }.getOrDefault(false)
        refreshNow()
        result
    }
}
