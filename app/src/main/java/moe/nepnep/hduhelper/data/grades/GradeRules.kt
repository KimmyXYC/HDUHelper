package moe.nepnep.hduhelper.data.grades

import java.math.BigDecimal
import java.math.RoundingMode

data class GradeSummary(val average: String, val withoutC: String, val excluded: Int, val unknownNature: Boolean)

object GradeRules {
    fun summary(items: List<CourseGrade>): GradeSummary {
        val valid = items.mapNotNull { item ->
            val credits = item.credits.toBigDecimalOrNull()
            val point = item.gradePoint.toBigDecimalOrNull()
            if (item.invalidated || credits == null || credits <= BigDecimal.ZERO || point == null || point < BigDecimal.ZERO) null
            else Triple(item, credits, point)
        }
        fun average(rows: List<Triple<CourseGrade, BigDecimal, BigDecimal>>): String {
            val credits = rows.fold(BigDecimal.ZERO) { total, row -> total + row.second }
            if (credits.signum() == 0) return "—"
            return rows.fold(BigDecimal.ZERO) { total, row -> total + row.second * row.third }
                .divide(credits, 4, RoundingMode.HALF_UP).toPlainString()
        }
        val unknown = valid.any { it.first.nature.isBlank() }
        return GradeSummary(average(valid), if (unknown) "—" else average(valid.filter { it.first.nature != "通识选修" }),
            items.size - valid.size, unknown)
    }
}
