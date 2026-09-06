package moe.nepnep.hduhelper.data.auth

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.jsoup.nodes.Document
import kotlin.coroutines.resumeWithException

class AuthEndpoints(
    val portal: HttpUrl = "https://i.hdu.edu.cn/".toHttpUrl(),
    val sso: HttpUrl = "https://sso.hdu.edu.cn/".toHttpUrl(),
) {
    val login: HttpUrl = sso.resolve("login")!!.newBuilder()
        .addQueryParameter("service", portal.resolve("sopcb/").toString()).build()
    val userInfo: HttpUrl = portal.resolve("sopplus/_web/portal/api/user/loginInfo.rst")!!.newBuilder()
        .addQueryParameter("_p", "YXM9MiZ0PTUmZD05NyZwPTEmZj0yMiZtPU4m")
        .addQueryParameter("callback", "hduHelper").build()
    fun accepts(url: HttpUrl): Boolean = listOf(portal, sso).any {
        it.scheme == url.scheme && it.host == url.host && it.port == url.port
    }
}

/** Session-local cookies isolate in-flight requests from logout and account changes. */
class SessionCookieJar(initial: List<StoredCookie>, private val endpoints: AuthEndpoints) : CookieJar {
    private val values = initial.mapNotNull { runCatching { it.toCookie() }.getOrNull() }.toMutableList()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!endpoints.accepts(url)) return
        cookies.forEach { cookie ->
            values.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) values += cookie
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        values.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return if (endpoints.accepts(url)) values.filter { it.matches(url) } else emptyList()
    }

    @Synchronized
    fun snapshot(): List<StoredCookie> {
        values.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return values.map(StoredCookie::from)
    }
}

interface AuthSession {
    val cookies: List<StoredCookie>
    val verificationUrl: String
    suspend fun check(): UserProfile
    suspend fun renewSso(): UserProfile
    suspend fun login(account: String, password: String): UserProfile
    suspend fun get(url: HttpUrl): String
}

fun interface AuthSessionFactory { fun create(cookies: List<StoredCookie>): AuthSession }

class HduAuthApi(private val endpoints: AuthEndpoints = AuthEndpoints()) : AuthSessionFactory {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    override fun create(cookies: List<StoredCookie>): AuthSession = NetworkSession(cookies)

    private inner class NetworkSession(initial: List<StoredCookie>) : AuthSession {
        private val jar = SessionCookieJar(initial, endpoints)
        private val http = client.newBuilder().cookieJar(jar).build()
        override val cookies: List<StoredCookie> get() = jar.snapshot()
        // Always re-enter through a fresh CAS flow; execution/ticket values are never persisted in a route.
        override val verificationUrl: String get() = endpoints.login.toString()

        override suspend fun check(): UserProfile {
            val reply = request(Request.Builder().url(endpoints.userInfo).build(), allowSso = false)
            return parseProfile(reply.body)
        }

        override suspend fun renewSso(): UserProfile {
            val reply = request(Request.Builder().url(endpoints.login).build(), allowSso = true)
            if (reply.url.host == endpoints.sso.host && reply.url.encodedPath.startsWith("/login")) {
                val doc = Jsoup.parse(reply.body)
                errorFromLogin(doc)?.let { throw it }
                throw AuthException(AuthFailure.EXPIRED, "登录已过期")
            }
            return check()
        }

        override suspend fun login(account: String, password: String): UserProfile {
            val entry = request(Request.Builder().url(endpoints.login).build(), allowSso = true)
            if (entry.url.host != endpoints.sso.host || !entry.url.encodedPath.startsWith("/login")) {
                val profile = check()
                if (profile.account != account) throw AuthException(AuthFailure.PROTOCOL, "登录账号不一致，请重新登录")
                return profile
            }
            val doc = Jsoup.parse(entry.body)
            if (needsVerification(doc)) throw AuthException(AuthFailure.VERIFICATION, "学校要求额外验证，请在官方页面完成")
            val execution = doc.getElementById("login-page-flowkey")?.text().orEmpty()
            val crypto = doc.getElementById("login-croypto")?.text().orEmpty()
            if (execution.isBlank() || crypto.isBlank()) throw AuthException(AuthFailure.PROTOCOL, "登录页面发生变化，请稍后重试或使用官方验证")

            // Mirror the official pre-submit captcha check, without attempting to solve any challenge.
            val countUrl = endpoints.sso.newBuilder().addPathSegments("api/protected/user/findCaptchaCount")
                .addPathSegment(account).build()
            val csrf = csrfHeaders()
            val captcha = parseObject(request(
                Request.Builder().url(countUrl).header("Csrf-Key", csrf.first).header("Csrf-Value", csrf.second).build(),
                allowSso = true,
            ).body)
            if ((captcha["code"] as? JsonPrimitive)?.content != "200") throw AuthException(AuthFailure.SERVICE, "暂时无法检查学校登录服务，请稍后重试")
            val data = captcha["data"] as? JsonObject ?: throw AuthException(AuthFailure.PROTOCOL, "登录验证响应无法识别，请使用官方验证")
            if ((data["captchaInvisible"] as? JsonPrimitive)?.content in listOf("true", "1")) {
                throw AuthException(AuthFailure.VERIFICATION, "学校要求验证码，请在官方页面完成")
            }

            val form = FormBody.Builder()
                .add("username", account).add("password", encryptPassword(crypto, password))
                .add("type", "UsernamePassword").add("_eventId", "submit")
                .add("execution", execution).add("croypto", crypto)
                .add("geolocation", "").add("captcha_code", "")
                .add("captcha_payload", encryptPassword(crypto, "{}"))
                .build()
            val result = request(
                Request.Builder().url(entry.url).header("Referer", entry.url.toString())
                    .header("Origin", endpoints.sso.toString().trimEnd('/')).post(form).build(),
                allowSso = true,
            )
            if (result.url.host == endpoints.sso.host) {
                val resultDoc = Jsoup.parse(result.body)
                errorFromLogin(resultDoc)?.let { throw it }
                if (needsVerification(resultDoc) || result.url.encodedPath != "/login") {
                    throw AuthException(AuthFailure.VERIFICATION, "请在官方页面完成身份验证")
                }
                throw AuthException(AuthFailure.PROTOCOL, "学校未确认登录成功，请重试或使用官方验证")
            }
            val profile = check()
            if (profile.account != account) throw AuthException(AuthFailure.PROTOCOL, "登录账号不一致，请重新登录")
            return profile
        }

        override suspend fun get(url: HttpUrl): String {
            val result = request(Request.Builder().url(url).build(), allowSso = false)
            if (isLoginHtml(result.body)) throw AuthException(AuthFailure.EXPIRED, "登录已过期")
            return result.body
        }

        private suspend fun request(original: Request, allowSso: Boolean): Reply {
            var next = original
            repeat(12) {
                if (!endpoints.accepts(next.url)) throw AuthException(AuthFailure.PROTOCOL, "学校登录跳转地址无法识别，请使用官方验证")
                val response = try {
                    http.newCall(next.newBuilder().header("User-Agent", "HDUHelper/1.0 Android").build()).await()
                } catch (_: IOException) {
                    throw AuthException(AuthFailure.NETWORK, "网络连接失败或超时，请检查网络后重试")
                }
                response.use { r ->
                    if (r.code in listOf(301, 302, 303, 307, 308)) {
                        val target = r.header("Location")?.let { r.request.url.resolve(it) }
                            ?: throw AuthException(AuthFailure.PROTOCOL, "学校返回了无法识别的跳转")
                        if (!endpoints.accepts(target)) throw AuthException(AuthFailure.PROTOCOL, "学校登录跳转地址无法识别，请使用官方验证")
                        if (!allowSso && target.host == endpoints.sso.host && target.encodedPath.startsWith("/login")) {
                            throw AuthException(AuthFailure.EXPIRED, "登录已过期")
                        }
                        // Credentials must never follow a cross-origin 307/308 redirect.
                        if (r.code in listOf(307, 308) && next.method != "GET" && (target.host != next.url.host || target.port != next.url.port || target.scheme != next.url.scheme)) {
                            throw AuthException(AuthFailure.PROTOCOL, "无法安全处理学校登录跳转")
                        }
                        next = if (r.code in listOf(307, 308)) next.newBuilder().url(target).build()
                        else Request.Builder().url(target).get().build()
                    } else {
                        if (r.code == 401) throw AuthException(AuthFailure.EXPIRED, "登录已过期")
                        if (!r.isSuccessful) throw AuthException(AuthFailure.SERVICE, "学校服务暂不可用，请稍后重试")
                        val body = try { r.body.string() } catch (_: IOException) {
                            throw AuthException(AuthFailure.NETWORK, "网络连接中断，请重试")
                        }
                        return Reply(r.request.url, body)
                    }
                }
            }
            throw AuthException(AuthFailure.PROTOCOL, "学校登录跳转次数过多，请使用官方验证")
        }
    }

    private class Reply(val url: HttpUrl, val body: String)

    companion object {
        /** The official protected endpoint requires this per-request CSRF pair, including for GET requests. */
        @android.annotation.SuppressLint("WeakHash") // School wire protocol, never used for password storage.
        internal fun csrfHeaders(): Pair<String, String> {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val random = SecureRandom()
            val key = buildString { repeat(32) { append(alphabet[random.nextInt(alphabet.length)]) } }
            val base64 = Base64.getEncoder().encodeToString(key.toByteArray(Charsets.US_ASCII))
            val middle = base64.length / 2
            val combined = base64.substring(0, middle) + base64 + base64.substring(middle)
            val digest = MessageDigest.getInstance("MD5").digest(combined.toByteArray(Charsets.US_ASCII))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return key to digest
        }

        @android.annotation.SuppressLint("GetInstance") // Required by the school protocol only; local storage uses AES-GCM.
        fun encryptPassword(keyBase64: String, value: String): String = try {
            val key = SecretKeySpec(Base64.getDecoder().decode(keyBase64), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
        } catch (_: Exception) {
            throw AuthException(AuthFailure.PROTOCOL, "无法读取学校登录加密参数")
        }

        internal fun isLoginHtml(body: String): Boolean {
            if (!body.trimStart().startsWith('<')) return false
            val doc = Jsoup.parse(body)
            return doc.getElementById("login-page-flowkey") != null || doc.title().contains("统一身份认证")
        }

        internal fun parseObject(body: String): JsonObject = try {
            val trimmed = body.trim().removePrefix("\uFEFF")
            val value = if (trimmed.startsWith('{')) trimmed else {
                val match = Regex("^[a-zA-Z_$][\\w$]*\\s*\\((.*)\\)\\s*;?\\s*$", RegexOption.DOT_MATCHES_ALL).matchEntire(trimmed)
                    ?: throw IllegalArgumentException()
                match.groupValues[1]
            }
            Json.parseToJsonElement(value).jsonObject
        } catch (_: Exception) {
            throw AuthException(AuthFailure.PROTOCOL, "学校响应格式无法识别，请稍后重试或使用官方验证")
        }

        internal fun parseProfile(body: String): UserProfile {
            if (isLoginHtml(body)) throw AuthException(AuthFailure.EXPIRED, "登录已过期")
            val root = parseObject(body)
            if ((root["code"] as? JsonPrimitive)?.content == "401") {
                throw AuthException(AuthFailure.EXPIRED, "登录已过期")
            }
            if ((root["result"] as? JsonPrimitive)?.content != "1") {
                val reason = (root["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (reason.contains("未登录") || reason.contains("登录超时") || reason.contains("not logged", true)) {
                    throw AuthException(AuthFailure.EXPIRED, "登录已过期")
                }
                throw AuthException(AuthFailure.PROTOCOL, "学校未返回有效登录状态")
            }
            val data = root["data"] as? JsonObject ?: throw AuthException(AuthFailure.PROTOCOL, "学校未返回账号信息")
            val account = (data["loginName"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (account.isEmpty()) throw AuthException(AuthFailure.PROTOCOL, "学校未返回有效账号")
            return UserProfile(account, (data["userName"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: account)
        }

        private fun needsVerification(doc: Document): Boolean {
            val type = doc.getElementById("current-login-type")?.text().orEmpty()
            val rule = doc.getElementById("login-rule-type")?.text().orEmpty()
            val vendor = doc.getElementById("recaptchaVendor")?.text().orEmpty()
            return (type.isNotEmpty() && type != "UsernamePassword") || (rule.isNotEmpty() && rule != "normal") ||
                doc.getElementById("sso-second")?.text()?.isNotBlank() == true ||
                doc.getElementById("recaptcha-invisible")?.text()?.trim() == "true" ||
                vendor in listOf("google", "geetest", "netease")
        }

        internal fun errorFromLogin(doc: Document): AuthException? {
            val code = doc.getElementById("login-error-code")?.text()?.trim().orEmpty()
                .ifEmpty { doc.getElementById("login-error-msg")?.text()?.trim().orEmpty() }
            if (code.isEmpty()) return null
            return when (code) {
                "1030023", "1030027", "1030031" -> AuthException(AuthFailure.CREDENTIALS, "账号或密码错误，请检查后重试")
                // Account lock, dormant accounts, captcha and all unfamiliar server challenges require user intervention.
                else -> AuthException(AuthFailure.VERIFICATION, "学校要求进一步验证，请在官方页面查看并处理")
            }
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
