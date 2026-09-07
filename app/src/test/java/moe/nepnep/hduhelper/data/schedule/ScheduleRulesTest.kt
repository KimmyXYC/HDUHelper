package moe.nepnep.hduhelper.data.schedule

import java.time.*
import moe.nepnep.hduhelper.data.timetable.*
import org.junit.Assert.*
import org.junit.Test

class ScheduleRulesTest {
    private fun event(start: String = "2026-09-07T10:00", end: String = "2026-09-07T11:00", repeat: ScheduleRepeat = ScheduleRepeat.NEVER) =
        ScheduleEvent("测试", start = start, end = end, repeat = repeat)
    private fun date(value: String) = LocalDate.parse(value)

    @Test fun coursesUseExactCalendarWeekAndKeepWeekendsAndOverlaps() {
        val d = data(meeting("one", weeks = listOf(1), day = 7), meeting("two", weeks = listOf(1), day = 7), meeting("other", weeks = listOf(2), day = 7))
        assertEquals(listOf("one", "two"), ScheduleRules.courses(d, date("2026-09-20")).map { it.id })
        assertEquals(listOf("other"), ScheduleRules.courses(d, date("2026-09-27")).map { it.id })
        assertTrue(ScheduleRules.courses(d, date("2026-09-13")).isEmpty())
        assertTrue(ScheduleRules.courses(d, date("2028-09-20")).isEmpty())
        assertEquals(LocalTime.of(10, 0), ScheduleRules.courseStart(d.meetings.first(), d.clocks))
        assertNull(ScheduleRules.courseStart(d.meetings.first(), emptyList()))
    }

    @Test fun crossDayAndExclusiveEndDoNotSpillOntoExtraDay() {
        val series = ScheduleSeries(event = event("2026-09-07T23:00", "2026-09-09T00:00"))
        assertEquals(1, ScheduleRules.occurrences(series, date("2026-09-07")).size)
        assertEquals(1, ScheduleRules.occurrences(series, date("2026-09-08")).size)
        assertTrue(ScheduleRules.occurrences(series, date("2026-09-09")).isEmpty())
        val allDay = series.copy(event = event("2026-09-07T00:00", "2026-09-09T00:00").copy(allDay = true))
        assertEquals(1, ScheduleRules.occurrences(allDay, date("2026-09-08")).size)
        assertTrue(ScheduleRules.occurrences(allDay, date("2026-09-09")).isEmpty())
    }

    @Test fun monthEndAndLeapDaySkipMissingDatesWithoutDrift() {
        val monthly = event("2026-01-31T10:00", "2026-01-31T11:00", ScheduleRepeat.MONTHLY)
        assertEquals(listOf(date("2026-01-31"), date("2026-03-31")), ScheduleRules.dates(monthly, date("2026-01-01"), date("2026-04-30")).toList())
        val yearly = event("2096-02-29T10:00", "2096-02-29T11:00", ScheduleRepeat.YEARLY)
        assertEquals(listOf(date("2104-02-29")), ScheduleRules.dates(yearly, date("2097-01-01"), date("2105-01-01")).toList())
        assertEquals(date("2104-02-29"), ScheduleRules.nextReminder(ScheduleSeries(event = yearly.copy(reminderMinutes = 0)), date("2097-01-01").atStartOfDay(campusZone).toInstant().toEpochMilli())!!.originalDate)
    }

    @Test fun weekdaysAndWeeklyRespectOriginalAnchor() {
        val friday = event("2026-09-11T10:00", "2026-09-11T11:00", ScheduleRepeat.WEEKDAYS)
        assertEquals(listOf(date("2026-09-11"), date("2026-09-14")), ScheduleRules.dates(friday, date("2026-09-10"), date("2026-09-14")).toList())
        assertEquals(listOf(date("2026-09-18")), ScheduleRules.dates(friday.copy(repeat = ScheduleRepeat.WEEKLY), date("2026-09-12"), date("2026-09-20")).toList())
    }

    @Test fun movedExceptionAppearsOnceEvenOutsideOriginalDateWindow() {
        val series = ScheduleSeries(event = event(repeat = ScheduleRepeat.DAILY), exceptions = listOf(
            ScheduleException("2026-09-08", event("2027-03-02T10:00", "2027-03-02T11:00")), ScheduleException("2026-09-09")))
        assertTrue(ScheduleRules.occurrences(series, date("2026-09-08")).isEmpty())
        assertTrue(ScheduleRules.occurrences(series, date("2026-09-09")).isEmpty())
        assertEquals(setOf(date("2026-09-08"), date("2027-03-02")), ScheduleRules.occurrences(series, date("2027-03-02")).map { it.originalDate }.toSet())
    }

    @Test fun remindersUseAllDayNineAndAdvancePastHandledOccurrences() {
        val allDay = event("2026-09-07T00:00", "2026-09-08T00:00").copy(allDay = true, reminderMinutes = 1440)
        assertEquals(date("2026-09-06").atTime(9, 0).atZone(campusZone).toInstant().toEpochMilli(), ScheduleRules.reminderTime(allDay))
        val regular = event(repeat = ScheduleRepeat.DAILY).copy(reminderMinutes = 10)
        val at = ScheduleRules.reminderTime(regular)!!
        assertEquals(date("2026-09-07"), ScheduleRules.nextReminder(ScheduleSeries(event = regular), at - 1)!!.originalDate)
        assertEquals(date("2026-09-08"), ScheduleRules.nextReminder(ScheduleSeries(event = regular), at)!!.originalDate)
        val replacement = regular.onDate(date("2026-09-09")).copy(repeat = ScheduleRepeat.NEVER, reminderMinutes = 60)
        val modified = ScheduleSeries(event = regular.copy(reminderMinutes = null), exceptions = listOf(ScheduleException("2026-09-08", replacement)))
        assertEquals(date("2026-09-08"), ScheduleRules.nextReminder(modified, at)!!.originalDate)
    }

    @Test fun validationAndQuarterHourDefault() {
        assertNotNull(event().copy(title = "  ").validationError())
        assertNotNull(event().copy(end = "2026-09-07T09:00").validationError())
        val draft = newScheduleEvent(date("2026-09-10"), LocalDateTime.parse("2026-09-07T23:59:30"))
        assertEquals("2026-09-11T00:00", draft.start)
        assertEquals("2026-09-11T01:00", draft.end)
    }
}
