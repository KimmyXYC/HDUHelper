package moe.nepnep.hduhelper.ui

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import moe.nepnep.hduhelper.data.update.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private class Store : UpdateCheckStore { override var lastAttempt: Long? = null }
    private class Source : UpdateSource {
        var calls = 0
        var fail = false
        var pending: CompletableDeferred<Unit>? = null
        var release: AppRelease? = null
        override suspend fun check(currentVersion: String): AppRelease? {
            calls++
            pending?.await()
            if (fail) error("synthetic failure")
            return release
        }
    }

    @Test fun foregroundConnectivityDailyThrottleAndRecreation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owners = ViewModelStore()
        try {
            val source = Source()
            val store = Store()
            val online = MutableStateFlow(false)
            var now = 1000L
            fun model(key: String) = UpdateViewModel(source, store, online, "1.1.0", { now }).also { owners.put(key, it) }
            val first = model("first")
            first.setForeground(true); runCurrent()
            assertEquals(0, source.calls); assertNull(store.lastAttempt)
            first.check(); runCurrent()
            assertNotNull(first.state.value.message)
            assertEquals(0, source.calls)
            online.value = true; runCurrent()
            assertEquals(1, source.calls)
            first.setForeground(false); first.setForeground(true); runCurrent()
            assertEquals(1, source.calls)
            first.setForeground(false)
            val second = model("second")
            second.setForeground(true); runCurrent()
            assertEquals(1, source.calls)
            now += 24 * 60 * 60 * 1000L
            second.setForeground(false); second.setForeground(true); runCurrent()
            assertEquals(2, source.calls)
            second.check(); runCurrent()
            assertEquals(3, source.calls)
            assertEquals("暂无更新版本", second.state.value.message)
        } finally { owners.clear(); Dispatchers.resetMain() }
    }

    @Test fun manualJoinsAutomaticRequestAndErrorsStaySilentUnlessManual() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owners = ViewModelStore()
        try {
            val source = Source().apply { fail = true }
            val model = UpdateViewModel(source, Store(), MutableStateFlow(true), "1.1.0")
            owners.put("model", model)
            model.setForeground(true); runCurrent()
            assertNull(model.state.value.message)
            source.pending = CompletableDeferred()
            model.check(); runCurrent(); model.check(); runCurrent()
            assertEquals(2, source.calls)
            assertTrue(model.state.value.checking)
            source.pending!!.complete(Unit); runCurrent()
            assertNotNull(model.state.value.message)
            source.fail = false
            source.release = AppRelease("v1.2.0", "notes")
            model.check(); runCurrent()
            assertNotNull(model.state.value.release)
            model.browserFailed(); assertNotNull(model.state.value.message)
            model.dismiss(); assertNull(model.state.value.release)
        } finally { owners.clear(); Dispatchers.resetMain() }
    }

    @Test fun manualPromotesInFlightAutomaticCheckAndBackgroundCancels() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owners = ViewModelStore()
        try {
            val source = Source().apply { pending = CompletableDeferred() }
            val model = UpdateViewModel(source, Store(), MutableStateFlow(true), "1.1.0")
            owners.put("model", model)
            model.setForeground(true); runCurrent()
            model.check(); runCurrent()
            assertEquals(1, source.calls)
            source.pending!!.complete(Unit); runCurrent()
            assertEquals("暂无更新版本", model.state.value.message)
            source.pending = CompletableDeferred()
            model.check(); runCurrent(); model.setForeground(false); runCurrent()
            assertFalse(model.state.value.checking)
            source.pending!!.complete(Unit); runCurrent()
            assertNull(model.state.value.release)
        } finally { owners.clear(); Dispatchers.resetMain() }
    }
}
