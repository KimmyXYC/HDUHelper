package moe.nepnep.hduhelper.data.timetable

import org.junit.Assert.*
import org.junit.Test

class TimetableParserTest {
    private val prefix=""""xsxx":{"XH":"student","XNM":"2026","XQM":"3"},"xkkg":true,"jfckbkg":true,"xnxqsfkz":"false""""
    @Test fun mixedParityAndSingleWeeks() {
        assertEquals(listOf(1,3,7,11,13,15),TimetableParser.weeks("1-3周(单),7周,11-15周(单)"))
        assertEquals(listOf(2,4,6,12,14,16),TimetableParser.weeks("2-6周（双），12-16周(双)"))
        assertEquals(listOf(1,2,3,4,5,6)+(9..17),TimetableParser.weeks("1-6周,9-17周"))
        assertEquals(listOf(1,7,9,11,13,15),TimetableParser.weeks("1周,7-15周(单)"))
    }
    @Test fun singleDiscontinuousAllDayAndInvalidRanges() {
        assertEquals(listOf(3),TimetableParser.sections("3节"))
        assertEquals(listOf(1,2,4),TimetableParser.sections("1-2,4"))
        assertEquals((1..12).toList(),TimetableParser.sections("1-12"))
        for (bad in listOf("", "2-1周", "0周", "1-99999周", "待定", "1-3周,?", "2周(单)")) assertTrue(runCatching { TimetableParser.weeks(bad) }.isFailure)
    }
    @Test fun preservesDifferentMeetingsAndUnplacedCourses() {
        val body="""{$prefix,"kbList":[
            {"kcmc":"同名课","kch_id":"a","jxb_id":"class","xqj":"1","jcs":"3-5","zcd":"1-6周","xm":"甲"},
            {"kcmc":"同名课","kch_id":"a","jxb_id":"class","xqj":1,"jcs":"3-5","zcd":"7-17周","xm":"乙"},
            {"kcmc":"待安排","xqj":"1","zcd":"待定"}],
            "sjkList":[{"kcmc":"实践课","sfsjk":"1","sjkcgs":"第18周<br>实践","jsxm":"丙"}],"jxhjkcList":[] }"""
        val p=TimetableParser.courses(TimetableParser.objectResponse(body),"student",term)
        assertEquals(2,p.meetings.size)
        assertEquals(p.meetings[0].courseKey,p.meetings[1].courseKey)
        assertNotEquals(p.meetings[0].id,p.meetings[1].id)
        assertEquals(2,p.others.size)
        assertEquals("第18周 实践",p.others[1].description)
        assertTrue(p.warnings.isNotEmpty())
    }
    @Test fun errorsAreNotEmptyTimetables() {
        fun check(kind: TimetableFailure, body: String) {
            val e=runCatching { TimetableParser.courses(TimetableParser.objectResponse(body),"student",term) }.exceptionOrNull() as TimetableException
            assertEquals(kind,e.kind)
        }
        check(TimetableFailure.PERMISSION,"\"没有访问权限!\"")
        check(TimetableFailure.PROTOCOL,"{$prefix,\"kbList\":[]}".replace("student","other"))
        check(TimetableFailure.PROTOCOL,"{$prefix,\"kbList\":[]}".replace("\"3\"","\"12\""))
        check(TimetableFailure.CLOSED,"{$prefix,\"kbList\":[]}".replace("\"xnxqsfkz\":\"false\"","\"xnxqsfkz\":\"true\""))
        assertTrue(TimetableParser.courses(TimetableParser.objectResponse("{$prefix,\"kbList\":[]}"),"student",term).meetings.isEmpty())
    }
    @Test fun schoolTermsCalendarAndPeriodIdentifiers() {
        val c=TimetableParser.catalog("""<select id="xnm"><option value="2026" selected>2026-2027</option></select><select id="xqm"><option value="3" selected>1</option><option value="12">2</option><option value="16">3</option></select>""")
        assertEquals("16",c.terms[2].code)
        assertEquals("2026",c.current.year)
        val weeks=TimetableParser.calendar("""[{"zs":"1","rq":"2026-09-14/2026-09-20","dateDigitSeparator":"2026-9-7"}]""")
        assertEquals("2026-09-14",weeks.single().start)
        val periods=TimetableParser.periods("""[{"jcmc":"3","xsdj":"2","qssj":"10:00","jssj":"10:45","rsdmc":"上午"}]""")
        assertEquals(3,periods.single().section)
    }
}
