package moe.nepnep.hduhelper.data.background

import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The companion owns libxposed. The main APK never loads framework classes. */
object XposedFramework {
    data class State(val apiVersion: Int, val scope: Set<String>, val remote: Boolean, val modulePath: String)
    private val serviceState = MutableStateFlow<State?>(null)
    val service = serviceState.asStateFlow()
    enum class ConnectionIssue { UNAVAILABLE, INACTIVE }
    private val connectionIssueState = MutableStateFlow<ConnectionIssue?>(null)
    val connectionIssue = connectionIssueState.asStateFlow()
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        worker.launch { refresh(app) }
    }

    private var observing = false
    @Synchronized private fun observe(context: Context) {
        if (observing) return
        val app = context.applicationContext
        observing = runCatching {
            app.contentResolver.registerContentObserver(ModuleWire.URI, false, object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) { worker.launch { refresh(app) } }
            })
            true
        }.getOrDefault(false) // The optional companion may not be installed yet.
    }

    fun refresh(context: Context): State? {
        observe(context)
        val result = runCatching { context.contentResolver.call(ModuleWire.URI, "status", null, null) }.getOrNull()
        val value = result?.takeIf { it.getInt("protocol") == ModuleWire.VERSION && it.getBoolean("active") }?.let {
            State(it.getInt("api"), it.getStringArrayList("scope")?.toSet().orEmpty(),
                it.getBoolean("remote"), it.getString("modulePath").orEmpty())
        }
        val installed = runCatching { context.packageManager.getApplicationInfo(ModuleWire.PACKAGE, 0) }.isSuccess
        connectionIssueState.value = when {
            value != null || !installed -> null
            result == null || result.getInt("protocol") != ModuleWire.VERSION -> ConnectionIssue.UNAVAILABLE
            else -> ConnectionIssue.INACTIVE
        }
        serviceState.value = value
        return value
    }

    fun setBackground(context: Context, enabled: Boolean, force: Boolean): Boolean = runCatching {
        val response = context.contentResolver.call(ModuleWire.URI, "background", null, Bundle().apply {
            putBoolean("enabled", enabled)
            putBoolean("force", force)
        })
        response?.getInt("protocol") == ModuleWire.VERSION && response.getBoolean("success")
    }.getOrDefault(false)
}
