package moe.nepnep.hduhelper.data.timetable

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TimetableRulesTest {
    @Test fun nearerFutureCourseWinsAndCurrentWeekOverridesIt() {
        val d = data(meeting("B", listOf(2,4,6)), meeting("C", listOf(3,5,7)))
        val first = TimetableRules.cards(d, 1, TimetableSettings())
        assertEquals("B", first.first().meeting.id)
        assertEquals(listOf(3,4,5), TimetableRules.visibleSections(first)["B"])
        assertTrue(TimetableRules.visibleSections(first)["C"]!!.isEmpty())
        val third = TimetableRules.cards(d, 3, TimetableSettings())
        assertEquals("C", third.first().meeting.id)
        assertEquals(MeetingState.CURRENT, third.first().state)
    }
    @Test fun equallyDistantFutureWinsAndTieIsStable() {
        val d = data(meeting("past", listOf(1,6)), meeting("future", listOf(3,6)))
        assertEquals("future", TimetableRules.cards(d, 2, TimetableSettings()).first().meeting.id)
        val tied = data(meeting("Z"), meeting("A"))
        assertEquals("A", TimetableRules.cards(tied, 1, TimetableSettings()).first().meeting.id)
    }
    @Test fun finishedSwitchIsIndependentAndWholeCourseDeterminesCompletion() {
        val d = data(meeting("early", listOf(1), course="shared"), meeting("late", listOf(8), day=2, course="shared"), meeting("done", listOf(1,2), day=3))
        val cards = TimetableRules.cards(d, 4, TimetableSettings(showOtherWeeks=true, showFinished=false))
        assertEquals(setOf("early","late"), cards.map { it.meeting.id }.toSet())
        assertTrue(cards.all { it.state == MeetingState.OTHER_WEEK })
        val onlyFinished = TimetableRules.cards(d, 4, TimetableSettings(showOtherWeeks=false, showFinished=true))
        assertEquals(listOf("done"), onlyFinished.map { it.meeting.id })
        assertEquals(MeetingState.CURRENT, TimetableRules.cards(d, 1, TimetableSettings(showOtherWeeks=false)).first().state)
    }
    @Test fun hiddenOverlapsRemainInDetailsAndPartialOverlapDoesNotExpandCards() {
        val d = data(meeting("A", listOf(1), listOf(3,4,5)), meeting("B", listOf(2), listOf(4,5,6)))
        val hidden = TimetableRules.cards(d, 1, TimetableSettings(showOtherWeeks=false))
        assertEquals(1, hidden.size)
        assertEquals(setOf("A","B"), hidden[0].overlaps.map { it.id }.toSet())
        val visible = TimetableRules.visibleSections(TimetableRules.cards(d, 1, TimetableSettings()))
        assertEquals(listOf(3,4,5), visible["A"])
        assertEquals(listOf(6), visible["B"])
    }
    @Test fun weekendAndHolidayFollowSettings() {
        val d = data(meeting("weekend", day=7))
        assertTrue(TimetableRules.cards(d,1,TimetableSettings()).isEmpty())
        assertEquals(1,TimetableRules.cards(d,1,TimetableSettings(showWeekend=true)).size)
        assertEquals(MeetingState.OTHER_WEEK,TimetableRules.cards(d,0,TimetableSettings(showWeekend=true)).single().state)
        assertTrue(TimetableRules.cards(d,0,TimetableSettings(showOtherWeeks=false,showWeekend=true)).isEmpty())
    }
    @Test fun datesCoverHolidayBoundariesCrossYearAndPastSemester() {
        val weeks=data().weeks
        assertEquals(0,TimetableRules.defaultWeek(weeks,LocalDate.of(2026,9,7)))
        assertEquals(1,TimetableRules.defaultWeek(weeks,LocalDate.of(2026,9,14)))
        assertEquals(1,TimetableRules.defaultWeek(weeks,LocalDate.of(2026,9,20)))
        assertEquals(2,TimetableRules.defaultWeek(weeks,LocalDate.of(2026,9,21)))
        assertEquals(16,TimetableRules.defaultWeek(weeks,LocalDate.of(2027,1,1)))
        assertEquals(1,TimetableRules.defaultWeek(weeks,LocalDate.of(2027,3,1)))
        assertEquals(24,TimetableRules.availableWeeks(weeks,LocalDate.of(2026,9,7)).size)
        assertFalse(0 in TimetableRules.availableWeeks(weeks,LocalDate.of(2026,9,14)))
    }
    @Test fun campusCountsDistinctCoursesAndDetailsUseTheirOwnClock() {
        val d=data(meeting("a",campus="1",course="a"),meeting("a2",campus="1",course="a"),meeting("b",campus="2"),meeting("c",campus="2"))
        assertEquals("2",TimetableRules.automaticCampus(d))
        assertEquals("10:00–12:25",TimetableRules.timeText(d.meetings[0],d.clocks))
        assertEquals("09:50–12:20",TimetableRules.timeText(d.meetings[2],d.clocks))
        assertEquals("时间暂不可用",TimetableRules.timeText(d.meetings[2],d.clocks.take(1)))
        assertEquals(listOf(1..2,4..4),TimetableRules.runs(listOf(1,2,4)))
    }
}
