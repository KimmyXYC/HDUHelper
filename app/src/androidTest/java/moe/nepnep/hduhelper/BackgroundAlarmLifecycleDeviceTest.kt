package moe.nepnep.hduhelper

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import moe.nepnep.hduhelper.data.background.BackgroundHookState
import moe.nepnep.hduhelper.data.notifications.*
import moe.nepnep.hduhelper.data.schedule.*
import moe.nepnep.hduhelper.data.timetable.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Paired tests: finish instrumentation, kill the cached app, then check already-posted notices. */
class BackgroundAlarmLifecycleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container get() = (context.applicationContext as HDUHelperApplication).container
    private val checkpoint get() = context.cacheDir.resolve("background-alarm-device-recovery.json")
    private val temporarySettings = NotificationSettings(island = false, beforeClass = true, afterClass = false, beforeMinutes = 0)

    @Test fun prepareColdLockedReminders(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("backgroundLifecycle") == "true")
        container.auth.initialize()
        check(!checkpoint.exists()) { "Finish or restore the preceding alarm probe first" }
        assertTrue(container.courseReminders.permissionStatus().notifications)
        assertTrue(container.scheduleReminders.notificationsAllowed())
        assertTrue(context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
        val identity = requireNotNull(container.auth.serviceIdentity())
        val store = androidTimetableStore(context)
        val latest = requireNotNull(store.load(identity.account))
        val original = requireNotNull(store.load(identity.account, latest.catalog.current))
        val prefs = container.settings.state.value.notifications
        val enhanced = InstrumentationRegistry.getArguments().getString("enhanced") == "true"
        val start = LocalDateTime.now(campusZone).plusSeconds(50).withNano(0)
        val end = start.plusMinutes(2)
        val until = end.atZone(campusZone).toInstant().toEpochMilli()
        assumeTrue(start.toLocalDate() == end.toLocalDate())
        assumeTrue(CourseReminderRules.reminders(original, prefs, System.currentTimeMillis()).none { it.begins < until })
        val day = start.toLocalDate()
        val existingWeek = original.weeks.firstOrNull { day >= it.startDate && day <= it.endDate }
        val week = existingWeek ?: WeekRange((original.weeks.maxOfOrNull { it.week } ?: 0) + 1000, day.toString(), day.plusDays(6).toString())
        val suffix = UUID.randomUUID().toString()
        val campus = "background-test-$suffix"
        val courseId = "background-course-$suffix"
        val title = "后台课程测试 $suffix"
        val scheduleTitle = "后台日程测试 $suffix"
        val record = JSONObject().put("accountKey", CourseReminderRules.hash(identity.account))
            .put("year", original.term.year).put("term", original.term.code).put("courseId", courseId).put("campus", campus)
            .put("addedWeek", if (existingWeek == null) week.week else -1).put("weekStart", week.start)
            .put("preferences", Json.encodeToString(NotificationSettings.serializer(), prefs))
            .put("originalEnhancement", container.settings.state.value.backgroundEnhancement).put("enhanced", enhanced)
            .put("title", title).put("scheduleTitle", scheduleTitle).put("pid", Process.myPid())
            .put("at", start.atZone(campusZone).toInstant().toEpochMilli())
        checkpoint.outputStream().use { it.write(record.toString().toByteArray()); it.fd.sync() }
        try {
            container.settings.setBackgroundEnhancement(enhanced)
            withTimeout(20_000) {
                val expected = if (enhanced) BackgroundHookState.ACTIVE else BackgroundHookState.READY
                while (container.background.refreshNow().hook != expected) delay(200)
            }
            val meeting = CourseMeeting(courseId, courseId, title, location = "测试教室", campusId = campus,
                weekday = day.dayOfWeek.value, sections = listOf(1), weeks = listOf(week.week), rawWeeks = week.week.toString(), rawSections = "1")
            store.save(original.copy(meetings = original.meetings + meeting,
                weeks = if (existingWeek == null) original.weeks + week else original.weeks,
                clocks = original.clocks + CampusClock(campus, "测试校区", listOf(CampusPeriod(1, start.toLocalTime().toString(), end.toLocalTime().toString(), "测试")))))
            val event = ScheduleEvent(scheduleTitle, start = start.toString(), end = end.toString(), reminderMinutes = 0)
            container.schedules.save(ScheduleEditor(null, null, event), event)
            container.settings.setNotifications(temporarySettings)
            container.courseReminders.reconcileSafely()
            container.scheduleReminders.reconcile()
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN))
            withTimeout(5_000) { while (context.getSystemService(PowerManager::class.java).isInteractive) delay(100) }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nCold alarm probe prepared: enhanced=$enhanced, pid=${Process.myPid()}, target=${record.getLong("at")}, interactive=false\n")
            })
        } catch (e: Throwable) { withContext(NonCancellable) { restore() }; throw e }
    }

    @Test fun verifyPreviouslyDeliveredColdRemindersAndRestore(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("backgroundLifecycle") == "true")
        val record = JSONObject(checkpoint.readText())
        try {
            assertNotEquals(record.getInt("pid"), Process.myPid())
            // Inspect lock state from adb before starting verification: instrumentation can wake the screen.
            val notices = context.getSystemService(NotificationManager::class.java).activeNotifications
            val course = notices.single { it.notification.extras.getString(Notification.EXTRA_TITLE)?.startsWith(record.getString("title")) == true }
            val schedule = notices.single { it.notification.extras.getString(Notification.EXTRA_TITLE) == record.getString("scheduleTitle") }
            val at = record.getLong("at")
            for (notice in listOf(course, schedule)) {
                assertTrue("Reminder must precede verification, not be replayed by the test launch", notice.postTime in at..(at + 10_000))
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nCold reminders delivered: enhanced=${record.getBoolean("enhanced")}, courseDelay=${course.postTime - at}ms, scheduleDelay=${schedule.postTime - at}ms\n")
            })
        } finally { withContext(NonCancellable) { restore() } }
    }

    @Test fun restoreInterruptedColdReminderProbe(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("backgroundRecovery") == "true")
        restore()
    }

    private suspend fun restore() {
        if (!checkpoint.exists()) return
        container.auth.initialize()
        val record = JSONObject(checkpoint.readText())
        val account = requireNotNull(container.auth.serviceIdentity()).account
        check(CourseReminderRules.hash(account) == record.getString("accountKey"))
        val store = androidTimetableStore(context)
        store.load(account, AcademicTerm(record.getString("year"), record.getString("term")))?.let { current ->
            store.save(current.copy(meetings = current.meetings.filterNot { it.id == record.getString("courseId") },
                clocks = current.clocks.filterNot { it.id == record.getString("campus") },
                weeks = current.weeks.filterNot { it.week == record.getInt("addedWeek") && it.start == record.getString("weekStart") }))
        }
        container.schedules.load()
        container.schedules.state.value.book.series.filter { it.event.title == record.getString("scheduleTitle") }.forEach { container.schedules.delete(it.id, null) }
        if (container.settings.state.value.notifications == temporarySettings) {
            container.settings.setNotifications(Json.decodeFromString<NotificationSettings>(record.getString("preferences")))
        }
        if (container.settings.state.value.backgroundEnhancement == record.getBoolean("enhanced")) {
            container.settings.setBackgroundEnhancement(record.getBoolean("originalEnhancement"))
        }
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.activeNotifications.filter {
            val title = it.notification.extras.getString(Notification.EXTRA_TITLE)
            title?.startsWith(record.getString("title")) == true || title == record.getString("scheduleTitle")
        }.forEach { notifications.cancel(it.tag, it.id) }
        container.background.refreshNow()
        container.courseReminders.reconcileSafely()
        container.scheduleReminders.reconcile()
        check(checkpoint.delete())
    }
}
