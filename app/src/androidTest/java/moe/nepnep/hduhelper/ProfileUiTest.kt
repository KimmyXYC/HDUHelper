package moe.nepnep.hduhelper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import moe.nepnep.hduhelper.data.auth.AuthState
import moe.nepnep.hduhelper.data.auth.AuthStatus
import moe.nepnep.hduhelper.data.settings.AppSettings
import moe.nepnep.hduhelper.data.settings.ThemeMode
import moe.nepnep.hduhelper.ui.LoginFormState
import moe.nepnep.hduhelper.ui.screens.AppearanceScreen
import moe.nepnep.hduhelper.ui.screens.LoginScreen
import moe.nepnep.hduhelper.ui.screens.ProfileScreen
import moe.nepnep.hduhelper.ui.theme.HDUHelperTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class ProfileUiTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(45)
    @get:Rule val compose = createComposeRule()

    @Test fun accountCardAndAppearanceAreTheOnlyRelevantEntrypoints() {
        var loginClicks = 0
        var appearanceClicks = 0
        compose.setContent {
            HDUHelperTheme {
                ProfileScreen(
                    auth = AuthState(AuthStatus.SIGNED_OUT), settings = AppSettings(),
                    onLogin = { loginClicks++ }, onAppearance = { appearanceClicks++ },
                    onLogout = {}, onAbout = {}, onVerify = {}, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithText("自动登录").assertDoesNotExist()
        compose.onNodeWithText("登录数字杭电").assertDoesNotExist()
        compose.onNodeWithText("未登录").performClick()
        compose.onNodeWithText("外观设置").performClick()
        compose.runOnIdle { assertEquals(1, loginClicks); assertEquals(1, appearanceClicks) }
    }

    @Test fun passwordVisibilityUsesAccessibleIconsAndSubmissionIsNotDuplicated() {
        var submissions = 0
        compose.setContent {
            var form by remember { mutableStateOf(LoginFormState()) }
            HDUHelperTheme {
                LoginScreen(
                    form, { form = form.copy(account = it) }, { form = form.copy(password = it) },
                    { form = form.copy(autoLogin = it) },
                    { submissions++; form = form.copy(loading = true) }, {}, Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithTag("login_account").performTextInput("synthetic-student")
        compose.onNodeWithTag("login_password").performTextInput("synthetic-password")
        compose.onNodeWithText("显示").assertDoesNotExist()
        compose.onNodeWithText("隐藏").assertDoesNotExist()
        compose.onNodeWithContentDescription("显示密码").performClick()
        compose.onNodeWithContentDescription("隐藏密码").assertIsDisplayed().performClick()
        compose.onNodeWithTag("submit_login").performClick()
        compose.onNodeWithText("正在登录…").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, submissions) }
    }

    @Test fun appearanceOffersThreeImmediatelySelectableModes() {
        var selection = ThemeMode.SYSTEM
        compose.setContent {
            var mode by remember { mutableStateOf(ThemeMode.SYSTEM) }
            HDUHelperTheme(darkTheme = mode == ThemeMode.DARK) {
                AppearanceScreen(mode, { mode = it; selection = it }, Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithText("跟随系统").assertIsDisplayed()
        compose.onNodeWithText("浅色").assertIsDisplayed()
        compose.onNodeWithText("深色").performClick()
        compose.runOnIdle { assertEquals(ThemeMode.DARK, selection) }
        compose.onNodeWithText("浅色").performClick()
        compose.runOnIdle { assertEquals(ThemeMode.LIGHT, selection) }
    }
}
