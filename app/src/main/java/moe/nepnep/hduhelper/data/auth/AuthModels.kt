package moe.nepnep.hduhelper.data.auth

import kotlinx.serialization.Serializable
import okhttp3.Cookie
import okhttp3.HttpUrl
import kotlinx.coroutines.flow.StateFlow

data class ServiceIdentity(val generation: Long, val account: String) {
    override fun toString() = "ServiceIdentity([redacted])"
}

interface ServiceAuthorizer {
    val sessionGeneration: StateFlow<Long>
    fun serviceIdentity(): ServiceIdentity?
    fun isCurrent(identity: ServiceIdentity): Boolean
    suspend fun authorizeService(identity: ServiceIdentity, service: HttpUrl): String
}

@Serializable
data class UserProfile(val account: String, val name: String)

enum class AuthStatus { LOADING, SIGNED_OUT, SIGNING_IN, AUTHENTICATED, REFRESHING, UNAVAILABLE, VERIFICATION_REQUIRED }

data class AuthState(
    val status: AuthStatus = AuthStatus.LOADING,
    val profile: UserProfile? = null,
    val account: String = "",
    val message: String? = null,
    val notice: String? = null,
)

enum class AuthFailure { EXPIRED, CREDENTIALS, VERIFICATION, NETWORK, SERVICE, PROTOCOL, STORAGE, CANCELLED }

// Never include request URLs, response bodies or credentials in exceptions.
class AuthException(val kind: AuthFailure, override val message: String) : Exception(message)

@Serializable
data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
    val persistent: Boolean,
) {
    fun toCookie(): Cookie = Cookie.Builder().name(name).value(value).path(path).apply {
        if (hostOnly) hostOnlyDomain(domain) else domain(domain)
        if (persistent) expiresAt(expiresAt)
        if (secure) secure()
        if (httpOnly) httpOnly()
    }.build()

    override fun toString() = "StoredCookie([redacted])"

    companion object {
        fun from(cookie: Cookie) = StoredCookie(
            cookie.name, cookie.value, cookie.domain, cookie.path, cookie.expiresAt,
            cookie.secure, cookie.httpOnly, cookie.hostOnly, cookie.persistent,
        )
    }
}

@Serializable
data class StoredSession(
    val account: String = "",
    val password: String? = null,
    val profile: UserProfile? = null,
    val cookies: List<StoredCookie> = emptyList(),
    val failureCount: Int = 0,
    val notice: String? = null,
) {
    override fun toString() = "StoredSession([redacted])"
}

interface SessionStore {
    fun load(): StoredSession?
    fun save(session: StoredSession)
    fun clear()
}
