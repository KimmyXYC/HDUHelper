package moe.nepnep.hduhelper

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import moe.nepnep.hduhelper.data.notifications.*
import moe.nepnep.hduhelper.data.schedule.*
import moe.nepnep.hduhelper.data.timetable.*
import moe.nepnep.hduhelper.ui.*
import moe.nepnep.hduhelper.ui.screens.*
import moe.nepnep.hduhelper.ui.theme.HDUHelperTheme
import top.yukonga.miuix.kmp.basic.Scaffold
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class ExamUiTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(90)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private var activity: ComponentActivity? = null

    private fun show(content: @Composable () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Some physical ROMs stall Instrumentation.startActivitySync; a shell launch still
        // delivers normal lifecycle callbacks to the instrumentation process.
        instrumentation.uiAutomation.executeShellCommand("am start -W -n moe.nepnep.hduhelper/androidx.activity.ComponentActivity").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        compose.waitUntil(10_000) {
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ComponentActivity>().firstOrNull { it.javaClass == ComponentActivity::class.java }
            }
            activity != null
        }
        compose.runOnUiThread { requireNotNull(activity).setContent(content = content) }
        compose.waitForIdle()
    }

    @After fun closeFixture() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { activity?.finish() }
    }
    private val date = LocalDate.of(2026, 9, 19)
    private val exam = ExamArrangement("exam", "合成考试", "期末", "2026-09-19(09:15-11:15)", "2026-09-19T09:15", "2026-09-19T11:15", "合成考场", "测试校区", "12")
    private fun fixture(): TimetableData {
        val term = AcademicTerm("2026", "3")
        return TimetableData("synthetic", term, TimetableCatalog(listOf("2026"), listOf(TermOption("3", "1")), term),
            listOf(CourseMeeting("course", "course", "合成课程", campusId = "1", weekday = 6, sections = listOf(1), weeks = listOf(1), rawWeeks = "1周", rawSections = "1")),
            emptyList(), listOf(WeekRange(1, "2026-09-14", "2026-09-20")),
            listOf(CampusClock("1", "测试校区", listOf(CampusPeriod(1, "09:00", "10:00", "上午"), CampusPeriod(2, "10:10", "11:00", "上午")))),
            123, exams = ExamSnapshot(listOf(exam), 123))
    }
    @Test fun timetableShowsWeekendExactTimeAndAllConflictingDetails() {
        val data = fixture()
        val state = TimetableUiState(TimetableStatus.READY, data, data.catalog, data.term, 1, date)
        show { HDUHelperTheme { Scaffold { padding ->
            TimetableScreen(state, {}, {}, {}, {}, Modifier.fillMaxSize().padding(padding))
        } } }
        compose.onNodeWithText("周六").assertIsDisplayed()
        compose.onNodeWithTag("timetable_screen").captureToImage().asAndroidBitmap().let { bitmap ->
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("exam-grid.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithTag("grid_exam/exam").performClick()
        compose.onNodeWithTag("exam_conflicts").assertIsDisplayed()
        compose.onNodeWithText("考试 · 合成考试").performClick()
        compose.onNodeWithTag("exam_details").assertIsDisplayed()
        compose.onNodeWithText("2026-09-19(09:15-11:15)").assertIsDisplayed()
        compose.onNodeWithText("12").assertIsDisplayed()
        compose.onNodeWithText("编辑").assertDoesNotExist()
        assertFalse(state.settings.showWeekend)
    }
    @Test fun scheduleSortsExamAfterCourseAndBeforeCustomEvent() {
        val event = ScheduleEvent(title = "合成日程", start = "2026-09-19T12:00", end = "2026-09-19T13:00")
        val state = ScheduleUiState(date, date, storage = ScheduleStorageState(ScheduleBook(listOf(ScheduleSeries("custom", event))), true),
            courses = fixture(), courseStatus = TimetableStatus.READY)
        show { HDUHelperTheme { Scaffold { padding ->
            ScheduleScreen(state, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, Modifier.fillMaxSize().padding(padding))
        } } }
        val course = compose.onNodeWithTag("agenda_course/course").fetchSemanticsNode().boundsInRoot.top
        val exam = compose.onNodeWithTag("agenda_exam/exam").fetchSemanticsNode().boundsInRoot.top
        assertTrue(course < exam)
        compose.onNodeWithTag("agenda_exam/exam").performClick()
        compose.onNodeWithText("合成考场").assertIsDisplayed()
        compose.onNodeWithText("删除").assertDoesNotExist()
    }
    @Test fun examOnlyTermOutsideClassHoursStillHasGrid() {
        val data = fixture().copy(meetings = emptyList(), clocks = emptyList(), weeks = emptyList(),
            exams = ExamSnapshot(listOf(exam.copy(start = "2026-09-19T06:00", end = "2026-09-19T07:00", rawTime = "2026-09-19(06:00-07:00)")), 123))
        show { HDUHelperTheme { Scaffold { padding ->
            TimetableScreen(TimetableUiState(TimetableStatus.READY, data, data.catalog, data.term, 1, date), {}, {}, {}, {}, Modifier.fillMaxSize().padding(padding))
        } } }
        compose.onNodeWithTag("grid_exam/exam").assertIsDisplayed().performClick()
        compose.onNodeWithText("2026-09-19(06:00-07:00)").assertIsDisplayed()
    }
    @Test fun examReminderUsesIndependentTimeAndHasNoEndOption() {
        var settings by mutableStateOf(NotificationSettings())
        show { HDUHelperTheme { Scaffold { padding ->
            NotificationSettingsScreen(settings, CourseNotificationStatus(), { settings = it }, {}, {}, Modifier.fillMaxSize().padding(padding))
        } } }
        compose.onNodeWithTag("notify_exam_time").performScrollTo().performClick()
        compose.onNodeWithTag("notify_minutes_input").assertTextContains("30").performTextReplacement("0")
        compose.onNodeWithTag("notify_time_confirm").performClick()
        compose.runOnIdle { assertEquals(0, settings.examMinutes); assertEquals(10, settings.beforeMinutes) }
        compose.onNodeWithTag("notify_exam").performScrollTo().performClick()
        compose.onNodeWithTag("notify_exam_time").assertDoesNotExist()
        compose.onNodeWithText("考试结束提醒").assertDoesNotExist()
    }
    @Test fun timetableExamSwitchHidesGridWithoutChangingNotificationSettings() {
        val data = fixture()
        var state by mutableStateOf(TimetableUiState(TimetableStatus.READY, data, data.catalog, data.term, 1, date))
        show { HDUHelperTheme { Scaffold { padding ->
            TimetableScreen(state, {}, {}, {}, {}, Modifier.fillMaxSize().padding(padding))
        } } }
        compose.onNodeWithTag("grid_exam/exam").assertExists()
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(showExams = false)) }
        compose.onNodeWithTag("grid_exam/exam").assertDoesNotExist()
        compose.onNodeWithText("周六").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(showExams = true)) }
        compose.onNodeWithTag("grid_exam/exam").assertExists()
    }
    @Test fun examVisibilityPreferenceCanBeToggled() {
        val data = fixture()
        var state by mutableStateOf(TimetableUiState(TimetableStatus.READY, data, data.catalog, data.term, 1, date))
        show { HDUHelperTheme { Scaffold { padding ->
            TimetableSettingsScreen(state, { state = state.copy(settings = it) }, {}, Modifier.fillMaxSize().padding(padding))
        } } }
        compose.onNodeWithTag("setting_exams").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(state.settings.showExams) }
    }
    @Test fun weekPickerOpensWithoutJumpingSelectsWeekAndReturnsCurrent() {
        val d = fixture().copy(weeks = (1..30).map {
            val first = LocalDate.of(2026, 9, 14).plusWeeks((it - 1).toLong())
            WeekRange(it, first.toString(), first.plusDays(6).toString())
        }, exams = ExamSnapshot(updatedAt = 1))
        var state by mutableStateOf(TimetableUiState(TimetableStatus.READY, d, d.catalog, d.term, 5, LocalDate.of(2026, 9, 21)))
        var returned = false
        show { HDUHelperTheme(darkTheme = true) { Scaffold(topBar = {
            TimetableTopBar(state, { returned = true; state = state.copy(week = 2) }, {}, { state = state.copy(week = it) })
        }) { padding -> TimetableScreen(state, {}, { state = state.copy(week = it) }, {}, {}, Modifier.fillMaxSize().padding(padding)) } } }
        compose.onNodeWithTag("timetable_week_title").performClick()
        compose.onNodeWithTag("timetable_week_picker").assertIsDisplayed()
        compose.runOnIdle { assertEquals(5, state.week); assertFalse(returned) }
        compose.onNodeWithContentDescription("2，当前周").assertExists()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("week-picker-window.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithTag("week_choice_5").assertIsSelected()
        compose.onNodeWithTag("timetable_week_picker").captureToImage().asAndroidBitmap().let { bitmap ->
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("week-picker-dark.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithTag("week_choice_30").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(30, state.week) }
        compose.onNodeWithTag("timetable_week_picker").assertDoesNotExist()
        compose.onNodeWithTag("timetable_week_title").performClick()
        compose.onNodeWithTag("week_picker_cancel").performClick()
        compose.runOnIdle { assertEquals(30, state.week) }
        compose.onNodeWithTag("timetable_week_title").performClick()
        compose.onNodeWithTag("week_picker_current").performClick()
        compose.runOnIdle { assertTrue(returned); assertEquals(2, state.week) }
    }
    @Test fun holidayPickerSupportsLightThemeAndLargeText() {
        val d = fixture()
        val state = TimetableUiState(TimetableStatus.READY, d, d.catalog, d.term, 0, LocalDate.of(2026, 9, 8))
        show {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.5f)) {
                HDUHelperTheme(darkTheme = false) { TimetableTopBar(state, {}, {}, {}) }
            }
        }
        compose.onNodeWithTag("timetable_week_title").performClick()
        compose.onNodeWithContentDescription("假期中，当前周").assertIsDisplayed()
        compose.onNodeWithTag("week_picker_current").assertIsDisplayed()
        compose.onNodeWithTag("timetable_week_picker").captureToImage().asAndroidBitmap().let { bitmap ->
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("week-picker-large.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
