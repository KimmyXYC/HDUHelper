package moe.nepnep.hduhelper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import moe.nepnep.hduhelper.AppContainer
import moe.nepnep.hduhelper.data.auth.AuthException
import moe.nepnep.hduhelper.data.auth.AuthFailure
import moe.nepnep.hduhelper.data.auth.AuthState
import moe.nepnep.hduhelper.data.auth.AuthStatus
import moe.nepnep.hduhelper.data.settings.AppSettings
import moe.nepnep.hduhelper.data.timetable.*

enum class TimetableStatus { SIGNED_OUT, LOADING, READY, ERROR, LOGIN_REQUIRED, VERIFICATION_REQUIRED }
data class TimetableUiState(
    val status: TimetableStatus = TimetableStatus.SIGNED_OUT,
    val data: TimetableData? = null,
    val catalog: TimetableCatalog? = null,
    val selectedTerm: AcademicTerm? = null,
    val week: Int = 1,
    val today: LocalDate = LocalDate.now(campusZone),
    val settings: TimetableSettings = TimetableSettings(),
    val selectedCampus: String? = null,
    val refreshing: Boolean = false,
    val offline: Boolean = false,
    val message: String? = null,
) {
    val clock: CampusClock? get() = data?.let { d -> d.clocks.firstOrNull { it.id == (selectedCampus ?: TimetableRules.automaticCampus(d)) } }
    override fun toString() = "TimetableUiState([redacted])"
}

class TimetableViewModel(
    private val source: TimetableSource,
    private val auth: StateFlow<AuthState>,
    generation: StateFlow<Long>,
    online: Flow<Boolean>,
    settings: StateFlow<AppSettings>,
    private val readCampus: (String, String) -> String?,
    private val writeSettings: (TimetableSettings) -> Unit,
    private val writeCampus: (String, String, String?) -> Unit,
    private val today: () -> LocalDate = { LocalDate.now(campusZone) },
) : ViewModel() {
    private val mutable = MutableStateFlow(TimetableUiState(today = today(), settings = settings.value.timetable))
    val state = mutable.asStateFlow()
    private var visible = false
    private var connected = false
    private var blocked = true
    private var request: Job? = null
    private var sequence = 0L
    private var identity: Pair<String?, Long>? = null
    private var defaultPending = true

    init {
        viewModelScope.launch {
            combine(auth, generation, online, settings) { a, g, net, prefs -> Gate(a, g, net, prefs) }.collect { gate ->
                val nextIdentity = gate.auth.profile?.account to gate.generation
                val changed = identity != nextIdentity
                val wasConnected = connected
                val wasBlocked = blocked
                connected = gate.online
                blocked = gate.auth.status in listOf(AuthStatus.LOADING, AuthStatus.SIGNING_IN, AuthStatus.VERIFICATION_REQUIRED)
                if (changed) {
                    identity = nextIdentity
                    cancel()
                    defaultPending = true
                    mutable.value = TimetableUiState(today = today(), settings = gate.settings.timetable)
                }
                val old = mutable.value
                mutable.value = old.copy(settings = gate.settings.timetable, offline = !connected,
                    selectedCampus = old.data?.let { d -> readCampus(d.account, d.term.key)?.takeIf { id -> d.clocks.any { it.id == id } } },
                    status = when {
                        gate.auth.status == AuthStatus.VERIFICATION_REQUIRED -> TimetableStatus.VERIFICATION_REQUIRED
                        blocked -> if (old.data == null) TimetableStatus.LOADING else old.status
                        nextIdentity.first == null -> TimetableStatus.SIGNED_OUT
                        old.data != null -> TimetableStatus.READY
                        else -> old.status
                    })
                if (blocked || !connected) cancel()
                if (visible && !blocked && nextIdentity.first != null && (changed || (connected && !wasConnected) || (wasBlocked && !blocked))) load(defaultPending)
            }
        }
    }
    private data class Gate(val auth: AuthState, val generation: Long, val online: Boolean, val settings: AppSettings)

    fun setVisible(value: Boolean, resetToDefault: Boolean = true) {
        if (visible == value) return
        visible = value
        if (!value) { cancel(); return }
        defaultPending = resetToDefault || mutable.value.selectedTerm == null
        mutable.value = mutable.value.copy(today = today())
        if (!blocked && auth.value.profile != null) load(defaultPending)
    }

    fun refresh() { if (request?.isActive != true) load(defaultPending, userInitiated = true) }
    fun selectTerm(term: AcademicTerm) {
        if (term.key == mutable.value.selectedTerm?.key) return
        defaultPending = false
        mutable.value = mutable.value.copy(selectedTerm = term, data = null, message = null)
        load(false, term, resetWeek = true)
    }
    fun selectWeek(week: Int) {
        val data = mutable.value.data ?: return
        if (week in TimetableRules.availableWeeks(data.weeks, mutable.value.today)) mutable.value = mutable.value.copy(week = week)
    }
    fun goToDefaultWeek() { mutable.value.data?.let { selectWeek(TimetableRules.defaultWeek(it.weeks, mutable.value.today)) } }
    fun setSettings(value: TimetableSettings) = writeSettings(value)
    fun setCampus(value: String?) { mutable.value.data?.let { writeCampus(it.account, it.term.key, value) } }

    private fun cancel() {
        sequence++
        request?.cancel()
        request = null
        mutable.value = mutable.value.copy(refreshing = false)
    }

    private fun load(defaultTerm: Boolean, requested: AcademicTerm? = mutable.value.selectedTerm, resetWeek: Boolean = defaultTerm, userInitiated: Boolean = false) {
        if (!visible || blocked) return
        val account = auth.value.profile?.account ?: return
        cancel()
        val operation = sequence
        val owner = identity
        var resetDisplayedWeek = resetWeek
        fun current() = sequence == operation && identity == owner && auth.value.profile?.account == account
        fun show(data: TimetableData) {
            if (!current()) return
            val allowed = TimetableRules.availableWeeks(data.weeks, mutable.value.today)
            val week = if (resetDisplayedWeek || mutable.value.data?.term?.key != data.term.key || mutable.value.week !in allowed) TimetableRules.defaultWeek(data.weeks, mutable.value.today) else mutable.value.week
            mutable.value = mutable.value.copy(data = data, catalog = data.catalog, selectedTerm = data.term, week = week,
                selectedCampus = readCampus(account, data.term.key)?.takeIf { id -> data.clocks.any { it.id == id } }, status = TimetableStatus.READY)
            resetDisplayedWeek = false
        }
        request = viewModelScope.launch {
            mutable.value = mutable.value.copy(refreshing = connected && userInitiated, message = null, status = if (mutable.value.data == null) TimetableStatus.LOADING else mutable.value.status)
            try {
                val latest = if (defaultTerm) source.cached(account) else null
                if (!current()) return@launch
                val cachedTerm = if (defaultTerm) latest?.catalog?.current else requested
                val cached = if (latest != null && latest.term.key == cachedTerm?.key) latest else cachedTerm?.let { source.cached(account, it) }
                if (!current()) return@launch
                if (defaultTerm && mutable.value.data?.term?.key != cachedTerm?.key) mutable.value = mutable.value.copy(data = null)
                if (cached != null) show(cached)
                if (!connected) {
                    mutable.value = mutable.value.copy(status = if (mutable.value.data == null) TimetableStatus.ERROR else TimetableStatus.READY,
                        message = if (mutable.value.data == null) "尚无离线课表，请联网后刷新" else "离线课表 · 显示最近成功更新的数据")
                    return@launch
                }
                val catalog = source.catalog()
                if (!current()) return@launch
                val term = if (defaultTerm) catalog.current else requested ?: catalog.current
                mutable.value = mutable.value.copy(catalog = catalog, selectedTerm = mutable.value.data?.term ?: term)
                if (mutable.value.data?.term?.key != term.key) {
                    source.cached(account, term)?.let(::show)
                }
                val silent = !userInitiated && mutable.value.data != null
                val data = source.refresh(term, catalog)
                if (!current()) return@launch
                show(data)
                defaultPending = false
                mutable.value = mutable.value.copy(message = data.warnings.takeIf { !silent && it.isNotEmpty() }?.joinToString("；"), offline = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!current()) return@launch
                if (e is AuthException && e.kind == AuthFailure.CANCELLED) return@launch
                val status = when {
                    e is AuthException && e.kind == AuthFailure.VERIFICATION -> TimetableStatus.VERIFICATION_REQUIRED
                    e is AuthException && e.kind in listOf(AuthFailure.EXPIRED, AuthFailure.CREDENTIALS) -> TimetableStatus.LOGIN_REQUIRED
                    e is TimetableException && e.kind == TimetableFailure.AUTHORIZATION -> TimetableStatus.LOGIN_REQUIRED
                    mutable.value.data != null -> TimetableStatus.READY
                    else -> TimetableStatus.ERROR
                }
                mutable.value = mutable.value.copy(status = status, message = if (!userInitiated && status == TimetableStatus.READY) null else when (e) {
                    is AuthException -> e.message
                    is TimetableException -> e.message
                    else -> "无法读取课表，请重试"
                })
            } finally { if (current()) mutable.value = mutable.value.copy(refreshing = false) }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TimetableViewModel(container.timetables, container.auth.state,
                container.auth.sessionGeneration, container.network.online, container.settings.state,
                container.settings::timetableCampus, container.settings::setTimetable, container.settings::setTimetableCampus) as T
        }
    }
}
