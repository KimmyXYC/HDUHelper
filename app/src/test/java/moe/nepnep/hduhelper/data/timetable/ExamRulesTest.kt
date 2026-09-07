package moe.nepnep.hduhelper.data.timetable

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import moe.nepnep.hduhelper.data.notifications.*
import moe.nepnep.hduhelper.data.island.CourseIslandTemplate
import org.junit.Assert.*
import org.junit.Test

class ExamRulesTest {
    private fun page(items: String, page: Int = 1, pages: Int = 1, count: Int = 1) =
        """{"currentPage":$page,"totalPage":$pages,"totalCount":$count,"items":[$items]}"""
    private fun row(time: String = "2026-09-19(09:15-11:15)", seat: String = "12") =
        """{"xh":"student","xnm":"2026","xqm":"3","kcmc":"合成考试","kch":"EX1","ksmc":"期末考试","kssj":"$time","cdmc":"测试教室","zwh":"$seat","row_id":"1"}"""
    private fun exam() = ExamParser.page(page(row()), "student", term).items.single()
    private fun timetable(exams: List<ExamArrangement> = listOf(exam())) = data(meeting("a")).copy(exams = ExamSnapshot(exams, 123))

    @Test fun parsesSchoolTimesAndKeepsUncertainText() {
        val exam = exam()
        assertEquals("2026-09-19T09:15", exam.start)
        assertEquals("12", exam.seat)
        assertTrue(exam.timed)
        for (time in listOf("待安排", "2026-09-19(11:00-09:00)", "2026-02-30(09:00-11:00)", "2026-09-19(25:00-26:00)")) {
            val uncertain = ExamParser.page(page(row(time)), "student", term).items.single()
            assertFalse(uncertain.timed)
            assertEquals(time, uncertain.rawTime)
        }
    }
    @Test fun rowNumbersDoNotDefineIdentityAndAccountMustMatch() {
        val first = exam()
        assertEquals(first.id, ExamParser.page(page(row().replace("\"row_id\":\"1\"", "\"row_id\":\"99\"")), "student", term).items.single().id)
        assertNotEquals(first.id, ExamParser.page(page(row(seat = "13")), "student", term).items.single().id)
        assertTrue(runCatching { ExamParser.page(page(row()), "another", term) }.isFailure)
    }
    @Test fun paginatesAllExamResultsAndAcceptsEmptyResults() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val session = JwApi(JwEndpoints(server.url("/")), now = { 456 }).create()
            server.enqueue(MockResponse.Builder().body(page(row(), pages = 2, count = 2)).build())
            server.enqueue(MockResponse.Builder().body(page(row(seat = "13"), page = 2, pages = 2, count = 2)).build())
            val snapshot = session.fetchExams("student", term)
            assertEquals(2, snapshot.items.size)
            assertEquals(456L, snapshot.updatedAt)
            assertTrue(server.takeRequest().target.contains("doType=query&gnmkdm=N358105"))
            assertTrue(server.takeRequest().body!!.utf8().contains("queryModel.currentPage=2"))
            server.enqueue(MockResponse.Builder().body(page("", pages = 0, count = 0)).build())
            assertTrue(session.fetchExams("student", term).items.isEmpty())
        }
    }
    @Test fun incompletePaginationFailsInsteadOfDeletingCachedRecords() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(page(row(), count = 2)).build())
            assertTrue(runCatching { JwApi(JwEndpoints(server.url("/"))).create().fetchExams("student", term) }.isFailure)
        }
    }
    @Test fun oldCacheDefaultsToUnsyncedAndNewCacheRoundTrips() {
        val old = Json.encodeToString(data(meeting("a")))
        assertNull(Json.decodeFromString<TimetableData>(old).exams.updatedAt)
        val fresh = timetable()
        assertEquals(fresh, Json.decodeFromString<TimetableData>(Json.encodeToString(fresh)))
    }
    @Test fun suppliesExamWeeksOutsideTeachingCalendarAndSupportsExamOnlyTerms() {
        val early = exam().copy(start = "2026-09-06T09:00", end = "2026-09-06T11:00")
        val late = exam().copy(id = "late", start = "2027-03-06T09:00", end = "2027-03-06T11:00")
        val d = timetable(listOf(early, late))
        for (date in listOf(LocalDate.parse("2026-09-06"), LocalDate.parse("2027-03-06"))) {
            val week = ExamRules.weeks(d).single { date in it.startDate..it.endDate }
            assertEquals(1, ExamRules.week(d, week.week).size)
            assertEquals(1, ExamRules.onDate(d, date).size)
        }
        assertFalse(ExamRules.weeks(d.copy(weeks = emptyList())).isEmpty())
        assertEquals(d.weeks, ExamRules.weeks(d, showExams = false))
    }
    @Test fun gridPreservesExactTimesAndFindsConflicts() {
        val items = listOf(ExamGridItem("a", 6, 555, 675, exam = exam()), ExamGridItem("b", 6, 600, 700, exam = exam()))
        val clock = CampusClock("1", "测试", listOf(CampusPeriod(1, "09:00", "10:00", "上午"), CampusPeriod(2, "10:10", "11:00", "上午")))
        val axis = ExamGridRules.axis(clock, items)
        assertEquals(15f, axis.position(555), .01f)
        assertTrue(axis.position(675) > axis.position(660))
        assertTrue(ExamGridRules.overlaps(items[0], items[1]))
        assertFalse(ExamGridRules.fragments(items, axis).getValue("a").isEmpty())
        assertTrue(ExamGridRules.fragments(items, axis).getValue("b").isEmpty())
        assertFalse(ExamGridRules.overlaps(items[0], items[1].copy(start = 675)))
        assertTrue(ExamGridRules.axis(null, items).height > 0)
    }
    @Test fun examStartOnlyDefaultsToThirtyMinutesAndIslandExpiresAfterStart() {
        val d = timetable()
        val target = exam().startTime!!.atZone(campusZone).toInstant().toEpochMilli()
        val prefs = NotificationSettings(beforeClass = false, afterClass = true)
        val reminders = CourseReminderRules.reminders(d, prefs, target - 3_600_000).filter { it.exam != null }
        val reminder = reminders.single()
        assertEquals(CourseReminderKind.EXAM_START, reminder.kind)
        assertEquals(target - 1_800_000, reminder.begins)
        assertEquals(target + 60_000, reminder.expires)
        assertTrue(CourseIslandTemplate.json(reminder, target).contains("考试已开始"))
        assertEquals(CourseReminderPhase.EXPIRED, reminder.phase(target + 60_000))
        assertTrue(CourseReminderRules.reminders(d, prefs.copy(beforeExam = false), target - 3_600_000).none { it.exam != null })
        assertTrue(CourseReminderRules.reminders(d.copy(term = term.copy(code = "12")), prefs, target - 3_600_000).isEmpty())
        assertTrue(CourseReminderRules.reminders(timetable(listOf(exam().copy(start = null))), prefs, target - 3_600_000).none { it.exam != null })
    }
    @Test fun changedOrRemovedExamInvalidatesOldReminderKeyAndZeroMinutesWorks() {
        val d = timetable()
        val target = exam().startTime!!.atZone(campusZone).toInstant().toEpochMilli()
        val prefs = NotificationSettings(beforeClass = false, examMinutes = 0)
        val old = CourseReminderRules.reminders(d, prefs, target - 1).single()
        assertEquals(target, old.begins)
        val changed = d.copy(exams = d.exams.copy(items = listOf(exam().copy(start = "2026-09-19T09:30"))))
        assertNotEquals(old.key, CourseReminderRules.reminders(changed, prefs, target - 1).single().key)
        assertTrue(CourseReminderRules.reminders(timetable(emptyList()), prefs, target - 1).isEmpty())
        assertTrue(CourseReminderDelivery.ordinaryDue(listOf(old), mapOf(old.key to CourseReminderRecord(old.expires, delivered = true)), target, target).isEmpty())
    }
    @Test fun examAxisUsesCourseRowHeightAndOnlyCourseRestBands() {
        val clock = CampusClock("1", "测试", listOf(
            CampusPeriod(1, "09:00", "09:45", "上午"), CampusPeriod(2, "09:55", "10:40", "上午"),
            CampusPeriod(3, "13:30", "14:15", "下午"), CampusPeriod(4, "18:30", "19:15", "晚上")))
        val axis = ExamGridRules.axis(clock, listOf(ExamGridItem("exam", 1, 550, 620, exam = exam())))
        assertEquals(0f, axis.position(540), .01f)
        assertEquals(60f, axis.position(595), .01f)
        assertEquals(144f, axis.position(810), .01f)
        assertEquals(228f, axis.position(1110), .01f)
        assertEquals(288f, axis.height, .01f)
    }
}
