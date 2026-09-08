package moe.nepnep.hduhelper.data.grades

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import moe.nepnep.hduhelper.data.timetable.*
import org.junit.Assert.*
import org.junit.Test

class GradeApiTest {
    private fun row(key: String) = """{"key":"$key","xh":"student","xnm":"2026","xqm":"3","kcmc":"合成课程","xf":"2","jd":"4"}"""
    private fun page(number: Int, pages: Int, total: Int, rows: String) =
        """{"currentPage":$number,"totalPage":$pages,"totalResult":$total,"items":[$rows]}"""
    @Test fun allPagesAreFetchedWithGradeRefererAndSameRecordDuplicatesRemoved() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(page(1, 2, 3, row("a") + "," + row("b"))).build())
            server.enqueue(MockResponse.Builder().body(page(2, 2, 3, row("b"))).build())
            val session = JwApi(JwEndpoints(server.url("/"), server.url("/sso/driot4login"))).create()
            val data = session.fetchGrades("student", term, catalog)
            assertEquals(2, data.items.size)
            val first = server.takeRequest()
            assertTrue(first.url.encodedPath.endsWith("cjcx_cxXsgrcj.html"))
            assertTrue(first.headers["Referer"]!!.contains("N305005"))
            val body = first.body!!.utf8()
            assertTrue(body.contains("queryModel.currentPage=1"))
            assertTrue(body.contains("queryModel.sortName=&"))
            assertTrue(server.takeRequest().body!!.utf8().contains("queryModel.currentPage=2"))
        }
    }
    @Test fun rejectsIncompleteChangingOrEmptyIntermediatePages() = runBlocking {
        for (bodies in listOf(listOf(page(1, 1, 2, row("a"))), listOf(page(1, 2, 2, "")),
            listOf(page(1, 2, 2, row("a")), page(2, 2, 3, row("b"))), listOf(page(2, 2, 1, row("a"))))) {
            MockWebServer().use { server ->
                server.start()
                bodies.forEach { server.enqueue(MockResponse.Builder().body(it).build()) }
                val session = JwApi(JwEndpoints(server.url("/"), server.url("/sso/driot4login"))).create()
                assertTrue(runCatching { session.fetchGrades("student", term, catalog) }.exceptionOrNull() is TimetableException)
            }
        }
    }
    @Test fun loginHtmlAndRedirectAreAuthorizationFailuresAndEmptyTermIsValid() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val session = JwApi(JwEndpoints(server.url("/"), server.url("/sso/driot4login"))).create()
            server.enqueue(MockResponse.Builder().body("<html><title>用户登录</title><input id='yhm'></html>").build())
            assertEquals(TimetableFailure.AUTHORIZATION, (runCatching { session.fetchGrades("student", term, catalog) }.exceptionOrNull() as TimetableException).kind)
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/jwglxt/xtgl/login_slogin.html").build())
            assertEquals(TimetableFailure.AUTHORIZATION, (runCatching { session.fetchGrades("student", term, catalog) }.exceptionOrNull() as TimetableException).kind)
            server.enqueue(MockResponse.Builder().body(page(1, 0, 0, "")).build())
            assertTrue(session.fetchGrades("student", term, catalog).items.isEmpty())
        }
    }
    @Test fun authenticatedCatalogHiddenAccountFieldIsNotALoginPage() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""<html><input id="yhm" type="hidden" value="synthetic">
                <select id="xnm"><option value="2026" selected>2026-2027</option></select>
                <select id="xqm"><option value="3" selected>1</option></select></html>""").build())
            val session = JwApi(JwEndpoints(server.url("/"), server.url("/sso/driot4login"))).create()
            assertEquals(term, session.gradeCatalog().current)
        }
    }
}
