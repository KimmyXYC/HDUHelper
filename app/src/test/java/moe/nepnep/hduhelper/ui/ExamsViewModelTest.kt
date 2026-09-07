package moe.nepnep.hduhelper.ui

import androidx.lifecycle.ViewModelStore
import java.time.LocalDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import moe.nepnep.hduhelper.data.auth.*
import moe.nepnep.hduhelper.data.timetable.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExamsViewModelTest {
    private val current = AcademicTerm("2026", "3", "1")
    private val previous = AcademicTerm("2025", "12", "2")
    private val catalog = TimetableCatalog(listOf("2026", "2025"), listOf(TermOption("3", "1"), TermOption("12", "2")), current)
    private val auth = MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED, UserProfile("student", "合成用户")))
    private val generation = MutableStateFlow(1L)
    private val online = MutableStateFlow(true)
    private fun data(term: AcademicTerm, account: String = "student") = TimetableData(account, term, catalog,
        emptyList(), emptyList(), emptyList(), emptyList(), 1, exams = ExamSnapshot(listOf(ExamArrangement(term.key, "合成考试")), 1))
    private inner class Source : TimetableSource {
        var lastBrowsed = previous
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        val fetched = mutableListOf<AcademicTerm>()
        override suspend fun cached(account: String, term: AcademicTerm?) = data(term ?: lastBrowsed, account)
        override suspend fun catalog(): TimetableCatalog { gate?.await(); return catalog }
        override suspend fun refresh(term: AcademicTerm, catalog: TimetableCatalog): TimetableData {
            fetched += term
            if (fail) throw TimetableException(TimetableFailure.NETWORK, "synthetic")
            return data(term, auth.value.profile!!.account)
        }
        override fun clear() = Unit
    }
    private fun model(source: Source, store: ViewModelStore, now: () -> LocalDateTime = { LocalDateTime.of(2026, 9, 8, 10, 0) }) =
        ExamsViewModel(source, auth, generation, online, now).also { store.put("exams", it) }

    @Test fun defaultUsesCurrentTermAndIndependentSelectionSurvivesPageReturn() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val source = Source().apply { gate = CompletableDeferred() }
            val model = model(source, store)
            model.setVisible(true); runCurrent()
            assertEquals(current, model.state.value.selectedTerm)
            assertEquals(current.key, model.state.value.exams!!.items.single().id)
            source.gate!!.complete(Unit); runCurrent()
            model.selectTerm(previous); runCurrent()
            model.setVisible(false); model.setVisible(true); runCurrent()
            assertEquals(previous, model.state.value.selectedTerm)
            assertEquals(current, catalog.current)
            assertEquals(listOf(current, previous, previous), source.fetched)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun offlineAndRefreshFailureKeepCachedExamsAndRetryRecovers() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            online.value = false
            val source = Source(); val model = model(source, store)
            model.setVisible(true); runCurrent()
            assertNotNull(model.state.value.exams)
            assertTrue(model.state.value.offline)
            assertTrue(source.fetched.isEmpty())
            source.fail = true; online.value = true; runCurrent()
            assertNotNull(model.state.value.exams)
            assertEquals("考试更新失败，显示最近同步的安排", model.state.value.message)
            source.fail = false; model.refresh(); runCurrent()
            assertNull(model.state.value.message)
            assertFalse(model.state.value.refreshing)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun cancelledOldTermCannotReplaceNewSelectionAndSignOutClearsData() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val source = Source().apply { gate = CompletableDeferred() }
            val model = model(source, store)
            model.setVisible(true); runCurrent()
            model.selectTerm(previous); runCurrent()
            source.gate!!.complete(Unit); runCurrent()
            assertEquals(listOf(previous), source.fetched)
            auth.value = AuthState(AuthStatus.SIGNED_OUT); runCurrent()
            assertNull(model.state.value.exams)
            assertNull(model.state.value.selectedTerm)
            assertEquals(TimetableStatus.SIGNED_OUT, model.state.value.status)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun verificationAndSameAccountRecoveryRetainIndependentTerm() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val source = Source(); val model = model(source, store)
            model.setVisible(true); runCurrent(); model.selectTerm(previous); runCurrent()
            auth.value = auth.value.copy(status = AuthStatus.VERIFICATION_REQUIRED); runCurrent()
            assertEquals(TimetableStatus.VERIFICATION_REQUIRED, model.state.value.status)
            model.setVisible(false)
            generation.value++; auth.value = auth.value.copy(status = AuthStatus.AUTHENTICATED); runCurrent()
            model.setVisible(true); runCurrent()
            assertEquals(previous, model.state.value.selectedTerm)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun clockStopsOffscreenAndRecomputesImmediatelyOnReturn() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            var clock = LocalDateTime.of(2026, 9, 8, 10, 0)
            val model = model(Source(), store) { clock }
            model.setVisible(true); runCurrent()
            clock = clock.plusSeconds(1); advanceTimeBy(1_000); runCurrent()
            assertEquals(clock, model.state.value.now)
            model.setVisible(false); val stopped = model.state.value.now
            clock = clock.plusHours(2); advanceTimeBy(5_000); runCurrent()
            assertEquals(stopped, model.state.value.now)
            model.setVisible(true); runCurrent()
            assertEquals(clock, model.state.value.now)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
