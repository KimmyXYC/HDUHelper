package moe.nepnep.hduhelper.data.grades

import org.junit.Assert.*
import org.junit.Test

class GradeRulesTest {
    @Test fun weightedAverageExcludesOnlyCAndIncludesZeroGradePoints() {
        val summary = GradeRules.summary(listOf(grade(credits = "2", point = "4"), grade("b", "1", "0"), grade("c", "2", "5", "通识选修")))
        assertEquals("3.6000", summary.average)
        assertEquals("2.6667", summary.withoutC)
        assertEquals(0, summary.excluded)
    }
    @Test fun invalidRecordsAreExcludedAndTextScoresUseOfficialGradePoint() {
        val items = listOf(grade().copy(score = "优秀"), grade("void").copy(invalidated = true),
            grade("missing", point = ""), grade("zeroCredit", credits = "0"), grade("negative", point = "-1"), grade("invalid", point = "NaN"))
        val summary = GradeRules.summary(items)
        assertEquals("4.0000", summary.average)
        assertEquals(5, summary.excluded)
    }
    @Test fun unavailableAverageIsNeverShownAsZero() {
        assertEquals("—", GradeRules.summary(emptyList()).average)
        assertEquals("—", GradeRules.summary(listOf(grade(nature = "通识选修"))).withoutC)
        val unknown = GradeRules.summary(listOf(grade(nature = "")))
        assertEquals("4.0000", unknown.average)
        assertEquals("—", unknown.withoutC)
        assertTrue(unknown.unknownNature)
    }
    @Test fun roundingHappensOnceWithDecimalHalfUpAndRetakesStaySeparate() {
        assertEquals("1.2345", GradeRules.summary(listOf(grade(point = "1.23445"))).average)
        assertEquals("2.0000", GradeRules.summary(listOf(grade("first", point = "0"), grade("retake", point = "4"))).average)
    }
}
