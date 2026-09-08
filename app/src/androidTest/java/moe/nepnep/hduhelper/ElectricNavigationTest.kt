package moe.nepnep.hduhelper

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class ElectricNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun applicationsEntryAndBackAreConnected() {
        compose.onNodeWithText("应用").performClick()
        compose.onNodeWithTag("application_electric").performClick()
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("login_account").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("返回").performClick()
        }
        compose.onNodeWithTag("electric_screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("application_electric").assertIsDisplayed()
        compose.onNodeWithTag("application_exams").assertIsDisplayed()
    }
}
