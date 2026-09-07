package moe.nepnep.hduhelper.data.notifications

import java.time.LocalDate
import java.time.LocalDateTime
import moe.nepnep.hduhelper.data.timetable.*
import org.junit.Assert.*
import org.junit.Test

class CourseReminderRulesTest {
    private fun at(value: String) = LocalDateTime.parse(value).atZone(campusZone).toInstant().toEpochMilli()
    private val now = at("2026-09-14T08:00")
    private val both = NotificationSettings(live = true, afterClass = true)
    private fun firstDay(settings: NotificationSettings = both, vararg courses: CourseMeeting) =
        CourseReminderRules.reminders(data(*courses), settings, now).filter { it.date == LocalDate.of(2026, 9, 14) }

    @Test fun defaultsAndInvalidStoredMinutesAreSafe() {
        val defaults = NotificationSettings()
        assertFalse(defaults.live); assertTrue(defaults.beforeClass); assertFalse(defaults.afterClass)
        assertEquals(10, defaults.beforeMinutes); assertEquals(1, defaults.afterMinutes)
        assertEquals(defaults, defaults.copy(beforeMinutes = -1, afterMinutes = Int.MAX_VALUE).normalized())
        for (minutes in 0..30) {
            val settings = defaults.copy(beforeMinutes = minutes, afterMinutes = minutes).normalized()
            assertEquals(minutes, settings.beforeMinutes); assertEquals(minutes, settings.afterMinutes)
        }
        assertEquals(defaults, defaults.copy(beforeMinutes = 31, afterMinutes = -1).normalized())
    }

    @Test fun contiguousSectionsHaveOneStartAndOneEndDespiteBreaks() {
        val reminders = firstDay(both, meeting("a"))
        assertEquals(2, reminders.size)
        assertEquals(at("2026-09-14T09:50"), reminders.first().begins)
        assertEquals(at("2026-09-14T10:00"), reminders.first().target)
        assertEquals(at("2026-09-14T12:24"), reminders.last().begins)
        assertEquals(at("2026-09-14T12:25"), reminders.last().target)
    }

    @Test fun nonContiguousSectionsAndCampusClocksRemainIndependent() {
        val reminders = firstDay(both, meeting("a", sections = listOf(3, 5), campus = "2"))
        assertEquals(4, reminders.size)
        assertEquals(listOf("09:50", "10:35", "11:35", "12:20"), reminders.map {
            java.time.Instant.ofEpochMilli(it.target).atZone(campusZone).toLocalTime().toString()
        })
    }

    @Test fun actualTeachingWeeksAndWeekendsIgnoreUiFilters() {
        val course = meeting("saturday", weeks = listOf(2), day = 6)
        val reminders = CourseReminderRules.reminders(data(course), both, now)
        assertEquals(2, reminders.size)
        assertTrue(reminders.all { it.date == LocalDate.of(2026, 9, 26) })
        assertTrue(CourseReminderRules.reminders(data(course).copy(term = term.copy(year = "2025")), both, now).isEmpty())
    }

    @Test fun absentOrMalformedPeriodsNeverInventTimes() {
        assertTrue(firstDay(both, meeting("a", campus = "unknown")).isEmpty())
        assertTrue(firstDay(both, meeting("a", sections = listOf(2, 3))).isEmpty())
        val source = data(meeting("a"))
        val broken = source.copy(clocks = listOf(CampusClock("1", "", listOf(CampusPeriod(3, "n/a", "10:45", "")))))
        assertTrue(CourseReminderRules.reminders(broken, both, now).isEmpty())
    }

    @Test fun everySwitchCombinationRespectsCorrespondingReminder() {
        for (live in listOf(false, true)) for (start in listOf(false, true)) for (end in listOf(false, true)) {
            val reminders = firstDay(NotificationSettings(live, start, end), meeting("a"))
            assertEquals((if (start) 1 else 0) + (if (end) 1 else 0), reminders.size)
            assertEquals(start, reminders.any { it.kind == CourseReminderKind.START })
            assertEquals(end, reminders.any { it.kind == CourseReminderKind.END })
        }
    }

    @Test fun countdownChangesToElapsedAndDisappearsExactlySixtySecondsLater() {
        val reminder = firstDay(both, meeting("a")).first()
        val records = emptyMap<String, CourseReminderRecord>()
        assertTrue(CourseReminderDelivery.active(listOf(reminder), records, reminder.begins - 1).isEmpty())
        assertEquals(listOf(reminder), CourseReminderDelivery.active(listOf(reminder), records, reminder.begins))
        assertEquals(CourseReminderPhase.UPCOMING, reminder.phase(reminder.target - 1))
        assertEquals(CourseReminderPhase.ELAPSED, reminder.phase(reminder.target))
        assertEquals(CourseReminderPhase.ELAPSED, reminder.phase(reminder.target + 59_999))
        assertEquals(CourseReminderPhase.EXPIRED, reminder.phase(reminder.target + 60_000))
        assertTrue(CourseReminderDelivery.active(listOf(reminder), records, reminder.expires).isEmpty())
        assertEquals(reminder.target, CourseReminderDelivery.nextBoundary(listOf(reminder), reminder.begins, true))
        assertEquals(reminder.expires, CourseReminderDelivery.nextBoundary(listOf(reminder), reminder.target, true))
    }

    @Test fun zeroLeadBeginsDirectlyInElapsedPhase() {
        val reminder = firstDay(both.copy(beforeMinutes = 0), meeting("a")).first()
        assertEquals(reminder.target, reminder.begins)
        assertEquals(CourseReminderPhase.ELAPSED, reminder.phase(reminder.begins))
    }

    @Test fun duplicateReceiverAndDismissedLiveWindowAreNotReplayed() {
        val reminder = firstDay(both, meeting("a")).first()
        val all = listOf(reminder)
        assertEquals(all, CourseReminderDelivery.ordinaryDue(all, emptyMap(), reminder.begins, reminder.begins))
        val delivered = mapOf(reminder.key to CourseReminderRecord(reminder.expires, delivered = true))
        assertTrue(CourseReminderDelivery.ordinaryDue(all, delivered, reminder.begins + 1000, reminder.begins).isEmpty())
        assertEquals(all, CourseReminderDelivery.active(all, delivered, reminder.target))
        val dismissed = mapOf(reminder.key to CourseReminderRecord(reminder.expires, true, true))
        assertTrue(CourseReminderDelivery.active(all, dismissed, reminder.target).isEmpty())
        assertTrue(CourseReminderDelivery.ordinaryDue(all, emptyMap(), reminder.expires, reminder.begins).isEmpty())
    }

    @Test fun overlappingCoursesHaveIndependentKeysAndLiveWindows() {
        val reminders = firstDay(both, meeting("a"), meeting("b")).filter { it.kind == CourseReminderKind.START }
        assertEquals(2, reminders.map { it.key }.distinct().size)
        assertEquals(2, CourseReminderDelivery.active(reminders, emptyMap(), reminders.first().target).size)
        val records = mapOf(reminders.first().key to CourseReminderRecord(reminders.first().expires, true, true))
        assertEquals(listOf(reminders.last()), CourseReminderDelivery.active(reminders, records, reminders.first().target))
    }

    @Test fun changedAccountOrCourseTimeInvalidatesOccurrenceButLeadChangesKeepDismissal() {
        val source = data(meeting("a"))
        val original = CourseReminderRules.reminders(source, both, now).first()
        val changedAccount = CourseReminderRules.reminders(source.copy(account = "other"), both, now).first()
        assertNotEquals(original.key, changedAccount.key)
        val changedTime = CourseReminderRules.reminders(data(meeting("a", campus = "2")), both, now).first()
        assertNotEquals(original.key, changedTime.key)
        val changedLead = CourseReminderRules.reminders(source, both.copy(beforeMinutes = 30), now).first()
        assertEquals(original.key, changedLead.key)
        assertNotEquals(original.begins, changedLead.begins)
    }
}
