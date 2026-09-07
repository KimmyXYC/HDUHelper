package moe.nepnep.hduhelper

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Exercises the actual app wiring without changing account data or display preferences. */
class TimetableNavigationTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(90)
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun timetableAndSecondarySettingsAreConnectedInTheApp() {
        compose.onNodeWithText("课表").performClick()
        compose.onNodeWithTag("timetable_screen").assertIsDisplayed()
        compose.onNodeWithTag("timetable_choose_term").assertIsDisplayed()
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithTag("open_timetable_settings").performClick()
        compose.onNodeWithTag("setting_other_weeks").assertIsDisplayed()
        compose.onNodeWithTag("setting_finished").assertIsDisplayed()
        compose.onNodeWithTag("setting_weekend").assertIsDisplayed()
        compose.onNodeWithTag("setting_teacher").assertIsDisplayed()
        compose.onNodeWithTag("setting_location").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("open_timetable_settings").assertIsDisplayed()
        compose.onNodeWithText("课表").performClick()
        compose.onNodeWithTag("timetable_screen").assertIsDisplayed()
    }
    @Test fun notificationSettingsEntryAndBackNavigationAreConnected() {
        // Avoid interacting with real runtime permission dialogs during unattended UI tests.
        org.junit.Assume.assumeTrue(androidx.core.content.ContextCompat.checkSelfPermission(compose.activity,
            android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithTag("open_notification_settings").performClick()
        compose.onNodeWithTag("notification_settings").assertIsDisplayed()
        // The island switch is hidden unless this module has framework and scope authorization.
        compose.onNodeWithTag("notify_start").assertIsDisplayed()
        compose.onNodeWithTag("notify_end").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("open_notification_settings").assertIsDisplayed()
    }

    @Test fun settingsFetchCampusChoicesBeforeTimetableIsOpened() {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("campusSettings") == "true")
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithTag("open_timetable_settings").performClick()
        compose.waitUntil(60_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("campus_auto")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("campus_auto").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("campus_options_status").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
    }

}
