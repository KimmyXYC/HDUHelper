package moe.nepnep.hduhelper

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class GradesNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun applicationsEntryAndBackAreConnected() {
        compose.onNodeWithText("应用").performClick()
        compose.onNodeWithTag("application_grades").performClick()
        compose.onNodeWithTag("grades_screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("application_grades").assertIsDisplayed()
        compose.onNodeWithTag("application_exams").assertIsDisplayed()
    }
}
