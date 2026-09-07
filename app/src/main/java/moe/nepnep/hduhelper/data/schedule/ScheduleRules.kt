package moe.nepnep.hduhelper.data.schedule

import java.time.*
import java.time.temporal.ChronoUnit
import moe.nepnep.hduhelper.data.timetable.*

object ScheduleRules {
    /** Seek directly to the requested window instead of expanding from the series origin. */
    fun dates(event: ScheduleEvent, from: LocalDate, through: LocalDate): Sequence<LocalDate> = sequence {
        val origin = event.startTime.toLocalDate()
        var date = maxOf(origin, from)
        if (date > through) return@sequence
        when (event.repeat) {
            ScheduleRepeat.NEVER -> if (origin in date..through) yield(origin)
            ScheduleRepeat.DAILY, ScheduleRepeat.WEEKDAYS -> while (date <= through) {
                if (event.repeat == ScheduleRepeat.DAILY || date.dayOfWeek.value <= 5) yield(date)
                date = date.plusDays(1)
            }
            ScheduleRepeat.WEEKLY -> {
                date = date.plusDays(((origin.dayOfWeek.value - date.dayOfWeek.value + 7) % 7).toLong())
                while (date <= through) { yield(date); date = date.plusWeeks(1) }
            }
            ScheduleRepeat.MONTHLY -> {
                var month = YearMonth.from(date)
                while (month <= YearMonth.from(through)) {
                    if (origin.dayOfMonth <= month.lengthOfMonth()) {
                        val candidate = month.atDay(origin.dayOfMonth)
                        if (candidate in date..through) yield(candidate)
                    }
                    month = month.plusMonths(1)
                }
            }
            ScheduleRepeat.YEARLY -> {
                for (year in date.year..through.year) {
                    val month = YearMonth.of(year, origin.month)
                    if (origin.dayOfMonth <= month.lengthOfMonth()) {
                        val candidate = month.atDay(origin.dayOfMonth)
                        if (candidate in date..through) yield(candidate)
                    }
                }
            }
        }
    }

    fun occurrences(series: ScheduleSeries, date: LocalDate): List<ScheduleOccurrence> {
        val event = series.event
        val span = ChronoUnit.DAYS.between(event.startTime.toLocalDate(), event.endTime.toLocalDate())
        val exceptions = series.exceptions.associateBy { it.originalDate }
        val regular = dates(event, date.minusDays(span), date).filter { it.toString() !in exceptions }
            .map { ScheduleOccurrence(series.id, it, event.onDate(it)) }
        val replacements = series.exceptions.asSequence().mapNotNull { exception ->
            exception.replacement?.let { ScheduleOccurrence(series.id, LocalDate.parse(exception.originalDate), it) }
        }
        return (regular + replacements).filter {
            it.event.startTime < date.plusDays(1).atStartOfDay() && it.event.endTime > date.atStartOfDay()
        }.toList()
    }

    fun courses(data: TimetableData, date: LocalDate): List<CourseMeeting> {
        val week = data.weeks.firstOrNull { date >= it.startDate && date <= it.endDate }?.week ?: return emptyList()
        return data.meetings.filter { it.weekday == date.dayOfWeek.value && week in it.weeks }
    }

    fun courseStart(meeting: CourseMeeting, clocks: List<CampusClock>): LocalTime? {
        val clock = clocks.firstOrNull { it.id == meeting.campusId } ?: return null
        return meeting.sections.minOrNull()?.let { section -> clock.periods.firstOrNull { it.section == section }?.start }
            ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    }

    fun reminderTime(event: ScheduleEvent): Long? = event.reminderMinutes?.let { minutes ->
        val base = if (event.allDay) event.startTime.toLocalDate().atTime(9, 0) else event.startTime
        base.minusMinutes(minutes.toLong()).atZone(campusZone).toInstant().toEpochMilli()
    }

    /** Returns one next reminder per series; exceptions may have independent times/reminders. */
    fun nextReminder(series: ScheduleSeries, after: Long): ScheduleOccurrence? {
        val event = series.event
        val from = Instant.ofEpochMilli(after).atZone(campusZone).toLocalDate().minusDays(1)
        val exceptions = series.exceptions.associateBy { it.originalDate }
        // Eight years covers the longest Gregorian leap-day gap; exclusions extend the horizon.
        val through = maxOf(from, event.startTime.toLocalDate()).plusYears(8L + series.exceptions.size * 4L)
        val regular = if (event.reminderMinutes == null) null else dates(event, from, through)
            .filter { it.toString() !in exceptions }
            .map { ScheduleOccurrence(series.id, it, event.onDate(it)) }
            .firstOrNull { (reminderTime(it.event) ?: Long.MIN_VALUE) > after }
        return (series.exceptions.mapNotNull { exception -> exception.replacement?.let {
            ScheduleOccurrence(series.id, LocalDate.parse(exception.originalDate), it)
        } } + listOfNotNull(regular)).filter { (reminderTime(it.event) ?: Long.MIN_VALUE) > after }
            .minByOrNull { reminderTime(it.event)!! }
    }
}
