package moe.nepnep.hduhelper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.nepnep.hduhelper.AppContainer
import moe.nepnep.hduhelper.data.auth.*
import moe.nepnep.hduhelper.data.schedule.*
import moe.nepnep.hduhelper.data.timetable.*

data class ScheduleUiState(
    val date: LocalDate,
    val today: LocalDate,
    val storage: ScheduleStorageState = ScheduleStorageState(),
    val courses: TimetableData? = null,
    val courseStatus: TimetableStatus = TimetableStatus.LOADING,
    val courseMessage: String? = null,
    val refreshing: Boolean = false,
    val offline: Boolean = false,
    val detailKey: String? = null,
    val courseDetailId: String? = null,
    val error: String? = null,
    val reminderStatus: String? = null,
) {
    val occurrences: List<ScheduleOccurrence> get() = storage.book.series.flatMap { ScheduleRules.occurrences(it, date) }
    val detail: ScheduleOccurrence? get() = occurrences.firstOrNull { it.key == detailKey }
}

data class ScheduleEditorState(
    val editor: ScheduleEditor,
    val draft: ScheduleEvent = editor.initial,
    val saving: Boolean = false,
    val error: String? = null,
)

class ScheduleViewModel(
    private val repository: ScheduleRepository,
    private val source: TimetableSource,
    private val auth: StateFlow<AuthState>,
    private val generation: StateFlow<Long>,
    online: Flow<Boolean>,
    private val reminders: ScheduleReminderScheduler,
    private val now: () -> LocalDateTime = { LocalDateTime.now(campusZone) },
) : ViewModel() {
    private val mutable = MutableStateFlow(ScheduleUiState(now().toLocalDate(), now().toLocalDate()))
    val state = mutable.asStateFlow()
    private val mutableEditor = MutableStateFlow<ScheduleEditorState?>(null)
    val editor = mutableEditor.asStateFlow()
    private var visible = false
    private var connected = false
    private var blocked = true
    private var identity: Pair<String?, Long>? = null
    private val readyIdentity = MutableStateFlow<Pair<String?, Long>?>(null)
    private var request: Job? = null
    private var courseLink: Job? = null
    private var ticker: Job? = null
    private var sequence = 0L

    init {
        viewModelScope.launch { repository.state.collect { storage -> mutable.update { it.copy(storage = storage) } } }
        retryStorage()
        viewModelScope.launch {
            combine(auth, generation, online) { a, g, net -> Triple(a, g, net) }.collect { (a, g, net) ->
                val owner = a.profile?.account to g
                val changed = identity != owner
                val resume = (!connected && net) || (blocked && a.status !in blockedStatuses)
                connected = net
                blocked = a.status in blockedStatuses
                if (changed) { identity = owner; cancelCourses(); mutable.update { it.copy(courses = null, courseMessage = null, courseDetailId = null) } }
                mutable.update { it.copy(offline = !net, courseStatus = when {
                    a.status == AuthStatus.VERIFICATION_REQUIRED -> TimetableStatus.VERIFICATION_REQUIRED
                    owner.first == null && !blocked -> TimetableStatus.SIGNED_OUT
                    blocked -> if (it.courses == null) TimetableStatus.LOADING else it.courseStatus
                    it.courses != null -> TimetableStatus.READY
                    else -> TimetableStatus.LOADING
                }) }
                if (blocked || !net) cancelCourses()
                if (visible && !blocked && owner.first != null && (changed || resume)) loadCourses()
                readyIdentity.value = owner
            }
        }
    }

    fun retryStorage() { viewModelScope.launch { try { repository.load() } catch (_: Exception) { /* State carries the retryable error. */ } } }
    fun selectDate(date: LocalDate) { mutable.update { it.copy(date = date, detailKey = null, courseDetailId = null) } }
    fun today() = selectDate(now().toLocalDate())
    fun showDetail(key: String?) { mutable.update { it.copy(detailKey = key, courseDetailId = null, error = null) } }
    fun foreground() {
        val today = now().toLocalDate()
        mutable.update { it.copy(today = today, date = if (it.date == it.today) today else it.date, reminderStatus = reminders.status()) }
        reminders.reschedule()
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        ticker?.cancel()
        if (!value) { cancelCourses(); return }
        foreground()
        ticker = viewModelScope.launch {
            while (isActive) { delay(30_000); val today = now().toLocalDate(); if (today != mutable.value.today) { foreground(); loadCourses() } }
        }
        loadCourses()
    }

    fun refreshCourses() = loadCourses(manual = true)
    private fun cancelCourses() { sequence++; request?.cancel(); request = null; mutable.update { it.copy(refreshing = false) } }
    private fun loadCourses(manual: Boolean = false) {
        if (!visible || blocked) return
        val account = auth.value.profile?.account ?: return
        cancelCourses()
        val version = sequence
        val owner = identity
        fun current() = version == sequence && owner == identity && auth.value.profile?.account == account
        fun show(data: TimetableData) { if (current()) mutable.update { it.copy(courses = data, courseStatus = TimetableStatus.READY) } }
        request = viewModelScope.launch {
            mutable.update { it.copy(refreshing = manual && connected, courseMessage = null) }
            try {
                val latest = source.cached(account)
                val cached = if (latest?.term?.key == latest?.catalog?.current?.key) latest else latest?.catalog?.current?.let { source.cached(account, it) }
                if (!current()) return@launch
                cached?.let(::show)
                if (!connected) {
                    mutable.update { it.copy(courseStatus = if (it.courses == null) TimetableStatus.ERROR else TimetableStatus.READY,
                        courseMessage = if (it.courses == null) "尚无离线课表，请联网后刷新" else "离线课程 · 显示最近成功更新的数据") }
                    return@launch
                }
                val catalog = source.catalog()
                if (!current()) return@launch
                if (mutable.value.courses?.term?.key != catalog.current.key) {
                    mutable.update { it.copy(courses = null, courseStatus = TimetableStatus.LOADING) }
                    source.cached(account, catalog.current)?.let(::show)
                }
                val fresh = source.refresh(catalog.current, catalog)
                if (!current()) return@launch
                show(fresh)
                mutable.update { it.copy(courseMessage = if (manual) fresh.warnings.takeIf { w -> w.isNotEmpty() }?.joinToString("；") else null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!current()) return@launch
                val status = when {
                    e is AuthException && e.kind == AuthFailure.VERIFICATION -> TimetableStatus.VERIFICATION_REQUIRED
                    e is AuthException && e.kind in listOf(AuthFailure.CREDENTIALS, AuthFailure.EXPIRED) -> TimetableStatus.LOGIN_REQUIRED
                    e is TimetableException && e.kind == TimetableFailure.AUTHORIZATION -> TimetableStatus.LOGIN_REQUIRED
                    mutable.value.courses != null -> TimetableStatus.READY
                    else -> TimetableStatus.ERROR
                }
                mutable.update { it.copy(courseStatus = status, courseMessage = if (!manual && status == TimetableStatus.READY) null else "课程读取失败，请重试") }
            } finally { if (current()) mutable.update { it.copy(refreshing = false) } }
        }
    }

    fun add() {
        mutableEditor.value = ScheduleEditorState(ScheduleEditor(null, null, newScheduleEvent(state.value.date, now())))
    }
    fun edit(occurrence: ScheduleOccurrence, onlyThis: Boolean) {
        val series = state.value.storage.book.series.firstOrNull { it.id == occurrence.seriesId } ?: return
        val single = onlyThis && series.event.repeat != ScheduleRepeat.NEVER
        mutableEditor.value = ScheduleEditorState(ScheduleEditor(series.id, if (single) occurrence.originalDate else null,
            if (single) occurrence.event.copy(repeat = ScheduleRepeat.NEVER) else series.event))
        showDetail(null)
    }
    fun updateDraft(value: ScheduleEvent) { mutableEditor.update { if (it?.saving == false) it.copy(draft = value, error = null) else it } }
    fun discardEditor() { if (editor.value?.saving != true) mutableEditor.value = null }
    fun needsExceptionReset(): Boolean {
        val edit = editor.value ?: return false
        val series = state.value.storage.book.series.firstOrNull { it.id == edit.editor.seriesId } ?: return false
        return edit.editor.originalDate == null && series.exceptions.isNotEmpty() &&
            (edit.draft.repeat != series.event.repeat || edit.draft.startTime.toLocalDate() != series.event.startTime.toLocalDate())
    }
    fun save(clearExceptions: Boolean = false, onSaved: () -> Unit) {
        val edit = editor.value?.takeIf { !it.saving } ?: return
        val error = edit.draft.validationError()
        if (error != null) { mutableEditor.value = edit.copy(error = error); return }
        mutableEditor.value = edit.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                repository.save(edit.editor, edit.draft, clearExceptions)
                selectDate(edit.draft.startTime.toLocalDate())
                mutableEditor.value = null
                onSaved()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableEditor.value = edit.copy(error = "日程保存失败，请重试") }
        }
    }
    fun delete(occurrence: ScheduleOccurrence, onlyThis: Boolean) {
        viewModelScope.launch {
            try {
                repository.delete(occurrence.seriesId, if (onlyThis) occurrence.originalDate else null)
                showDetail(null)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(error = "删除失败，请重试") } }
        }
    }
    fun openCourseNotification(accountKey: String, termKey: String, meetingId: String, date: LocalDate) {
        courseLink?.cancel()
        courseLink = viewModelScope.launch {
            auth.first { it.status !in setOf(AuthStatus.LOADING, AuthStatus.SIGNING_IN) }
            val account = auth.value.profile?.account
            if (account == null || moe.nepnep.hduhelper.data.notifications.CourseReminderRules.hash(account) != accountKey) {
                mutable.update { it.copy(date = date, courseDetailId = null, error = "该课程属于其他账号或登录已失效") }
                return@launch
            }
            val ownerGeneration = generation.value
            // Wait until the auth observer has cleared the previous account's UI state.
            readyIdentity.first { it != null && it == (auth.value.profile?.account to generation.value) }
            if (auth.value.profile?.account != account || generation.value != ownerGeneration) return@launch
            try {
                val latest = source.cached(account)
                val data = latest?.let { if (it.term.key == it.catalog.current.key) it else source.cached(account, it.catalog.current) }
                if (auth.value.profile?.account != account || generation.value != ownerGeneration) return@launch
                val match = data?.takeIf { it.term.key == termKey }?.let { ScheduleRules.courses(it, date) }?.firstOrNull { it.id == meetingId }
                mutable.update { it.copy(date = date, courses = data, detailKey = null, courseDetailId = match?.id,
                    error = if (match == null) "该课程已修改或不属于当前学期，请刷新课表" else null) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (auth.value.profile?.account == account && generation.value == ownerGeneration) mutable.update { it.copy(error = "课程读取失败，请重试") }
            }
        }
    }

    fun openNotification(seriesId: String, originalDate: LocalDate, date: LocalDate) {
        viewModelScope.launch {
            try {
                repository.load()
                val series = repository.state.value.book.series.firstOrNull { it.id == seriesId }
                val match = series?.let { ScheduleRules.occurrences(it, date) }?.firstOrNull { it.originalDate == originalDate }
                mutable.update { it.copy(date = date, detailKey = match?.key, error = if (match == null) "该日程已修改或删除" else null) }
            } catch (_: Exception) { mutable.update { it.copy(error = "日程读取失败，请重试") } }
        }
    }

    companion object {
        private val blockedStatuses = setOf(AuthStatus.LOADING, AuthStatus.SIGNING_IN, AuthStatus.VERIFICATION_REQUIRED)
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ScheduleViewModel(container.schedules, container.timetables,
                container.auth.state, container.auth.sessionGeneration, container.network.online, container.scheduleReminders) as T
        }
    }
}
