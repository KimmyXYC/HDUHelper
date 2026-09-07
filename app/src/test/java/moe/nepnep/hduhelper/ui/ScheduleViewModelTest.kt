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
        var terms: List<TimetableData>? = null
        override suspend fun cachedTerms(account: String) = terms?.filter { it.account == account } ?: listOfNotNull(cached(account))
        var cacheGate: CompletableDeferred<Unit>? = null
        var catalogGate: CompletableDeferred<Unit>? = null
        var fetchGate: CompletableDeferred<Unit>? = null
        var requested: AcademicTerm? = null
        override suspend fun cached(account: String, term: AcademicTerm?): TimetableData? {
            cacheGate?.await()
            return cache?.takeIf { it.account == account && (term == null || it.term.key == term.key) }
        }
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
    @Test fun courseNotificationWaitsForColdSessionThenOpensCachedCourseOffline() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val auth = MutableStateFlow(AuthState(AuthStatus.LOADING))
            val generation = MutableStateFlow(1L)
            val model = ScheduleViewModel(ScheduleRepository(Store(ScheduleBook()), StandardTestDispatcher(testScheduler)), Source(), auth,
                generation, MutableStateFlow(false), Reminders(), { LocalDateTime.parse("2026-09-14T09:00") })
            owner.put("schedule", model)
            val key = moe.nepnep.hduhelper.data.notifications.CourseReminderRules.hash("student")
            val date = LocalDate.of(2026, 9, 14)
            model.openCourseNotification(key, term.key, "a", date); runCurrent()
            assertNull(model.state.value.courseDetailId)
            auth.value = AuthState(AuthStatus.AUTHENTICATED, UserProfile("student", "测试")); runCurrent()
            assertEquals("a", model.state.value.courseDetailId)
            assertEquals(date, model.state.value.date)
            assertNull(model.state.value.error)
            model.showDetail(null)
            assertNull(model.state.value.courseDetailId)
            model.openCourseNotification(key, term.key, "a", date); runCurrent()
            assertEquals("a", model.state.value.courseDetailId)
            auth.value = AuthState(AuthStatus.SIGNED_OUT); runCurrent()
            assertNull(model.state.value.courseDetailId)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun courseNotificationRejectsOtherAccountsAndDeletedOrOldTermCourses() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val model = ScheduleViewModel(ScheduleRepository(Store(ScheduleBook()), StandardTestDispatcher(testScheduler)), Source(),
                MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED, UserProfile("student", "测试"))),
                MutableStateFlow(1L), MutableStateFlow(false), Reminders(), { LocalDateTime.parse("2026-09-14T09:00") })
            owner.put("schedule", model); runCurrent()
            val key = moe.nepnep.hduhelper.data.notifications.CourseReminderRules.hash("student")
            for ((account, termKey, id) in listOf(Triple("other", term.key, "a"), Triple(key, "2025-3", "a"), Triple(key, term.key, "deleted"))) {
                model.openCourseNotification(account, termKey, id, LocalDate.of(2026, 9, 14)); runCurrent()
                assertNull(model.state.value.courseDetailId)
                assertNotNull(model.state.value.error)
            }
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun aNewCourseNotificationReplacesPendingNavigation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val source = Source().apply { cache = data(meeting("a"), meeting("b")); cacheGate = CompletableDeferred() }
            val model = ScheduleViewModel(ScheduleRepository(Store(ScheduleBook()), StandardTestDispatcher(testScheduler)), source,
                MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED, UserProfile("student", "测试"))),
                MutableStateFlow(1L), MutableStateFlow(false), Reminders(), { LocalDateTime.parse("2026-09-14T09:00") })
            owner.put("schedule", model); runCurrent()
            val key = moe.nepnep.hduhelper.data.notifications.CourseReminderRules.hash("student")
            val date = LocalDate.of(2026, 9, 14)
            model.openCourseNotification(key, term.key, "a", date); runCurrent()
            model.openCourseNotification(key, term.key, "b", date); runCurrent()
            source.cacheGate!!.complete(Unit); runCurrent()
            assertEquals("b", model.state.value.courseDetailId)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun examNotificationOpensOfflineAndRejectsRemovedExamOrOtherAccount() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val date = LocalDate.of(2026, 9, 14)
            val exam = ExamArrangement("exam", "合成考试", start = "2026-09-14T09:00", end = "2026-09-14T11:00")
            val source = Source().apply { cache = data(meeting("a")).copy(exams = ExamSnapshot(listOf(exam), 123)) }
            val auth = MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED, UserProfile("student", "测试")))
            val model = ScheduleViewModel(ScheduleRepository(Store(ScheduleBook()), StandardTestDispatcher(testScheduler)), source,
                auth, MutableStateFlow(1L), MutableStateFlow(false), Reminders(), { date.atTime(8, 0) })
            owner.put("schedule", model)
            val key = moe.nepnep.hduhelper.data.notifications.CourseReminderRules.hash("student")
            model.openCourseNotification(key, term.key, "exam", date, exam = true); runCurrent()
            assertEquals("exam", model.state.value.examDetailId)
            assertNull(model.state.value.courseDetailId)
            assertEquals(date, model.state.value.date)
            model.openCourseNotification("another", term.key, "exam", date, exam = true); runCurrent()
            assertNull(model.state.value.examDetailId)
            assertNotNull(model.state.value.error)
            source.cache = source.cache!!.copy(exams = ExamSnapshot(updatedAt = 124))
            model.openCourseNotification(key, term.key, "exam", date, exam = true); runCurrent()
            assertNull(model.state.value.examDetailId)
            assertNotNull(model.state.value.error)
            auth.value = AuthState(AuthStatus.SIGNED_OUT); runCurrent()
            assertNull(model.state.value.courses)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun changingAgendaDateReadsPreviouslySyncedHistoricalExamsOffline() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val current = data(meeting("current"))
            val exam = ExamArrangement("past-exam", "历史考试", start = "2026-07-01T09:00", end = "2026-07-01T11:00")
            val past = current.copy(term = AcademicTerm("2025", "12"), weeks = listOf(WeekRange(20, "2026-06-29", "2026-07-05")),
                exams = ExamSnapshot(listOf(exam), 123))
            val source = Source().apply { cache = current; terms = listOf(current, past) }
            val model = ScheduleViewModel(ScheduleRepository(Store(ScheduleBook()), StandardTestDispatcher(testScheduler)), source,
                MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED, UserProfile("student", "测试"))),
                MutableStateFlow(1L), MutableStateFlow(false), Reminders(), { LocalDateTime.parse("2026-09-14T09:00") })
            owner.put("schedule", model); model.setVisible(true); runCurrent()
            assertEquals(current.term, model.state.value.courses!!.term)
            model.selectDate(LocalDate.of(2026, 7, 1)); runCurrent()
            assertEquals(past.term, model.state.value.courses!!.term)
            assertEquals(listOf(exam), ExamRules.onDate(model.state.value.courses!!, model.state.value.date))
            model.selectDate(LocalDate.of(2026, 9, 14)); runCurrent()
            assertEquals(current.term, model.state.value.courses!!.term)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }
}
