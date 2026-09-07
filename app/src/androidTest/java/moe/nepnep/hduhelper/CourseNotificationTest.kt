package moe.nepnep.hduhelper

import android.app.Notification
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import moe.nepnep.hduhelper.data.notifications.*
import moe.nepnep.hduhelper.data.island.CourseIslandTemplate
import moe.nepnep.hduhelper.data.timetable.CourseMeeting
import org.junit.Assert.*
import org.junit.Test

/** Platform packaging checks without posting or changing the user's timetable. */
class CourseNotificationTest {
    @Test fun islandUsesXiaomiTemplateAndOrdinaryNotificationHasNoLiveUpdateFlags() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reminders = (context.applicationContext as HDUHelperApplication).container.courseReminders
        val target = System.currentTimeMillis() + 60_000
        val course = CourseMeeting("synthetic", "synthetic", "通知格式测试", weekday = 1, sections = listOf(1), weeks = listOf(1), rawWeeks = "1", rawSections = "1")
        for (kind in listOf(CourseReminderKind.START, CourseReminderKind.END)) {
            val reminder = CourseReminder("synthetic-$kind", "synthetic", "2026-3", course, LocalDate.of(2026, 9, 14), kind, target, target - 60_000)
            val island = reminders.buildNotification(reminder, 999999, true, true, target - 1000)
            assertNotNull(island.extras.getString(CourseIslandTemplate.PARAM))
            assertFalse(island.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertEquals(61000L, island.timeoutAfter)
            assertNotNull(island.extras.getBundle("miui.focus.pics")?.getParcelable(CourseIslandTemplate.ICON, android.graphics.drawable.Icon::class.java))
            assertNotNull(island.extras.getBundle("miui.focus.actions")?.getParcelable(CourseIslandTemplate.ACTION, Notification.Action::class.java))
            val ordinary = reminders.buildNotification(reminder, 999999, false, false, target - 1000)
            assertNull(ordinary.extras.getString(CourseIslandTemplate.PARAM))
            assertFalse(ordinary.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertEquals(0, ordinary.flags and Notification.FLAG_ONGOING_EVENT)
            if (android.os.Build.VERSION.SDK_INT >= 36) {
                assertEquals(0, ordinary.flags and Notification.FLAG_PROMOTED_ONGOING)
                assertEquals(0, island.flags and Notification.FLAG_PROMOTED_ONGOING)
            }
            island.contentIntent.cancel()
            island.deleteIntent.cancel()
        }
    }
    @Test fun examUsesStartCopySeatAndExamLinkInBothNotificationModes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reminders = (context.applicationContext as HDUHelperApplication).container.courseReminders
        val now = System.currentTimeMillis()
        val exam = moe.nepnep.hduhelper.data.timetable.ExamArrangement("synthetic-exam", "合成考试", location = "测试考场", seat = "12")
        val reminder = CourseReminder("synthetic-exam", "synthetic", "2026-3", null, LocalDate.of(2026, 9, 19),
            CourseReminderKind.EXAM_START, now + 60_000, now, now + 60_000, now + 3_600_000, exam)
        for (island in listOf(false, true)) {
            val notification = reminders.buildNotification(reminder, 999998, island, true, now)
            assertTrue(notification.extras.getString(Notification.EXTRA_TITLE)!!.contains("合成考试"))
            assertTrue(notification.extras.getString(Notification.EXTRA_TEXT)!!.contains("座号 12"))
            if (island) {
                assertTrue(notification.extras.getString(CourseIslandTemplate.PARAM)!!.contains("考试开始"))
                val started = reminders.buildNotification(reminder, 999998, true, true, reminder.target)
                assertTrue(started.extras.getString(Notification.EXTRA_TITLE)!!.contains("考试已开始"))
                assertEquals(60_000L, started.timeoutAfter)
            } else assertNull(notification.extras.getString(CourseIslandTemplate.PARAM))
            notification.contentIntent.cancel()
            notification.deleteIntent?.cancel()
        }
    }
    @Test fun examVisibilityAndReminderPreferencesPersistIndependently() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "exam-preferences-${java.util.UUID.randomUUID()}"
        val isolated = object : android.content.ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try {
            val repo = moe.nepnep.hduhelper.data.settings.SettingsRepository(isolated)
            assertTrue(repo.state.value.timetable.showExams)
            assertTrue(repo.state.value.notifications.beforeExam)
            repo.setTimetable(repo.state.value.timetable.copy(showExams = false))
            repo.setNotifications(repo.state.value.notifications.copy(examMinutes = 7))
            val reopened = moe.nepnep.hduhelper.data.settings.SettingsRepository(isolated)
            assertFalse(reopened.state.value.timetable.showExams)
            assertTrue(reopened.state.value.notifications.beforeExam)
            assertEquals(7, reopened.state.value.notifications.examMinutes)
        } finally { base.deleteSharedPreferences(name) }
    }
}
