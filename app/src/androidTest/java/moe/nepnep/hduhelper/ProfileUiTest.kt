package moe.nepnep.hduhelper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import moe.nepnep.hduhelper.data.auth.AuthState
import moe.nepnep.hduhelper.data.auth.AuthStatus
import moe.nepnep.hduhelper.data.auth.UserProfile
import moe.nepnep.hduhelper.data.settings.ThemeMode
import moe.nepnep.hduhelper.ui.LoginFormState
import moe.nepnep.hduhelper.ui.screens.AppearanceScreen
import moe.nepnep.hduhelper.ui.screens.AboutScreen
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
                    auth = AuthState(AuthStatus.SIGNED_OUT),
                    onLogin = { loginClicks++ }, onAppearance = { appearanceClicks++ },
                    onLogout = {}, onAbout = {}, onVerify = {}, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithText("自动登录").assertDoesNotExist()
        compose.onNodeWithText("登录数字杭电").assertDoesNotExist()
        compose.onNodeWithTag("logout").assertDoesNotExist()
        compose.onNodeWithTag("logout_confirmation").assertDoesNotExist()
        compose.onNodeWithText("杭电助手").assertDoesNotExist()
        compose.onNodeWithText("跟随系统").assertDoesNotExist()
        compose.onNodeWithText("关于应用").assertIsDisplayed()
        compose.onNodeWithText("未登录").performClick()
        compose.onNodeWithText("外观设置").performClick()
        compose.runOnIdle { assertEquals(1, loginClicks); assertEquals(1, appearanceClicks) }
    }

    @Test fun aboutAndLauncherUseChineseAppName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("杭电助手", context.applicationInfo.loadLabel(context.packageManager).toString())
        compose.setContent { HDUHelperTheme { AboutScreen(Modifier.fillMaxSize()) } }
        compose.onNodeWithText("杭电助手").assertIsDisplayed()
        compose.onNodeWithText("HDUHelper").assertDoesNotExist()
    }

    @Test fun logoutRequiresConfirmationInLightTheme() = verifyLogoutConfirmation(darkTheme = false)

    @Test fun logoutRequiresConfirmationInDarkTheme() = verifyLogoutConfirmation(darkTheme = true)

    private fun verifyLogoutConfirmation(darkTheme: Boolean) {
        var logoutCalls = 0
        compose.setContent {
            HDUHelperTheme(darkTheme = darkTheme) {
                ProfileScreen(
                    auth = signedInState(),
                    onLogin = {}, onAppearance = {}, onLogout = { logoutCalls++ },
                    onAbout = {}, onVerify = {}, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithTag("logout").performScrollTo().performClick()
        compose.onNodeWithText("确定要退出当前账号吗？").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("确认退出").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, logoutCalls) }

        val bitmap = compose.onNodeWithTag("logout_confirmation").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val theme = if (darkTheme) "dark" else "light"
        java.io.File(context.cacheDir, "logout-$theme.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }

        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("logout_confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, logoutCalls) }
        compose.onNodeWithTag("logout").performClick()
        // Invoke the same action twice before recomposition to cover rapid repeat confirmation.
        val confirm = compose.onNodeWithTag("confirm_logout").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { confirm(); confirm(); assertEquals(1, logoutCalls) }
        compose.onNodeWithTag("logout_confirmation").assertDoesNotExist()
    }

    @Test fun backDismissesLogoutWithoutSigningOut() = verifyLogoutDismissal {
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
    }

    @Test fun outsideTapDismissesLogoutWithoutSigningOut() = verifyLogoutDismissal {
        compose.onNode(isDialog()).performTouchInput { click(Offset(center.x, height * 0.1f)) }
    }

    private fun verifyLogoutDismissal(dismiss: () -> Unit) {
        var logoutCalls = 0
        compose.setContent {
            HDUHelperTheme {
                ProfileScreen(
                    auth = signedInState(),
                    onLogin = {}, onAppearance = {}, onLogout = { logoutCalls++ },
                    onAbout = {}, onVerify = {}, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithTag("logout").performScrollTo().performClick()
        compose.onNodeWithText("确定要退出当前账号吗？").assertIsDisplayed()
        dismiss()
        compose.onNodeWithTag("logout_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("logout").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, logoutCalls) }
    }

    @Test fun logoutConfirmationIsDiscardedAfterSignOutOrLeavingProfile() {
        var auth by mutableStateOf(signedInState())
        var showProfile by mutableStateOf(true)
        var logoutCalls = 0
        compose.setContent {
            HDUHelperTheme {
                if (showProfile) {
                    ProfileScreen(
                        auth = auth,
                        onLogin = {}, onAppearance = {}, onLogout = { logoutCalls++ },
                        onAbout = {}, onVerify = {}, modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.onNodeWithTag("logout").performScrollTo().performClick()
        compose.onNodeWithText("确定要退出当前账号吗？").assertIsDisplayed()
        compose.runOnIdle { auth = AuthState(AuthStatus.SIGNED_OUT) }
        compose.onNodeWithTag("logout_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("logout").assertDoesNotExist()
        compose.runOnIdle { auth = signedInState() }
        compose.onNodeWithTag("logout_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("logout").performScrollTo().performClick()
        compose.onNodeWithText("确定要退出当前账号吗？").assertIsDisplayed()
        compose.runOnIdle { showProfile = false }
        compose.onNodeWithTag("logout_confirmation").assertDoesNotExist()
        compose.runOnIdle { showProfile = true }
        compose.onNodeWithTag("logout_confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, logoutCalls) }
    }

    private fun signedInState() = AuthState(
        status = AuthStatus.AUTHENTICATED,
        profile = UserProfile("synthetic-student", "测试用户"),
    )

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
