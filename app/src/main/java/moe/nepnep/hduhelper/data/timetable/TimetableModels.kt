package moe.nepnep.hduhelper.data.timetable

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

val campusZone: ZoneId = ZoneId.of("Asia/Shanghai")

@Serializable
data class AcademicTerm(val year: String, val code: String, val name: String = "") {
    val key: String get() = "$year-$code"
    val label: String get() = "$year–${year.toIntOrNull()?.plus(1) ?: ""} · 第${name.ifBlank { when (code) { "3" -> "1"; "12" -> "2"; "16" -> "3"; else -> code } }}学期"
}

@Serializable
data class TimetableCatalog(val years: List<String>, val terms: List<TermOption>, val current: AcademicTerm)
@Serializable data class TermOption(val code: String, val name: String)
@Serializable data class WeekRange(val week: Int, val start: String, val end: String) {
    val startDate: LocalDate get() = LocalDate.parse(start)
    val endDate: LocalDate get() = LocalDate.parse(end)
}
@Serializable data class CampusPeriod(val section: Int, val start: String, val end: String, val group: String)
@Serializable data class CampusClock(val id: String, val name: String, val periods: List<CampusPeriod>)

@Serializable
data class CourseMeeting(
    val id: String,
    val courseKey: String,
    val name: String,
    val teacher: String = "",
    val location: String = "",
    val campusId: String = "",
    val campusName: String = "",
    val credits: String = "",
    val weekday: Int,
    val sections: List<Int>,
    val weeks: List<Int>,
    val rawWeeks: String,
    val rawSections: String,
) {
    override fun toString() = "CourseMeeting([redacted])"
}

@Serializable
data class OtherArrangement(
    val id: String, val name: String, val description: String,
    val teacher: String = "", val location: String = "", val campusName: String = "",
    val credits: String = "", val rawWeeks: String = "",
) { override fun toString() = "OtherArrangement([redacted])" }

@Serializable
data class TimetableData(
    val account: String,
    val term: AcademicTerm,
    val catalog: TimetableCatalog,
    val meetings: List<CourseMeeting>,
    val others: List<OtherArrangement>,
    val weeks: List<WeekRange>,
    val clocks: List<CampusClock>,
    val updatedAt: Long,
    val warnings: List<String> = emptyList(),
    val exams: ExamSnapshot = ExamSnapshot(),
) { override fun toString() = "TimetableData([redacted])" }

@Serializable
data class TimetableSettings(
    val showOtherWeeks: Boolean = true,
    val showFinished: Boolean = false,
    val showWeekend: Boolean = false,
    val showTeacher: Boolean = true,
    val showLocation: Boolean = true,
    val showExams: Boolean = true,
)

enum class TimetableFailure { AUTHORIZATION, PERMISSION, CLOSED, PROTOCOL, NETWORK, SERVICE }
class TimetableException(val kind: TimetableFailure, override val message: String) : Exception(message)

interface TimetableSession {
    suspend fun gradeCatalog(): TimetableCatalog = throw TimetableException(TimetableFailure.PROTOCOL, "成绩查询不可用")
    suspend fun fetchGrades(account: String, term: AcademicTerm, catalog: TimetableCatalog): moe.nepnep.hduhelper.data.grades.GradeSnapshot =
        throw TimetableException(TimetableFailure.PROTOCOL, "成绩查询不可用")
    suspend fun gradeComponents(term: AcademicTerm, grade: moe.nepnep.hduhelper.data.grades.CourseGrade): List<moe.nepnep.hduhelper.data.grades.GradeComponent> =
        throw TimetableException(TimetableFailure.PROTOCOL, "成绩分项不可用")
    suspend fun fetchExams(account: String, term: AcademicTerm): ExamSnapshot = ExamSnapshot()
    suspend fun authorize(ticket: String)
    suspend fun catalog(): TimetableCatalog
    suspend fun fetch(account: String, term: AcademicTerm, catalog: TimetableCatalog): TimetableData
}
fun interface TimetableSessionFactory { fun create(): TimetableSession }

interface TimetableSource {
    suspend fun cachedTerms(account: String): List<TimetableData> = listOfNotNull(cached(account))
    suspend fun cached(account: String, term: AcademicTerm? = null): TimetableData?
    suspend fun catalog(): TimetableCatalog
    suspend fun refresh(term: AcademicTerm, catalog: TimetableCatalog): TimetableData
    fun clear()
}
