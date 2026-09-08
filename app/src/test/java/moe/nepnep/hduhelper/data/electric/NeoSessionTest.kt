package moe.nepnep.hduhelper.data.electric

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import mockwebserver3.*
import moe.nepnep.hduhelper.data.auth.*
import okhttp3.HttpUrl
import org.junit.Assert.*
import org.junit.Test

class NeoSessionTest {
    private class Store(var value: NeoTokens? = null) : NeoStore {
        override fun load() = value
        override fun save(session: NeoTokens) { value = session }
        override fun clear() { value = null }
    }
    private class Auth : ServiceAuthorizer {
        override val sessionGeneration = MutableStateFlow(1L)
        var calls = 0
        override fun serviceIdentity() = ServiceIdentity(sessionGeneration.value, "synthetic")
        override fun isCurrent(identity: ServiceIdentity) = identity.generation == sessionGeneration.value
        override suspend fun authorizeService(identity: ServiceIdentity, service: HttpUrl): String { calls++; return "test-ticket" }
    }
    private fun json(data: String) = MockResponse.Builder().body("""{"code":0,"data":$data}""").build()
    private fun tokens(access: String = "new-access") = """{"accessToken":"$access","refreshToken":"new-refresh","accessExpireAt":9999999999}"""
    @Test fun dynamicLoginExchangesCodeAndKeepsTokensOffRedirectRequests() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val auth = Auth(); val store = Store(); val base = server.url("/neo/")
            val callback = server.url("/auth")
            val service = base.resolve("identity/login/sso")!!.newBuilder().addQueryParameter("state", "abcdefghijklmnop").build()
            val login = server.url("/login").newBuilder().addQueryParameter("service", service.toString()).build()
            server.enqueue(json(buildJsonObject { put("url", login.toString()) }.toString()))
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "$callback?code=test-code").build())
            server.enqueue(json(tokens())); server.enqueue(json("null"))
            val repo = ElectricRepository(NeoSession(auth, store, base, server.url("/login"), callback))
            assertNull(repo.binding()); assertEquals(1, auth.calls)
            val entry = server.takeRequest(); assertTrue(entry.target.contains("client_id=app")); assertNull(entry.headers["Authorization"])
            val handoff = server.takeRequest(); assertTrue(handoff.target.contains("state=abcdefghijklmnop")); assertNull(handoff.headers["Cookie"])
            val exchange = server.takeRequest(); assertEquals("""{"code":"test-code"}""", exchange.body!!.utf8())
            assertEquals("Bearer new-access", server.takeRequest().headers["Authorization"])
        }
    }
    @Test fun rejectsMismatchedStateAndCrossOriginCallback() = runBlocking {
        for (badState in listOf(true, false)) MockWebServer().use { server ->
            server.start(); val auth = Auth(); val base = server.url("/neo/")
            val service = base.resolve("identity/login/sso")!!.newBuilder().addQueryParameter("state", "abcdefghijklmnop").build()
            val login = server.url("/login").newBuilder().addQueryParameter("service", service.toString()).build()
            server.enqueue(json(buildJsonObject { put("url", login.toString()); if (badState) put("state", "other-state") }.toString()))
            if (!badState) server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://example.com/auth?code=secret").build())
            try { NeoSession(auth, Store(), base, server.url("/login"), server.url("/auth")).request("campuslife/electric/binding"); fail() }
            catch (_: ElectricException) { }
            assertEquals(if (badState) 1 else 2, server.requestCount)
        }
    }
    @Test fun refreshRotatesTokensAndParsesNegativeAndMissingBalances() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val auth = Auth(); val store = Store(NeoTokens("synthetic", "old", "refresh", 1))
            server.enqueue(json(tokens())); server.enqueue(json("""{"balance":-1.5,"time":100}""")); server.enqueue(json("{}"))
            val repo = ElectricRepository(NeoSession(auth, store, server.url("/neo/")))
            assertEquals("-1.50", repo.balance().amount); assertNull(repo.balance().amount)
            assertTrue(server.takeRequest().target.endsWith("/identity/auth/token/refresh"))
            assertEquals("new-refresh", store.value!!.refreshToken); assertEquals(0, auth.calls)
        }
    }
    @Test fun accountChangeDuringResponseDiscardsResultAndCannotRestoreStorage() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val auth = Auth(); val store = Store(NeoTokens("synthetic", "old", "refresh", 1))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse { auth.sessionGeneration.value++; return json(tokens()) }
            }
            val neo = NeoSession(auth, store, server.url("/neo/"))
            try { neo.request("campuslife/electric/binding"); fail() }
            catch (e: AuthException) { assertEquals(AuthFailure.CANCELLED, e.kind) }
            assertEquals("old", store.value!!.accessToken)
        }
    }
    @Test fun rejectedRefreshReauthorizesThroughSsoOnlyOnce() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val auth = Auth(); val base = server.url("/neo/"); val callback = server.url("/auth")
            val store = Store(NeoTokens("synthetic", "expired", "expired-refresh", 1))
            val service = base.resolve("identity/login/sso")!!.newBuilder().addQueryParameter("state", "abcdefghijklmnop").build()
            val login = server.url("/login").newBuilder().addQueryParameter("service", service.toString()).build()
            server.enqueue(MockResponse.Builder().code(401).build())
            server.enqueue(json(buildJsonObject { put("url", login.toString()) }.toString()))
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "$callback?code=test-code").build())
            server.enqueue(json(tokens())); server.enqueue(json("null"))
            assertNull(ElectricRepository(NeoSession(auth, store, base, server.url("/login"), callback)).binding())
            assertEquals(1, auth.calls); assertEquals(5, server.requestCount)
        }
    }
    @Test fun synchronousInvalidationPreventsSavingBeforeGenerationFlowPublishes() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val store = Store(NeoTokens("synthetic", "old", "refresh", 1)); val auth = Auth()
            val neo = NeoSession(auth, store, server.url("/neo/"))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse { neo.clear(); return json(tokens()) }
            }
            try { neo.request("campuslife/electric/binding"); fail() }
            catch (e: AuthException) { assertEquals(AuthFailure.CANCELLED, e.kind) }
            assertNull(store.value)
        }
    }
    @Test fun historyUsesServerChangesAndWritesRoomIdWithoutRetryingNetworkFailure() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val store = Store(NeoTokens("synthetic", "token", "refresh"))
            val repo = ElectricRepository(NeoSession(Auth(), store, server.url("/neo/")))
            server.enqueue(json("""{"average":"1.20","history":[{"time":"2026-09-01","fee":"10","change":"-"},{"time":"2026-09-02","fee":"20","change":"10"}]}"""))
            assertEquals("10", repo.history().items.first().change)
            server.enqueue(json("{}")); repo.bind(123)
            server.takeRequest(); val bind = server.takeRequest(); assertEquals("PUT", bind.method); assertEquals("""{"room_id":123}""", bind.body!!.utf8())
            server.enqueue(MockResponse.Builder().code(503).build())
            try { repo.unbind(); fail() } catch (_: ElectricException) { }
            assertEquals(3, server.requestCount)
        }
    }
}
