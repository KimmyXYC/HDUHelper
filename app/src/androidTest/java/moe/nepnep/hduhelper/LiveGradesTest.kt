package moe.nepnep.hduhelper

import android.net.LocalServerSocket
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.*
import androidx.test.platform.app.InstrumentationRegistry
import moe.nepnep.hduhelper.data.auth.*
import moe.nepnep.hduhelper.data.settings.AuthSettings
import moe.nepnep.hduhelper.data.grades.*
import moe.nepnep.hduhelper.data.timetable.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Opt-in read-only network check; real credentials and grades remain in memory. */
class LiveGradesTest {
    @Test(timeout = 300_000)
    fun nativeSsoGradesDetailsAndCache() = runBlocking {
        val name = InstrumentationRegistry.getArguments().getString("liveAuthSocket")
        assumeNotNull(name)
        LocalServerSocket(name!!).use { server -> server.accept().use { socket ->
            socket.soTimeout = 30_000
            val input = DataInputStream(socket.inputStream)
            val output = DataOutputStream(socket.outputStream)
            fun stage(message: String) { output.writeUTF(message); output.flush() }
            val size = input.readInt(); require(size in 1..4096)
            val bytes = ByteArray(size).also { input.readFully(it) }
            val credentials = JSONObject(bytes.toString(Charsets.UTF_8)); bytes.fill(0)
            val sessions = object : SessionStore {
                var value: StoredSession? = null
                override fun load() = value
                override fun save(session: StoredSession) { value = session }
                override fun clear() { value = null }
            }
            val settings = object : AuthSettings {
                override var autoLogin = true
                    private set
                override fun setAutoLogin(enabled: Boolean) { autoLogin = enabled }
            }
            var logins = 0
            val api = HduAuthApi()
            val auth = AuthRepository(sessions, settings, AuthSessionFactory { cookies ->
                val session = api.create(cookies)
                object : AuthSession by session {
                    override suspend fun login(account: String, password: String): UserProfile {
                        logins++; return session.login(account, password)
                    }
                }
            })
            val store = object : GradeStore {
                var value: GradeSnapshot? = null
                override fun load(account: String, term: AcademicTerm?) = value?.takeIf { it.account == account && (term == null || it.term.key == term.key) }
                override fun save(data: GradeSnapshot) { value = data }
                override fun clear() { value = null }
            }
            val repo = GradeRepository(auth, store)
            auth.onSessionInvalidated(repo::clear)
            try {
                auth.login(credentials.getString("account"), credentials.getString("password"), true)
                credentials.remove("account"); credentials.remove("password")
                stage("原生登录成功，开始复用 SSO 查询成绩")
                val catalog = repo.catalog()
                stage("成绩学期选项读取成功")
                val current = repo.refresh(catalog.current, catalog)
                assertTrue(current.account == auth.state.value.profile!!.account)
                stage("当前学期成绩读取成功")
                // Historical term has published results; no personal values enter assertion output.
                val previous = AcademicTerm((catalog.current.year.toInt() - 1).toString(), "3", "1")
                val grades = repo.refresh(previous, catalog)
                assertTrue("Expected published historical grades", grades.items.isNotEmpty())
                assertTrue(grades.items.all { it.name.isNotBlank() })
                val summary = GradeRules.summary(grades.items)
                assertTrue(summary.average.matches(Regex("[0-9]+\\.[0-9]{4}")))
                val expected = grades.items.filter { !it.invalidated && (it.credits.toDoubleOrNull() ?: 0.0) > 0 && (it.gradePoint.toDoubleOrNull() ?: -1.0) >= 0 }
                val weighted = expected.sumOf { it.credits.toDouble() * it.gradePoint.toDouble() } / expected.sumOf { it.credits.toDouble() }
                assertTrue(kotlin.math.abs(weighted - summary.average.toDouble()) <= .000051)
                stage("当前与历史学期列表、完整分页和绩点加权核对通过")
                val detailed = repo.details(grades)
                assertFalse("Grade detail requests failed", detailed.detailsFailed)
                assertTrue(detailed.items.any { it.components.isNotEmpty() })
                assertTrue(detailed.items.all { item -> item.components.none { it.name == "总评成绩" } })
                assertTrue(repo.cached(detailed.account, previous) == detailed)
                assertEquals(1, logins)
                stage("真实成绩分项、缓存与单次密码登录验证通过")
                repo.clear()
                assertNull(repo.cached(detailed.account, previous))
                stage("成绩缓存清理通过")
            } finally {
                credentials.remove("account"); credentials.remove("password")
                repo.clear(); sessions.clear()
            }
        } }
    }
}
