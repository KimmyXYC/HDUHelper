package moe.nepnep.hduhelper.data.timetable

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import moe.nepnep.hduhelper.data.auth.*
import okhttp3.HttpUrl
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TimetableRepositoryTest {
    private class Authorizer: ServiceAuthorizer {
        override val sessionGeneration=MutableStateFlow(1L)
        var account="student"
        var grants=0
        override fun serviceIdentity()=ServiceIdentity(sessionGeneration.value,account)
        override fun isCurrent(identity: ServiceIdentity)=identity==serviceIdentity()
        override suspend fun authorizeService(identity: ServiceIdentity,service: HttpUrl): String { grants++;return "synthetic" }
    }
    private class Store: TimetableStore {
        var value:TimetableData?=null
        override fun load(account:String,term:AcademicTerm?)=value?.takeIf { it.account==account && (term==null || it.term.key==term.key) }
        override fun save(data:TimetableData) { value=data }
        override fun clear() { value=null }
    }
    @Test fun retriesOnlyExpiredSessionAndKeepsCacheOnFailure()=runTest {
        val auth=Authorizer();val store=Store();var calls=0;var failure:TimetableFailure?=null
        val repo=TimetableRepository(auth,store,{
            object:TimetableSession {
                override suspend fun authorize(ticket:String)=Unit
                override suspend fun catalog()=catalog
                override suspend fun fetch(account:String,term:AcademicTerm,catalog:TimetableCatalog):TimetableData {
                    calls++
                    if(calls==1 || failure!=null)throw TimetableException(failure?:TimetableFailure.AUTHORIZATION,"synthetic")
                    return data(meeting("a"))
                }
            }
        },io=StandardTestDispatcher(testScheduler))
        var changes = 0
        repo.onChanged = { changes++ }
        repo.refresh(term,catalog)
        assertEquals(1, changes)
        assertEquals(2,auth.grants)
        assertNotNull(repo.cached("student",term))
        failure=TimetableFailure.PERMISSION
        assertTrue(runCatching { repo.refresh(term,catalog) }.isFailure)
        assertEquals(2,auth.grants)
        assertNotNull(repo.cached("student",term))
        failure=TimetableFailure.AUTHORIZATION
        assertTrue(runCatching { repo.refresh(term,catalog) }.isFailure)
        assertEquals(3,auth.grants)
        assertEquals(1, changes)
        repo.clear()
        assertEquals(2, changes)
    }
    @Test fun accountChangePreventsLateCacheWrites()=runTest {
        val auth=Authorizer();val store=Store();val release=CompletableDeferred<Unit>()
        val repo=TimetableRepository(auth,store,{
            object:TimetableSession {
                override suspend fun authorize(ticket:String)=Unit
                override suspend fun catalog()=catalog
                override suspend fun fetch(account:String,term:AcademicTerm,catalog:TimetableCatalog):TimetableData {release.await();return data(meeting("a"))}
            }
        },io=StandardTestDispatcher(testScheduler))
        val result=async {runCatching {repo.refresh(term,catalog)}}
        runCurrent();auth.account="another";auth.sessionGeneration.value++;repo.clear();release.complete(Unit)
        assertEquals(AuthFailure.CANCELLED,(result.await().exceptionOrNull() as AuthException).kind)
        assertNull(store.value)
    }
    @Test fun examFailurePreservesCacheWhileCoursesUpdateAndEmptySuccessClearsExams() = runTest {
        val auth = Authorizer(); val store = Store()
        val exam = ExamArrangement("exam", "测试考试", start = "2026-09-19T09:00", end = "2026-09-19T11:00")
        store.value = data(meeting("old")).copy(exams = ExamSnapshot(listOf(exam), 100))
        var fail = true
        val repo = TimetableRepository(auth, store, {
            object : TimetableSession {
                override suspend fun authorize(ticket: String) = Unit
                override suspend fun catalog() = catalog
                override suspend fun fetch(account: String, term: AcademicTerm, catalog: TimetableCatalog) = data(meeting("new"))
                override suspend fun fetchExams(account: String, term: AcademicTerm): ExamSnapshot {
                    if (fail) throw TimetableException(TimetableFailure.NETWORK, "synthetic")
                    return ExamSnapshot(updatedAt = 200)
                }
            }
        }, io = StandardTestDispatcher(testScheduler))
        val partial = repo.refresh(term, catalog)
        assertEquals("new", partial.meetings.single().id)
        assertEquals(listOf(exam), partial.exams.items)
        assertEquals(100L, partial.exams.updatedAt)
        assertTrue(partial.exams.failed)
        fail = false
        val empty = repo.refresh(term, catalog)
        assertTrue(empty.exams.items.isEmpty())
        assertEquals(200L, empty.exams.updatedAt)
        assertFalse(empty.exams.failed)
    }
}
