package moe.nepnep.hduhelper.data.campuscode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.nepnep.hduhelper.data.auth.*
import okhttp3.HttpUrl
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CampusCodeRepositoryTest {
    private class Authorizer : ServiceAuthorizer {
        override val sessionGeneration = MutableStateFlow(1L)
        var account: String? = "synthetic-account"
        var grants = 0
        override fun serviceIdentity() = account?.let { ServiceIdentity(sessionGeneration.value, it) }
        override fun isCurrent(identity: ServiceIdentity) = identity == serviceIdentity()
        override suspend fun authorizeService(identity: ServiceIdentity, service: HttpUrl): String { grants++; return "synthetic-ticket" }
    }
    private fun code() = CampusCode("SYNTHETIC,7", CampusCodeProfile("测试", "学生", "学院"), 180, 1)

    @Test fun expiredBusinessSessionReauthorizesOnlyOnce() = runTest {
        val auth = Authorizer()
        var requests = 0
        var alwaysFail = false
        val factory = CampusCodeSessionFactory {
            object : CampusCodeSession {
                override suspend fun authorize(ticket: String) = Unit
                override suspend fun fetch(account: String): CampusCode {
                    requests++
                    if (requests == 1 || alwaysFail) throw CampusCodeException(CampusCodeFailure.AUTHORIZATION, "synthetic")
                    return code()
                }
            }
        }
        val repo = CampusCodeRepository(auth, factory, io = StandardTestDispatcher(testScheduler))
        repo.refresh()
        assertEquals(2, auth.grants)
        repo.refresh()
        assertEquals(2, auth.grants)
        alwaysFail = true
        try { repo.refresh(); fail() } catch (e: CampusCodeException) { assertEquals(CampusCodeFailure.AUTHORIZATION, e.kind) }
        assertEquals(3, auth.grants)
        assertEquals(5, requests)
    }

    @Test fun accountChangeDiscardsLateResultAndObtainsANewSession() = runTest {
        val auth = Authorizer()
        val release = CompletableDeferred<Unit>()
        val repo = CampusCodeRepository(auth, {
            object : CampusCodeSession {
                override suspend fun authorize(ticket: String) = Unit
                override suspend fun fetch(account: String): CampusCode { release.await(); return code() }
            }
        }, io = StandardTestDispatcher(testScheduler))
        val request = async { runCatching { repo.refresh() } }
        runCurrent()
        auth.account = "another-synthetic-account"; auth.sessionGeneration.value++; repo.clear()
        release.complete(Unit)
        val failure = request.await().exceptionOrNull() as AuthException
        assertEquals(AuthFailure.CANCELLED, failure.kind)
        repo.refresh()
        assertEquals(2, auth.grants)
    }
}
