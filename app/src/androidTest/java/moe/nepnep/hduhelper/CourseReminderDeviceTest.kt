package moe.nepnep.hduhelper

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.*
import moe.nepnep.hduhelper.data.notifications.*
import moe.nepnep.hduhelper.data.timetable.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Opt-in physical-device checks. Fixture cache additions and settings are restored in finally. */
class CourseReminderDeviceTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(240)
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realAlarmCountsDownChangesPhaseDismissesAndStopsService(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("courseReminders") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as HDUHelperApplication).container
        container.auth.initialize()
        restoreFixture(context, container)
        assumeTrue(container.courseReminders.permissionStatus().notifications)
        assumeTrue(container.island.refreshNow().ready)
        assumeTrue(context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
        val account = container.auth.serviceIdentity()?.account
        assumeTrue(account != null)
        val store = androidTimetableStore(context)
        val latest = store.load(account!!)
        val original = latest?.let { store.load(account, it.catalog.current) }
        assumeTrue(original != null)
        original!!
        val preferences = container.settings.state.value.notifications
        val suffix = UUID.randomUUID().toString().take(8)
        val title = "课程提醒调试 $suffix"
        val dismissedTitle = "课程取消调试 $suffix"
        val start = LocalDateTime.now(campusZone).plusSeconds(75).withNano(0)
        val end = start.plusSeconds(30)
        assumeTrue(start.toLocalDate() == end.plusSeconds(60).toLocalDate())
        // Do not overlap a user's real imminent course while temporarily enabling both reminders.
        val settings = NotificationSettings(island = true, afterClass = true, beforeMinutes = 1, afterMinutes = 1)
        val until = end.plusSeconds(60).atZone(campusZone).toInstant().toEpochMilli()
        assumeTrue(CourseReminderRules.reminders(original, settings, System.currentTimeMillis()).none { it.begins < until })
        val day = start.toLocalDate()
        val existingWeek = original.weeks.firstOrNull { day >= it.startDate && day <= it.endDate }
        val week = existingWeek ?: WeekRange((original.weeks.maxOfOrNull { it.week } ?: 0) + 1000, day.toString(), day.plusDays(6).toString())
        val campus = "notification-test-$suffix"
        val ids = setOf("notification-test-a-$suffix", "notification-test-b-$suffix")
        fun meeting(id: String, name: String) = CourseMeeting(id, id, name, location = "测试教室", campusId = campus,
            weekday = day.dayOfWeek.value, sections = listOf(1), weeks = listOf(week.week), rawWeeks = week.week.toString(), rawSections = "1")
        val fixture = original.copy(meetings = original.meetings + listOf(meeting(ids.first(), title), meeting(ids.last(), dismissedTitle)),
            weeks = if (existingWeek == null) original.weeks + week else original.weeks,
            clocks = original.clocks + CampusClock(campus, "测试校区", listOf(CampusPeriod(1, start.toLocalTime().toString(), end.toLocalTime().toString(), "测试"))))
        fun evidence(phase: String) {
            val interactive = context.getSystemService(android.os.PowerManager::class.java).isInteractive
            val locked = context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("stream", "\nCourse reminder $phase: interactive=$interactive, keyguardLocked=$locked\n")
            })
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        fun notice(name: String, phase: String) = manager.activeNotifications.firstOrNull {
            it.notification.extras.getString(Notification.EXTRA_TITLE) == "$name · $phase"
        }
        suspend fun waitFor(name: String, phase: String): android.service.notification.StatusBarNotification = withTimeout(100_000) {
            while (true) { notice(name, phase)?.let { return@withTimeout it }; delay(100) }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
        val checkpoint = org.json.JSONObject().put("accountKey", CourseReminderRules.hash(account))
            .put("year", original.term.year).put("term", original.term.code).put("ids", org.json.JSONArray(ids.toList()))
            .put("campus", campus).put("addedWeek", if (existingWeek == null) week.week else -1).put("weekStart", week.start)
            .put("preferences", kotlinx.serialization.json.Json.encodeToString(NotificationSettings.serializer(), preferences))
        context.cacheDir.resolve("course-reminder-device-recovery.json").outputStream().use {
            it.write(checkpoint.toString().toByteArray()); it.fd.sync()
        }
        try {
            store.save(fixture)
            container.settings.setNotifications(settings)
            container.courseReminders.reconcileSafely()
            val first = waitFor(title, "距上课")
            evidence("countdown")
            assertTrue(first.notification.extras.getString("miui.focus.param")!!.contains("\"timerType\":-1"))
            withTimeout(5000) { while (!CourseIslandService.running) delay(100) }
            assertTrue(container.courseReminders.sendTestNotification())
            val preview = manager.activeNotifications.single { it.id == AndroidCourseReminders.TEST_ISLAND_ID }
            preview.notification.actions.single().actionIntent.send()
            withTimeout(5000) {
                while (manager.activeNotifications.any { it.id == AndroidCourseReminders.TEST_ISLAND_ID }) delay(100)
            }
            assertNotNull(notice(title, "距上课"))
            assertTrue(CourseIslandService.running)
            val dismiss = waitFor(dismissedTitle, "距上课")
            dismiss.notification.actions.single().actionIntent.send()
            withTimeout(5000) { while (notice(dismissedTitle, "距上课") != null) delay(100) }
            container.courseReminders.reconcileSafely()
            container.courseReminders.reconcileSafely()
            assertNull(notice(dismissedTitle, "距上课"))
            assertEquals(first.id, notice(title, "距上课")!!.id)
            val started = waitFor(title, "已上课")
            evidence("class-started")
            assertFalse(started.notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
            assertEquals(first.id, started.id)
            assertNull(notice(dismissedTitle, "已上课"))
            val finished = waitFor(title, "已下课")
            evidence("class-ended")
            assertFalse(finished.notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
            assertNotEquals(first.id, finished.id)
            withTimeout(100_000) {
                while (manager.activeNotifications.any { it.notification.extras.getString(Notification.EXTRA_TITLE)?.startsWith(title) == true } || CourseIslandService.running) delay(100)
            }
            assertTrue(System.currentTimeMillis() >= until)
            evidence("notifications-cleared")
        } finally {
            withContext(NonCancellable) { restoreFixture(context, container) }
        }
    }

    @Test fun restoreAnInterruptedDeviceTest(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("courseReminderRecovery") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as HDUHelperApplication).container
        container.auth.initialize()
        restoreFixture(context, container)
    }

    private suspend fun restoreFixture(context: android.content.Context, container: AppContainer) {
        val file = context.cacheDir.resolve("course-reminder-device-recovery.json")
        if (!file.isFile) return
        val checkpoint = org.json.JSONObject(file.readText())
        val account = container.auth.serviceIdentity()?.account ?: error("Sign in to the original test account before recovery")
        check(CourseReminderRules.hash(account) == checkpoint.getString("accountKey"))
        val store = androidTimetableStore(context)
        val term = AcademicTerm(checkpoint.getString("year"), checkpoint.getString("term"))
        val ids = checkpoint.getJSONArray("ids").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
        store.load(account, term)?.let { current ->
            store.save(current.copy(meetings = current.meetings.filterNot { it.id in ids },
                clocks = current.clocks.filterNot { it.id == checkpoint.getString("campus") },
                weeks = current.weeks.filterNot { it.week == checkpoint.optInt("addedWeek", -1) && it.start == checkpoint.getString("weekStart") }))
        }
        val testSettings = NotificationSettings(island = true, afterClass = true, beforeMinutes = 1, afterMinutes = 1)
        if (container.settings.state.value.notifications == testSettings) {
            container.settings.setNotifications(kotlinx.serialization.json.Json.decodeFromString<NotificationSettings>(checkpoint.getString("preferences")))
        }
        container.courseReminders.reconcileSafely()
        check(file.delete())
    }

    @Test fun currentCourseLinksOpenColdAndWarmDetails(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("courseReminders") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as HDUHelperApplication).container
        container.auth.initialize()
        val account = container.auth.serviceIdentity()?.account
        assumeTrue(account != null)
        val latest = container.timetables.cached(account!!)
        val data = latest?.let { container.timetables.cached(account, it.catalog.current) }
        assumeTrue(data != null)
        val reminders = CourseReminderRules.reminders(data!!, NotificationSettings(), System.currentTimeMillis())
        val first = reminders.firstOrNull()
        val second = reminders.firstOrNull { it.date != first?.date }
        assumeTrue(first != null && second != null)
        first!!; second!!
        val link = Uri.Builder().scheme("hduhelper").authority("course").appendPath(first.accountKey).appendPath(first.termKey)
            .appendPath(first.itemId).appendPath(first.date.toString()).build()
        val launch = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(link)
        var scenario: ActivityScenario<MainActivity>? = null
        val notification = container.courseReminders.buildNotification(second, 999998, false, true, System.currentTimeMillis())
        try {
            scenario = ActivityScenario.launch(launch)
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("course_details").fetchSemanticsNodes().isNotEmpty() }
            val format = DateTimeFormatter.ofPattern("yyyy年M月d日")
            compose.onNodeWithTag("schedule_date").assertTextContains(first.date.format(format))
            notification.contentIntent.send()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(second.date.format(format)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("course_details").assertIsDisplayed()
        } finally {
            notification.contentIntent.cancel()
            scenario?.onActivity { it.intent = launch }
            scenario?.close()
        }
    }
}
