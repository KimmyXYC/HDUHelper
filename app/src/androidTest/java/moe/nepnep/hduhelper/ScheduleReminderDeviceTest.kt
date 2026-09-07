package moe.nepnep.hduhelper

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.*
import moe.nepnep.hduhelper.data.schedule.*
import moe.nepnep.hduhelper.data.timetable.campusZone
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Opt-in: requires already enabled notification and exact-alarm permissions; never changes permissions. */
class ScheduleReminderDeviceTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(90)
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun exactAlarmSurvivesReconcileNotifiesOnceAndOpensColdAndWarmDetails(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("scheduleReminders") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as HDUHelperApplication).container
        assumeTrue(container.scheduleReminders.notificationsAllowed())
        assumeTrue(context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
        val notifications = context.getSystemService(NotificationManager::class.java)
        val title = "日程提醒调试 ${UUID.randomUUID().toString().take(8)}"
        val start = LocalDateTime.now(campusZone).plusSeconds(15).withNano(0)
        val event = ScheduleEvent(title, start = start.toString(), end = start.plusMinutes(10).toString(), reminderMinutes = 0)
        val other = event.copy(title = "$title 次日", start = start.plusDays(1).toString(), end = start.plusDays(1).plusMinutes(10).toString(), reminderMinutes = null)
        var scenario: ActivityScenario<MainActivity>? = null
        val ids = mutableListOf<String>()
        var launchIntent: Intent? = null
        try {
            container.schedules.save(ScheduleEditor(null, null, event), event)
            container.schedules.save(ScheduleEditor(null, null, other), other)
            val first = container.schedules.state.value.book.series.single { it.event.title == title }
            val second = container.schedules.state.value.book.series.single { it.event.title == other.title }
            ids += listOf(first.id, second.id)
            // Rebuild from the same encrypted store as application/boot restoration does.
            val reopened = ScheduleRepository(androidScheduleStore(context))
            reopened.load()
            assertTrue(reopened.state.value.book.series.any { it.id == first.id })
            container.scheduleReminders.reconcile()
            container.scheduleReminders.reconcile()
            withTimeout(35_000) {
                while (notifications.activeNotifications.none { it.tag == "${first.id}/${start.toLocalDate()}" }) delay(200)
            }
            val notice = notifications.activeNotifications.single { it.tag == "${first.id}/${start.toLocalDate()}" }
            assertEquals(title, notice.notification.extras.getString("android.title"))
            val at = ScheduleRules.reminderTime(event)!!
            assertTrue(container.schedules.claimReminder(container.schedules.state.value.book.revision, at).isEmpty())
            // A newly created Activity consumes the cold-start intent.
            val link = Uri.parse("hduhelper://schedule/${second.id}/${other.startTime.toLocalDate()}/${other.startTime.toLocalDate()}")
            launchIntent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(link)
            scenario = ActivityScenario.launch(launchIntent)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("schedule_details").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText(other.title) and hasAnyAncestor(hasTestTag("schedule_details")), useUnmergedTree = true).assertIsDisplayed()
            // Sending the actual notification PendingIntent reuses the live Activity via onNewIntent.
            notice.notification.contentIntent.send()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("schedule_details").assertIsDisplayed()
            compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("schedule_details")), useUnmergedTree = true).assertIsDisplayed()
        } finally {
            container.schedules.state.value.book.series.filter { it.event.title == title || it.event.title == other.title }.forEach {
                ids += it.id
                container.schedules.delete(it.id, null)
            }
            notifications.activeNotifications.filter { n -> ids.any { n.tag?.startsWith("$it/") == true } }.forEach { notifications.cancel(it.tag, it.id) }
            container.scheduleReminders.reconcile()
            // ActivityScenario matches lifecycle events against its original launch intent.
            scenario?.onActivity { it.intent = launchIntent }
            scenario?.close()
        }
    }
}
