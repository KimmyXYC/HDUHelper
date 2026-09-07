package moe.nepnep.hduhelper.ui

import androidx.lifecycle.ViewModelStore
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import moe.nepnep.hduhelper.data.auth.*
import moe.nepnep.hduhelper.data.settings.AppSettings
import moe.nepnep.hduhelper.data.timetable.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TimetableViewModelTest {
    private class Source:TimetableSource {
        var calls=0
        var pause:CompletableDeferred<Unit>?=null
        var catalogPause:CompletableDeferred<Unit>?=null
        var failure=false
        val values=mutableMapOf(term.key to data(meeting("a")))
        override suspend fun cached(account:String,term:AcademicTerm?)=values[term?.key ?: catalog.current.key]?.takeIf {it.account==account}
        override suspend fun catalog():TimetableCatalog {catalogPause?.await();return catalog}
        override suspend fun refresh(term:AcademicTerm,catalog:TimetableCatalog):TimetableData {
            calls++;pause?.await()
            if(failure)throw TimetableException(TimetableFailure.NETWORK,"synthetic network failure")
            return data(meeting("a")).copy(term=term,updatedAt=calls.toLong()).also { values[term.key]=it }
        }
        override fun clear() {values.clear()}
    }
    @Test fun cacheAppearsBeforeNetworkAndSilentUpdatePreservesBrowsedWeek()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val store=ViewModelStore()
        try {
            val source=Source().apply {catalogPause=CompletableDeferred();pause=CompletableDeferred()}
            val cached=source.values.getValue(term.key)
            val model=TimetableViewModel(source,MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED,UserProfile("student","测试"))),MutableStateFlow(1L),MutableStateFlow(true),MutableStateFlow(AppSettings()),{_,_->null},{},{_,_,_->},today={LocalDate.of(2026,9,21)})
            store.put("test",model);model.setVisible(true);runCurrent()
            assertEquals(cached,model.state.value.data)
            assertEquals(TimetableStatus.READY,model.state.value.status)
            assertFalse(model.state.value.refreshing)
            assertEquals(0,source.calls)
            model.selectWeek(5)
            source.catalogPause!!.complete(Unit);runCurrent()
            assertEquals(1,source.calls);assertEquals(cached,model.state.value.data)
            source.pause!!.complete(Unit);runCurrent()
            assertEquals(1L,model.state.value.data!!.updatedAt)
            assertEquals(5,model.state.value.week)
            assertFalse(model.state.value.refreshing);assertNull(model.state.value.message)
        }finally{store.clear();Dispatchers.resetMain()}
    }
    @Test fun automaticFailureIsQuietWhileManualRefreshReportsFailure()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val store=ViewModelStore()
        try {
            val source=Source().apply {failure=true;pause=CompletableDeferred()}
            val model=TimetableViewModel(source,MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED,UserProfile("student","测试"))),MutableStateFlow(1L),MutableStateFlow(true),MutableStateFlow(AppSettings()),{_,_->null},{},{_,_,_->})
            store.put("test",model);model.setVisible(true);runCurrent()
            source.pause!!.complete(Unit);runCurrent()
            assertNotNull(model.state.value.data);assertNull(model.state.value.message)
            assertEquals(TimetableStatus.READY,model.state.value.status)
            source.pause=CompletableDeferred();model.refresh();runCurrent()
            assertTrue(model.state.value.refreshing)
            source.pause!!.complete(Unit);runCurrent()
            assertNotNull(model.state.value.data);assertNotNull(model.state.value.message)
            assertFalse(model.state.value.refreshing)
        }finally{store.clear();Dispatchers.resetMain()}
    }
    @Test fun firstLoadWithoutCacheShowsLoadingAndReportsFailure()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val store=ViewModelStore()
        try {
            val source=Source().apply {values.clear();failure=true;pause=CompletableDeferred()}
            val model=TimetableViewModel(source,MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED,UserProfile("student","测试"))),MutableStateFlow(1L),MutableStateFlow(true),MutableStateFlow(AppSettings()),{_,_->null},{},{_,_,_->})
            store.put("test",model);model.setVisible(true);runCurrent()
            assertNull(model.state.value.data);assertEquals(TimetableStatus.LOADING,model.state.value.status)
            source.pause!!.complete(Unit);runCurrent()
            assertEquals(TimetableStatus.ERROR,model.state.value.status);assertNotNull(model.state.value.message)
        }finally{store.clear();Dispatchers.resetMain()}
    }
    @Test fun defaultsSemesterChangesOfflineCacheAndReentry()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val store=ViewModelStore()
        try {
            val source=Source();val auth=MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED,UserProfile("student","测试")))
            val net=MutableStateFlow(true)
            val model=TimetableViewModel(source,auth,MutableStateFlow(1),net,MutableStateFlow(AppSettings()),{_,_->null},{},{_,_,_->},today={LocalDate.of(2026,9,7)})
            store.put("test",model);model.setVisible(true);runCurrent()
            assertEquals(0,model.state.value.week)
            assertEquals(1,source.calls)
            model.selectWeek(3);runCurrent();assertEquals(3,model.state.value.week)
            val old=AcademicTerm("2025","12","2")
            model.selectTerm(old);runCurrent();assertEquals(old.key,model.state.value.selectedTerm!!.key)
            // This fake's calendar remains in the future; default follows dates, not numeric year guessing.
            assertEquals(0,model.state.value.week)
            model.setVisible(false);model.setVisible(true);runCurrent()
            assertEquals(term.key,model.state.value.selectedTerm!!.key)
            net.value=false;runCurrent();model.refresh();runCurrent()
            assertNotNull(model.state.value.data);assertTrue(model.state.value.offline)
            val before=source.calls;net.value=true;runCurrent();assertEquals(before+1,source.calls)
        }finally{store.clear();Dispatchers.resetMain()}
    }
    @Test fun refreshIsSharedFailurePreservesCacheAndLogoutClearsUi()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val store=ViewModelStore()
        try {
            val source=Source();val auth=MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED,UserProfile("student","测试")))
            val gen=MutableStateFlow(1L)
            val model=TimetableViewModel(source,auth,gen,MutableStateFlow(true),MutableStateFlow(AppSettings()),{_,_->null},{},{_,_,_->},today={LocalDate.of(2026,9,21)})
            store.put("test",model);model.setVisible(true);runCurrent();assertEquals(2,model.state.value.week)
            source.pause=CompletableDeferred();model.refresh();runCurrent();repeat(8){model.refresh()};runCurrent();assertEquals(2,source.calls)
            source.failure=true;source.pause!!.complete(Unit);runCurrent()
            assertNotNull(model.state.value.data);assertNotNull(model.state.value.message)
            source.failure=false;source.pause=CompletableDeferred();model.refresh();runCurrent()
            auth.value=AuthState(AuthStatus.SIGNED_OUT);gen.value++;runCurrent();source.pause!!.complete(Unit);runCurrent()
            assertNull(model.state.value.data);assertEquals(TimetableStatus.SIGNED_OUT,model.state.value.status)
        }finally{store.clear();Dispatchers.resetMain()}
    }
    @Test fun settingsDoNotRefetchOrResetSelectedWeek()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val store=ViewModelStore()
        try {
            val source=Source();val prefs=MutableStateFlow(AppSettings());val campuses=mutableMapOf<String,String?>()
            val model=TimetableViewModel(source,MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED,UserProfile("student","测试"))),MutableStateFlow(1L),MutableStateFlow(true),prefs,
                {a,t->campuses["$a/$t"]},{prefs.value=prefs.value.copy(timetable=it)},{a,t,c->campuses["$a/$t"]=c;prefs.value=prefs.value.copy(campusRevision=prefs.value.campusRevision+1)},today={LocalDate.of(2026,9,21)})
            store.put("test",model);model.setVisible(true);runCurrent();model.selectWeek(5)
            model.setSettings(TimetableSettings(showWeekend=true,showTeacher=false));model.setCampus("2");runCurrent()
            assertEquals(5,model.state.value.week);assertEquals(1,source.calls)
            assertTrue(model.state.value.settings.showWeekend);assertFalse(model.state.value.settings.showTeacher)
            assertEquals("2",model.state.value.clock!!.id)
        }finally{store.clear();Dispatchers.resetMain()}
    }

    @Test fun settingsEntryLoadsCampusesWithoutOpeningTimetableAndRetriesAfterNetworkRecovery() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val source = Source().apply { values.clear() }
            val net = MutableStateFlow(false)
            val model = TimetableViewModel(source, MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED, UserProfile("student", "测试"))),
                MutableStateFlow(1L), net, MutableStateFlow(AppSettings()), { _, _ -> null }, {}, { _, _, _ -> })
            store.put("test", model)
            model.setVisible(true, resetToDefault = false); runCurrent()
            assertEquals(TimetableStatus.ERROR, model.state.value.status)
            assertNull(model.state.value.data)
            assertEquals(0, source.calls)
            net.value = true; runCurrent()
            assertEquals(TimetableStatus.READY, model.state.value.status)
            assertTrue(model.state.value.data!!.clocks.isNotEmpty())
            assertEquals(1, source.calls)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun settingsEntryPreservesBrowsedTermWeekAndCampusAndCancelsWhenHidden() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val source = Source()
            val model = TimetableViewModel(source, MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED, UserProfile("student", "测试"))),
                MutableStateFlow(1L), MutableStateFlow(true), MutableStateFlow(AppSettings()), { _, _ -> "2" }, {}, { _, _, _ -> })
            store.put("test", model)
            model.setVisible(true); runCurrent()
            val previous = AcademicTerm("2025", "12", "2")
            model.selectTerm(previous); runCurrent(); model.selectWeek(5)
            model.setVisible(false)
            source.pause = CompletableDeferred()
            model.setVisible(true, resetToDefault = false); runCurrent()
            assertEquals(previous, model.state.value.selectedTerm)
            assertEquals(5, model.state.value.week)
            assertEquals("2", model.state.value.selectedCampus)
            val displayed = model.state.value.data
            model.setVisible(false); source.pause!!.complete(Unit); runCurrent()
            assertEquals(displayed, model.state.value.data)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
