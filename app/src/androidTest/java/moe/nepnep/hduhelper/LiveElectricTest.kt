package moe.nepnep.hduhelper

import android.net.LocalServerSocket
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.*
import androidx.test.platform.app.InstrumentationRegistry
import moe.nepnep.hduhelper.data.auth.*
import moe.nepnep.hduhelper.data.settings.AuthSettings
import moe.nepnep.hduhelper.data.electric.*
import moe.nepnep.hduhelper.data.timetable.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Opt-in read-only network check; real credentials and electricity records remain in memory. */
class LiveElectricTest {
    @Test(timeout = 300_000)
    fun nativeSsoElectricReadOnly() = runBlocking {
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
            val store = object : NeoStore {
                var value: NeoTokens? = null
                override fun load() = value
                override fun save(session: NeoTokens) { value = session }
                override fun clear() { value = null }
            }
            val neo = NeoSession(auth, store)
            val repo = ElectricRepository(neo)
            auth.onSessionInvalidated(neo::clear)
            try {
                auth.login(credentials.getString("account"), credentials.getString("password"), true)
                credentials.remove("account"); credentials.remove("password")
                stage("原生登录成功，开始复用 SSO 查询电费")
                val binding = repo.binding()
                stage("Neo 授权及绑定状态查询成功")
                if (binding != null) {
                    val balance = repo.balance()
                    val history = repo.history()
                    assertTrue(balance.amount == null || balance.amount.toBigDecimalOrNull() != null)
                    assertTrue(history.items.all { it.date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) })
                    stage("已绑定宿舍余额、历史查询成功")
                } else stage("账号未绑定电表，余额及历史查询跳过")
                val buildings = repo.buildings()
                assertTrue(buildings.isNotEmpty())
                val floors = repo.floors(buildings.first().id)
                if (floors.isNotEmpty()) repo.rooms(floors.first().id)
                assertEquals(1, logins)
                stage("楼栋、楼层和房间只读查询成功；未修改绑定")
            } finally {
                credentials.remove("account"); credentials.remove("password")
                neo.clear(); sessions.clear()
            }
        } }
    }
}
