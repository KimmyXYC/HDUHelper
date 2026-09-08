package moe.nepnep.hduhelper.ui

import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.nepnep.hduhelper.AppContainer
import moe.nepnep.hduhelper.data.auth.*
import moe.nepnep.hduhelper.data.electric.*

data class ElectricUiState(
    val status: TimetableStatus = TimetableStatus.SIGNED_OUT,
    val loading: Boolean = false, val bindingKnown: Boolean = false,
    val binding: ElectricBinding? = null, val balance: ElectricBalance? = null,
    val history: ElectricHistory? = null, val message: String? = null,
    val editing: Boolean = false, val buildings: List<ElectricOption> = emptyList(),
    val floors: List<ElectricOption> = emptyList(), val rooms: List<ElectricOption> = emptyList(),
    val building: ElectricOption? = null, val floor: ElectricOption? = null, val room: ElectricOption? = null,
) {
    val selectionLabel get() = listOfNotNull(building?.name, floor?.name, room?.name).joinToString(" ")
}
class ElectricViewModel(private val source: ElectricSource, private val auth: StateFlow<AuthState>,
    generation: StateFlow<Long>) : ViewModel() {
    private val mutable = MutableStateFlow(ElectricUiState())
    val state = mutable.asStateFlow()
    private var owner: Pair<String?, Long>? = null
    private var visible = false
    private var job: Job? = null
    private var sequence = 0L
    init {
        viewModelScope.launch {
            combine(auth, generation) { a, g -> a to g }.collect { (a, g) ->
                val next = a.profile?.account to g
                val changed = owner != next
                if (changed) { cancelRequest(); owner = next; mutable.value = ElectricUiState() }
                val blocked = when {
                    a.status == AuthStatus.VERIFICATION_REQUIRED -> TimetableStatus.VERIFICATION_REQUIRED
                    a.status in listOf(AuthStatus.LOADING, AuthStatus.SIGNING_IN) -> TimetableStatus.LOADING
                    a.profile == null -> TimetableStatus.SIGNED_OUT
                    else -> null
                }
                if (blocked != null) { cancelRequest(); mutable.value = mutable.value.copy(status = blocked) }
                else if (visible && (changed || mutable.value.status in listOf(TimetableStatus.LOADING, TimetableStatus.VERIFICATION_REQUIRED))) refresh()
            }
        }
    }
    private fun cancelRequest() { sequence++; job?.cancel(); job = null; mutable.value = mutable.value.copy(loading = false) }
    fun setVisible(value: Boolean) { if (visible == value) return; visible = value; if (value) refresh() else cancelRequest() }
    private fun launch(block: suspend () -> Unit) {
        if (!visible || auth.value.profile == null || auth.value.status in listOf(AuthStatus.LOADING, AuthStatus.SIGNING_IN, AuthStatus.VERIFICATION_REQUIRED)) return
        cancelRequest(); val captured = sequence; val account = owner
        job = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, message = null, status = TimetableStatus.READY)
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (captured == sequence && account == owner) fail(e)
            } finally { if (captured == sequence && account == owner) mutable.value = mutable.value.copy(loading = false) }
        }
    }
    private fun fail(e: Exception) {
        val status = when {
            e is AuthException && e.kind == AuthFailure.VERIFICATION || e is ElectricException && e.verification -> TimetableStatus.VERIFICATION_REQUIRED
            e is AuthException && e.kind in listOf(AuthFailure.EXPIRED, AuthFailure.CREDENTIALS) || e is ElectricException && e.loginRequired -> TimetableStatus.LOGIN_REQUIRED
            else -> TimetableStatus.READY
        }
        mutable.value = mutable.value.copy(status = status, message = "电费数据未更新，请重试")
    }
    private suspend fun active() { currentCoroutineContext().ensureActive() }
    private suspend fun load() {
        val binding = source.binding(); active()
        val changed = mutable.value.binding?.roomId != binding?.roomId
        mutable.value = mutable.value.copy(binding = binding, bindingKnown = true,
            balance = mutable.value.balance.takeUnless { changed || binding == null },
            history = mutable.value.history.takeUnless { changed || binding == null })
        if (binding == null) return
        var failed = false
        try { val balance = source.balance(); active(); mutable.value = mutable.value.copy(balance = balance) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (requiresLogin(e)) throw e; failed = true }
        try { val history = source.history(); active(); mutable.value = mutable.value.copy(history = history) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (requiresLogin(e)) throw e; failed = true }
        if (failed) mutable.value = mutable.value.copy(message = "部分数据未更新，保留已获取的数据，请重试")
    }
    private fun requiresLogin(e: Exception) = e is AuthException || e is ElectricException && (e.loginRequired || e.verification)
    fun refresh() { if (job?.isActive != true) launch { load() } }
    fun edit() = launch {
        mutable.value = mutable.value.copy(editing = true, building = null, floor = null, room = null, floors = emptyList(), rooms = emptyList())
        val options = source.buildings(); active(); mutable.value = mutable.value.copy(buildings = options)
    }
    fun closeEditor() { cancelRequest(); mutable.value = mutable.value.copy(editing = false) }
    fun building(value: ElectricOption) = launch {
        mutable.value = mutable.value.copy(building = value, floor = null, room = null, floors = emptyList(), rooms = emptyList())
        val options = source.floors(value.id); active(); mutable.value = mutable.value.copy(floors = options)
    }
    fun floor(value: ElectricOption) = launch {
        mutable.value = mutable.value.copy(floor = value, room = null, rooms = emptyList())
        val options = source.rooms(value.id); active(); mutable.value = mutable.value.copy(rooms = options)
    }
    fun room(value: ElectricOption) { if (!mutable.value.loading) mutable.value = mutable.value.copy(room = value) }
    fun bind() { val room = mutable.value.room ?: return; mutate(room.id) }
    fun unbind() = mutate(null)
    private fun mutate(room: Long?) = launch {
        try { if (room == null) source.unbind() else source.bind(room) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (requiresLogin(e)) throw e
            // Timeout may follow a successful server write. Read before offering another submission.
            val actual = try { source.binding() } catch (read: Exception) {
                if (read is CancellationException) throw read
                active(); mutable.value = mutable.value.copy(editing = false, bindingKnown = false, binding = null,
                    balance = null, history = null, message = "绑定结果尚未确认，请刷新后再操作")
                return@launch
            }
            active()
            if (actual?.roomId != room) { load(); mutable.value = mutable.value.copy(message = "绑定操作未完成，请重试"); return@launch }
        }
        active(); mutable.value = mutable.value.copy(editing = false, balance = null, history = null)
        load()
    }
    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ElectricViewModel(container.electric, container.auth.state, container.auth.sessionGeneration) as T
        }
    }
}
