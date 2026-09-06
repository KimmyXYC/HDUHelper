package moe.nepnep.hduhelper.data.campuscode

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.nepnep.hduhelper.data.auth.AuthException
import moe.nepnep.hduhelper.data.auth.AuthFailure
import moe.nepnep.hduhelper.data.auth.ServiceAuthorizer
import moe.nepnep.hduhelper.data.auth.ServiceIdentity

class CampusCodeRepository(
    private val auth: ServiceAuthorizer,
    private val sessions: CampusCodeSessionFactory = YmtApi(),
    private val endpoints: YmtEndpoints = YmtEndpoints(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CampusCodeSource {
    private val work = Mutex()
    private val epoch = AtomicLong()
    private val cached = AtomicReference<Pair<ServiceIdentity, CampusCodeSession>?>(null)

    override fun clear() { epoch.incrementAndGet(); cached.set(null) }

    override suspend fun refresh(): CampusCode = withContext(io) {
        work.withLock {
            val identity = auth.serviceIdentity() ?: throw AuthException(AuthFailure.EXPIRED, "请先登录")
            val captured = epoch.get()
            fun check() {
                if (!auth.isCurrent(identity) || epoch.get() != captured) throw AuthException(AuthFailure.CANCELLED, "操作已取消")
            }
            suspend fun authorize(): CampusCodeSession {
                check()
                val session = sessions.create()
                val ticket = auth.authorizeService(identity, endpoints.service)
                check()
                session.authorize(ticket)
                check()
                val entry = identity to session
                cached.set(entry)
                // Logout may have cleared the cache between the preceding check and publication.
                try { check() } catch (e: AuthException) { cached.compareAndSet(entry, null); throw e }
                return session
            }
            var session = cached.get()?.takeIf { it.first == identity }?.second
            for (attempt in 0..1) {
                try {
                    check()
                    val active = session ?: authorize()
                    val result = active.fetch(identity.account)
                    check()
                    return@withLock result
                } catch (e: CampusCodeException) {
                    if (e.kind != CampusCodeFailure.AUTHORIZATION) throw e
                    cached.set(null)
                    session = null
                    if (attempt == 1) throw e
                }
            }
            error("Unreachable")
        }
    }
}
