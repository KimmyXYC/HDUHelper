package moe.nepnep.hduhelper

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.test.platform.app.InstrumentationRegistry
import moe.nepnep.hduhelper.data.update.AndroidUpdateCheckStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import moe.nepnep.hduhelper.data.update.AppRelease
import moe.nepnep.hduhelper.ui.UpdateState
import moe.nepnep.hduhelper.ui.components.UpdateDialog
import moe.nepnep.hduhelper.ui.screens.AboutScreen
import moe.nepnep.hduhelper.ui.theme.HDUHelperTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UpdateUiTest {
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(45)
    @get:Rule val compose = createComposeRule()

    @Test fun attemptTimeSurvivesStoreRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidUpdateCheckStore(context)
        val previous = store.lastAttempt
        try {
            store.lastAttempt = 123456789L
            assertEquals(123456789L, AndroidUpdateCheckStore(context).lastAttempt)
        } finally { store.lastAttempt = previous }
    }

    @Test fun manualEntryLoadingAndResult() {
        val state = mutableStateOf(UpdateState())
        compose.setContent { HDUHelperTheme { AboutScreen(updateState = state.value, onCheckUpdate = { state.value = UpdateState(checking = true) }) } }
        compose.onNodeWithTag("check_update").performScrollTo().performClick()
        compose.onNodeWithText("正在检查更新…").assertIsDisplayed()
        compose.runOnIdle { state.value = UpdateState(message = "暂无更新版本") }
        compose.onNodeWithText("暂无更新版本").assertIsDisplayed()
        compose.runOnIdle { state.value = UpdateState(message = "检查更新失败，请稍后重试") }
        compose.onNodeWithText("检查更新失败，请稍后重试").assertIsDisplayed()
    }

    @Test fun releaseDialogDownloadAndDismiss() = verifyDialog(darkTheme = false)
    @Test fun releaseDialogInDarkTheme() = verifyDialog(darkTheme = true)

    private fun verifyDialog(darkTheme: Boolean) {
        val state = mutableStateOf(UpdateState())
        var url: String? = null
        compose.setContent {
            HDUHelperTheme(darkTheme = darkTheme) {
                top.yukonga.miuix.kmp.basic.Scaffold(
                    topBar = { top.yukonga.miuix.kmp.basic.TopAppBar("关于应用") },
                ) { padding ->
                    AboutScreen(Modifier.padding(padding), updateState = state.value, onCheckUpdate = {
                        state.value = UpdateState(release = AppRelease("v1.2.0", "新增应用更新检查\n\n支持自动检查最新正式版，也可在关于应用中手动检查。\n\n优化更新提示与浏览器下载体验。"))
                    })
                }
                UpdateDialog(state.value, { state.value = UpdateState() }, { url = it })
            }
        }
        compose.onNodeWithTag("check_update").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("update_dialog").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("发现新版本 v1.2.0").assertIsDisplayed()
        compose.onNodeWithText("稍后").assertIsDisplayed()
        compose.onNodeWithText("前往下载").assertIsDisplayed()
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve(if (darkTheme) "update-dialog-dark.png" else "update-dialog-light.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("前往下载").performClick()
        compose.runOnIdle { assertEquals("https://github.com/KimmyXYC/HDUHelper/releases/tag/v1.2.0", url) }
        compose.onNodeWithText("稍后").performClick()
        compose.onNodeWithText("发现新版本 v1.2.0").assertDoesNotExist()
    }
}
