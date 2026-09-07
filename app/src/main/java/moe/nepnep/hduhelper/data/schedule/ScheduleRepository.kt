package moe.nepnep.hduhelper.data.schedule

import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ScheduleStorageState(val book: ScheduleBook = ScheduleBook(), val loaded: Boolean = false, val error: String? = null)

class ScheduleRepository(private val store: ScheduleStore, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(ScheduleStorageState())
    val state = mutable.asStateFlow()
    var onChanged: () -> Unit = {}

    suspend fun load() = withContext(io) {
        mutex.withLock {
            if (!mutable.value.loaded) try {
                mutable.value = ScheduleStorageState(store.load(), loaded = true)
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(error = "本地日程读取失败，请重试")
                throw e
            }
        }
    }

    private suspend fun change(transform: (ScheduleBook) -> ScheduleBook) = withContext(io) {
        load()
        mutex.withLock {
            val old = mutable.value.book
            val next = transform(old).copy(revision = old.revision + 1)
            store.save(next)
            mutable.value = ScheduleStorageState(next, loaded = true)
        }
        onChanged()
    }

    suspend fun save(editor: ScheduleEditor, event: ScheduleEvent, clearExceptions: Boolean = false) {
        require(event.validationError() == null) { event.validationError().orEmpty() }
        change { book ->
            if (editor.seriesId == null) book.copy(series = book.series + ScheduleSeries(event = event.copy(title = event.title.trim())))
            else {
                val old = book.series.firstOrNull { it.id == editor.seriesId } ?: error("日程已被删除")
                val next = if (editor.originalDate != null) {
                    require(event.repeat == ScheduleRepeat.NEVER)
                    old.copy(exceptions = old.exceptions.filterNot { it.originalDate == editor.originalDate.toString() } +
                        ScheduleException(editor.originalDate.toString(), event.copy(title = event.title.trim())))
                } else {
                    val resets = old.event.repeat != event.repeat || old.event.startTime.toLocalDate() != event.startTime.toLocalDate()
                    require(!resets || old.exceptions.isEmpty() || clearExceptions) { "请确认清除单次修改" }
                    old.copy(event = event.copy(title = event.title.trim()), exceptions = if (resets) emptyList() else old.exceptions)
                }
                book.copy(series = book.series.map { if (it.id == old.id) next else it })
            }
        }
    }

    suspend fun delete(id: String, originalDate: LocalDate?) = change { book ->
        book.copy(series = if (originalDate == null) book.series.filterNot { it.id == id } else book.series.map {
            if (it.id == id) it.copy(exceptions = it.exceptions.filterNot { ex -> ex.originalDate == originalDate.toString() } + ScheduleException(originalDate.toString())) else it
        })
    }

    /** Persist deduplication before delivering; stale alarms cannot notify after an edit/delete. */
    suspend fun claimReminder(revision: Long, at: Long): List<ScheduleOccurrence> = withContext(io) {
        load()
        mutex.withLock {
            val book = mutable.value.book
            if (book.revision != revision || at <= book.deliveredThrough) return@withLock emptyList()
            val occurrences = book.series.mapNotNull { ScheduleRules.nextReminder(it, at - 1) }
                .filter { ScheduleRules.reminderTime(it.event) == at }
            if (occurrences.isNotEmpty()) {
                val next = book.copy(deliveredThrough = at)
                store.save(next)
                mutable.value = ScheduleStorageState(next, loaded = true)
            }
            occurrences
        }
    }
}
