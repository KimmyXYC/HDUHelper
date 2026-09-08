package moe.nepnep.hduhelper.data.schedule

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import moe.nepnep.hduhelper.data.timetable.*

/** A dated arrangement shared by the day screen and desktop widgets. */
data class AgendaItem(
    val key: String,
    val title: String,
    val location: String,
    val start: LocalDateTime?,
    val end: LocalDateTime?,
    val allDay: Boolean = false,
    val occurrence: ScheduleOccurrence? = null,
    val course: CourseMeeting? = null,
    val exam: ExamArrangement? = null,
) {
    fun ongoing(now: LocalDateTime) = !allDay && start != null && end != null && now >= start && now < end
    override fun toString() = "AgendaItem([redacted])"
}

object AgendaRules {
    fun items(book: ScheduleBook, data: TimetableData?, date: LocalDate): List<AgendaItem> {
        val custom = book.series.flatMap { ScheduleRules.occurrences(it, date) }.map {
            AgendaItem(it.key, it.event.title, it.event.location, it.event.startTime, it.event.endTime, it.event.allDay, occurrence = it)
        }
        val courses = data?.let { d -> ScheduleRules.courses(d, date).map { meeting ->
            val end = d.clocks.firstOrNull { it.id == meeting.campusId }?.periods
                ?.firstOrNull { it.section == meeting.sections.maxOrNull() }?.end
                ?.let { runCatching { LocalTime.parse(it).atDate(date) }.getOrNull() }
            AgendaItem("course/${meeting.id}", meeting.name, meeting.location,
                ScheduleRules.courseStart(meeting, d.clocks)?.atDate(date), end, course = meeting)
        } }.orEmpty()
        val exams = data?.exams?.items.orEmpty().filter { !it.timed || it.startTime?.toLocalDate() == date }.map {
            AgendaItem("exam/${it.id}", it.name, it.place, it.startTime.takeIf { _ -> it.timed },
                it.endTime.takeIf { _ -> it.timed }, exam = it)
        }
        return (custom + courses + exams).sortedWith(compareByDescending<AgendaItem> { it.allDay }
            .thenBy { it.start ?: LocalDateTime.MAX }.thenBy { it.key })
    }

    fun pending(items: List<AgendaItem>, now: LocalDateTime): List<AgendaItem> = items
        .filter { it.end == null || it.end > now }
        .sortedWith(compareBy<AgendaItem> { when { it.ongoing(now) -> 0; it.allDay -> 1; else -> 2 } }
            .thenBy { it.start ?: LocalDateTime.MAX }.thenBy { it.key })

    fun time(item: AgendaItem, date: LocalDate): String = when {
        item.allDay -> {
            val first = item.start?.toLocalDate()
            val last = item.end?.toLocalDate()?.minusDays(1)
            if (first != null && last != null && first != last) "全天 · ${first.monthValue}/${first.dayOfMonth}–${last.monthValue}/${last.dayOfMonth}"
            else "全天"
        }
        item.start == null -> "时间待定"
        else -> {
            fun label(value: LocalDateTime): String = if (value.toLocalDate() == date) value.toLocalTime().toString()
                else "${value.monthValue}/${value.dayOfMonth} ${value.toLocalTime()}"
            label(item.start) + (item.end?.let { "–${label(it)}" } ?: " · 结束待定")
        }
    }
}
