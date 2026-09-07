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
import moe.nepnep.hduhelper.data.background.*
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
        val testedModes = mutableListOf<Boolean>()
        compose.setContent {
            var settings by remember { mutableStateOf(current) }
            HDUHelperTheme(darkTheme = dark) {
                NotificationSettingsScreen(settings, CourseNotificationStatus(island = moe.nepnep.hduhelper.data.island.IslandCapability(supported = true, framework = true, scoped = true, hookReady = true)),
                    { settings = it; current = it }, { notificationSettings++ }, {}, Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
                    onTest = { testedModes += current.island })
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        capture("notification-settings-$dark.png")
        compose.onNodeWithText("后台运行设置").assertDoesNotExist()
        compose.onNodeWithTag("notify_autostart").assertExists()
        compose.onNodeWithTag("notify_battery").assertExists()
        compose.onNodeWithText("显示倒计时，到点后继续计时", substring = true).assertDoesNotExist()
        compose.onNodeWithText("连堂课只在整段课程", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("notify_test").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(false), testedModes) }
        compose.onNodeWithTag("notify_start_time").performScrollTo().assertIsDisplayed()
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
        compose.onNodeWithTag("notify_minutes_input").assertTextContains("30")
        compose.onNodeWithTag("notify_time_confirm").performClick()
        compose.waitUntil(5_000) { current.afterMinutes == 30 }
        compose.runOnIdle { assertEquals(30, current.afterMinutes) }
        compose.onNodeWithTag("notify_end_time").performClick()
        compose.onNodeWithTag("notify_minutes_input").performTextReplacement("0")
        compose.onNodeWithTag("notify_time_cancel").performClick()
        compose.runOnIdle { assertEquals(30, current.afterMinutes) }
        compose.onNodeWithTag("notify_end_time").performClick()
        compose.onNodeWithTag("notify_minutes_input").performTextReplacement("0")
        compose.onNodeWithTag("notify_time_confirm").performClick()
        compose.onNodeWithTag("notify_island").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(current.island); assertEquals(0, current.afterMinutes) }
        compose.onNodeWithTag("notify_test").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(false, true), testedModes) }
        compose.onNodeWithTag("notify_permission").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, notificationSettings) }
    }

    @Test fun islandOptionRequiresActivationScopeAndLoadedHooks() {
        var capability by mutableStateOf(moe.nepnep.hduhelper.data.island.IslandCapability(supported = true))
        compose.setContent {
            HDUHelperTheme {
                NotificationSettingsScreen(NotificationSettings(), CourseNotificationStatus(island = capability), {}, {}, {})
            }
        }
        compose.onNodeWithTag("notify_island").assertDoesNotExist()
        compose.runOnIdle { capability = capability.copy(framework = true) }
        compose.onNodeWithTag("notify_island").assertDoesNotExist()
        compose.runOnIdle { capability = capability.copy(scoped = true) }
        compose.onNode(hasAnyAncestor(hasTestTag("notify_island")) and isToggleable(), useUnmergedTree = true).assertIsNotEnabled()
        compose.runOnIdle { capability = capability.copy(hookReady = true) }
        compose.onNode(hasAnyAncestor(hasTestTag("notify_island")) and isToggleable(), useUnmergedTree = true).assertIsEnabled()
        compose.runOnIdle { capability = capability.copy(framework = false) }
        compose.onNodeWithTag("notify_island").assertDoesNotExist()
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
            isolated.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("notify_live", true).commit()
            val first = SettingsRepository(isolated)
            assertFalse(first.state.value.backgroundEnhancement)
            first.setBackgroundEnhancement(true)
            assertTrue(SettingsRepository(isolated).state.value.backgroundEnhancement)
            assertFalse(first.state.value.notifications.island)
            assertFalse(isolated.getSharedPreferences("settings", Context.MODE_PRIVATE).contains("notify_live"))
            assertEquals(NotificationSettings(), first.state.value.notifications)
            val value = NotificationSettings(true, false, true, 7, 23)
            first.setNotifications(value)
            assertEquals(value, SettingsRepository(isolated).state.value.notifications)
            assertTrue(first.claimNotificationPermissionPrompt())
            assertFalse(SettingsRepository(isolated).claimNotificationPermissionPrompt())
        } finally { base.deleteSharedPreferences(name) }
    }

    @Test fun backgroundRowsRefreshAndBatteryDialogHasSeparateSettingsTargets() {
        var status by mutableStateOf(BackgroundStatus())
        var enabled by mutableStateOf(false)
        var autostart = 0
        var battery = 0
        var vendor = 0
        compose.setContent {
            HDUHelperTheme {
                NotificationSettingsScreen(NotificationSettings(), CourseNotificationStatus(), {}, {}, {},
                    background = status, backgroundEnhancement = enabled, onBackgroundEnhancement = { enabled = it },
                    onAutostart = { autostart++ }, onBattery = { battery++ }, onVendorBattery = { vendor++ })
            }
        }
        val toggle = compose.onNode(hasAnyAncestor(hasTestTag("notify_background_enhancement")) and isToggleable(), useUnmergedTree = true)
        compose.onNodeWithTag("notify_background_enhancement").assertDoesNotExist()
        compose.runOnIdle { status = status.copy(hook = BackgroundHookState.SCOPE_REQUIRED) }
        compose.onNodeWithTag("notify_background_enhancement").assertDoesNotExist()
        compose.runOnIdle { status = status.copy(hook = BackgroundHookState.RESTART_REQUIRED) }
        toggle.assertIsNotEnabled()
        compose.runOnIdle { status = status.copy(hook = BackgroundHookState.READY) }
        toggle.assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(enabled) }
        compose.runOnIdle { status = status.copy(hook = BackgroundHookState.RESTART_REQUIRED) }
        toggle.assertIsEnabled().performClick() // Turning a previously enabled feature off remains possible.
        toggle.assertIsNotEnabled()
        compose.onNodeWithTag("notify_autostart").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, autostart) }
        compose.onNodeWithTag("notify_battery").performScrollTo().performClick()
        compose.onNodeWithTag("notify_battery_android").performClick()
        compose.onNodeWithTag("notify_battery_vendor").performClick()
        compose.runOnIdle {
            assertEquals(1, battery); assertEquals(1, vendor)
            status = status.copy(autostart = PermissionState.ALLOWED, batteryExemption = PermissionState.ALLOWED,
                vendorBattery = PermissionState.RESTRICTED)
        }
        compose.onNodeWithText("已豁免", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("有限制", useUnmergedTree = true).assertExists()
        capture("notification-battery.png", "notify_battery_dialog")
        compose.onNodeWithTag("notify_battery_close").performClick()
        compose.onNodeWithText("已开启", useUnmergedTree = true).assertExists()
        compose.runOnIdle { enabled = true; status = status.copy(hook = BackgroundHookState.SCOPE_REQUIRED) }
        compose.onNodeWithTag("notify_background_enhancement").assertDoesNotExist()
    }

    @Test fun unsupportedSystemOnlyShowsAndroidBatteryOptimization() {
        var status by mutableStateOf(BackgroundStatus(vendorBattery = PermissionState.UNSUPPORTED,
            batteryExemption = PermissionState.ALLOWED, hook = BackgroundHookState.UNSUPPORTED))
        compose.setContent {
            HDUHelperTheme {
                NotificationSettingsScreen(NotificationSettings(), CourseNotificationStatus(), {}, {}, {}, background = status)
            }
        }
        compose.onNodeWithTag("notify_background_enhancement").assertDoesNotExist()
        compose.onNodeWithText("Android：已豁免", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("小米", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("notify_battery").performScrollTo().performClick()
        compose.onNodeWithTag("notify_battery_android").assertIsDisplayed()
        compose.onNodeWithTag("notify_battery_vendor").assertDoesNotExist()
        compose.runOnIdle { status = status.copy(vendorBattery = PermissionState.UNKNOWN) }
        compose.onNodeWithTag("notify_battery_vendor").assertIsDisplayed()
        compose.runOnIdle { status = status.copy(vendorBattery = PermissionState.UNSUPPORTED) }
        compose.onNodeWithTag("notify_battery_vendor").assertDoesNotExist()
        compose.onNodeWithTag("notify_battery_close").performClick()
        compose.onNodeWithText("Android：已豁免", useUnmergedTree = true).assertExists()
    }
}
