package moe.nepnep.hduhelper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.nepnep.hduhelper.AppContainer
import moe.nepnep.hduhelper.data.auth.*
import moe.nepnep.hduhelper.data.timetable.*

data class ExamsUiState(
    val status: TimetableStatus = TimetableStatus.SIGNED_OUT,
    val catalog: TimetableCatalog? = null,
    val selectedTerm: AcademicTerm? = null,
    val exams: ExamSnapshot? = null,
    val now: LocalDateTime = LocalDateTime.now(campusZone),
    val refreshing: Boolean = false,
    val offline: Boolean = false,
    val message: String? = null,
) {
    override fun toString() = "ExamsUiState([redacted])"
}

class ExamsViewModel(
    private val source: TimetableSource,
    private val auth: StateFlow<AuthState>,
    generation: StateFlow<Long>,
    online: Flow<Boolean>,
    private val now: () -> LocalDateTime = { LocalDateTime.now(campusZone) },
) : ViewModel() {
    private val mutable = MutableStateFlow(ExamsUiState(now = now()))
    val state = mutable.asStateFlow()
    private var identity: Pair<String?, Long>? = null
    private var visible = false
    private var connected = false
    private var blocked = true
    private var sequence = 0L
    private var request: Job? = null
    private var ticker: Job? = null

    init {
        viewModelScope.launch {
            combine(auth, generation, online) { a, g, net -> Triple(a, g, net) }.collect { (a, version, net) ->
                val next = a.profile?.account to version
                val changed = identity != next
                val recovered = (!connected && net) || (blocked && a.status == AuthStatus.AUTHENTICATED)
                connected = net
                blocked = a.status in listOf(AuthStatus.LOADING, AuthStatus.SIGNING_IN, AuthStatus.VERIFICATION_REQUIRED)
                if (changed) {
                    val retainedTerm = mutable.value.selectedTerm.takeIf { next.first != null && identity?.first == next.first }
                    cancelRequest()
                    identity = next
                    mutable.value = ExamsUiState(now = now(), selectedTerm = retainedTerm)
                }
                if (!net || blocked || next.first == null) cancelRequest()
                mutable.value = mutable.value.copy(offline = !net, status = when {
                    a.status == AuthStatus.VERIFICATION_REQUIRED -> TimetableStatus.VERIFICATION_REQUIRED
                    blocked -> TimetableStatus.LOADING
                    next.first == null -> TimetableStatus.SIGNED_OUT
                    mutable.value.exams != null -> TimetableStatus.READY
                    else -> mutable.value.status
                })
                if (visible && !blocked && next.first != null && (changed || recovered)) load()
            }
        }
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        ticker?.cancel()
        if (!value) { cancelRequest(); return }
        mutable.value = mutable.value.copy(now = now())
        ticker = viewModelScope.launch {
            while (isActive) {
                mutable.value = mutable.value.copy(now = now())
                delay(1_000)
            }
        }
        load()
    }

    fun selectTerm(term: AcademicTerm) {
        if (term.key == mutable.value.selectedTerm?.key) return
        cancelRequest()
        mutable.value = mutable.value.copy(selectedTerm = term, exams = null, message = null)
        load()
    }

    fun refresh() { if (request?.isActive != true) load(userInitiated = true) }

    private fun cancelRequest() {
        sequence++
        request?.cancel()
        request = null
        mutable.value = mutable.value.copy(refreshing = false)
    }

    private fun load(userInitiated: Boolean = false) {
        if (!visible || blocked) return
        val owner = identity ?: return
        val account = owner.first ?: return
        cancelRequest()
        val captured = sequence
        val selected = mutable.value.selectedTerm
        fun current() = captured == sequence && identity == owner && auth.value.profile?.account == account
        fun show(data: TimetableData) {
            if (!current() || data.account != account) return
            mutable.value = mutable.value.copy(exams = data.exams, catalog = data.catalog,
                selectedTerm = data.term, status = TimetableStatus.READY)
        }
        request = viewModelScope.launch {
            mutable.value = mutable.value.copy(refreshing = connected && userInitiated, message = null,
                status = if (mutable.value.exams == null) TimetableStatus.LOADING else mutable.value.status)
            try {
                // A cache's last browsed term may differ from the school's current term.
                val latest = source.cached(account, selected)
                if (!current()) return@launch
                val initialTerm = selected ?: latest?.catalog?.current
                val cached = if (latest?.term?.key == initialTerm?.key) latest
                    else initialTerm?.let { source.cached(account, it) }
                if (!current()) return@launch
                latest?.catalog?.let { mutable.value = mutable.value.copy(catalog = it, selectedTerm = initialTerm) }
                cached?.let(::show)
                if (!connected) {
                    mutable.value = mutable.value.copy(status = if (mutable.value.exams == null) TimetableStatus.ERROR else TimetableStatus.READY,
                        message = if (mutable.value.exams == null) "尚无离线考试安排，请联网后刷新" else "离线显示最近同步的考试安排")
                    return@launch
                }
                val catalog = source.catalog()
                if (!current()) return@launch
                val term = selected ?: catalog.current
                mutable.value = mutable.value.copy(catalog = catalog, selectedTerm = term,
                    exams = mutable.value.exams.takeIf { mutable.value.selectedTerm?.key == term.key })
                if (cached?.term?.key != term.key) source.cached(account, term)?.let(::show)
                if (!current()) return@launch
                val data = source.refresh(term, catalog)
                if (!current()) return@launch
                show(data)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!current()) return@launch
                val status = when {
                    e is AuthException && e.kind == AuthFailure.VERIFICATION -> TimetableStatus.VERIFICATION_REQUIRED
                    e is AuthException && e.kind in listOf(AuthFailure.EXPIRED, AuthFailure.CREDENTIALS) -> TimetableStatus.LOGIN_REQUIRED
                    e is TimetableException && e.kind == TimetableFailure.AUTHORIZATION -> TimetableStatus.LOGIN_REQUIRED
                    mutable.value.exams != null -> TimetableStatus.READY
                    else -> TimetableStatus.ERROR
                }
                mutable.value = mutable.value.copy(status = status,
                    message = if (mutable.value.exams != null) "考试更新失败，显示最近同步的安排" else "无法读取考试安排，请重试")
            } finally { if (current()) mutable.value = mutable.value.copy(refreshing = false) }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ExamsViewModel(
                container.timetables, container.auth.state, container.auth.sessionGeneration, container.network.online,
            ) as T
        }
    }
}
