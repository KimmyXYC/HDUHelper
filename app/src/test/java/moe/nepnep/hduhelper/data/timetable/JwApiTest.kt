package moe.nepnep.hduhelper.data.timetable

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import mockwebserver3.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class JwApiTest {
    private fun response(body: String="", code: Int=200, location: String?=null, cookie: String?=null) = MockResponse.Builder().code(code).body(body).apply {
        location?.let { addHeader("Location", it) }; cookie?.let { addHeader("Set-Cookie",it) }
    }.build()
    @Test fun serviceIdentityRemainsHttpButCallbackTransportIsHttps() {
        val e=JwEndpoints()
        assertEquals("http",e.service.scheme)
        assertEquals("https://newjw.hdu.edu.cn/sso/driot4login?ticket=synthetic",e.callbackTarget("http://newjw.hdu.edu.cn/sso/driot4login?ticket=synthetic".toHttpUrl()).toString())
        for (url in listOf("https://evil.example/sso/driot4login", "https://newjw.hdu.edu.cn/other", "https://newjw.hdu.edu.cn:8443/sso/driot4login", "https://u:p@newjw.hdu.edu.cn/sso/driot4login", "https://newjw.hdu.edu.cn/sso/driot4login#x")) {
            assertTrue(runCatching { e.callbackTarget(url.toHttpUrl()) }.isFailure)
        }
    }
    @Test fun fullBridgePreservesPathScopedCookiesAndConsumesServerGrant() = runBlocking {
        MockWebServer().use { s ->
            s.start()
            val e=JwEndpoints(s.url("/"),"http://newjw.hdu.edu.cn/sso/driot4login".toHttpUrl())
            s.enqueue(response(code=302,location="https://cas.hdu.edu.cn/cas/login",cookie="route=synthetic-route; Path=/"))
            s.enqueue(response(code=302,location="/sso/driot4login;jsessionid=synthetic",cookie="JSESSIONID=bridge; Path=/sso; HttpOnly"))
            s.enqueue(response(code=302,location="/jwglxt/ticketlogin?uid=synthetic&timestamp=1&verify=synthetic"))
            s.enqueue(response(code=302,location="/jwglxt/xtgl/login_slogin.html",cookie="JSESSIONID=academic; Path=/jwglxt; HttpOnly"))
            s.enqueue(response(code=302,location="/jwglxt/xtgl/index_initMenu.html"))
            s.enqueue(response("<title>本科教学管理服务平台</title>"))
            JwApi(e).create().authorize("synthetic+&ticket")
            val req=(1..6).map { s.takeRequest() }
            assertTrue(req[1].target.contains("ticket=synthetic%2B%26ticket"))
            assertTrue(req[2].headers["Cookie"]!!.contains("JSESSIONID=bridge"))
            assertFalse(req[3].headers["Cookie"].orEmpty().contains("bridge"))
            assertTrue(req[4].headers["Cookie"]!!.contains("JSESSIONID=academic"))
            assertTrue(req.all { it.method=="GET" })
        }
    }
    @Test fun unauthenticatedAjaxAndLoginHtmlAreAuthorizationFailures() = runBlocking {
        MockWebServer().use { s ->
            s.start();val session=JwApi(JwEndpoints(s.url("/"))).create()
            for (r in listOf(response(code=901),response(code=302,location="/jwglxt/xtgl/login_slogin.html"),response("<title>统一身份认证平台</title>"))) {
                s.enqueue(r)
                val error=runCatching { session.catalog() }.exceptionOrNull() as TimetableException
                assertEquals(TimetableFailure.AUTHORIZATION,error.kind)
                assertEquals("XMLHttpRequest",s.takeRequest().headers["X-Requested-With"])
            }
        }
    }
    @Test fun fetchUsesBothCampusClocksAndDoesNotPageTheMainArray() = runBlocking {
        MockWebServer().use { s ->
            s.start();val session=JwApi(JwEndpoints(s.url("/")),now={1234}).create()
            s.enqueue(response("""{"xsxx":{"XH":"student","XNM":"2026","XQM":"3"},"xkkg":true,"jfckbkg":true,"xnxqsfkz":"false","kbList":[
                {"kcmc":"A","kch_id":"A","xqj":"1","jcs":"3","zcd":"1周","xqh_id":"1","xqmc":"一校区"},
                {"kcmc":"B","kch_id":"B","xqj":"2","jcs":"3","zcd":"1周","xqh_id":"2","xqmc":"二校区"}],"sjkList":[]}"""))
            s.enqueue(response("""[{"zs":"1","rq":"2026-09-14/2026-09-20"}]"""))
            for (start in listOf("10:00","09:50")) {
                s.enqueue(response("""[{"rsdmc":"上午","rsdzjs":"5"}]"""))
                s.enqueue(response("""[{"jcmc":"3","qssj":"$start","jssj":"10:45","rsdmc":"上午"}]"""))
            }
            val d=session.fetch("student",term,catalog)
            assertEquals(2,d.clocks.size)
            assertEquals("09:50",d.clocks[1].periods.single().start)
            assertEquals(1234,d.updatedAt)
            val requests=(1..6).map { s.takeRequest() }
            assertTrue(requests[0].target.endsWith("gnmkdm=N2151"))
            assertEquals("xnm=2026&xqm=3&kzlx=ck&xsdm=&kclbdm=&kclxdm=",requests[0].body!!.utf8())
            assertTrue(requests[4].body!!.utf8().contains("xqh_id=2"))
        }
    }
}
