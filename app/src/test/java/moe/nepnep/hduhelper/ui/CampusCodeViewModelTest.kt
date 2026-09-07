package moe.nepnep.hduhelper.ui

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.nepnep.hduhelper.data.auth.AuthState
import moe.nepnep.hduhelper.data.auth.AuthStatus
import moe.nepnep.hduhelper.data.auth.UserProfile
import moe.nepnep.hduhelper.data.campuscode.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CampusCodeViewModelTest {
    private class Source : CampusCodeSource {
        var calls = 0
        var clears = 0
        var pause: CompletableDeferred<Unit>? = null
        var failure = false
        override fun clear() { clears++ }
        override suspend fun refresh(): CampusCode {
            calls++
            pause?.await()
            if (failure) throw CampusCodeException(CampusCodeFailure.NETWORK, "synthetic network error")
            return CampusCode("SYNTHETIC-$calls,123", CampusCodeProfile("测试", "学生", "学院"), 180, calls.toLong())
        }
    }

    @Test fun returningWithMoreThanThirtySecondsReusesCodeAndOriginalExpiry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val source = Source()
            val auth = MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED, UserProfile("test", "测试")))
            val model = CampusCodeViewModel(source, auth, MutableStateFlow(1L), MutableStateFlow(true), { testScheduler.currentTime }, dispatcher)
            store.put("model", model)
            model.setVisible(true); runCurrent()
            val code = model.state.value.code
            val image = model.state.value.image
            advanceTimeBy(60_000); runCurrent()
            model.setVisible(false); runCurrent()
            assertNull(model.state.value.image)
            advanceTimeBy(89_999); runCurrent()
            model.setVisible(true); runCurrent()
            assertEquals(1, source.calls)
            assertSame(code, model.state.value.code)
            assertSame(image, model.state.value.image)
            assertEquals(31L, model.state.value.nextRefreshSeconds)
            advanceTimeBy(30_250); runCurrent()
            assertEquals(2, source.calls)
            assertNotSame(code, model.state.value.code)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun returningAtOrBelowThirtySecondsFetchesNewCode() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val source = Source()
            val auth = MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED, UserProfile("test", "测试")))
            val model = CampusCodeViewModel(source, auth, MutableStateFlow(1L), MutableStateFlow(true), { testScheduler.currentTime }, dispatcher)
            store.put("model", model)
            model.setVisible(true); runCurrent()
            for (awayMillis in listOf(150_000L, 150_001L, 180_000L, 600_000L)) {
                val calls = source.calls
                val code = model.state.value.code
                model.setVisible(false); runCurrent()
                advanceTimeBy(awayMillis); runCurrent()
                assertEquals(calls, source.calls)
                model.setVisible(true); runCurrent()
                assertEquals(calls + 1, source.calls)
                assertNotSame(code, model.state.value.code)
                assertEquals(180L, model.state.value.nextRefreshSeconds)
            }
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun hiddenCacheIsInvalidatedByIdentitySessionNetworkAndVerificationChanges() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val source = Source()
            val signedIn = AuthState(AuthStatus.AUTHENTICATED, UserProfile("test", "测试"))
            val auth = MutableStateFlow(signedIn)
            val generation = MutableStateFlow(1L)
            val online = MutableStateFlow(true)
            val model = CampusCodeViewModel(source, auth, generation, online, { testScheduler.currentTime }, dispatcher)
            store.put("model", model)
            model.setVisible(true); runCurrent()
            val changes: List<() -> Unit> = listOf(
                { generation.value++ },
                { auth.value = AuthState(AuthStatus.AUTHENTICATED, UserProfile("other", "其他")) },
                { auth.value = AuthState(AuthStatus.SIGNED_OUT) },
                { online.value = false },
                { auth.value = AuthState(AuthStatus.VERIFICATION_REQUIRED, signedIn.profile) },
            )
            for (change in changes) {
                val calls = source.calls
                model.setVisible(false); runCurrent()
                change(); runCurrent()
                assertNull(model.state.value.code)
                auth.value = signedIn
                online.value = true
                runCurrent()
                model.setVisible(true); runCurrent()
                assertEquals(calls + 1, source.calls)
                assertEquals(CampusCodeStatus.READY, model.state.value.status)
            }
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun manualRefreshPreservesCountdownAcrossNavigationAndFailureInvalidatesCache() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val source = Source()
            val auth = MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED, UserProfile("test", "测试")))
            val model = CampusCodeViewModel(source, auth, MutableStateFlow(1L), MutableStateFlow(true), { testScheduler.currentTime }, dispatcher)
            store.put("model", model)
            model.setVisible(true); runCurrent()
            advanceTimeBy(120_000); runCurrent()
            model.refresh(); runCurrent()
            assertEquals(60L, model.state.value.nextRefreshSeconds)
            val code = model.state.value.code
            model.setVisible(false); runCurrent()
            advanceTimeBy(20_000); runCurrent()
            model.setVisible(true); runCurrent()
            assertEquals(2, source.calls)
            assertSame(code, model.state.value.code)
            assertEquals(40L, model.state.value.nextRefreshSeconds)
            model.setVisible(false); runCurrent()
            model.setVisible(true); runCurrent()
            assertEquals(2, source.calls)
            assertEquals(40L, model.state.value.nextRefreshSeconds)
            advanceTimeBy(40_000); runCurrent()
            assertEquals(3, source.calls)
            source.failure = true
            model.refresh(); runCurrent()
            assertEquals(CampusCodeStatus.ERROR, model.state.value.status)
            model.setVisible(false); runCurrent()
            source.failure = false
            model.setVisible(true); runCurrent()
            assertEquals(5, source.calls)
            assertNotSame(code, model.state.value.code)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun manualRefreshDoesNotExtendCacheBeyondOriginalRefreshBoundary() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val source = Source()
            val auth = MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED, UserProfile("test", "测试")))
            val model = CampusCodeViewModel(source, auth, MutableStateFlow(1L), MutableStateFlow(true), { testScheduler.currentTime }, dispatcher)
            store.put("model", model)
            model.setVisible(true); runCurrent()
            for (awayMillis in listOf(90_000L, 120_000L)) {
                advanceTimeBy(60_000); runCurrent()
                model.refresh(); runCurrent()
                val calls = source.calls
                val code = model.state.value.code
                model.setVisible(false); runCurrent()
                advanceTimeBy(awayMillis); runCurrent()
                model.setVisible(true); runCurrent()
                assertEquals(calls + 1, source.calls)
                assertNotSame(code, model.state.value.code)
                assertEquals(180L, model.state.value.nextRefreshSeconds)
            }
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun manualRefreshDoesNotMoveDeadlineAndBackgroundStopsWork() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val source = Source()
            val auth = MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED, UserProfile("test", "测试")))
            val online = MutableStateFlow(true)
            val generation = MutableStateFlow(1L)
            val model = CampusCodeViewModel(source, auth, generation, online, { testScheduler.currentTime }, dispatcher)
            store.put("model", model)
            runCurrent()
            assertEquals(0, source.calls)
            model.setVisible(true); runCurrent()
            assertEquals(CampusCodeStatus.READY, model.state.value.status)
            assertEquals(1, source.calls)
            advanceTimeBy(60_000); runCurrent()
            model.refresh(); runCurrent()
            assertEquals(2, source.calls)
            advanceTimeBy(120_000); runCurrent()
            assertEquals(3, source.calls)
            model.setVisible(false); runCurrent()
            assertNull(model.state.value.image)
            advanceTimeBy(600_000); runCurrent()
            assertEquals(3, source.calls)
            model.setVisible(true); runCurrent()
            assertEquals(4, source.calls)
            online.value = false; runCurrent()
            assertNull(model.state.value.code)
            online.value = true; runCurrent()
            assertEquals(5, source.calls)
            auth.value = AuthState(AuthStatus.SIGNED_OUT); generation.value++; runCurrent()
            assertNull(model.state.value.image)
            assertEquals(CampusCodeStatus.SIGNED_OUT, model.state.value.status)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun overlappingRefreshesCoalesceAndErrorsHideOldCode() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val source = Source()
            val auth = MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED, UserProfile("test", "测试")))
            val model = CampusCodeViewModel(source, auth, MutableStateFlow(1L), MutableStateFlow(true), { testScheduler.currentTime }, dispatcher)
            store.put("model", model)
            model.setVisible(true); runCurrent()
            source.pause = CompletableDeferred()
            model.refresh(); runCurrent()
            repeat(10) { model.refresh() }; runCurrent()
            assertEquals(2, source.calls)
            source.failure = true
            source.pause!!.complete(Unit); runCurrent()
            assertNull(model.state.value.code)
            assertNull(model.state.value.image)
            assertEquals(CampusCodeStatus.ERROR, model.state.value.status)
            source.failure = false; source.pause = CompletableDeferred()
            model.refresh(); runCurrent()
            model.setVisible(false); runCurrent()
            source.pause!!.complete(Unit); runCurrent()
            assertNull(model.state.value.code)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
}
