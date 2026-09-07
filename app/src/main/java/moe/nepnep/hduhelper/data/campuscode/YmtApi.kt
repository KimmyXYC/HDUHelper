package moe.nepnep.hduhelper.data.campuscode

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resumeWithException

class YmtEndpoints(val origin: HttpUrl = "https://ymt.hdu.edu.cn/".toHttpUrl()) {
    val service: HttpUrl = origin.resolve("uias-h5/login")!!
    fun accepts(url: HttpUrl) = url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port &&
        url.username.isEmpty() && url.password.isEmpty() && url.fragment == null
}

class YmtApi(private val endpoints: YmtEndpoints = YmtEndpoints(), private val now: () -> Long = System::currentTimeMillis) : CampusCodeSessionFactory {
    private val base = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build()

    override fun create(): CampusCodeSession = NetworkSession()

    private inner class NetworkSession : CampusCodeSession {
        private val jar = object : CookieJar {
            private val cookies = mutableListOf<Cookie>()
            @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (!endpoints.accepts(url)) return
                cookies.forEach { cookie ->
                    this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                    if (cookie.expiresAt > now()) this.cookies += cookie
                }
            }
            @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
                if (endpoints.accepts(url)) cookies.filter { it.expiresAt > now() && it.matches(url) } else emptyList()
        }
        private val client = base.newBuilder().cookieJar(jar).build()
        private var appToken: String? = null
        private var refreshSeconds: Long? = null

        override suspend fun authorize(ticket: String) {
            get("/uias/authentication/index/cas/login", linkedMapOf("ticket" to ticket, "loginSrc" to "2",
                "redirectUrl" to "", "service" to endpoints.service.toString()))
            val portalToken = get("/uias/authentication/index/token-h5", linkedMapOf("appUrl" to "", "isCas" to "1", "isAppEnter" to "0")).obj().required("value")
            val applications = get("/uias/portal-manage/portal-h5/my-functions", token = portalToken) as? JsonArray ?: protocol()
            val app = applications.filterIsInstance<JsonObject>().firstOrNull { it.text("applicationName") == "电子凭证" } ?: protocol("未找到电子凭证入口")
            val url = app.required("envIp").let { runCatching { it.toHttpUrl() }.getOrNull() } ?: protocol()
            if (!endpoints.accepts(url) || url.encodedPath != "/uias-h5/uias-app/voucher" || url.query != null) protocol("电子凭证入口发生变化")
            appToken = get("/uias/authentication/index/token-h5", mapOf("clientId" to app.required("appId")), portalToken).obj().required("value")
            refreshSeconds = null
        }

        override suspend fun fetch(account: String): CampusCode {
            val token = appToken ?: throw CampusCodeException(CampusCodeFailure.AUTHORIZATION, "请重新授权一码通")
            val user = get("/user-center/user-info", token = token).obj()
            val userAccount = user.text("userNumber").ifEmpty { user.text("accountNumber") }
            if (userAccount != account) protocol("一码通账号与当前登录账号不一致，请重新登录")
            val id = user.required("id")
            if (id.toLongOrNull() == null) protocol()
            val info = get("/uias/portal-manage/portal-h5/electronicEvidenceInfo", token = token).obj()
            if (info.text("userNumber").isNotEmpty() && info.text("userNumber") != account) protocol("电子凭证账号不一致，请重新登录")
            val interval = refreshSeconds ?: refreshInterval(get("/uias/portal-manage/portal-h5/getFreshTime", token = token).obj()).also { refreshSeconds = it }
            val raw = get("/uias/portal-manage/portal-h5/qr-code", YmtSigner.qrParameters(id, now()), token)
            val content = (raw as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: protocol()
            if (content.isBlank() || content.length > 2048 || content.first() in '\u4e00'..'\u9fa5') protocol("学校暂未返回有效二维码，请重试")
            return CampusCode(content, CampusCodeProfile(info.text("name").ifEmpty { user.text("userName") },
                identityName(user.text("mainClassNames")), info.text("schoolClass").ifEmpty { info.text("college") }), interval, now(), balance(user))
        }

        private suspend fun get(path: String, params: Map<String, String> = emptyMap(), token: String? = null): JsonElement {
            val url = endpoints.origin.resolve(path)!!.newBuilder().apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
            if (!endpoints.accepts(url)) protocol()
            val request = Request.Builder().url(url).header("Accept", "application/json").header("Cache-Control", "no-store")
                .header("User-Agent", "HDUHelper/1.0 Android").apply {
                    YmtSigner.headers(path, params, now()).forEach { (key, value) -> header(key, value) }
                    token?.let { header("Authorization", it) }
                }.build()
            val response = try { client.newCall(request).awaitYmt() } catch (_: IOException) {
                throw CampusCodeException(CampusCodeFailure.NETWORK, "无法连接一码通，请检查网络后重试")
            }
            response.use { r ->
                if (r.code == 401) throw CampusCodeException(CampusCodeFailure.AUTHORIZATION, "一码通授权已过期")
                if (!r.isSuccessful) throw CampusCodeException(CampusCodeFailure.SERVICE, "一码通服务暂不可用，请稍后重试")
                val body = try { r.body.string() } catch (_: IOException) {
                    throw CampusCodeException(CampusCodeFailure.NETWORK, "网络连接中断，请重试")
                }
                val root = try { Json.parseToJsonElement(body) as? JsonObject ?: protocol() } catch (_: IllegalArgumentException) { protocol() }
                if (root.text("code") in listOf("401", "20078")) throw CampusCodeException(CampusCodeFailure.AUTHORIZATION, "一码通授权已过期")
                if (root.text("code") != "200") throw CampusCodeException(CampusCodeFailure.SERVICE, "学校未能提供电子凭证，请稍后重试")
                return root["data"] ?: protocol()
            }
        }
    }

    companion object {
        // The official voucher displays user-info.balance directly in yuan.
        // Missing or malformed balances must not be presented as zero or block the QR.
        internal fun balance(root: JsonObject): String? {
            val raw = root.text("balance").trim()
            if (!raw.matches(Regex("-?[0-9]{1,12}(\\.[0-9]{1,2})?"))) return null
            return raw.toBigDecimalOrNull()?.setScale(2)?.toPlainString()
        }

        internal fun refreshInterval(root: JsonObject): Long = (root["freshTime"] as? JsonPrimitive)?.longOrNull
            ?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 } ?: 300L

        internal fun identityName(value: String): String = when (value) {
            "本专科生", "研究生", "高起专", "清真（优惠）-R", "成教生", "本专科生-R", "研究生-R", "清真（优惠）" -> "学生"
            "教师优惠", "职工", "教师不优惠" -> "教工"
            "外来人员", "搭伙青山湖", "美食城员工-暂停使用", "校友", "校友嘉宾", "匿名支付" -> "其他人员"
            "临时卡", "余额为负冻结卡", "临时卡（3个月）", "临时卡（9个月）-暂停使用", "临时卡-暂停使用" -> "临时人员"
            else -> "—"
        }

        private fun JsonElement.obj() = this as? JsonObject ?: protocol()
        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun JsonObject.required(key: String) = text(key).takeIf { it.isNotBlank() } ?: protocol()
        private fun protocol(message: String = "一码通响应格式发生变化，请稍后重试"): Nothing = throw CampusCodeException(CampusCodeFailure.PROTOCOL, message)
    }
}

private suspend fun Call.awaitYmt(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
    })
}
