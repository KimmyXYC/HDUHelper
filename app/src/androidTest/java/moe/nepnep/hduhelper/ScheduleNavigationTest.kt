package moe.nepnep.hduhelper

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import androidx.test.platform.app.InstrumentationRegistry
import moe.nepnep.hduhelper.data.settings.ThemeMode
import org.junit.Assert.assertTrue
import moe.nepnep.hduhelper.data.schedule.*
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Uses unique local fixtures and removes only those fixtures, preserving account and user data. */
class ScheduleNavigationTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(90)
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun addEditAndDeleteAreWiredThroughRealStorageAndNavigation() {
        val title = "日程调试 ${UUID.randomUUID().toString().take(8)}"
        val container = (compose.activity.application as HDUHelperApplication).container
        try {
            compose.onNodeWithTag("schedule_add").performClick()
            compose.onNodeWithTag("schedule_title").performTextInput(title)
            compose.onNodeWithTag("schedule_title").assertTextContains(title)
            compose.onNodeWithTag("schedule_repeat").performScrollTo().performClick()
            compose.onNodeWithTag("repeat_NEVER").assertIsSelected()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("schedule_title").assertTextContains(title)
            compose.onNodeWithTag("schedule_reminder").performScrollTo().performClick()
            compose.onNodeWithTag("reminder_NONE").performClick()
            compose.onNodeWithTag("schedule_title").assertTextContains(title)
            compose.onNodeWithTag("schedule_save").performClick()
            try {
                compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: AssertionError) {
                throw AssertionError("Stored fixture count: ${container.schedules.state.value.book.series.count { it.event.title == title }}\n${compose.onRoot().printToString()}", failure)
            }
            compose.onNodeWithText(title).performClick()
            compose.onNodeWithTag("schedule_edit").performClick()
            compose.onNodeWithTag("schedule_location").performScrollTo().performTextInput("图书馆")
            compose.onNodeWithTag("schedule_save").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("schedule_editor").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText(title).performClick()
            compose.onNodeWithText("位置：图书馆").assertIsDisplayed()
            compose.onNodeWithTag("schedule_delete").performClick()
            compose.onNodeWithTag("schedule_entire_series").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }
        } finally {
            runBlocking {
                container.schedules.load()
                container.schedules.state.value.book.series.filter { it.event.title == title }.forEach { container.schedules.delete(it.id, null) }
            }
        }
    }

    @Test fun closingEditorRetainsItsContentThroughoutTheExitAnimation() {
        val settings = (compose.activity.application as HDUHelperApplication).container.settings
        val originalTheme = settings.state.value.theme
        try {
            compose.runOnIdle { settings.setTheme(ThemeMode.DARK) }
            for (modified in listOf(false, true)) {
                compose.onNodeWithTag("schedule_add").performClick()
                if (modified) compose.onNodeWithTag("schedule_title").performTextInput("退出动画测试")
                compose.waitForIdle()
                if (modified) {
                    compose.onNodeWithContentDescription("关闭").performClick()
                    compose.onNodeWithTag("schedule_discard").assertIsDisplayed()
                }
                compose.mainClock.autoAdvance = false
                if (modified) compose.onNodeWithTag("schedule_discard").performClick()
                else compose.onNodeWithContentDescription("关闭").performClick()
                repeat(2) { frame ->
                    compose.mainClock.advanceTimeBy(80)
                    compose.onNodeWithTag("schedule_editor").assertExists()
                    if (modified) compose.onNodeWithTag("schedule_title").assertTextContains("退出动画测试")
                    val instrument = InstrumentationRegistry.getInstrumentation()
                    val bitmap = instrument.uiAutomation.takeScreenshot()
                    try {
                        var bright = 0
                        var samples = 0
                        for (x in 1..9) for (y in 2..8) {
                            val color = bitmap.getPixel(bitmap.width * x / 10, bitmap.height * y / 10)
                            if (android.graphics.Color.red(color) > 220 && android.graphics.Color.green(color) > 220 && android.graphics.Color.blue(color) > 220) bright++
                            samples++
                        }
                        assertTrue("Dark-mode close transition must not expose a white screen", bright < samples / 4)
                        if (frame == 0) instrument.targetContext.cacheDir.resolve("editor-close-$modified.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { bitmap.recycle() }
                }
                compose.mainClock.autoAdvance = true
                compose.waitForIdle()
                compose.onNodeWithTag("schedule_editor").assertDoesNotExist()
                compose.onNodeWithTag("schedule_add").assertIsDisplayed()
            }
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { settings.setTheme(originalTheme) }
        }
    }

}
