package moe.nepnep.hduhelper.data.timetable

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TimetableWeekRulesTest {
    @Test fun marksTodayAndHolidayButNeverHistoricalTermDefault() {
        val d = data(meeting("a"))
        assertEquals(0, TimetableWeekRules.current(d, LocalDate.of(2026, 9, 8), true))
        assertEquals(2, TimetableWeekRules.current(d, LocalDate.of(2026, 9, 21), true))
        assertNull(TimetableWeekRules.current(d.copy(term = AcademicTerm("2025", "12")), LocalDate.of(2026, 9, 21), true))
        assertNull(TimetableWeekRules.current(d, LocalDate.of(2028, 1, 1), true))
        assertEquals("假期中", TimetableWeekRules.label(d, 0, true))
    }
    @Test fun choicesUseActualWeeksAndRespectExamVisibility() {
        val exam = ExamArrangement("exam", "合成考试", start = "2027-08-01T09:00", end = "2027-08-01T11:00")
        val d = data(meeting("a")).copy(exams = ExamSnapshot(listOf(exam), 1))
        val date = LocalDate.of(2026, 9, 8)
        val visible = TimetableWeekRules.available(d, date, true)
        val hidden = TimetableWeekRules.available(d, date, false)
        val extra = (visible - hidden.toSet()).single()
        assertTrue(TimetableWeekRules.label(d, extra, true).contains('/'))
        assertEquals(d.weeks.size + 1, hidden.size)
        assertEquals(visible.size, visible.distinct().size)
    }
}
