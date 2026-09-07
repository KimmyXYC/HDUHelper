package moe.nepnep.hduhelper.ui

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import moe.nepnep.hduhelper.data.auth.*
import moe.nepnep.hduhelper.data.campuscode.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CampusCodeRecoveryTest {
    private class Source : CampusCodeSource {
        var calls = 0
        var failure: Exception? = CampusCodeException(CampusCodeFailure.NETWORK, "synthetic network failure")
        override fun clear() = Unit
        override suspend fun refresh(): CampusCode {
            calls++
            failure?.let { throw it }
            return CampusCode("synthetic-$calls", CampusCodeProfile("测试", "学生", "学院"), 180, 0)
        }
    }
    private class Fixture(scope: TestScope) {
        val source = Source()
        val profile = UserProfile("test", "测试")
        val auth = MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED, profile))
        val online = MutableStateFlow(true)
        val generation = MutableStateFlow(1L)
        val store = ViewModelStore()
        val model = CampusCodeViewModel(source, auth, generation, online, { scope.testScheduler.currentTime }, StandardTestDispatcher(scope.testScheduler))
        init { store.put("model", model); model.setVisible(true) }
        fun status(status: AuthStatus) { auth.value = AuthState(status, profile) }
    }
    private fun check(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(this)
        try { block(fixture) } finally { fixture.store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun authenticatedRecoveryRefreshesAfterExhaustedNetworkRetriesWithoutNetworkEvent() = check { f ->
        f.status(AuthStatus.UNAVAILABLE); runCurrent()
        advanceTimeBy(10_001); runCurrent()
        assertEquals(3, f.source.calls)
        assertEquals(CampusCodeStatus.ERROR, f.model.state.value.status)
        f.status(AuthStatus.REFRESHING); runCurrent()
        f.source.failure = null
        f.status(AuthStatus.AUTHENTICATED); runCurrent()
        assertEquals(4, f.source.calls)
        assertEquals(CampusCodeStatus.READY, f.model.state.value.status)
        advanceTimeBy(15_000); runCurrent()
        assertEquals(4, f.source.calls)
    }

    @Test fun eitherNetworkAndAuthenticationRecoveryOrderProducesCodeWithoutNavigation() = check { f ->
        runCurrent()
        for (authFirst in listOf(false, true)) {
            f.online.value = false; f.status(AuthStatus.UNAVAILABLE); runCurrent()
            val before = f.source.calls
            f.source.failure = null
            if (authFirst) {
                f.status(AuthStatus.AUTHENTICATED); runCurrent()
                assertEquals(before, f.source.calls)
                f.online.value = true; runCurrent()
                advanceTimeBy(1_000); runCurrent()
            } else {
                f.online.value = true; runCurrent()
                f.status(AuthStatus.REFRESHING); runCurrent()
                f.status(AuthStatus.AUTHENTICATED); runCurrent()
            }
            assertEquals(before + 1, f.source.calls)
            assertEquals(CampusCodeStatus.READY, f.model.state.value.status)
        }
    }

    @Test fun retryBudgetIsBoundedAndManualRequestsCoalesceWithRecovery() = check { f ->
        runCurrent(); assertEquals(1, f.source.calls)
        repeat(5) { f.model.refresh() }; runCurrent()
        assertEquals(1, f.source.calls)
        advanceTimeBy(2_000); runCurrent(); assertEquals(2, f.source.calls)
        advanceTimeBy(8_000); runCurrent(); assertEquals(3, f.source.calls)
        advanceTimeBy(20_000); runCurrent(); assertEquals(3, f.source.calls)
        f.source.failure = null
        f.model.refresh(); runCurrent()
        assertEquals(4, f.source.calls)
        assertEquals(CampusCodeStatus.READY, f.model.state.value.status)
    }

    @Test fun leavingPageOrGoingOfflineCancelsScheduledRetries() = check { f ->
        runCurrent()
        f.model.setVisible(false); runCurrent()
        advanceTimeBy(15_000); runCurrent(); assertEquals(1, f.source.calls)
        f.model.setVisible(true); runCurrent(); assertEquals(2, f.source.calls)
        f.online.value = false; runCurrent()
        advanceTimeBy(15_000); runCurrent(); assertEquals(2, f.source.calls)
        assertNull(f.model.state.value.code)
    }

    @Test fun credentialsAndVerificationDoNotRetryAutomatically() = check { f ->
        for (failure in listOf(AuthFailure.CREDENTIALS, AuthFailure.VERIFICATION)) {
            f.model.setVisible(false); runCurrent()
            f.source.failure = AuthException(failure, "synthetic authentication failure")
            f.model.setVisible(true); runCurrent()
            val count = f.source.calls
            advanceTimeBy(30_000); runCurrent()
            assertEquals(count, f.source.calls)
            assertNull(f.model.state.value.code)
        }
    }
}
