package moe.nepnep.hduhelper.ui

import androidx.lifecycle.ViewModelStore
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import moe.nepnep.hduhelper.data.auth.*
import moe.nepnep.hduhelper.data.schedule.*
import moe.nepnep.hduhelper.data.timetable.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModelTest {
    private val event = ScheduleEvent("本机日程", start = "2026-09-14T10:00", end = "2026-09-14T11:00")
    private class Store(var book: ScheduleBook) : ScheduleStore {
        var failure = false
        override fun load() = book
        override fun save(book: ScheduleBook) { check(!failure); this.book = book }
    }
    private class Reminders : ScheduleReminderScheduler {
        override fun reschedule() {}
        override fun status(): String? = null
    }
    private class Source : TimetableSource {
        var cache: TimetableData? = data(meeting("a"))
        var catalogGate: CompletableDeferred<Unit>? = null
        var fetchGate: CompletableDeferred<Unit>? = null
        var requested: AcademicTerm? = null
        override suspend fun cached(account: String, term: AcademicTerm?) = cache?.takeIf { it.account == account && (term == null || it.term.key == term.key) }
        override suspend fun catalog(): TimetableCatalog { catalogGate?.await(); return catalog }
        override suspend fun refresh(term: AcademicTerm, catalog: TimetableCatalog): TimetableData {
            requested = term; fetchGate?.await(); return data(meeting("fresh"))
        }
        override fun clear() {}
    }

    @Test fun currentSemesterCacheIsImmediateAndSwitchAccountDropsCoursesOnly() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val store = Store(ScheduleBook(listOf(ScheduleSeries("local", event))))
            val source = Source().apply { catalogGate = CompletableDeferred() }
            val auth = MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED, UserProfile("student", "测试")))
            val model = ScheduleViewModel(ScheduleRepository(store, StandardTestDispatcher(testScheduler)), source, auth,
                MutableStateFlow(1L), MutableStateFlow(true), Reminders(), { LocalDateTime.parse("2026-09-14T09:00") })
            owner.put("schedule", model); model.setVisible(true); runCurrent()
            assertEquals("a", model.state.value.courses!!.meetings.single().id)
            assertEquals(1, model.state.value.occurrences.size)
            source.catalogGate!!.complete(Unit); runCurrent()
            assertEquals(catalog.current, source.requested)
            assertEquals("fresh", model.state.value.courses!!.meetings.single().id)
            auth.value = AuthState(AuthStatus.SIGNED_OUT); runCurrent()
            assertNull(model.state.value.courses)
            assertEquals(TimetableStatus.SIGNED_OUT, model.state.value.courseStatus)
            assertEquals(1, model.state.value.occurrences.size)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }

    @Test fun saveFailureRetainsDraftAndNotificationSelectsActualOccurrence() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val store = Store(ScheduleBook())
            val repo = ScheduleRepository(store, StandardTestDispatcher(testScheduler))
            val model = ScheduleViewModel(repo, Source(), MutableStateFlow(AuthState(AuthStatus.SIGNED_OUT)),
                MutableStateFlow(1L), MutableStateFlow(false), Reminders(), { LocalDateTime.parse("2026-09-14T09:00") })
            owner.put("schedule", model); model.setVisible(true); runCurrent()
            model.add(); model.updateDraft(event)
            var saved = false
            store.failure = true
            model.save { saved = true }; runCurrent()
            assertFalse(saved); assertEquals(event, model.editor.value!!.draft); assertNotNull(model.editor.value!!.error)
            store.failure = false
            model.save { saved = true }; runCurrent()
            assertTrue(saved); assertNull(model.editor.value)
            val series = repo.state.value.book.series.single()
            model.openNotification(series.id, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 14)); runCurrent()
            assertEquals("本机日程", model.state.value.detail!!.event.title)
            repo.delete(series.id, null)
            model.openNotification(series.id, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 14)); runCurrent()
            assertNull(model.state.value.detail); assertNotNull(model.state.value.error)
        } finally { owner.clear(); Dispatchers.resetMain() }
    }
}
