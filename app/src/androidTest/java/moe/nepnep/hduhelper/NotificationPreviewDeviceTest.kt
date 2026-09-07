package moe.nepnep.hduhelper

import android.app.Notification
import android.app.NotificationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.theme.MiuixTheme
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.nepnep.hduhelper.data.notifications.*
import moe.nepnep.hduhelper.ui.screens.NotificationSettingsScreen
import moe.nepnep.hduhelper.ui.theme.HDUHelperTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in: posts only synthetic notifications, restoring the original notification preferences. */
class NotificationPreviewDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun buttonPostsOrdinaryOrSixtySecondIslandPreview() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("notificationPreview") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as HDUHelperApplication).container
        val manager = context.getSystemService(NotificationManager::class.java)
        val original = container.settings.state.value.notifications
        assumeTrue(container.courseReminders.permissionStatus().notifications)
        assumeTrue(runBlocking { container.island.refreshNow() }.ready)
        fun posted(id: Int) = manager.activeNotifications.firstOrNull { it.id == id }?.notification
        try {
            container.settings.setNotifications(original.copy(island = false, beforeClass = false, afterClass = false))
            compose.setContent {
                val settings by container.settings.state.collectAsState()
                val scope = rememberCoroutineScope()
                HDUHelperTheme {
                    NotificationSettingsScreen(settings.notifications, container.courseReminders.permissionStatus(),
                        container.settings::setNotifications, {}, {}, Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface), onTest = {
                            scope.launch { assertTrue(container.courseReminders.sendTestNotification()) }
                        })
                }
            }
            compose.onNodeWithTag("notify_test").performScrollTo().performClick()
            waitFor(10_000) { posted(AndroidCourseReminders.TEST_ORDINARY_ID) != null }
            val ordinary = posted(AndroidCourseReminders.TEST_ORDINARY_ID)!!
            assertFalse(ordinary.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertEquals(0, ordinary.flags and Notification.FLAG_ONGOING_EVENT)
            assertTrue(ordinary.extras.getString(Notification.EXTRA_TITLE)!!.contains("测试课程"))
            compose.onNodeWithTag("notify_island").performScrollTo().performClick()
            val clicked = System.currentTimeMillis()
            compose.onNodeWithTag("notify_test").performScrollTo().performClick()
            waitFor(10_000) { posted(AndroidCourseReminders.TEST_ISLAND_ID) != null }
            val island = posted(AndroidCourseReminders.TEST_ISLAND_ID)!!
            assertTrue(org.json.JSONObject(island.extras.getString("miui.focus.param")!!).getJSONObject("param_v2").getJSONObject("hintInfo").getJSONObject("timerInfo").getLong("timerWhen") in (clicked + 60_000)..(System.currentTimeMillis() + 60_000))
            assertNotNull(island.extras.getString("miui.focus.param"))
            assertTrue(island.extras.getString("miui.focus.param")!!.contains("\"timerType\":-1"))
            assertTrue(island.flags and Notification.FLAG_ONGOING_EVENT != 0)
            if (Build.VERSION.SDK_INT >= 36) assertEquals(0, island.flags and Notification.FLAG_PROMOTED_ONGOING)
            waitFor(5000) { posted(AndroidCourseReminders.TEST_ORDINARY_ID) == null }
            island.actions.single().actionIntent.send()
            waitFor(10_000) { posted(AndroidCourseReminders.TEST_ISLAND_ID) == null }
            compose.onNodeWithTag("notify_test").performClick()
            waitFor(10_000) { posted(AndroidCourseReminders.TEST_ISLAND_ID) != null }
            val restarted = posted(AndroidCourseReminders.TEST_ISLAND_ID)!!
            assertNotEquals(island.extras.getString("miui.focus.param"), restarted.extras.getString("miui.focus.param"))
            captureSystemIsland()
            waitFor(70_000) {
                posted(AndroidCourseReminders.TEST_ISLAND_ID)?.extras?.getString(Notification.EXTRA_TITLE)?.contains("已上课") == true
            }
            assertFalse(posted(AndroidCourseReminders.TEST_ISLAND_ID)!!.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
            waitFor(70_000) { posted(AndroidCourseReminders.TEST_ISLAND_ID) == null }
        } finally {
            posted(AndroidCourseReminders.TEST_ISLAND_ID)?.deleteIntent?.send()
            manager.cancel(AndroidCourseReminders.TEST_ORDINARY_ID)
            container.settings.setNotifications(original)
            runBlocking { container.courseReminders.reconcileSafely() }
        }
    }
    private fun captureSystemIsland() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        Thread.sleep(1200)
        fun capture(name: String) {
            val bitmap = requireNotNull(automation.takeScreenshot())
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve(name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        capture("island-expanded-system.png")
        Thread.sleep(6000)
        capture("island-chip-system.png")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(android.content.Intent().setClassName(context.packageName, "androidx.activity.ComponentActivity")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun waitFor(timeout: Long, condition: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(android.os.SystemClock.elapsedRealtime() < until) { "Notification transition timed out" }
            Thread.sleep(100)
        }
    }

}
