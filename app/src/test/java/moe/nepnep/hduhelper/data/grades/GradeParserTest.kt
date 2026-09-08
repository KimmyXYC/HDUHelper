package moe.nepnep.hduhelper.data.grades

import org.junit.Assert.*
import org.junit.Test
import moe.nepnep.hduhelper.data.timetable.TimetableException

class GradeParserTest {
    private val body = """{"currentPage":1,"totalPage":1,"totalResult":1,"items":[{"key":"one","xh":"student","xnm":"2026","xqm":"3","kcmc":"测试课程","xf":"2","cj":"合格","jd":"0","kcxzmc":"通识选修","cjsfzf":"否"}]}"""
    @Test fun optionalFieldsAndTextScoresPreserved() {
        val page = GradeParser.page(body, "student", term)
        assertEquals(1, page.total)
        assertEquals("合格", page.items.single().score)
        assertEquals("0", page.items.single().gradePoint)
        assertTrue(page.items.single().components.isEmpty())
        assertTrue(GradeParser.page(body.replace("\"否\"", "\"是\""), "student", term).items.single().invalidated)
    }
    @Test fun rejectsWrongOwnerTermMalformedAndMissingPagination() {
        for (value in listOf(body.replace("student", "other"), body.replace("2026", "2025"), "<html>error</html>",
            body.replace("\"totalResult\":1,", ""), body.replace("\"items\":[", "\"items\":null,\"unused\":["))) {
            assertTrue(runCatching { GradeParser.page(value, "student", term) }.exceptionOrNull() is TimetableException)
        }
    }
    @Test fun detailsSortKnownComponentsPreserveZeroAndSkipOverallOrAbsentValues() {
        val parts = GradeParser.components("""<table id="subtab"><tbody>
            <tr><td>【 期末 】</td><td>50%</td><td>0&nbsp;</td></tr>
            <tr><td>【 总评 】</td><td></td><td>80</td></tr>
            <tr><td>实验</td><td>10%</td><td>合格</td></tr>
            <tr><td>期中</td><td></td><td>&nbsp;</td></tr>
            <tr><td>平时</td><td>40%</td><td>90</td></tr>
            </tbody></table>""")
        assertEquals(listOf("平时成绩", "期末成绩", "实验成绩"), parts.map { it.name })
        assertEquals(listOf("90", "0", "合格"), parts.map { it.score })
        assertTrue(GradeParser.components("<table id='subtab'><tbody></tbody></table>").isEmpty())
        assertTrue(runCatching { GradeParser.components("<html>服务不可用</html>") }.isFailure)
    }
}
