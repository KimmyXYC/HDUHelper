package moe.nepnep.hduhelper

import android.app.Notification
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import moe.nepnep.hduhelper.data.notifications.*
import moe.nepnep.hduhelper.data.timetable.CourseMeeting
import org.junit.Assert.*
import org.junit.Test

/** Builds real platform templates without posting notifications or modifying the user's timetable. */
class CourseNotificationTest {
    @Test fun platformChronometerSwitchesDirectionAndHasBoundedLifetime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reminders = (context.applicationContext as HDUHelperApplication).container.courseReminders
        val target = System.currentTimeMillis() + 60_000
        val course = CourseMeeting("synthetic", "synthetic", "通知格式测试", weekday = 1, sections = listOf(1), weeks = listOf(1), rawWeeks = "1", rawSections = "1")
        for (kind in CourseReminderKind.entries) {
            val reminder = CourseReminder("synthetic-$kind", "synthetic", "2026-3", course, LocalDate.of(2026, 9, 14), kind, target, target - 60_000)
            val before = reminders.buildNotification(reminder, 999999, true, true, target - 1000)
            assertEquals(target, before.`when`)
            assertTrue(before.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertTrue(before.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
            assertEquals(61000L, before.timeoutAfter)
            assertTrue(before.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertNotNull(before.deleteIntent)
            assertEquals("关闭本次提醒", before.actions.single().title)
            if (Build.VERSION.SDK_INT >= 36) assertTrue(before.hasPromotableCharacteristics())
            val after = reminders.buildNotification(reminder, 999999, true, true, target + 5000)
            assertFalse(after.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
            assertEquals(55000L, after.timeoutAfter)
            assertTrue(after.extras.getString(Notification.EXTRA_TITLE)!!.contains("已${kind.label}"))
            val ordinary = reminders.buildNotification(reminder, 999999, false, false, target - 1000)
            assertFalse(ordinary.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertEquals(0, ordinary.flags and Notification.FLAG_ONGOING_EVENT)
            before.contentIntent.cancel()
            before.deleteIntent.cancel()
        }
    }
}
