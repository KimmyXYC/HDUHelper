package moe.nepnep.hduhelper.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.nepnep.hduhelper.AppContainer
import moe.nepnep.hduhelper.data.auth.AuthException
import moe.nepnep.hduhelper.data.auth.AuthFailure
import moe.nepnep.hduhelper.data.auth.AuthState
import moe.nepnep.hduhelper.data.auth.AuthStatus
import moe.nepnep.hduhelper.data.campuscode.CampusCode
import moe.nepnep.hduhelper.data.campuscode.CampusCodeSource
import moe.nepnep.hduhelper.data.campuscode.CampusCodeException
import moe.nepnep.hduhelper.data.campuscode.QrCodeEncoder
import moe.nepnep.hduhelper.data.campuscode.QrPixels

enum class CampusCodeStatus { SIGNED_OUT, AUTHORIZING, READY, ERROR, LOGIN_REQUIRED, VERIFICATION_REQUIRED }

data class CampusCodeUiState(
    val status: CampusCodeStatus = CampusCodeStatus.SIGNED_OUT,
    val code: CampusCode? = null,
    val image: QrPixels? = null,
    val refreshing: Boolean = false,
    val nextRefreshSeconds: Long? = null,
    val message: String? = null,
) {
    override fun toString() = "CampusCodeUiState([redacted])"
}

class CampusCodeViewModel(
    private val source: CampusCodeSource,
    auth: StateFlow<AuthState>,
    generation: StateFlow<Long>,
    online: Flow<Boolean>,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val visible = MutableStateFlow(false)
    private val mutableState = MutableStateFlow(CampusCodeUiState())
    val state = mutableState.asStateFlow()
    private var activeScope: CoroutineScope? = null
    private var request: Job? = null
    private var interval = 300L
    private data class Gate(val visible: Boolean, val online: Boolean, val account: String?, val generation: Long, val blocked: AuthStatus?)

    init {
        viewModelScope.launch {
            var previousIdentity: Pair<String?, Long>? = null
            combine(visible, online, auth, generation) { shown, connected, state, version ->
                Gate(shown, connected, state.profile?.account, version,
                    state.status.takeIf { it in listOf(AuthStatus.LOADING, AuthStatus.SIGNING_IN, AuthStatus.VERIFICATION_REQUIRED) })
            }.distinctUntilChanged().collectLatest { gate ->
                val identity = gate.account to gate.generation
                if (identity != previousIdentity) { source.clear(); previousIdentity = identity }
                mutableState.value = when {
                    gate.blocked == AuthStatus.VERIFICATION_REQUIRED -> CampusCodeUiState(CampusCodeStatus.VERIFICATION_REQUIRED, message = "请完成官方身份验证")
                    gate.blocked != null -> CampusCodeUiState(CampusCodeStatus.AUTHORIZING, message = "正在读取登录状态…")
                    gate.account == null -> CampusCodeUiState()
                    !gate.online -> CampusCodeUiState(CampusCodeStatus.ERROR, message = "网络未连接，联网后自动刷新")
                    else -> CampusCodeUiState(CampusCodeStatus.AUTHORIZING)
                }
                if (!gate.visible || !gate.online || gate.account == null || gate.blocked != null) return@collectLatest
                coroutineScope {
                    activeScope = this
                    interval = 300L
                    try {
                        refresh()?.join()
                        var deadline = elapsed() + interval * 1000
                        while (isActive) {
                            val left = deadline - elapsed()
                            mutableState.value = mutableState.value.copy(nextRefreshSeconds = ((left + 999) / 1000).coerceAtLeast(0))
                            if (left <= 0) {
                                if (mutableState.value.status !in listOf(CampusCodeStatus.LOGIN_REQUIRED, CampusCodeStatus.VERIFICATION_REQUIRED)) refresh()
                                deadline = elapsed() + interval * 1000
                            }
                            delay(250)
                        }
                    } finally {
                        activeScope = null
                        request?.cancel()
                        request = null
                        mutableState.value = mutableState.value.copy(code = null, image = null, refreshing = false, nextRefreshSeconds = null)
                    }
                }
            }
        }
    }

    fun setVisible(value: Boolean) {
        visible.value = value
        if (!value) {
            request?.cancel()
            mutableState.value = mutableState.value.copy(code = null, image = null, refreshing = false, nextRefreshSeconds = null)
        }
    }

    /** The periodic deadline is intentionally independent of manual requests. */
    fun refresh(): Job? {
        val scope = activeScope ?: return null
        if (request?.isActive == true) return request
        return scope.launch {
            mutableState.value = mutableState.value.copy(refreshing = true, message = null)
            try {
                val code = source.refresh()
                val image = withContext(compute) { QrCodeEncoder.encode(code.content) }
                interval = code.refreshSeconds
                mutableState.value = mutableState.value.copy(status = CampusCodeStatus.READY, code = code, image = image, refreshing = false)
            } catch (e: CancellationException) { throw e }
            catch (e: AuthException) {
                if (e.kind == AuthFailure.CANCELLED) return@launch
                val status = when (e.kind) {
                    AuthFailure.EXPIRED, AuthFailure.CREDENTIALS -> CampusCodeStatus.LOGIN_REQUIRED
                    AuthFailure.VERIFICATION -> CampusCodeStatus.VERIFICATION_REQUIRED
                    else -> CampusCodeStatus.ERROR
                }
                mutableState.value = CampusCodeUiState(status, message = e.message)
            } catch (e: CampusCodeException) {
                mutableState.value = CampusCodeUiState(CampusCodeStatus.ERROR, message = e.message)
            } catch (_: Exception) {
                mutableState.value = CampusCodeUiState(CampusCodeStatus.ERROR, message = "暂时无法生成二维码，请重试")
            }
        }.also { request = it }
    }

    override fun onCleared() { source.clear() }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CampusCodeViewModel(
                container.campusCodes, container.auth.state, container.auth.sessionGeneration, container.network.online,
            ) as T
        }
    }
}
