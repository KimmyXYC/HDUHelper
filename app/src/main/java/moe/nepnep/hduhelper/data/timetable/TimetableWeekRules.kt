package moe.nepnep.hduhelper.data.timetable

import java.time.LocalDate

object TimetableWeekRules {
    fun available(data: TimetableData, today: LocalDate, showExams: Boolean): List<Int> =
        TimetableRules.availableWeeks(ExamRules.weeks(data, showExams), today).distinct()

    fun current(data: TimetableData, today: LocalDate, showExams: Boolean): Int? {
        if (data.term.key != data.catalog.current.key) return null
        val weeks = ExamRules.weeks(data, showExams)
        weeks.firstOrNull { today in it.startDate..it.endDate }?.let { return it.week }
        return 0.takeIf { weeks.minByOrNull { range -> range.startDate }?.startDate?.let { today < it } == true &&
            weeks.none { range -> range.week == 0 } }
    }

    fun label(data: TimetableData, week: Int, showExams: Boolean): String {
        if (data.weeks.any { it.week == week }) return week.toString()
        val extra = ExamRules.weeks(data, showExams).firstOrNull { it.week == week }
        return if (extra != null) "${extra.startDate.monthValue}/${extra.startDate.dayOfMonth}" else if (week == 0) "假期中" else week.toString()
    }
}
