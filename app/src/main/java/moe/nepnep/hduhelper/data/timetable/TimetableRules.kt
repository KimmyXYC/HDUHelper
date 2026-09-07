package moe.nepnep.hduhelper.data.timetable

import java.time.LocalDate
import kotlin.math.abs

enum class MeetingState { CURRENT, OTHER_WEEK, FINISHED }
data class MeetingCard(val meeting: CourseMeeting, val state: MeetingState, val overlaps: List<CourseMeeting>)

object TimetableRules {
    fun defaultWeek(weeks: List<WeekRange>, today: LocalDate): Int {
        val ordered = weeks.sortedBy { it.week }
        if (ordered.isEmpty()) return 1
        if (today < ordered.first().startDate) return 0
        return ordered.firstOrNull { today >= it.startDate && today <= it.endDate }?.week ?: ordered.first().week
    }

    fun availableWeeks(weeks: List<WeekRange>, today: LocalDate): List<Int> =
        (if (weeks.minByOrNull { it.week }?.startDate?.let { today < it } == true) listOf(0) else emptyList()) + weeks.map { it.week }.distinct().sorted()

    fun automaticCampus(data: TimetableData): String? = data.meetings.groupBy { it.campusId }.entries
        .filter { it.key.isNotBlank() }
        .sortedWith(compareByDescending<Map.Entry<String, List<CourseMeeting>>> { it.value.map { m -> m.courseKey }.distinct().size }.thenBy { it.key })
        .firstOrNull()?.key ?: data.clocks.firstOrNull()?.id

    fun overlap(a: CourseMeeting, b: CourseMeeting): Boolean = a.weekday == b.weekday && a.sections.any { it in b.sections }

    fun cards(data: TimetableData, week: Int, settings: TimetableSettings): List<MeetingCard> {
        val endings = data.meetings.groupBy { it.courseKey }.mapValues { (_, meetings) -> meetings.flatMap { it.weeks }.maxOrNull() ?: 0 }
        fun state(m: CourseMeeting): MeetingState = when {
            week == 0 -> MeetingState.OTHER_WEEK
            endings.getValue(m.courseKey) < week -> MeetingState.FINISHED
            week in m.weeks -> MeetingState.CURRENT
            else -> MeetingState.OTHER_WEEK
        }
        val comparator = compareBy<CourseMeeting> { state(it).ordinal }
            .thenBy { m -> if (state(m) == MeetingState.FINISHED) -(endings[m.courseKey] ?: 0) else m.weeks.minOfOrNull { abs(it - week) } ?: Int.MAX_VALUE }
            .thenBy { m ->
                val distance = m.weeks.minOfOrNull { abs(it - week) }
                if (m.weeks.any { it >= week && abs(it - week) == distance }) 0 else 1
            }.thenBy { it.id }
        return data.meetings.filter { m ->
            (settings.showWeekend || m.weekday <= 5) && when (state(m)) {
                MeetingState.CURRENT -> true
                MeetingState.OTHER_WEEK -> settings.showOtherWeeks
                MeetingState.FINISHED -> settings.showFinished
            }
        }.sortedWith(comparator).map { m ->
            // Include hidden records in details, but only direct time intersections, not transitive neighbours.
            val candidates = data.meetings.filter { overlap(m, it) }.sortedWith(comparator)
            MeetingCard(m, state(m), candidates)
        }
    }

    /** The first card wins each occupied cell. Lower cards remain visible outside that intersection. */
    fun visibleSections(cards: List<MeetingCard>): Map<String, List<Int>> {
        val occupied = mutableSetOf<Pair<Int, Int>>()
        return cards.associate { card ->
            card.meeting.id to card.meeting.sections.filter { occupied.add(card.meeting.weekday to it) }
        }
    }

    fun runs(numbers: List<Int>): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        for (n in numbers.distinct().sorted()) {
            val last = runs.lastOrNull()
            if (last != null && last.last + 1 == n) runs[runs.lastIndex] = last.first..n else runs += n..n
        }
        return runs
    }

    fun timeText(meeting: CourseMeeting, clocks: List<CampusClock>): String {
        val periods = clocks.firstOrNull { it.id == meeting.campusId }?.periods.orEmpty().associateBy { it.section }
        return runs(meeting.sections).joinToString("、") { range ->
            val start = periods[range.first]?.start
            val end = periods[range.last]?.end
            if (start.isNullOrBlank() || end.isNullOrBlank() || range.any { it !in periods }) "时间暂不可用" else "$start–$end"
        }.ifBlank { "时间暂不可用" }
    }
}
