package moe.nepnep.hduhelper.widget

import java.time.LocalDate
import java.time.LocalDateTime
import moe.nepnep.hduhelper.data.schedule.*
import moe.nepnep.hduhelper.data.timetable.*
import org.junit.Assert.*
import org.junit.Test

class WidgetRulesTest {
    private val date = LocalDate.parse("2026-09-08")
    private val term = AcademicTerm("2026", "3")
    private fun data(exams: List<ExamArrangement> = emptyList()) = TimetableData("synthetic", term,
        TimetableCatalog(listOf("2026"), emptyList(), term), listOf(CourseMeeting("c", "c", "课程", campusId = "1",
            weekday = 2, sections = listOf(1, 2), weeks = listOf(1), rawWeeks = "1", rawSections = "1-2")), emptyList(),
        listOf(WeekRange(1, "2026-09-07", "2026-09-13")), listOf(CampusClock("1", "校区", listOf(
            CampusPeriod(1, "08:00", "08:45", "上午"), CampusPeriod(2, "08:50", "09:35", "上午")))), 1,
        exams = ExamSnapshot(exams, 1))
    private fun event(id: String, start: String, end: String, allDay: Boolean = false) = ScheduleSeries(id,
        ScheduleEvent(title = id, start = start, end = end, allDay = allDay))

    @Test fun ongoingThenAllDayThenFutureAndUnknownWithExclusiveEnd() {
        val book = ScheduleBook(listOf(event("all", "2026-09-08T00:00", "2026-09-09T00:00", true),
            event("ended", "2026-09-08T07:00", "2026-09-08T09:00"),
            event("future", "2026-09-08T10:00", "2026-09-08T11:00")))
        val rows = AgendaRules.items(book, data(listOf(ExamArrangement("unknown", "时间待定考试"))), date)
        val pending = AgendaRules.pending(rows, date.atTime(9, 0))
        assertEquals(listOf("course/c", "all/$date", "future/$date", "exam/unknown"), pending.map { it.key })
        assertEquals(3, pending.size - pending.take(1).size)
        assertFalse(AgendaRules.pending(rows, date.atTime(9, 35)).any { it.course != null })
        assertEquals("时间待定", AgendaRules.time(pending.last(), date))
    }
    @Test fun crossDayAndRepeatedExceptionsUseTheExistingOccurrenceRules() {
        val series = event("night", "2026-09-07T23:00", "2026-09-08T10:00")
        val repeat = event("repeat", "2026-09-07T12:00", "2026-09-07T13:00").let {
            it.copy(event = it.event.copy(repeat = ScheduleRepeat.DAILY), exceptions = listOf(ScheduleException(date.toString())))
        }
        val rows = AgendaRules.items(ScheduleBook(listOf(series, repeat)), null, date)
        assertEquals(1, rows.size)
        assertTrue(AgendaRules.time(rows.single(), date).contains("9/7"))
        assertTrue(rows.single().ongoing(date.atTime(9, 0)))
        assertTrue(AgendaRules.pending(rows, date.atTime(10, 0)).isEmpty())
    }
    @Test fun aMissingCourseClockDoesNotHideAnArrangementAsEnded() {
        val rows = AgendaRules.items(ScheduleBook(), data().copy(clocks = emptyList()), date)
        assertEquals(1, AgendaRules.pending(rows, date.atTime(23, 59)).size)
        assertEquals("时间待定", AgendaRules.time(rows.single(), date))
    }
    @Test fun allDaySpansShowInclusiveDatesAndDoNotLeakIntoTheExclusiveEndDate() {
        val book = ScheduleBook(listOf(event("trip", "2026-09-07T00:00", "2026-09-10T00:00", true)))
        val item = AgendaRules.items(book, null, date).single()
        assertEquals("全天 · 9/7–9/9", AgendaRules.time(item, date))
        assertTrue(AgendaRules.items(book, null, LocalDate.parse("2026-09-10")).isEmpty())
    }

}
