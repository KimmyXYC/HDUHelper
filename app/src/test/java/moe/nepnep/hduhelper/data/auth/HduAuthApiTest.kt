package moe.nepnep.hduhelper.data.auth

import java.net.URLDecoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HduAuthApiTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoints: AuthEndpoints
    private lateinit var api: HduAuthApi
    private val key = "AAECAwQFBgcICQoLDA0ODw=="
    private val profileJson = """{"result":"1","data":{"loginName":"student01","userName":"测试用户"}}"""
    private val loginHtml get() = """<html><head><title>统一身份认证平台</title></head><body><p id="current-login-type">UsernamePassword</p><p id="login-rule-type">normal</p><p id="login-page-flowkey">fresh-flow</p><p id="login-croypto">$key</p></body></html>"""

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        endpoints = AuthEndpoints(server.url("/").newBuilder().host("localhost").build(), server.url("/").newBuilder().host("127.0.0.1").build())
        api = HduAuthApi(endpoints)
    }
    @After fun cleanup() = server.close()
    private fun response(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).build()

    @Test fun casFormEncryptionRedirectCookiesAndProfile() = runBlocking {
        server.enqueue(MockResponse.Builder().body(loginHtml).addHeader("Set-Cookie", "SESSION=flow-cookie; Path=/; HttpOnly").build())
        server.enqueue(response("""{"code":200,"data":{"captchaInvisible":false}}"""))
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", endpoints.portal.resolve("sopcb/?ticket=fake-ticket").toString())
            .addHeader("Set-Cookie", "SOURCEID_TGC=fake-tgc; Path=/; HttpOnly").build())
        server.enqueue(MockResponse.Builder().body("<html>portal</html>").addHeader("Set-Cookie", "portal-session=portal-value; Path=/").build())
        server.enqueue(response("hduHelper($profileJson);"))
        val session = api.create(emptyList())
        val password = " imaginary +&密码% "
        assertEquals(UserProfile("student01", "测试用户"), session.login("student01", password))
        assertEquals("GET", server.takeRequest().method)
        val captcha = server.takeRequest()
        assertTrue(captcha.target.contains("findCaptchaCount/student01"))
        val csrfKey = captcha.headers["Csrf-Key"]!!
        val encodedKey = Base64.getEncoder().encodeToString(csrfKey.toByteArray())
        val csrfPayload = encodedKey.take(encodedKey.length / 2) + encodedKey + encodedKey.drop(encodedKey.length / 2)
        val expectedCsrf = java.security.MessageDigest.getInstance("MD5").digest(csrfPayload.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(expectedCsrf, captcha.headers["Csrf-Value"])
        val posted = server.takeRequest()
        val fields = posted.body!!.utf8().split('&').associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals("POST", posted.method)
        assertEquals("fresh-flow", fields["execution"])
        assertEquals("UsernamePassword", fields["type"])
        assertEquals("submit", fields["_eventId"])
        assertEquals(password, decrypt(fields.getValue("password")))
        assertEquals("{}", decrypt(fields.getValue("captcha_payload")))
        assertFalse(posted.body!!.utf8().contains(password))
        val portalRequest = server.takeRequest()
        assertEquals("GET", portalRequest.method)
        assertNull(portalRequest.headers["Cookie"])
        assertTrue(server.takeRequest().headers["Cookie"].orEmpty().contains("portal-session=portal-value"))
        assertTrue(session.cookies.any { it.name == "SOURCEID_TGC" && it.httpOnly })
    }

    @Test fun csrfHeadersAreFreshForEveryRequest() {
        val a = HduAuthApi.csrfHeaders()
        val b = HduAuthApi.csrfHeaders()
        assertNotEquals(a.first, b.first)
        assertNotEquals(a.second, b.second)
        assertEquals(32, a.first.length)
    }

    @Test fun serviceTicketUsesExistingSsoCookiesAndStopsBeforeBusinessCallback() = runBlocking {
        val cookie = Cookie.Builder().name("TGC").value("synthetic").hostOnlyDomain("127.0.0.1").build()
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "${endpoints.campusCodeService}?ticket=synthetic-ticket").build())
        assertEquals("synthetic-ticket", api.create(listOf(StoredCookie.from(cookie))).authorizeService(endpoints.campusCodeService))
        val request = server.takeRequest()
        assertTrue(request.target.contains("service="))
        assertEquals("TGC=synthetic", request.headers["Cookie"])
        assertEquals(1, server.requestCount)
        expectSuspendFailure(AuthFailure.PROTOCOL) { api.create(emptyList()).authorizeService("https://example.com/login".toHttpUrl()) }
    }

    @Test fun serviceAuthorizationDetectsExpiredSsoAndRejectsOtherCallbacks() = runBlocking {
        server.enqueue(response(loginHtml))
        expectSuspendFailure(AuthFailure.EXPIRED) { api.create(emptyList()).authorizeService(endpoints.campusCodeService) }
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://ymt.hdu.edu.cn/other?ticket=synthetic").build())
        expectSuspendFailure(AuthFailure.PROTOCOL) { api.create(emptyList()).authorizeService(endpoints.campusCodeService) }
    }

    @Test fun successfulJsonRequiresAccountAndExplicitResult() {
        assertEquals("student01", HduAuthApi.parseProfile(profileJson).account)
        for (body in listOf("""{"result":1,"data":{}}""", """{"data":{"loginName":"x"}}""", """{"result":{},"data":{}}""", "<html>server error</html>")) {
            expectFailure(AuthFailure.PROTOCOL) { HduAuthApi.parseProfile(body) }
        }
    }

    @Test fun htmlLoginWithHttp200IsExpired() = runBlocking {
        server.enqueue(response(loginHtml))
        expectSuspendFailure(AuthFailure.EXPIRED) { api.create(emptyList()).check() }
    }

    @Test fun redirectToSsoIsExpiredAndDoesNotSilentlyReauthenticate() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", endpoints.login.toString()).build())
        expectSuspendFailure(AuthFailure.EXPIRED) { api.create(emptyList()).check() }
        assertEquals(1, server.requestCount)
    }

    @Test fun forbiddenOrServerErrorIsNotInvalidPassword() = runBlocking {
        for (status in listOf(403, 429, 500, 503)) {
            server.enqueue(response("error", status))
            expectSuspendFailure(AuthFailure.SERVICE) { api.create(emptyList()).check() }
        }
    }

    @Test fun captchaPreflightStopsPasswordSubmission() = runBlocking {
        server.enqueue(response(loginHtml))
        server.enqueue(response("""{"code":200,"data":{"captchaInvisible":true}}"""))
        expectSuspendFailure(AuthFailure.VERIFICATION) { api.create(emptyList()).login("student01", "fake-password") }
        assertEquals(2, server.requestCount)
    }

    @Test fun passwordRejectionIsAnAuthenticationFailure() = runBlocking {
        server.enqueue(response(loginHtml))
        server.enqueue(response("""{"code":200,"data":{"captchaInvisible":false}}"""))
        server.enqueue(response(loginHtml.replace("</body>", "<p id=\"login-error-code\">1030027</p></body>")))
        expectSuspendFailure(AuthFailure.CREDENTIALS) { api.create(emptyList()).login("student01", "wrong-fake-password") }
    }

    @Test fun lockedOrUnknownServerChallengeRequiresInteractiveVerification() {
        for (code in listOf("1030028", "3910001", "1320007", "unexpected")) {
            val doc = org.jsoup.Jsoup.parse("<p id=login-error-code>$code</p>")
            assertEquals(AuthFailure.VERIFICATION, HduAuthApi.errorFromLogin(doc)?.kind)
        }
    }

    @Test fun ssoRenewalDoesNotSubmitPassword() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", endpoints.portal.toString()).build())
        server.enqueue(response("<html>portal</html>"))
        server.enqueue(response(profileJson))
        assertEquals("student01", api.create(emptyList()).renewSso().account)
        repeat(3) { assertEquals("GET", server.takeRequest().method) }
    }

    @Test fun externalRedirectIsRejected() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://unrelated.example/").build())
        expectSuspendFailure(AuthFailure.PROTOCOL) { api.create(emptyList()).check() }
        assertEquals(1, server.requestCount)
    }

    @Test fun cookieScopeExpiryAndDeletionArePreserved() {
        val prod = AuthEndpoints()
        val jar = SessionCookieJar(emptyList(), prod)
        val cookie = Cookie.Builder().name("session").value("fake").hostOnlyDomain("i.hdu.edu.cn")
            .path("/sopplus/").secure().httpOnly().expiresAt(System.currentTimeMillis() + 60_000).build()
        jar.saveFromResponse(prod.userInfo, listOf(cookie))
        assertEquals(1, jar.loadForRequest(prod.userInfo).size)
        assertTrue(jar.loadForRequest(prod.sso).isEmpty())
        assertTrue(jar.loadForRequest(prod.portal).isEmpty())
        assertTrue(jar.loadForRequest("http://i.hdu.edu.cn/sopplus/".toHttpUrl()).isEmpty())
        val restored = SessionCookieJar(jar.snapshot(), prod).loadForRequest(prod.userInfo).single()
        assertTrue(restored.hostOnly && restored.secure && restored.httpOnly && restored.persistent)
        jar.saveFromResponse(prod.userInfo, listOf(Cookie.parse(prod.userInfo, "session=; Max-Age=0; Path=/sopplus/")!!))
        assertTrue(jar.snapshot().isEmpty())
    }

    @Test fun userInfoBusinessUnauthorizedIsExpired() {
        expectFailure(AuthFailure.EXPIRED) {
            HduAuthApi.parseProfile("""{"code":401,"message":"Unauthorized"}""")
        }
    }

    private fun decrypt(encoded: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Base64.getDecoder().decode(key), "AES"))
        return cipher.doFinal(Base64.getDecoder().decode(encoded)).toString(Charsets.UTF_8)
    }

    private fun expectFailure(kind: AuthFailure, block: () -> Unit) {
        try { block(); fail("Expected $kind") } catch (e: AuthException) { assertEquals(kind, e.kind) }
    }
    private suspend fun expectSuspendFailure(kind: AuthFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected $kind") } catch (e: AuthException) { assertEquals(kind, e.kind) }
    }
}
