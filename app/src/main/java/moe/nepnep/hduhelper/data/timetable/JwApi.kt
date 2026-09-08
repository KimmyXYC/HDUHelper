package moe.nepnep.hduhelper.data.timetable

import moe.nepnep.hduhelper.data.grades.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.nepnep.hduhelper.data.timetable.TimetableParser.text
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import kotlin.coroutines.resumeWithException

class JwEndpoints(
    val origin: HttpUrl = "https://newjw.hdu.edu.cn/".toHttpUrl(),
    val service: HttpUrl = "http://newjw.hdu.edu.cn/sso/driot4login".toHttpUrl(),
) {
    val index = origin.resolve("/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default")!!
    val gradeIndex = origin.resolve("/jwglxt/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005&layout=default")!!
    val examIndex = origin.resolve("/jwglxt/kwgl/kscx_cxXsksxxIndex.html?gnmkdm=N358105&layout=default")!!
    internal fun accepts(url: HttpUrl): Boolean = url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port &&
        url.username.isEmpty() && url.password.isEmpty() && url.fragment == null

    internal fun callbackTarget(url: HttpUrl): HttpUrl {
        val secured = if (origin.isHttps && url.scheme == "http" && url.host == origin.host && url.port == 80) {
            url.newBuilder().scheme("https").port(origin.port).build()
        } else url
        val path = secured.encodedPath
        val allowed = path == "/sso/driot4login" || Regex("^/sso/driot4login;jsessionid=[A-Za-z0-9._-]+$").matches(path) ||
            path in listOf("/jwglxt/ticketlogin", "/jwglxt/xtgl/login_slogin.html", "/jwglxt/xtgl/index_initMenu.html")
        if (!accepts(secured) || !allowed) throw TimetableException(TimetableFailure.PROTOCOL, "无法安全处理教务授权跳转")
        return secured
    }
}

internal class JwCookieJar(private val endpoints: JwEndpoints) : CookieJar {
    private val values = mutableListOf<Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!endpoints.accepts(url)) return
        cookies.forEach { c ->
            values.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            if (c.expiresAt > System.currentTimeMillis()) values += c
        }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        values.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return if (endpoints.accepts(url)) values.filter { it.matches(url) } else emptyList()
    }
}

class JwApi(private val endpoints: JwEndpoints = JwEndpoints(), private val now: () -> Long = System::currentTimeMillis) : TimetableSessionFactory {
    override fun create(): TimetableSession = NetworkSession()

    private inner class NetworkSession : TimetableSession {
        private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).cookieJar(JwCookieJar(endpoints))
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

        override suspend fun authorize(ticket: String) {
            // Obtain the deployment's route cookie without following the bootstrap redirect back to CAS.
            response(Request.Builder().url(endpoints.origin.resolve("/sso/driot4login")!!).build()).use {
                if (!it.isSuccessful && it.code !in redirects) throw TimetableException(TimetableFailure.SERVICE, "教务授权入口暂不可用")
            }
            var url = endpoints.origin.resolve("/sso/driot4login")!!.newBuilder().addQueryParameter("ticket", ticket).build()
            repeat(12) {
                url = endpoints.callbackTarget(url)
                response(Request.Builder().url(url).build()).use { r ->
                    if (r.code in redirects) {
                        url = r.header("Location")?.let { url.resolve(it) } ?: protocol()
                    } else {
                        checkStatus(r)
                        val body = readBody(r)
                        if (loginHtml(body)) expired()
                        if (url.encodedPath != "/jwglxt/xtgl/index_initMenu.html") protocol()
                        return
                    }
                }
            }
            protocol()
        }

        override suspend fun catalog(): TimetableCatalog = TimetableParser.catalog(get(endpoints.index))

        override suspend fun fetch(account: String, term: AcademicTerm, catalog: TimetableCatalog): TimetableData {
            val form = linkedMapOf("xnm" to term.year, "xqm" to term.code, "kzlx" to "ck", "xsdm" to "", "kclbdm" to "", "kclxdm" to "")
            val body = post("/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151", form)
            val courses = TimetableParser.courses(TimetableParser.objectResponse(body), account, term)
            val calendar = TimetableParser.calendar(post("/jwglxt/kbcx/xskbcxZccx_cxZcByXnxq.html?gnmkdm=N2154", mapOf("xnm" to term.year, "xqm" to term.code)))
            val clocks = mutableListOf<CampusClock>()
            val warnings = courses.warnings.toMutableList()
            for ((campus, meetings) in courses.meetings.groupBy { it.campusId }.toSortedMap()) {
                if (campus.isBlank()) continue
                val clockForm = mapOf("xnm" to term.year, "xqm" to term.code, "xqh_id" to campus)
                try {
                    val groups = TimetableParser.json(post("/jwglxt/kbcx/xskbcx_cxRsd.html?gnmkdm=N2151", clockForm)) as? kotlinx.serialization.json.JsonArray ?: protocol()
                    val groupNames = groups.filterIsInstance<kotlinx.serialization.json.JsonObject>().map { it.text("rsdmc") }
                    val periods = TimetableParser.periods(post("/jwglxt/kbcx/xskbcx_cxRjc.html?gnmkdm=N2151", clockForm))
                    clocks += CampusClock(campus, meetings.first().campusName.ifBlank { "校区 $campus" }, periods)
                    if (periods.isEmpty() || groupNames.isEmpty()) warnings += "部分校区作息暂不可用"
                } catch (e: TimetableException) {
                    if (e.kind == TimetableFailure.AUTHORIZATION) throw e
                    clocks += CampusClock(campus, meetings.first().campusName.ifBlank { "校区 $campus" }, emptyList())
                    warnings += "部分校区作息暂不可用"
                }
            }
            return TimetableData(account, term, catalog, courses.meetings, courses.others, calendar, clocks, now(), warnings.distinct())
        }

        override suspend fun fetchExams(account: String, term: AcademicTerm): ExamSnapshot {
            val all = mutableListOf<ExamArrangement>()
            var page = 1
            var expectedTotal: Int? = null
            while (true) {
                val result = ExamParser.page(post("/jwglxt/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105", linkedMapOf(
                    "xnm" to term.year, "xqm" to term.code, "ksmcdmb_id" to "", "kch" to "", "kc" to "", "ksrq" to "", "kkbm_id" to "",
                    "_search" to "false", "queryModel.showCount" to "100", "queryModel.currentPage" to page.toString(),
                    "queryModel.sortName" to " ", "queryModel.sortOrder" to "asc",
                )), account, term)
                if (result.page != page || result.pages !in 0..1000 || result.total < 0 ||
                    expectedTotal?.let { it != result.total } == true || (result.pages > page && result.items.isEmpty())) protocol()
                expectedTotal = result.total
                all += result.items
                if (page >= result.pages) {
                    if (all.size != result.total) protocol()
                    return ExamSnapshot(all.distinctBy { it.id }, now())
                }
                page++
            }
        }

        override suspend fun gradeCatalog(): TimetableCatalog = TimetableParser.catalog(get(endpoints.gradeIndex))

        override suspend fun fetchGrades(account: String, term: AcademicTerm, catalog: TimetableCatalog): GradeSnapshot {
            val all = mutableListOf<CourseGrade>()
            var page = 1
            var expectedTotal: Int? = null
            var expectedPages: Int? = null
            while (true) {
                val result = GradeParser.page(post("/jwglxt/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005", linkedMapOf(
                    "xnm" to term.year, "xqm" to term.code, "sfzgcj" to "", "kcbj" to "",
                    "_search" to "false", "queryModel.showCount" to "100", "queryModel.currentPage" to page.toString(),
                    "queryModel.sortName" to "", "queryModel.sortOrder" to "asc",
                )), account, term)
                if (result.page != page || result.pages !in 0..1000 || result.total < 0 ||
                    expectedTotal?.let { it != result.total } == true || expectedPages?.let { it != result.pages } == true ||
                    (result.pages > page && result.items.isEmpty())) GradeParser.fail()
                expectedTotal = result.total
                expectedPages = result.pages
                all += result.items
                if (page >= result.pages) {
                    if (all.size != result.total) GradeParser.fail()
                    return GradeSnapshot(account, term, catalog, all.distinctBy { it.id }, now())
                }
                page++
            }
        }

        override suspend fun gradeComponents(term: AcademicTerm, grade: CourseGrade): List<GradeComponent> {
            if (grade.teachingClass.isBlank() || grade.studentId.isBlank()) GradeParser.fail()
            return GradeParser.components(post("/jwglxt/cjcx/cjcx_cxCjxqGjh.html?gnmkdm=N305005", mapOf(
                "jxb_id" to grade.teachingClass, "xnm" to term.year, "xqm" to term.code,
                "xh_id" to grade.studentId, "kcmc" to grade.name,
            )))
        }

        private suspend fun get(url: HttpUrl): String = query(Request.Builder().url(url).build())
        private suspend fun post(path: String, values: Map<String, String>): String = query(Request.Builder()
            .url(endpoints.origin.resolve(path)!!).post(FormBody.Builder().apply { values.forEach { (k, v) -> add(k, v) } }.build()).build())

        private suspend fun query(request: Request): String = response(request.newBuilder()
            .header("X-Requested-With", "XMLHttpRequest").header("Referer", (when { request.url.encodedPath.startsWith("/jwglxt/cjcx/") -> endpoints.gradeIndex; request.url.encodedPath == endpoints.examIndex.encodedPath -> endpoints.examIndex; else -> endpoints.index }).toString()).build()).use { r ->
            if (r.code in redirects) {
                val target = r.header("Location")?.let { request.url.resolve(it) }
                if (target?.encodedPath?.contains("login", ignoreCase = true) == true) expired()
                protocol()
            }
            checkStatus(r)
            val body = readBody(r)
            if (loginHtml(body)) expired()
            body
        }

        private suspend fun response(request: Request): Response {
            if (!endpoints.accepts(request.url)) protocol()
            return try { http.newCall(request.newBuilder().header("User-Agent", "HDUHelper/1.0 Android").header("Cache-Control", "no-store").build()).awaitJw() }
            catch (_: IOException) { throw TimetableException(TimetableFailure.NETWORK, "无法连接教务系统，请检查网络后重试") }
        }
        private fun readBody(r: Response): String = try { r.body.string() } catch (_: IOException) { throw TimetableException(TimetableFailure.NETWORK, "教务数据读取中断，请重试") }
        private fun checkStatus(r: Response) {
            if (r.code == 901 || r.code == 401) expired()
            if (r.code == 403) throw TimetableException(TimetableFailure.PERMISSION, "没有访问此教务数据的权限")
            if (!r.isSuccessful) throw TimetableException(TimetableFailure.SERVICE, "教务系统暂不可用，请稍后重试")
        }
    }

    companion object {
        private val redirects = listOf(301, 302, 303, 307, 308)
        internal fun loginHtml(body: String): Boolean = body.trimStart().startsWith('<') && Jsoup.parse(body).let {
            it.getElementById("login-page-flowkey") != null || it.title().contains("统一身份认证") || it.title().contains("用户登录") || it.select("input#yhm:not([type=hidden])").isNotEmpty()
        }
        private fun expired(): Nothing = throw TimetableException(TimetableFailure.AUTHORIZATION, "教务登录已过期，请重新授权")
        private fun protocol(): Nothing = throw TimetableException(TimetableFailure.PROTOCOL, "教务响应或跳转格式发生变化")
    }
}

private suspend fun Call.awaitJw(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
    })
}
