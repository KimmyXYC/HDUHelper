package moe.nepnep.hduhelper

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import moe.nepnep.hduhelper.data.campuscode.*
import moe.nepnep.hduhelper.ui.CampusCodeStatus
import moe.nepnep.hduhelper.ui.CampusCodeUiState
import moe.nepnep.hduhelper.ui.screens.CampusCodeScreen
import moe.nepnep.hduhelper.ui.theme.HDUHelperTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CampusCodeUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun brightnessRestoresOnTabChangeAndBackground() {
        var active by mutableStateOf(false)
        lateinit var window: android.view.Window
        lateinit var registry: androidx.lifecycle.LifecycleRegistry
        val owner = object : androidx.lifecycle.LifecycleOwner {
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        compose.setContent {
            window = androidx.activity.compose.LocalActivity.current!!.window
            registry = androidx.compose.runtime.remember { androidx.lifecycle.LifecycleRegistry(owner).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED } }
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                moe.nepnep.hduhelper.ui.components.CampusCodeBrightness(active)
            }
        }
        var original = -1f
        compose.runOnIdle {
            original = window.attributes.screenBrightness
            window.attributes = window.attributes.apply { screenBrightness = 0.35f }
            active = true
        }
        try {
            compose.runOnIdle { assertEquals(1f, window.attributes.screenBrightness, 0f); active = false }
            compose.runOnIdle { assertEquals(0.35f, window.attributes.screenBrightness, 0f); active = true }
            compose.runOnIdle { assertEquals(1f, window.attributes.screenBrightness, 0f); registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
            compose.runOnIdle { assertEquals(0.35f, window.attributes.screenBrightness, 0f); registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
            compose.runOnIdle { assertEquals(1f, window.attributes.screenBrightness, 0f); active = false }
            compose.runOnIdle { assertEquals(0.35f, window.attributes.screenBrightness, 0f) }
        } finally { compose.runOnIdle { active = false; window.attributes = window.attributes.apply { screenBrightness = original } } }
    }

    @Test fun darkThemeRenderedCodeDecodesAndFailureRemovesIt() {
        val code = CampusCode("SYNTHETIC-NOT-A-CREDENTIAL,12345", CampusCodeProfile("测试用户", "学生", "测试学院"), 180, 1, "123.45")
        var state by mutableStateOf(CampusCodeUiState(CampusCodeStatus.READY, code, QrCodeEncoder.encode(code.content), nextRefreshSeconds = 180))
        var clicks = 0
        compose.setContent { HDUHelperTheme(darkTheme = true) { CampusCodeScreen(state, { clicks++ }, {}, {}, Modifier.fillMaxSize()) } }
        compose.onNodeWithText("测试用户 · 学生").assertIsDisplayed()
        compose.onNodeWithText("测试学院").assertIsDisplayed()
        compose.onNodeWithTag("campus_balance").assertTextEquals("余额 ¥123.45")
        val nameBounds = compose.onNodeWithTag("campus_identity").getUnclippedBoundsInRoot()
        val balanceBounds = compose.onNodeWithTag("campus_balance").getUnclippedBoundsInRoot()
        assertTrue(balanceBounds.left > nameBounds.right)
        assertEquals(nameBounds.top, balanceBounds.top)
        compose.onNodeWithText("180 秒后自动刷新").assertIsDisplayed()
        val bitmap = compose.onNodeWithTag("campus_qr").captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
        assertEquals(code.content, decoded.text)
        val preview = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("campus-balance-layout.png")
        file.outputStream().use { preview.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("campus_refresh").performClick()
        compose.runOnIdle { assertEquals(1, clicks); state = CampusCodeUiState(CampusCodeStatus.ERROR, message = "网络连接失败") }
        compose.onNodeWithTag("campus_qr").assertDoesNotExist()
        compose.onNodeWithTag("campus_balance").assertDoesNotExist()
        compose.onNodeWithText("网络连接失败").assertIsDisplayed()
    }

    @Test fun signedOutAndVerificationActionsAreAvailable() {
        var state by mutableStateOf(CampusCodeUiState())
        var logins = 0
        var verifies = 0
        compose.setContent { HDUHelperTheme { CampusCodeScreen(state, {}, { logins++ }, { verifies++ }, Modifier.fillMaxSize()) } }
        compose.onNodeWithTag("campus_login").performClick()
        compose.runOnIdle { assertEquals(1, logins); state = CampusCodeUiState(CampusCodeStatus.VERIFICATION_REQUIRED) }
        compose.onNodeWithText("完成官方验证").performClick()
        compose.runOnIdle { assertEquals(1, verifies) }
    }
}
