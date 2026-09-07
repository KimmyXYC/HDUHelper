package moe.nepnep.hduhelper.data.timetable

import java.time.LocalDate

internal val term = AcademicTerm("2026", "3", "1")
internal val catalog = TimetableCatalog(listOf("2026", "2025"), listOf(TermOption("3", "1"), TermOption("12", "2"), TermOption("16", "3")), term)
internal fun meeting(id: String, weeks: List<Int> = listOf(1, 2, 3), sections: List<Int> = listOf(3, 4, 5), day: Int = 1, campus: String = "1", course: String = id) =
    CourseMeeting(id, course, "示例课程$id", "教师$id", "教室$id", campus, "校区$campus", "3.0", day, sections, weeks, weeks.joinToString(",") + "周", sections.joinToString(","))
internal fun data(vararg meetings: CourseMeeting) = TimetableData("student", term, catalog, meetings.toList(), emptyList(), (1..23).map {
    val start = LocalDate.of(2026, 9, 14).plusWeeks((it - 1).toLong())
    WeekRange(it, start.toString(), start.plusDays(6).toString())
}, listOf(CampusClock("1", "校区1", listOf(CampusPeriod(3, "10:00", "10:45", "上午"), CampusPeriod(4, "10:50", "11:35", "上午"), CampusPeriod(5, "11:40", "12:25", "上午"))),
    CampusClock("2", "校区2", listOf(CampusPeriod(3, "09:50", "10:35", "上午"), CampusPeriod(4, "10:45", "11:30", "上午"), CampusPeriod(5, "11:35", "12:20", "上午")))), 1000)
