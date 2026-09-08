package moe.nepnep.hduhelper.data.electric

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import moe.nepnep.hduhelper.data.auth.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Independent transport: school cookies never enter Neo requests, tokens never follow redirects. */
class NeoSession(private val auth: ServiceAuthorizer, private val store: NeoStore,
    private val base: HttpUrl = "https://api.hduhelp.com/hduhelp-neo/".toHttpUrl(),
    private val sso: HttpUrl = "https://sso.hdu.edu.cn/login".toHttpUrl(),
    private val callback: HttpUrl = "https://neo.hduhelp.com/auth".toHttpUrl(),
    private val http: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(java.time.Duration.ofSeconds(30)).build(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val work = Mutex()
    private val guard = Any()
    @Volatile private var invalidation = 0L
    private var requestInvalidation = 0L
    private var loaded = false
    private var tokens: NeoTokens? = null
    fun clear() = synchronized(guard) { invalidation++; tokens = null; loaded = true; store.clear() }
    private fun check(owner: ServiceIdentity) {
        if (invalidation != requestInvalidation || auth.sessionGeneration.value != owner.generation) throw AuthException(AuthFailure.CANCELLED, "操作已取消")
    }
    private fun save(owner: ServiceIdentity, value: NeoTokens) = synchronized(guard) {
        check(owner); store.save(value); tokens = value
    }
    private fun parseTokens(owner: ServiceIdentity, data: JsonObject): NeoTokens {
        val access = data.string("accessToken")?.takeIf { it.isNotBlank() } ?: throw ElectricException(message = "登录响应缺少令牌")
        val refresh = data.string("refreshToken")?.takeIf { it.isNotBlank() } ?: throw ElectricException(message = "登录响应缺少刷新令牌")
        return NeoTokens(owner.account, access, refresh,
            data.long("accessExpireAt") ?: data.long("accessTokenExpireAt") ?: 0,
            data.long("refreshExpireAt") ?: data.long("refreshTokenExpireAt") ?: 0)
    }
    private suspend fun login(owner: ServiceIdentity): NeoTokens {
        val entry = base.resolve("identity/login/url")!!.newBuilder().addQueryParameter("grant-key", "cas")
            .addQueryParameter("client_id", "app").addQueryParameter("redirect_uri", callback.toString())
            .addQueryParameter("return_to", "/navigation/electric").build()
        val data = jsonRequest(entry).jsonObject
        val url = data.string("url")?.toHttpUrl() ?: throw ElectricException()
        if (url.newBuilder().query(null).build() != sso || url.queryParameterNames != setOf("service")) throw ElectricException()
        val service = url.queryParameter("service")?.toHttpUrl() ?: throw ElectricException()
        if (service.newBuilder().query(null).build() != base.resolve("identity/login/sso") ||
            service.queryParameterNames != setOf("state") || service.queryParameterValues("state").size != 1 ||
            service.queryParameter("state")?.matches(Regex("[A-Za-z0-9_-]{16,256}")) != true) throw ElectricException()
        data.string("state")?.let { if (it != service.queryParameter("state")) throw ElectricException() }
        val ticket = auth.authorizeService(owner, service)
        check(owner)
        val request = Request.Builder().url(service.newBuilder().addQueryParameter("ticket", ticket).build()).build()
        val handoff = execute(request).use { response ->
            if (response.code !in listOf(302, 303)) throw ElectricException(message = "统一认证回调未完成，请重试")
            val target = response.header("Location")?.let { response.request.url.resolve(it) } ?: throw ElectricException()
            if (target.newBuilder().query(null).fragment(null).build() != callback || target.fragment != null ||
                target.queryParameterValues("code").size != 1) throw ElectricException(message = "登录回调地址无法识别")
            target.queryParameter("code")?.takeIf { it.isNotBlank() } ?: throw ElectricException()
        }
        check(owner)
        return parseTokens(owner, jsonRequest(base.resolve("identity/login/exchange")!!, "POST",
            buildJsonObject { put("code", handoff) }).jsonObject).also { save(owner, it) }
    }
    suspend fun request(path: String, method: String = "GET", body: JsonObject? = null): JsonElement = withContext(Dispatchers.IO) {
        work.withLock {
            requestInvalidation = invalidation
            val owner = auth.serviceIdentity() ?: throw ElectricException(loginRequired = true, message = "请先登录")
            var current = synchronized(guard) {
                check(owner)
                if (!loaded) { tokens = try { store.load() } catch (_: Exception) { store.clear(); null }; loaded = true }
                tokens?.takeIf { it.account == owner.account }
            }
            suspend fun renew(): NeoTokens {
                val old = current
                if (old != null && (old.refreshExpireAt == 0L || old.refreshExpireAt > now())) {
                    try {
                        return parseTokens(owner, jsonRequest(base.resolve("identity/auth/token/refresh")!!, "POST",
                            buildJsonObject { put("refreshToken", old.refreshToken) }).jsonObject).also { save(owner, it) }
                    } catch (e: ElectricException) { if (!e.loginRequired) throw e }
                }
                return login(owner)
            }
            if (current == null || current.accessExpireAt != 0L && current.accessExpireAt <= now() + 30) current = renew()
            check(owner)
            val url = base.resolve(path) ?: throw ElectricException()
            if (!url.toString().startsWith(base.toString()) || !path.startsWith("campuslife/electric/")) throw ElectricException()
            val result = try { jsonRequest(url, method, body, current.accessToken) }
            catch (e: ElectricException) {
                if (!e.loginRequired) throw e
                // Only a rejected authorization is retried, never an ambiguous mutation response.
                current = renew(); check(owner)
                jsonRequest(url, method, body, current.accessToken)
            }
            check(owner); result
        }
    }
    private fun execute(request: Request): Response = try { http.newCall(request).execute() }
        catch (_: IOException) { throw ElectricException(message = "网络连接失败，请重试") }
    private fun jsonRequest(url: HttpUrl, method: String = "GET", body: JsonObject? = null, token: String? = null): JsonElement {
        val request = Request.Builder().url(url).header("Accept", "application/json")
        token?.let { request.header("Authorization", "Bearer $it") }
        request.method(method, if (method in listOf("POST", "PUT")) (body ?: buildJsonObject {}).toString()
            .toRequestBody("application/json".toMediaType()) else null)
        return execute(request.build()).use { response ->
            if (response.code == 401) throw ElectricException(loginRequired = true, message = "登录已失效，请重新登录")
            if (!response.isSuccessful) throw ElectricException()
            val obj = try { Json.parseToJsonElement(response.body.string()).jsonObject } catch (_: Exception) { throw ElectricException() }
            if (obj.long("code") == 401L) throw ElectricException(loginRequired = true)
            if (obj.long("code") != 0L) throw ElectricException()
            obj["data"] ?: JsonNull
        }
    }
}
internal fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
