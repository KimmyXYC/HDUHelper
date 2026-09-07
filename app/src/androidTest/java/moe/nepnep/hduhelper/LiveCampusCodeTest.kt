package moe.nepnep.hduhelper

import android.net.LocalServerSocket
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.nepnep.hduhelper.data.auth.*
import moe.nepnep.hduhelper.data.campuscode.CampusCodeRepository
import moe.nepnep.hduhelper.data.settings.AuthSettings
import moe.nepnep.hduhelper.ui.CampusCodeStatus
import moe.nepnep.hduhelper.ui.CampusCodeViewModel
import moe.nepnep.hduhelper.ui.screens.CampusCodeScreen
import moe.nepnep.hduhelper.ui.theme.HDUHelperTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test

/** Only synthetic assertions/status escape this test. All live credentials and QR pixels remain in memory. */
class LiveCampusCodeTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 600_000)
    fun existingSsoNativeQrManualAutomaticRefreshAndLogout() = runBlocking {
        val socketName = InstrumentationRegistry.getArguments().getString("liveAuthSocket")
        assumeNotNull(socketName)
        LocalServerSocket(socketName!!).use { server ->
            server.accept().use { socket ->
                socket.soTimeout = 30_000
                val input = DataInputStream(socket.inputStream)
                val output = DataOutputStream(socket.outputStream)
                fun stage(message: String) { output.writeUTF(message); output.flush() }
                val size = input.readInt()
                require(size in 1..4096)
                val bytes = ByteArray(size).also { input.readFully(it) }
                val credentials = JSONObject(bytes.toString(Charsets.UTF_8))
                bytes.fill(0)
                val store = object : SessionStore {
                    private var value: StoredSession? = null
                    override fun load() = value
                    override fun save(session: StoredSession) { value = session }
                    override fun clear() { value = null }
                }
                val settings = object : AuthSettings {
                    private var enabled = true
                    override val autoLogin get() = enabled
                    override fun setAutoLogin(enabled: Boolean) { this.enabled = enabled }
                }
                val api = HduAuthApi()
                var passwordLogins = 0
                val auth = AuthRepository(store, settings, { cookies ->
                    val session = api.create(cookies)
                    object : AuthSession by session {
                        override suspend fun login(account: String, password: String): UserProfile {
                            passwordLogins++
                            return session.login(account, password)
                        }
                    }
                })
                val source = CampusCodeRepository(auth)
                auth.onSessionInvalidated(source::clear)
                val viewModels = ViewModelStore()
                var model: CampusCodeViewModel? = null
                try {
                    auth.login(credentials.getString("account"), credentials.getString("password"), true)
                    credentials.remove("account"); credentials.remove("password")
                    stage("原生登录成功，开始复用 SSO 获取电子凭证")
                    val codeModel = withContext(Dispatchers.Main) {
                        CampusCodeViewModel(source, auth.state, auth.sessionGeneration, MutableStateFlow(true)).also { viewModels.put("campus", it); model = it }
                    }
                    var dark by mutableStateOf(false)
                    compose.setContent {
                        val state by codeModel.state.collectAsStateWithLifecycle()
                        val activity = LocalActivity.current
                        DisposableEffect(activity) {
                            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                        }
                        HDUHelperTheme(darkTheme = dark) {
                            CampusCodeScreen(state, { codeModel.refresh() }, {}, {}, Modifier.fillMaxSize())
                        }
                    }
                    withContext(Dispatchers.Main) { codeModel.setVisible(true) }
                    fun awaitReady(after: Long = -1) = compose.waitUntil(90_000) {
                        codeModel.state.value.let {
                            if (it.status in listOf(CampusCodeStatus.ERROR, CampusCodeStatus.LOGIN_REQUIRED, CampusCodeStatus.VERIFICATION_REQUIRED)) throw AssertionError(it.message ?: "Campus code unavailable")
                            it.status == CampusCodeStatus.READY && !it.refreshing && (it.code?.updatedAt ?: 0) > after
                        }
                    }
                    awaitReady()
                    assertEquals("SSO authorization must not submit the password again", 1, passwordLogins)
                    assertTrue("Live user-info must contain a valid yuan balance", codeModel.state.value.code!!.balance != null)
                    compose.onNodeWithTag("campus_balance").assertExists()
                    stage("真实余额解析与页面显示通过（金额不输出）")
                    assertTrue(codeModel.state.value.code!!.profile.name.isNotBlank())
                    assertTrue(codeModel.state.value.code!!.profile.college.isNotBlank())
                    fun verifyRenderedQr() {
                        // A theme change can finish composition before PixelCopy sees its first drawn frame.
                        compose.waitUntil(5000) {
                            runCatching {
                                val bitmap = compose.onNodeWithTag("campus_qr").captureToImage().asAndroidBitmap()
                                val pixels = IntArray(bitmap.width * bitmap.height)
                                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))),
                                    mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true))
                                decoded.text == codeModel.state.value.code!!.content
                            }.getOrDefault(false)
                        }
                    }
                    verifyRenderedQr()
                    stage("SSO 免输密码授权通过；姓名、学院和原生二维码解码比对通过")
                    val initial = codeModel.state.value.code!!
                    delay(1200)
                    compose.onNodeWithTag("campus_refresh").performClick()
                    awaitReady(initial.updatedAt)
                    val manual = codeModel.state.value.code!!
                    assertTrue("Manual refresh must change the QR", manual.content != initial.content)
                    compose.runOnIdle { dark = true }
                    verifyRenderedQr()
                    stage("手动刷新更换二维码、深色主题扫码检查通过；等待学校下发的自动周期")
                    compose.waitUntil(manual.refreshSeconds * 1000 + 30_000) {
                        val latest = codeModel.state.value.code
                        latest != null && latest.updatedAt > manual.updatedAt && !codeModel.state.value.refreshing
                    }
                    assertTrue(codeModel.state.value.code!!.content != manual.content)
                    assertTrue(codeModel.state.value.code!!.updatedAt - initial.updatedAt >= (initial.refreshSeconds - 2) * 1000)
                    verifyRenderedQr()
                    stage("完整自动刷新周期通过")
                    withContext(Dispatchers.Main) { codeModel.setVisible(false) }
                    assertNull(codeModel.state.value.image)
                    delay(500)
                    withContext(Dispatchers.Main) { codeModel.setVisible(true) }
                    awaitReady()
                    auth.logout()
                    compose.waitUntil(5000) { codeModel.state.value.status == CampusCodeStatus.SIGNED_OUT }
                    assertNull(codeModel.state.value.code)
                    assertNull(codeModel.state.value.image)
                    assertNull(store.load()?.password)
                    stage("页面隐藏清码、返回重新取码、退出账号清理通过")
                } finally {
                    withContext(Dispatchers.Main) { model?.setVisible(false); viewModels.clear() }
                    auth.logout()
                }
            }
        }
    }
}
