package moe.nepnep.hduhelper.data.campuscode

enum class CampusCodeFailure { AUTHORIZATION, NETWORK, SERVICE, PROTOCOL, CANCELLED }

class CampusCodeException(val kind: CampusCodeFailure, override val message: String) : Exception(message)

data class CampusCodeProfile(val name: String, val identity: String, val college: String) {
    override fun toString() = "CampusCodeProfile([redacted])"
}

class CampusCode(val content: String, val profile: CampusCodeProfile, val refreshSeconds: Long, val updatedAt: Long, val balance: String? = null) {
    override fun toString() = "CampusCode([redacted])"
}

interface CampusCodeSession {
    suspend fun authorize(ticket: String)
    suspend fun fetch(account: String): CampusCode
}

fun interface CampusCodeSessionFactory { fun create(): CampusCodeSession }

interface CampusCodeSource {
    suspend fun refresh(): CampusCode
    fun clear()
}
