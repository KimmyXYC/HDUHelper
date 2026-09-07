package moe.nepnep.hduhelper.data.timetable

import java.time.LocalTime

/** Coordinates are density-independent; teaching periods retain their 60 dp rows. */
data class ExamGridAxis(val points: List<Pair<Int, Float>>, val periods: List<CampusPeriod>) {
    val height: Float get() = points.last().second
    fun position(minute: Int): Float {
        if (minute <= points.first().first) return points.first().second
        val pair = points.zipWithNext().firstOrNull { minute <= it.second.first } ?: return height
        val (a, b) = pair
        return a.second + (b.second - a.second) * (minute - a.first) / (b.first - a.first)
    }
}

data class ExamGridItem(
    val key: String, val weekday: Int, val start: Int, val end: Int,
    val course: CourseMeeting? = null, val exam: ExamArrangement? = null,
    val state: MeetingState? = null,
) {
    val title: String get() = exam?.name ?: requireNotNull(course).name
}

object ExamGridRules {
    fun minutes(time: String): Int? = runCatching { LocalTime.parse(time).let { it.hour * 60 + it.minute } }.getOrNull()
    fun items(data: TimetableData, week: Int, settings: TimetableSettings): List<ExamGridItem> {
        val courses = TimetableRules.cards(data, week, settings).flatMap { card ->
            val course = card.meeting
            val clock = data.clocks.firstOrNull { it.id == course.campusId }?.periods.orEmpty().associateBy { it.section }
            TimetableRules.runs(course.sections).mapNotNull { run ->
                val start = clock[run.first]?.start?.let(::minutes)
                val end = clock[run.last]?.end?.let(::minutes)
                if (start == null || end == null || end <= start) null
                else ExamGridItem("course/${course.id}/${run.first}", course.weekday, start, end, course = course, state = card.state)
            }
        }
        val exams = ExamRules.week(data, week).map {
            ExamGridItem("exam/${it.id}", it.startTime!!.dayOfWeek.value,
                minutes(it.startTime!!.toLocalTime().toString())!!, minutes(it.endTime!!.toLocalTime().toString())!!, exam = it)
        }
        return courses + exams
    }
    fun overlaps(a: ExamGridItem, b: ExamGridItem) = a.weekday == b.weekday && a.start < b.end && b.start < a.end

    fun axis(clock: CampusClock?, items: List<ExamGridItem>): ExamGridAxis {
        val valid = clock?.periods.orEmpty().filter { p ->
            val start = minutes(p.start); val end = minutes(p.end)
            start != null && end != null && end > start
        }.sortedBy { minutes(it.start) }
        val periods = valid.takeIf { list -> list.zipWithNext().all { (a, b) -> minutes(a.end)!! <= minutes(b.start)!! } }.orEmpty()
        val first = minOf(periods.firstOrNull()?.start?.let(::minutes) ?: 1440, items.minOfOrNull { it.start } ?: 1440)
        val last = maxOf(periods.lastOrNull()?.end?.let(::minutes) ?: 0, items.maxOfOrNull { it.end } ?: 0)
        if (first >= last) return ExamGridAxis(listOf(0 to 0f, 1440 to 600f), emptyList())
        if (periods.isEmpty()) return ExamGridAxis(listOf(first to 0f, last to maxOf(120f, (last - first) * .8f)), emptyList())
        val points = mutableListOf(first to 0f)
        var cursor = first
        var y = 0f
        var previous: CampusPeriod? = null
        for (period in periods) {
            val start = minutes(period.start)!!
            val end = minutes(period.end)!!
            if (start > cursor) {
                val gap = if (previous == null) (start - cursor) * .8f
                    else if (previous.group.isNotBlank() && period.group.isNotBlank() && previous.group != period.group) 24f
                    else 0f
                y += gap
                points += start to y
            }
            y += 60f
            points += end to y
            cursor = end
            previous = period
        }
        if (last > cursor) { y += (last - cursor) * .8f; points += last to y }
        return ExamGridAxis(points, periods)
    }

    fun visibleConflicts(item: ExamGridItem, items: List<ExamGridItem>, axis: ExamGridAxis): List<ExamGridItem> {
        val top = axis.position(item.start)
        val bottom = maxOf(axis.position(item.end), top + 36f)
        return items.filter {
            val otherTop = axis.position(it.start)
            val otherBottom = maxOf(axis.position(it.end), otherTop + 36f)
            it.weekday == item.weekday && top < otherBottom && otherTop < bottom
        }
    }

    /** Exams use full-width cards. Covered arrangements remain reachable through conflicts. */
    fun fragments(items: List<ExamGridItem>, axis: ExamGridAxis): Map<String, List<Pair<Float, Float>>> {
        val occupied = mutableMapOf<Int, MutableList<Pair<Float, Float>>>()
        return items.sortedWith(compareByDescending<ExamGridItem> { it.exam != null }
            .thenBy { it.state?.ordinal ?: 0 }.thenBy { it.start }.thenBy { it.key }).associate { item ->
            val top = axis.position(item.start)
            val bottom = maxOf(axis.position(item.end), top + 36f)
            var pieces = listOf(top to bottom)
            for ((start, end) in occupied[item.weekday].orEmpty()) {
                pieces = pieces.flatMap { (a, b) ->
                    if (start >= b || end <= a) listOf(a to b)
                    else buildList {
                        if (a < start) add(a to start)
                        if (end < b) add(end to b)
                    }
                }
            }
            occupied.getOrPut(item.weekday) { mutableListOf() }.add(top to bottom)
            item.key to pieces.filter { (a, b) -> b - a >= 36f }
        }
    }
}
