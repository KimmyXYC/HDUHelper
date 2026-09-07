package moe.nepnep.hduhelper

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import moe.nepnep.hduhelper.data.notifications.*
import moe.nepnep.hduhelper.data.settings.SettingsRepository
import moe.nepnep.hduhelper.ui.screens.NotificationSettingsScreen
import moe.nepnep.hduhelper.ui.theme.HDUHelperTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NotificationSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchesAndLeadTimeInLightTheme() = checkScreen(false)
    @Test fun switchesAndLeadTimeInDarkTheme() = checkScreen(true)

    private fun checkScreen(dark: Boolean) {
        var current = NotificationSettings()
        var notificationSettings = 0
        compose.setContent {
            var settings by remember { mutableStateOf(current) }
            HDUHelperTheme(darkTheme = dark) {
                NotificationSettingsScreen(settings, CourseNotificationStatus(promotionSupported = false),
                    { settings = it; current = it }, { notificationSettings++ }, {}, {}, Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface))
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        capture("notification-settings-$dark.png")
        compose.onNodeWithText("关闭电池优化并开启自启动").assertExists()
        compose.onNodeWithText("显示倒计时，到点后继续计时", substring = true).assertDoesNotExist()
        compose.onNodeWithText("连堂课只在整段课程", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("notify_start_time").assertIsDisplayed()
        compose.onNodeWithTag("notify_end_time").assertDoesNotExist()
        compose.onNodeWithTag("notify_start_time").performClick()
        compose.waitForIdle()
        capture("notification-time-$dark.png", "notify_time_picker")
        val input = compose.onNodeWithTag("notify_minutes_input")
        input.assertTextContains("10")
        input.performTextClearance()
        compose.onNodeWithTag("notify_time_confirm").assertIsNotEnabled()
        for (invalid in listOf("31", "-1", "1.5", "abc", "999", " 5", "５")) {
            input.performTextReplacement(invalid)
            input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNodeWithTag("notify_time_confirm").assertIsNotEnabled()
        }
        input.performTextReplacement("7")
        compose.onNodeWithTag("notify_time_confirm").performClick()
        compose.runOnIdle { assertEquals(7, current.beforeMinutes) }
        compose.onNodeWithTag("notify_start").performClick()
        compose.onNodeWithTag("notify_start_time").assertDoesNotExist()
        compose.onNodeWithTag("notify_start").performClick()
        compose.runOnIdle { assertEquals(7, current.beforeMinutes) }
        compose.onNodeWithTag("notify_end").performClick()
        compose.onNodeWithTag("notify_end_time").performScrollTo().performClick()
        compose.onNodeWithTag("notify_minutes_input").performTextReplacement("30")
        compose.onNodeWithTag("notify_time_confirm").performClick()
        compose.runOnIdle { assertEquals(30, current.afterMinutes) }
        compose.onNodeWithTag("notify_end_time").performClick()
        compose.onNodeWithTag("notify_minutes_input").performTextReplacement("0")
        compose.onNodeWithTag("notify_time_cancel").performClick()
        compose.runOnIdle { assertEquals(30, current.afterMinutes) }
        compose.onNodeWithTag("notify_end_time").performClick()
        compose.onNodeWithTag("notify_minutes_input").performTextReplacement("0")
        compose.onNodeWithTag("notify_time_confirm").performClick()
        compose.onNodeWithTag("notify_live").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(current.live); assertEquals(0, current.afterMinutes) }
        compose.onNodeWithTag("notify_permission").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, notificationSettings) }
    }

    private fun capture(name: String, tag: String? = null) {
        val image = (if (tag == null) compose.onRoot() else compose.onNodeWithTag(tag)).captureToImage().asAndroidBitmap()
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve(name).outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun settingsSurviveRepositoryRecreationWithoutTouchingUserPreferences() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "notification-test-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences = base.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
        try {
            val first = SettingsRepository(isolated)
            assertEquals(NotificationSettings(), first.state.value.notifications)
            val value = NotificationSettings(true, false, true, 7, 23)
            first.setNotifications(value)
            assertEquals(value, SettingsRepository(isolated).state.value.notifications)
            assertTrue(first.claimNotificationPermissionPrompt())
            assertFalse(SettingsRepository(isolated).claimNotificationPermissionPrompt())
        } finally { base.deleteSharedPreferences(name) }
    }
}
