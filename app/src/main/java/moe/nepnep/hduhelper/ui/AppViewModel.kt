package moe.nepnep.hduhelper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import moe.nepnep.hduhelper.AppContainer
import moe.nepnep.hduhelper.data.auth.observeForegroundSession
import moe.nepnep.hduhelper.data.auth.AuthException
import moe.nepnep.hduhelper.data.auth.AuthFailure
import moe.nepnep.hduhelper.data.auth.AuthRepository
import moe.nepnep.hduhelper.data.auth.StoredCookie
import moe.nepnep.hduhelper.data.auth.VerificationSession
import moe.nepnep.hduhelper.data.settings.SettingsRepository
import moe.nepnep.hduhelper.data.settings.ThemeMode

// Password deliberately stays in ViewModel memory, never SavedStateHandle / rememberSaveable.
data class LoginFormState(
    val account: String = "",
    val password: String = "",
    val autoLogin: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
) {
    override fun toString() = "LoginFormState([redacted])"
}

sealed interface AppEvent {
    data object LoggedIn : AppEvent
    data object OpenVerification : AppEvent
    data object ClearWebSession : AppEvent
}

class AppViewModel(val auth: AuthRepository, private val preferences: SettingsRepository, online: Flow<Boolean>) : ViewModel() {
    val authState = auth.state
    val settings = preferences.state
    private val form = MutableStateFlow(LoginFormState())
    val loginForm = form.asStateFlow()
    private val eventChannel = Channel<AppEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var loginJob: Job? = null
    private var verificationJob: Job? = null
    private val verificationErrorState = MutableStateFlow<String?>(null)
    val verificationError = verificationErrorState.asStateFlow()
    private val actionErrorState = MutableStateFlow<String?>(null)
    val actionError = actionErrorState.asStateFlow()

    private val foreground = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            auth.initialize()
            if (form.value.account.isEmpty()) form.value = form.value.copy(account = authState.value.account, autoLogin = settings.value.autoLogin)
            observeForegroundSession(foreground, online, auth)
        }
    }

    fun onForeground() { foreground.value = true }
    fun onBackground() {
        foreground.value = false
        if (loginJob?.isActive == true) {
            auth.cancelLogin()
            loginJob?.cancel()
            form.value = form.value.copy(loading = false, error = null)
        }
        verificationJob?.cancel()
    }
    fun setTheme(mode: ThemeMode) = preferences.setTheme(mode)

    fun openLogin(enableAutoLogin: Boolean = settings.value.autoLogin) {
        cancelLogin()
        form.value = LoginFormState(account = authState.value.account, autoLogin = enableAutoLogin)
    }

    fun setAccount(value: String) { if (!form.value.loading) form.value = form.value.copy(account = value, error = null) }
    fun setPassword(value: String) { if (!form.value.loading) form.value = form.value.copy(password = value, error = null) }
    fun setLoginAutoLogin(value: Boolean) { if (!form.value.loading) form.value = form.value.copy(autoLogin = value) }

    fun login() {
        if (loginJob?.isActive == true) return
        val input = form.value
        if (input.account.isBlank() || input.password.isEmpty()) {
            form.value = input.copy(error = "请输入账号和密码")
            return
        }
        form.value = input.copy(loading = true, error = null)
        loginJob = viewModelScope.launch {
            try {
                auth.login(input.account, input.password, input.autoLogin)
                form.value = LoginFormState(account = input.account.trim(), autoLogin = input.autoLogin)
                eventChannel.send(AppEvent.LoggedIn)
            } catch (e: AuthException) {
                if (e.kind == AuthFailure.CANCELLED) return@launch
                form.value = form.value.copy(loading = false, error = e.message)
                if (e.kind == AuthFailure.VERIFICATION) {
                    form.value = form.value.copy(password = "")
                    eventChannel.send(AppEvent.OpenVerification)
                }
            } catch (e: CancellationException) { throw e }
        }
    }

    fun cancelLogin() {
        auth.cancelLogin()
        loginJob?.cancel()
        verificationJob?.cancel()
        form.value = form.value.copy(password = "", loading = false, error = null)
        verificationErrorState.value = null
    }

    fun openVerification() {
        form.value = form.value.copy(password = "", loading = false)
        verificationErrorState.value = null
        viewModelScope.launch {
            auth.startVerification()
            eventChannel.send(AppEvent.OpenVerification)
        }
    }

    fun verificationSession(): VerificationSession? = auth.verificationSession()

    fun verificationFinished(session: VerificationSession, cookies: List<StoredCookie>) {
        if (verificationJob?.isActive == true) return
        verificationJob = viewModelScope.launch {
            try {
                auth.completeVerification(session.generation, cookies)
                verificationErrorState.value = null
                eventChannel.send(AppEvent.LoggedIn)
            } catch (e: AuthException) {
                if (e.kind != AuthFailure.CANCELLED) verificationErrorState.value = e.message
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { verificationErrorState.value = "暂时无法确认登录状态，请重试" }
        }
    }

    fun verificationFailed(message: String) { verificationErrorState.value = message }

    fun disableAutoLogin() { runAction { auth.disableAutoLogin() } }

    fun logout() {
        cancelLogin()
        runAction {
            auth.logout()
            eventChannel.send(AppEvent.ClearWebSession)
        }
    }

    fun acknowledgeNotice(notice: String) { runAction { auth.acknowledgeNotice(notice) } }

    private fun runAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            actionErrorState.value = null
            try { action() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { actionErrorState.value = "无法更新本机登录信息，请重试" }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container.auth, container.settings, container.network.online) as T
        }
    }
}
