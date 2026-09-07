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
        for (kind in CourseReminderKind.entries) {
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
}
