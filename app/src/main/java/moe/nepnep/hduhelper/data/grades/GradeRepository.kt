package moe.nepnep.hduhelper.data.grades

import moe.nepnep.hduhelper.data.timetable.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

class GradeRepository(
    private val auth: ServiceAuthorizer,
    private val store: GradeStore,
    private val factory: TimetableSessionFactory = JwApi(),
    private val endpoints: JwEndpoints = JwEndpoints(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : GradeSource {
    private val work = Mutex()
    private val publication = Any()
    private val epoch = AtomicLong()
    private val session = AtomicReference<Pair<ServiceIdentity, TimetableSession>?>(null)

    override fun clear() = synchronized(publication) {
        epoch.incrementAndGet()
        session.set(null)
        store.clear()
    }

    override suspend fun cached(account: String, term: AcademicTerm?): GradeSnapshot? = withContext(io) {
        val identity = auth.serviceIdentity()?.takeIf { it.account == account } ?: return@withContext null
        val version = epoch.get()
        val data = store.load(account, term)
        if (auth.isCurrent(identity) && version == epoch.get()) data else null
    }

    override suspend fun catalog(): TimetableCatalog = operation { active, _, _ -> active.gradeCatalog() }

    override suspend fun refresh(term: AcademicTerm, catalog: TimetableCatalog): GradeSnapshot = operation { active, identity, captured ->
        val result = active.fetchGrades(identity.account, term, catalog)
        if (result.account != identity.account || result.term.key != term.key) GradeParser.fail()
        publish(result, identity, captured)
        result
    }

    override suspend fun details(snapshot: GradeSnapshot): GradeSnapshot = operation { active, identity, captured ->
        if (snapshot.account != identity.account) cancelled()
        val limit = Semaphore(3)
        val items = coroutineScope {
            snapshot.items.map { grade -> async {
                if (grade.detailsLoaded) grade else limit.withPermit {
                    try {
                        grade.copy(components = active.gradeComponents(snapshot.term, grade), detailsLoaded = true)
                    } catch (e: CancellationException) { throw e }
                    catch (e: TimetableException) {
                        if (e.kind == TimetableFailure.AUTHORIZATION) throw e
                        grade
                    }
                }
            } }.awaitAll()
        }
        val result = snapshot.copy(items = items, detailsFailed = items.any { !it.detailsLoaded })
        publish(result, identity, captured)
        result
    }

    private fun publish(result: GradeSnapshot, identity: ServiceIdentity, captured: Long) {
        check(identity, captured)
        synchronized(publication) {
            if (epoch.get() != captured) cancelled()
            try { store.save(result) } catch (_: Exception) { throw AuthException(AuthFailure.STORAGE, "成绩缓存保存失败，请重试") }
        }
        check(identity, captured)
    }

    private suspend fun <T> operation(block: suspend (TimetableSession, ServiceIdentity, Long) -> T): T = withContext(io) {
        work.withLock {
            val identity = auth.serviceIdentity() ?: throw AuthException(AuthFailure.EXPIRED, "请先登录")
            val captured = epoch.get()
            for (attempt in 0..1) {
                try {
                    check(identity, captured)
                    val active = session.get()?.takeIf { it.first == identity }?.second ?: run {
                        val ticket = auth.authorizeService(identity, endpoints.service)
                        check(identity, captured)
                        val created = factory.create()
                        created.authorize(ticket)
                        check(identity, captured)
                        val entry = identity to created
                        session.set(entry)
                        try { check(identity, captured) } catch (e: AuthException) { session.compareAndSet(entry, null); throw e }
                        created
                    }
                    val result = block(active, identity, captured)
                    check(identity, captured)
                    return@withLock result
                } catch (e: TimetableException) {
                    if (e.kind != TimetableFailure.AUTHORIZATION) throw e
                    session.set(null)
                    if (attempt == 1) throw e
                }
            }
            error("Unreachable")
        }
    }
    private fun check(identity: ServiceIdentity, captured: Long) { if (!auth.isCurrent(identity) || epoch.get() != captured) cancelled() }
    private fun cancelled(): Nothing = throw AuthException(AuthFailure.CANCELLED, "操作已取消")
}
