package moe.nepnep.hduhelper.data.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.nepnep.hduhelper.data.settings.AuthSettings
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthRepositoryTest {
    private val user = UserProfile("student01", "测试用户")
    private fun cached(failures: Int = 0) = StoredSession("student01", "synthetic-password", user, failureCount = failures)
    private fun fail(kind: AuthFailure): Nothing = throw AuthException(kind, "synthetic failure")

    @Test fun simultaneousExpirationUsesOneRecovery() = runTest {
        val store = MemoryStore(cached())
        val session = FakeSession().apply {
            checkAction = { delay(100); fail(AuthFailure.EXPIRED) }
            renewAction = { fail(AuthFailure.EXPIRED) }
            loginAction = { _, _ -> user }
        }
        var creations = 0
        val repo = AuthRepository(store, FakeSettings(), { creations++; session }, StandardTestDispatcher(testScheduler))
        repo.initialize()
        val a = async { repo.checkAndRefresh() }
        val b = async { repo.checkAndRefresh() }
        a.await(); b.await()
        assertEquals(1, creations)
        assertEquals(1, session.logins)
        assertEquals(AuthStatus.AUTHENTICATED, repo.state.value.status)
    }

    @Test fun validSsoAvoidsPasswordAndResetsFailures() = runTest {
        val session = FakeSession().apply { checkAction = { fail(AuthFailure.EXPIRED) }; renewAction = { user } }
        val store = MemoryStore(cached(2))
        val repo = AuthRepository(store, FakeSettings(), { session }, StandardTestDispatcher(testScheduler))
        repo.checkAndRefresh()
        assertEquals(0, session.logins)
        assertEquals(0, store.value!!.failureCount)
    }

    @Test fun exactlyThreeCredentialFailuresExpireAndPersistNotice() = runTest {
        val session = FakeSession().apply {
            checkAction = { fail(AuthFailure.EXPIRED) }; renewAction = { fail(AuthFailure.EXPIRED) }
            loginAction = { _, _ -> fail(AuthFailure.CREDENTIALS) }
        }
        val delays = mutableListOf<Long>()
        val store = MemoryStore(cached())
        val repo = AuthRepository(store, FakeSettings(), { session }, StandardTestDispatcher(testScheduler), { delays += it })
        repo.checkAndRefresh()
        assertEquals(3, session.logins)
        assertEquals(listOf(2_000L, 8_000L), delays)
        assertEquals(AuthStatus.SIGNED_OUT, repo.state.value.status)
        assertNull(store.value!!.password)
        assertNull(store.value!!.profile)
        assertTrue(store.value!!.cookies.isEmpty())
        assertEquals("student01", store.value!!.account)
        val notice = store.value!!.notice!!
        val restored = AuthRepository(store, FakeSettings(), { session }, StandardTestDispatcher(testScheduler))
        restored.initialize()
        assertEquals(notice, restored.state.value.notice)
        restored.acknowledgeNotice(notice)
        assertNull(store.value!!.notice)
    }

    @Test fun networkAndServiceFailuresDoNotCountOrClearCredentials() = runTest {
        for (kind in listOf(AuthFailure.NETWORK, AuthFailure.SERVICE, AuthFailure.PROTOCOL)) {
            val store = MemoryStore(cached(1))
            val session = FakeSession().apply { checkAction = { fail(kind) } }
            val repo = AuthRepository(store, FakeSettings(), { session }, StandardTestDispatcher(testScheduler))
            repo.checkAndRefresh()
            assertEquals(AuthStatus.UNAVAILABLE, repo.state.value.status)
            assertEquals(1, store.value!!.failureCount)
            assertNotNull(store.value!!.password)
            assertEquals(0, session.logins)
        }
    }

    @Test fun transientFailureBetweenCredentialFailuresDoesNotIncreaseCount() = runTest {
        val store = MemoryStore(cached())
        val session = FakeSession().apply {
            checkAction = { fail(AuthFailure.EXPIRED) }; renewAction = { fail(AuthFailure.EXPIRED) }
            loginAction = { _, _ -> if (logins == 1) fail(AuthFailure.CREDENTIALS) else fail(AuthFailure.NETWORK) }
        }
        val repo = AuthRepository(store, FakeSettings(), { session }, StandardTestDispatcher(testScheduler), {})
        repo.checkAndRefresh()
        assertEquals(1, store.value!!.failureCount)
        assertEquals(AuthStatus.UNAVAILABLE, repo.state.value.status)
    }

    @Test fun challengePausesRefreshWithoutCountingAndWebLoginCanFinish() = runTest {
        val store = MemoryStore(cached())
        val session = FakeSession().apply {
            checkAction = { fail(AuthFailure.EXPIRED) }; renewAction = { fail(AuthFailure.VERIFICATION) }
        }
        val repo = AuthRepository(store, FakeSettings(), { session }, StandardTestDispatcher(testScheduler))
        repo.checkAndRefresh()
        assertEquals(AuthStatus.VERIFICATION_REQUIRED, repo.state.value.status)
        assertEquals(0, store.value!!.failureCount)
        assertEquals(0, session.logins)
        val verification = repo.verificationSession()!!
        session.checkAction = { user }
        repo.completeVerification(verification.generation, emptyList())
        assertEquals(AuthStatus.AUTHENTICATED, repo.state.value.status)
    }

    @Test fun disablingAutoLoginDeletesPasswordAndDoesNotRefreshAfterExpiry() = runTest {
        val store = MemoryStore(cached())
        val settings = FakeSettings()
        val session = FakeSession().apply { checkAction = { fail(AuthFailure.EXPIRED) } }
        val repo = AuthRepository(store, settings, { session }, StandardTestDispatcher(testScheduler))
        repo.initialize(); repo.disableAutoLogin()
        assertFalse(settings.autoLogin)
        assertNull(store.value!!.password)
        assertNotNull(repo.state.value.profile)
        repo.checkAndRefresh()
        assertEquals(0, session.renewals)
        assertEquals(0, session.logins)
        assertEquals(AuthStatus.SIGNED_OUT, repo.state.value.status)
    }

    @Test fun logoutWinsAgainstLateNetworkResult() = runTest {
        val release = CompletableDeferred<Unit>()
        val store = MemoryStore(cached())
        val session = FakeSession().apply { checkAction = { release.await(); user } }
        val repo = AuthRepository(store, FakeSettings(), { session }, StandardTestDispatcher(testScheduler))
        repo.initialize()
        val job = launch { repo.checkAndRefresh() }
        runCurrent()
        repo.logout()
        release.complete(Unit)
        job.join()
        assertEquals(AuthStatus.SIGNED_OUT, repo.state.value.status)
        assertNull(store.value!!.password)
        assertNull(store.value!!.profile)
    }

    @Test fun cancelledLoginCannotWriteCredentials() = runTest {
        val release = CompletableDeferred<Unit>()
        val store = MemoryStore(null)
        val session = FakeSession().apply { loginAction = { _, _ -> release.await(); user } }
        val repo = AuthRepository(store, FakeSettings(), { session }, StandardTestDispatcher(testScheduler))
        repo.initialize()
        val job = async {
            try { repo.login("student01", "synthetic-password", true); false }
            catch (e: AuthException) { e.kind == AuthFailure.CANCELLED }
        }
        runCurrent()
        repo.cancelLogin()
        release.complete(Unit)
        assertTrue(job.await())
        assertNull(store.value?.password)
        assertEquals(AuthStatus.SIGNED_OUT, repo.state.value.status)
    }

    @Test fun abandonedWebViewCannotRestoreSession() = runTest {
        val repo = AuthRepository(MemoryStore(cached()), FakeSettings(), { FakeSession() }, StandardTestDispatcher(testScheduler))
        val ticket = repo.startVerification()
        repo.logout()
        try { repo.completeVerification(ticket.generation, emptyList()); error("Expected cancellation") }
        catch (e: AuthException) { assertEquals(AuthFailure.CANCELLED, e.kind) }
    }

    @Test fun failedManualLoginDoesNotCachePasswordAndPreservesExactInput() = runTest {
        val store = MemoryStore(null)
        val session = FakeSession().apply {
            loginAction = { account, password ->
                assertEquals("student01", account); assertEquals(" +&密码% ", password)
                fail(AuthFailure.CREDENTIALS)
            }
        }
        val repo = AuthRepository(store, FakeSettings(), { session }, StandardTestDispatcher(testScheduler))
        try { repo.login(" student01 ", " +&密码% ", true); error("Expected failure") }
        catch (e: AuthException) { assertEquals(AuthFailure.CREDENTIALS, e.kind) }
        assertNull(store.value?.password)
    }

    @Test fun webLoginDoesNotSaveUnverifiedPasswordOrReuseOtherAccountsPassword() = runTest {
        val store = MemoryStore(cached())
        val settings = FakeSettings()
        val session = FakeSession().apply { checkAction = { UserProfile("another-student", "另一个测试用户") } }
        val repo = AuthRepository(store, settings, { session }, StandardTestDispatcher(testScheduler))
        val ticket = repo.startVerification()
        repo.completeVerification(ticket.generation, emptyList())
        assertNull(store.value!!.password)
        assertFalse(settings.autoLogin)
        assertEquals("another-student", repo.state.value.profile!!.account)
    }

    @Test fun corruptedStorageBecomesSignedOutWithNotice() = runTest {
        val store = object : SessionStore {
            var cleared = false
            override fun load(): StoredSession = throw java.security.GeneralSecurityException()
            override fun save(session: StoredSession) {}
            override fun clear() { cleared = true }
        }
        val repo = AuthRepository(store, FakeSettings(), { FakeSession() }, StandardTestDispatcher(testScheduler))
        repo.initialize()
        assertTrue(store.cleared)
        assertEquals(AuthStatus.SIGNED_OUT, repo.state.value.status)
        assertNotNull(repo.state.value.notice)
    }

    @Test fun protectedRequestRetriesOnlyOnceAfterSharedRecovery() = runTest {
        var gets = 0
        val session = FakeSession().apply {
            getAction = { gets++; if (gets == 1) fail(AuthFailure.EXPIRED) else "protected response" }
            checkAction = { fail(AuthFailure.EXPIRED) }; renewAction = { user }
        }
        val repo = AuthRepository(MemoryStore(cached()), FakeSettings(), { session }, StandardTestDispatcher(testScheduler))
        assertEquals("protected response", repo.authenticatedGet("https://i.hdu.edu.cn/test".toHttpUrl()))
        assertEquals(2, gets)
        assertEquals(1, session.renewals)
    }

    @Test fun passiveWakeFailuresDoNotExposeLoginErrors() = runTest {
        val session = FakeSession().apply { checkAction = { fail(AuthFailure.NETWORK) } }
        val store = MemoryStore(cached())
        val repo = AuthRepository(store, FakeSettings(), { session }, StandardTestDispatcher(testScheduler))
        repo.checkAndRefresh(passive = true)
        assertNotNull(repo.state.value.profile)
        assertNull(repo.state.value.message)
        assertEquals(0, store.value!!.failureCount)
    }

    @Test fun foregroundChecksWaitForConnectivityAndCancelOnScreenOff() = runTest {
        val foreground = MutableStateFlow(false)
        val online = MutableStateFlow(false)
        var checks = 0
        val session = FakeSession().apply { checkAction = { checks++; user } }
        val repo = AuthRepository(MemoryStore(cached()), FakeSettings(), { session }, StandardTestDispatcher(testScheduler))
        val observer = backgroundScope.launch { observeForegroundSession(foreground, online, repo) }
        foreground.value = true
        runCurrent()
        advanceTimeBy(5_000)
        assertEquals(0, checks)
        online.value = true
        runCurrent()
        advanceTimeBy(500)
        foreground.value = false
        runCurrent()
        advanceTimeBy(2_000)
        assertEquals(0, checks)
        foreground.value = true
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, checks)
        observer.cancel()
    }

    @Test fun wakeNetworkFailureRetriesSilentlyOnceNetworkSettles() = runTest {
        val session = FakeSession()
        var checks = 0
        session.checkAction = { checks++; if (checks == 1) fail(AuthFailure.NETWORK) else user }
        val repo = AuthRepository(MemoryStore(cached()), FakeSettings(), { session }, StandardTestDispatcher(testScheduler))
        val observer = backgroundScope.launch { observeForegroundSession(MutableStateFlow(true), MutableStateFlow(true), repo) }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, checks)
        assertNull(repo.state.value.message)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, checks)
        assertEquals(AuthStatus.AUTHENTICATED, repo.state.value.status)
        observer.cancel()
    }

    @Test fun anInFlightCheckDoesNotRestoreAnAcknowledgedNotice() = runTest {
        val release = CompletableDeferred<Unit>()
        val store = MemoryStore(cached().copy(notice = "one-time notice"))
        val session = FakeSession().apply { checkAction = { release.await(); user } }
        val repo = AuthRepository(store, FakeSettings(), { session }, StandardTestDispatcher(testScheduler))
        repo.initialize()
        val check = launch { repo.checkAndRefresh() }
        runCurrent()
        repo.acknowledgeNotice("one-time notice")
        release.complete(Unit)
        check.join()
        assertNull(repo.state.value.notice)
        assertNull(store.value!!.notice)
    }

    private class MemoryStore(var value: StoredSession?) : SessionStore {
        override fun load() = value
        override fun save(session: StoredSession) { value = session }
        override fun clear() { value = null }
    }
    private class FakeSettings : AuthSettings {
        private var enabled = true
        override val autoLogin get() = enabled
        override fun setAutoLogin(enabled: Boolean) { this.enabled = enabled }
    }
    private class FakeSession : AuthSession {
        override val cookies = emptyList<StoredCookie>()
        override val verificationUrl = "https://sso.hdu.edu.cn/login"
        var logins = 0
        var renewals = 0
        var checkAction: suspend () -> UserProfile = { UserProfile("student01", "测试用户") }
        var renewAction: suspend () -> UserProfile = { error("Unexpected renewal") }
        var loginAction: suspend (String, String) -> UserProfile = { _, _ -> error("Unexpected login") }
        var getAction: suspend () -> String = { error("Unexpected request") }
        override suspend fun check() = checkAction()
        override suspend fun renewSso(): UserProfile { renewals++; return renewAction() }
        override suspend fun login(account: String, password: String): UserProfile { logins++; return loginAction(account, password) }
        override suspend fun get(url: HttpUrl) = getAction()
    }
}
