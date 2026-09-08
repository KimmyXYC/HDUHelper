package moe.nepnep.hduhelper.data.grades

import moe.nepnep.hduhelper.data.timetable.*

internal val term = AcademicTerm("2026", "3", "1")
internal val catalog = TimetableCatalog(listOf("2026", "2025"), listOf(TermOption("3", "1"), TermOption("12", "2")), term)
internal fun grade(id: String = "a", credits: String = "2", point: String = "4", nature: String = "通识必修") =
    CourseGrade(id, "class-$id", "synthetic-student-id", "合成课程$id", credits, "90", point, nature, false)
internal fun snapshot(vararg items: CourseGrade) = GradeSnapshot("student", term, catalog, items.toList(), 1)
