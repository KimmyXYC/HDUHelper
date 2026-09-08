package moe.nepnep.hduhelper

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import moe.nepnep.hduhelper.data.electric.*
import moe.nepnep.hduhelper.ui.*
import moe.nepnep.hduhelper.ui.screens.*
import moe.nepnep.hduhelper.ui.theme.HDUHelperTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ElectricUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun balanceAndEmptyHistoryAreVisible() {
        var dark by mutableStateOf(false)
        compose.setContent { HDUHelperTheme(darkTheme = dark) {
            ElectricScreen(ElectricUiState(status = TimetableStatus.READY, bindingKnown = true,
                binding = ElectricBinding(1, "一号楼", "一层", "101"), balance = ElectricBalance("-2.50", 1788840000),
                history = ElectricHistory("1.20", emptyList())), {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("¥ -2.50").assertIsDisplayed()
        compose.onNodeWithText("暂无历史记录").assertExists()
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark = isDark }
            compose.onNodeWithTag("electric_screen").captureToImage().asAndroidBitmap().let { bitmap ->
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("electric-synthetic-${if (isDark) "dark" else "light"}.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
    @Test fun bindingRequiresConfirmationWithFullRoomName() {
        var submitted = false
        compose.setContent { HDUHelperTheme {
            ElectricScreen(ElectricUiState(status = TimetableStatus.READY, bindingKnown = true, editing = true,
                building = ElectricOption(1, "一号楼"), floor = ElectricOption(2, "一层"), room = ElectricOption(3, "101")),
                {}, {}, {}, {}, {}, {}, { submitted = true }, {}, {}, {})
        } }
        compose.onNodeWithTag("electric_bind").performClick()
        compose.onNodeWithText("一号楼 一层 101").assertExists()
        assertFalse(submitted)
        compose.onNodeWithTag("electric_confirm").performClick()
        assertTrue(submitted)
    }
    @Test fun longBuildingListScrollsToLastOptionAndKeepsCancelVisible() {
        var chosen: ElectricOption? = null
        val options = (1L..30L).map { ElectricOption(it, "${it}号楼") }
        compose.setContent { HDUHelperTheme(darkTheme = true) {
            ElectricScreen(ElectricUiState(status = TimetableStatus.READY, bindingKnown = true, editing = true,
                buildings = options, building = options.first()), {}, {}, {}, { chosen = it }, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("1号楼").performClick()
        compose.onNodeWithTag("electric_picker_cancel").assertIsDisplayed()
        compose.onNodeWithTag("electric_option_1").assertIsSelected()
        compose.onNodeWithTag("electric_options").performScrollToNode(hasTestTag("electric_option_30"))
        compose.onNodeWithTag("electric_option_30").assertIsDisplayed()
        compose.onNodeWithTag("electric_picker_cancel").assertIsDisplayed()
        run {
            compose.onNode(hasTestTag("electric_options")).captureToImage().asAndroidBitmap().let { bitmap ->
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("electric-picker.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
        compose.onNodeWithTag("electric_option_30").performClick()
        compose.runOnIdle { assertEquals(30L, chosen?.id) }
        compose.onNodeWithTag("electric_options").assertDoesNotExist()
    }
    @Test fun missingBalanceNeverBecomesZeroAndLoginCallbackWorks() {
        var login = false
        var state by mutableStateOf(ElectricUiState(status = TimetableStatus.READY, bindingKnown = true,
            binding = ElectricBinding(1, "楼", "层", "房")))
        compose.setContent { HDUHelperTheme { ElectricScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, { login = true }, {}) } }
        compose.onNodeWithText("余额暂不可用").assertIsDisplayed()
        compose.runOnIdle { state = ElectricUiState(status = TimetableStatus.SIGNED_OUT) }
        compose.onNodeWithTag("electric_login").performClick()
        assertTrue(login)
    }
}
