package moe.nepnep.hduhelper.data.timetable

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class ExamAgendaRulesTest {
    private val now = LocalDateTime.parse("2026-09-08T10:00:00")
    private fun exam(id: String, start: String, end: String) = ExamArrangement(id, id,
        start = "2026-09-08T$start", end = "2026-09-08T$end")

    @Test fun ongoingPrecedesFutureAndEndedExamsAreChronological() {
        val active = exam("active", "09:30", "11:00")
        val otherActive = exam("other", "10:00", "12:00")
        val future = exam("future", "14:00", "16:00")
        val older = exam("old", "07:00", "08:00")
        val recent = exam("recent", "08:00", "10:00")
        val untimed = ExamArrangement("unknown", "待定考试", rawTime = "另行通知")
        val result = ExamAgendaRules.arrange(listOf(future, recent, untimed, otherActive, older, active), now)
        assertEquals(active, result.featured)
        assertEquals(listOf(otherActive, future), result.upcoming)
        assertEquals(listOf(untimed), result.untimed)
        assertEquals(listOf(older, recent), result.ended)
    }

    @Test fun boundariesMoveFromCountdownToOngoingThenEnded() {
        val exam = exam("sample", "10:00", "12:00")
        assertFalse(ExamAgendaRules.ongoing(exam, now.minusSeconds(1)))
        assertEquals("0天0小时0分钟1秒", ExamAgendaRules.countdown(exam, now.minusSeconds(1)))
        assertTrue(ExamAgendaRules.ongoing(exam, now))
        assertEquals("0天2小时0分钟0秒", ExamAgendaRules.countdown(exam, now))
        val after = ExamAgendaRules.arrange(listOf(exam), now.plusHours(2))
        assertNull(after.featured)
        assertEquals(listOf(exam), after.ended)
    }

    @Test fun emptyAndInvalidTimesHaveNoFeaturedExam() {
        assertNull(ExamAgendaRules.arrange(emptyList(), now).featured)
        val invalid = exam("bad", "12:00", "10:00")
        val unknown = ExamArrangement("none", "none")
        val result = ExamAgendaRules.arrange(listOf(invalid, unknown), now)
        assertEquals(2, result.untimed.size)
        assertTrue(result.ended.isEmpty())
        assertNull(result.featured)
    }
}
