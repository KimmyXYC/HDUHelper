package moe.nepnep.hduhelper.data.grades

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import moe.nepnep.hduhelper.data.auth.*
import moe.nepnep.hduhelper.data.timetable.*
import okhttp3.HttpUrl
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GradeRepositoryTest {
    private class Authorizer : ServiceAuthorizer {
        override val sessionGeneration = MutableStateFlow(1L)
        var account = "student"
        var grants = 0
        override fun serviceIdentity() = ServiceIdentity(sessionGeneration.value, account)
        override fun isCurrent(identity: ServiceIdentity) = identity == serviceIdentity()
        override suspend fun authorizeService(identity: ServiceIdentity, service: HttpUrl): String { grants++; return "synthetic" }
    }
    private class Store : GradeStore {
        var value: GradeSnapshot? = null
        override fun load(account: String, term: AcademicTerm?) = value?.takeIf { it.account == account && (term == null || it.term.key == term.key) }
        override fun save(data: GradeSnapshot) { value = data }
        override fun clear() { value = null }
    }
    private open class Session : TimetableSession {
        override suspend fun authorize(ticket: String) = Unit
        override suspend fun catalog() = error("Grades must not load the timetable catalog")
        override suspend fun fetch(account: String, term: AcademicTerm, catalog: TimetableCatalog): TimetableData = error("Grades must not fetch timetable")
        override suspend fun gradeCatalog() = catalog
        override suspend fun fetchGrades(account: String, term: AcademicTerm, catalog: TimetableCatalog) = snapshot(grade())
        override suspend fun gradeComponents(term: AcademicTerm, grade: CourseGrade) = listOf(GradeComponent("平时成绩", "0"))
    }
    @Test fun retriesExpiredAuthorizationOnceAndKeepsCacheOnNetworkFailure() = runTest {
        val auth = Authorizer(); val store = Store(); var calls = 0; var fail = false
        val repo = GradeRepository(auth, store, { object : Session() {
            override suspend fun fetchGrades(account: String, term: AcademicTerm, catalog: TimetableCatalog): GradeSnapshot {
                calls++
                if (calls == 1) throw TimetableException(TimetableFailure.AUTHORIZATION, "expired")
                if (fail) throw TimetableException(TimetableFailure.NETWORK, "offline")
                return snapshot(grade())
            }
        } }, io = StandardTestDispatcher(testScheduler))
        assertEquals(catalog, repo.catalog())
        val data = repo.refresh(term, catalog)
        assertEquals(2, auth.grants)
        assertEquals(data, repo.cached("student", term))
        fail = true
        assertTrue(runCatching { repo.refresh(term, catalog) }.isFailure)
        assertEquals(2, auth.grants)
        assertEquals(data, store.value)
    }
    @Test fun boundsDetailConcurrencyAndPublishesPartialSuccess() = runTest {
        val auth = Authorizer(); val store = Store(); var running = 0; var maximum = 0
        val session = object : Session() {
            override suspend fun gradeComponents(term: AcademicTerm, grade: CourseGrade): List<GradeComponent> {
                running++; maximum = maxOf(maximum, running)
                try {
                    delay(100)
                    if (grade.id == "3") throw TimetableException(TimetableFailure.NETWORK, "offline")
                    return super.gradeComponents(term, grade)
                } finally { running-- }
            }
        }
        val repo = GradeRepository(auth, store, { session }, io = StandardTestDispatcher(testScheduler))
        val result = repo.details(snapshot(*(1..8).map { grade(it.toString()) }.toTypedArray()))
        assertEquals(3, maximum)
        assertEquals(7, result.items.count { it.detailsLoaded })
        assertTrue(result.detailsFailed)
        assertEquals("0", result.items.first().components.single().score)
        assertEquals(result, store.value)
    }
    @Test fun identityInvalidationPreventsLateCacheWritesAndCrossAccountDetails() = runTest {
        val auth = Authorizer(); val store = Store(); val gate = CompletableDeferred<Unit>()
        val repo = GradeRepository(auth, store, { object : Session() {
            override suspend fun fetchGrades(account: String, term: AcademicTerm, catalog: TimetableCatalog): GradeSnapshot {
                gate.await(); return snapshot(grade())
            }
        } }, io = StandardTestDispatcher(testScheduler))
        val result = async { runCatching { repo.refresh(term, catalog) } }
        runCurrent(); auth.account = "other"; auth.sessionGeneration.value++; repo.clear(); gate.complete(Unit)
        assertEquals(AuthFailure.CANCELLED, (result.await().exceptionOrNull() as AuthException).kind)
        assertNull(store.value)
        assertTrue(runCatching { repo.details(snapshot(grade())) }.isFailure)
        assertNull(store.value)
    }
    @Test fun cancelledDetailBatchDoesNotPublishIncompleteSnapshot() = runTest {
        val store = Store(); val auth = Authorizer(); val gate = CompletableDeferred<Unit>()
        val repo = GradeRepository(auth, store, { object : Session() {
            override suspend fun gradeComponents(term: AcademicTerm, grade: CourseGrade): List<GradeComponent> {
                gate.await(); return emptyList()
            }
        } }, io = StandardTestDispatcher(testScheduler))
        val initial = repo.refresh(term, catalog)
        val job = launch { repo.details(initial) }
        runCurrent(); job.cancelAndJoin()
        assertEquals(initial, store.value)
    }
}
