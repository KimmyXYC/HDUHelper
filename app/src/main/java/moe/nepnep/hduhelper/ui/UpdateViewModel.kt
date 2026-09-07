package moe.nepnep.hduhelper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import moe.nepnep.hduhelper.AppContainer
import moe.nepnep.hduhelper.BuildConfig
import moe.nepnep.hduhelper.data.update.*

data class UpdateState(val checking: Boolean = false, val message: String? = null, val release: AppRelease? = null)

class UpdateViewModel(
    private val source: UpdateSource,
    private val store: UpdateCheckStore,
    online: Flow<Boolean>,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    private var foreground = false
    private var connected = false
    private var checkJob: Job? = null
    private var manual = false

    init {
        viewModelScope.launch {
            online.distinctUntilChanged().collect { connected = it; automaticCheck() }
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) automaticCheck()
        else {
            checkJob?.cancel()
            checkJob = null
            mutableState.value = mutableState.value.copy(checking = false)
        }
    }

    private fun automaticCheck() {
        if (!foreground || !connected || checkJob?.isActive == true || state.value.release != null) return
        val now = clock()
        val previous = store.lastAttempt
        if (previous != null && now >= previous && now - previous < 24 * 60 * 60 * 1000L) return
        store.lastAttempt = now
        startCheck(false)
    }

    fun check() {
        if (checkJob?.isActive == true) { manual = true; return }
        if (!connected) { mutableState.value = UpdateState(message = "网络不可用，请联网后重试"); return }
        startCheck(true)
    }

    private fun startCheck(manually: Boolean) {
        manual = manually
        mutableState.value = UpdateState(checking = true)
        checkJob = viewModelScope.launch {
            try {
                val release = source.check(currentVersion)
                mutableState.value = UpdateState(release = release, message = if (manual && release == null) "暂无更新版本" else null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = UpdateState(message = if (manual) "检查更新失败，请稍后重试" else null)
            }
        }
    }

    fun dismiss() { mutableState.value = state.value.copy(release = null) }
    fun browserFailed() { mutableState.value = state.value.copy(message = "无法打开浏览器，请安装或启用浏览器后重试") }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = UpdateViewModel(
                container.updates, container.updateCheckStore, container.network.online,
            ) as T
        }
    }
}
