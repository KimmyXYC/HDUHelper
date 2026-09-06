package moe.nepnep.hduhelper

import android.net.LocalServerSocket
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.runBlocking
import moe.nepnep.hduhelper.data.auth.AuthRepository
import moe.nepnep.hduhelper.data.auth.AuthSession
import moe.nepnep.hduhelper.data.auth.AuthSessionFactory
import moe.nepnep.hduhelper.data.auth.AuthStatus
import moe.nepnep.hduhelper.data.auth.HduAuthApi
import moe.nepnep.hduhelper.data.auth.StoredCookie
import moe.nepnep.hduhelper.data.auth.UserProfile
import moe.nepnep.hduhelper.data.auth.androidSessionStore
import moe.nepnep.hduhelper.data.settings.SettingsRepository
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live smoke test. Credentials arrive only through an ephemeral ADB-forwarded socket. */
@RunWith(AndroidJUnit4::class)
class LiveAuthTest {
    @Test(timeout = 180_000)
    fun loginRestoreSsoPasswordRecoveryAndLogout() = runBlocking {
        val name = InstrumentationRegistry.getArguments().getString("liveAuthSocket")
        assumeNotNull(name)
        val server = LocalServerSocket(name!!)
        try {
            server.accept().use { socket ->
                socket.soTimeout = 30_000
                val input = DataInputStream(socket.inputStream)
                val output = DataOutputStream(socket.outputStream)
                val size = input.readInt()
                require(size in 1..4096)
                val bytes = ByteArray(size)
                input.readFully(bytes)
                val credentials = JSONObject(bytes.toString(Charsets.UTF_8))
                bytes.fill(0)
                val account = credentials.getString("account")
                val password = credentials.getString("password")
                credentials.remove("account"); credentials.remove("password")
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val store = androidSessionStore(context)
                val settings = SettingsRepository(context)
                val factory = CountingFactory()
                fun repository() = AuthRepository(store, settings, factory)
                fun stage(message: String) { output.writeUTF(message); output.flush() }
                val repo = repository()
                try {
                    repo.login(account, password, true)
                    assertEquals(AuthStatus.AUTHENTICATED, repo.state.value.status)
                    // Assertions intentionally report only booleans, not account or password values.
                    assertTrue(repo.state.value.profile?.account == account)
                    val ciphertext = context.noBackupFilesDir.resolve("auth/session.enc").readBytes().toString(Charsets.ISO_8859_1)
                    assertFalse(ciphertext.contains(account))
                    assertFalse(ciphertext.contains(password))
                    assertFalse(store.load()!!.cookies.any { it.value.length >= 8 && ciphertext.contains(it.value) })
                    stage("原生登录成功；Keystore 加密存储检查通过")

                    val restored = repository()
                    restored.initialize()
                    assertTrue(restored.state.value.profile?.account == account)
                    restored.checkAndRefresh()
                    assertEquals(AuthStatus.AUTHENTICATED, restored.state.value.status)
                    stage("持久会话重新加载与在线校验通过")

                    val persisted = store.load()!!
                    store.save(persisted.copy(cookies = persisted.cookies.filter { it.domain.trimStart('.') != "i.hdu.edu.cn" }))
                    val beforeSso = factory.passwordLogins
                    val ssoRestored = repository()
                    ssoRestored.checkAndRefresh()
                    assertEquals(AuthStatus.AUTHENTICATED, ssoRestored.state.value.status)
                    assertEquals(beforeSso, factory.passwordLogins)
                    stage("门户 Cookie 失效后，使用 SSO 会话恢复成功")

                    store.save(store.load()!!.copy(cookies = emptyList()))
                    val beforePassword = factory.passwordLogins
                    val passwordRestored = repository()
                    passwordRestored.checkAndRefresh()
                    assertEquals(AuthStatus.AUTHENTICATED, passwordRestored.state.value.status)
                    assertEquals(beforePassword + 1, factory.passwordLogins)
                    stage("全部 Cookie 失效后，使用加密保存的密码恢复成功")

                    passwordRestored.logout()
                    assertEquals(AuthStatus.SIGNED_OUT, passwordRestored.state.value.status)
                    assertNull(store.load()!!.password)
                    assertNull(store.load()!!.profile)
                    assertTrue(store.load()!!.cookies.isEmpty())
                    stage("退出登录与认证数据清理通过")
                } finally {
                    // Clear test session credentials when the smoke test exits.
                    runCatching { repo.logout() }
                }
            }
        } finally { server.close() }
    }

    private class CountingFactory : AuthSessionFactory {
        private val api = HduAuthApi()
        var passwordLogins = 0
        override fun create(cookies: List<StoredCookie>): AuthSession {
            val session = api.create(cookies)
            return object : AuthSession by session {
                override suspend fun login(account: String, password: String): UserProfile {
                    passwordLogins++
                    return session.login(account, password)
                }
            }
        }
    }
}
