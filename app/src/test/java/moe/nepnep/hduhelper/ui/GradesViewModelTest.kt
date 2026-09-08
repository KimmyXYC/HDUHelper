package moe.nepnep.hduhelper.ui

import androidx.lifecycle.ViewModelStore
import java.time.LocalDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import moe.nepnep.hduhelper.data.auth.*
import moe.nepnep.hduhelper.data.timetable.*
import moe.nepnep.hduhelper.data.grades.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GradesViewModelTest {
    private val current = AcademicTerm("2026", "3", "1")
    private val previous = AcademicTerm("2025", "12", "2")
    private val catalog = TimetableCatalog(listOf("2026", "2025"), listOf(TermOption("3", "1"), TermOption("12", "2")), current)
    private val auth = MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED, UserProfile("student", "合成用户")))
    private val generation = MutableStateFlow(1L)
    private val online = MutableStateFlow(true)
    private fun data(term: AcademicTerm, account: String = "student") = GradeSnapshot(account, term, catalog,
        listOf(CourseGrade(term.key, "class", "studentId", "合成课程", "2", "90", "4.5", "通识必修", false)), 1)
    private inner class Source : GradeSource {
        var lastBrowsed = previous
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        var detailGate: CompletableDeferred<Unit>? = null
        var partialDetails = false
        val fetched = mutableListOf<AcademicTerm>()
        override suspend fun cached(account: String, term: AcademicTerm?) = data(term ?: lastBrowsed, account)
        override suspend fun catalog(): TimetableCatalog { gate?.await(); return catalog }
        override suspend fun refresh(term: AcademicTerm, catalog: TimetableCatalog): GradeSnapshot {
            fetched += term
            if (fail) throw TimetableException(TimetableFailure.NETWORK, "synthetic")
            return data(term, auth.value.profile!!.account)
        }
        override suspend fun details(snapshot: GradeSnapshot): GradeSnapshot { detailGate?.await(); return snapshot.copy(detailsFailed = partialDetails) }
        override fun clear() = Unit
    }
    private fun model(source: Source, store: ViewModelStore) =
        GradesViewModel(source, auth, generation, online).also { store.put("grades", it) }

    @Test fun defaultUsesCurrentTermAndIndependentSelectionSurvivesPageReturn() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val source = Source().apply { gate = CompletableDeferred() }
            val model = model(source, store)
            model.setVisible(true); runCurrent()
            assertEquals(current, model.state.value.selectedTerm)
            assertEquals(current.key, model.state.value.grades!!.items.single().id)
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
            assertNotNull(model.state.value.grades)
            assertTrue(model.state.value.offline)
            assertTrue(source.fetched.isEmpty())
            source.fail = true; online.value = true; runCurrent()
            assertNotNull(model.state.value.grades)
            assertEquals("成绩更新失败，显示最近同步的数据", model.state.value.message)
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
            assertNull(model.state.value.grades)
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

    @Test fun listIsVisibleBeforeDetailsAndLateDetailsCannotOverwriteNewTerm() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val gate = CompletableDeferred<Unit>()
            val source = Source().apply { detailGate = gate }
            val model = model(source, store)
            model.setVisible(true); runCurrent()
            assertEquals(TimetableStatus.READY, model.state.value.status)
            assertEquals(current, model.state.value.grades!!.term)
            source.detailGate = null; source.partialDetails = true
            model.selectTerm(previous); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals(previous, model.state.value.grades!!.term)
            assertTrue(model.state.value.grades!!.detailsFailed)
            source.partialDetails = false; model.refresh(); runCurrent()
            assertFalse(model.state.value.grades!!.detailsFailed)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

}
