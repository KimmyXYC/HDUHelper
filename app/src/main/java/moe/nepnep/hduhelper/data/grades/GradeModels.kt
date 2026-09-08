package moe.nepnep.hduhelper.data.grades

import kotlinx.serialization.Serializable
import moe.nepnep.hduhelper.data.timetable.AcademicTerm
import moe.nepnep.hduhelper.data.timetable.TimetableCatalog

@Serializable
data class GradeComponent(val name: String, val score: String) {
    override fun toString() = "GradeComponent([redacted])"
}

@Serializable
data class CourseGrade(
    val id: String,
    val teachingClass: String,
    val studentId: String,
    val name: String,
    val credits: String,
    val score: String,
    val gradePoint: String,
    val nature: String,
    val invalidated: Boolean,
    val components: List<GradeComponent> = emptyList(),
    val detailsLoaded: Boolean = false,
) {
    override fun toString() = "CourseGrade([redacted])"
}

@Serializable
data class GradeSnapshot(
    val account: String,
    val term: AcademicTerm,
    val catalog: TimetableCatalog,
    val items: List<CourseGrade>,
    val updatedAt: Long,
    val detailsFailed: Boolean = false,
) {
    override fun toString() = "GradeSnapshot([redacted])"
}

interface GradeSource {
    suspend fun cached(account: String, term: AcademicTerm? = null): GradeSnapshot?
    suspend fun catalog(): TimetableCatalog
    suspend fun refresh(term: AcademicTerm, catalog: TimetableCatalog): GradeSnapshot
    suspend fun details(snapshot: GradeSnapshot): GradeSnapshot
    fun clear()
}
