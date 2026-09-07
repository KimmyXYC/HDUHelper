package moe.nepnep.hduhelper

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import moe.nepnep.hduhelper.data.settings.SettingsRepository
import moe.nepnep.hduhelper.data.timetable.*
import moe.nepnep.hduhelper.ui.TimetableUiState
import moe.nepnep.hduhelper.ui.TimetableStatus
import moe.nepnep.hduhelper.ui.screens.*
import moe.nepnep.hduhelper.ui.theme.HDUHelperTheme
import top.yukonga.miuix.kmp.basic.Scaffold
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class TimetableUiTest {
    @get:Rule val timeout:Timeout=Timeout.seconds(90)
    @get:Rule val compose=createComposeRule()

    private fun fixture():TimetableData {
        val term=AcademicTerm("2026","3","1")
        fun meeting(id:String,weeks:List<Int>,day:Int=1,campus:String="1")=CourseMeeting(id,id,"课程$id","老师$id","教室$id",campus,"校区$campus","3.0",day,listOf(3,4,5),weeks,weeks.joinToString(",")+"周","3-5")
        return TimetableData("synthetic",term,TimetableCatalog(listOf("2026","2025"),listOf(TermOption("3","1"),TermOption("12","2")),term),
            listOf(meeting("B",listOf(2,4,6)),meeting("C",listOf(3,5,7),campus="2"),meeting("周末",listOf(1,2,3),7)),emptyList(),(1..23).map {
                val start=LocalDate.of(2026,9,14).plusWeeks((it-1).toLong());WeekRange(it,start.toString(),start.plusDays(6).toString())
            },listOf(CampusClock("1","校区1",(1..12).map { CampusPeriod(it,"10:00","10:45",if(it<=5)"上午" else if(it<=9)"下午" else "晚上") }),
                CampusClock("2","校区2",(1..12).map {CampusPeriod(it,"09:50","10:35",if(it<=5)"上午" else if(it<=9)"下午" else "晚上")})),1000)
    }
    private fun initial(week:Int=0)=fixture().let {TimetableUiState(TimetableStatus.READY,it,it.catalog,it.term,week,LocalDate.of(2026,9,7))}

    @Test fun holidaySwipeHiddenDetailsAndCampusSpecificTimes() {
        var state by mutableStateOf(initial())
        compose.setContent {HDUHelperTheme(darkTheme=true) {Scaffold(topBar={TimetableTopBar(state, {state=state.copy(week=0)}, {state=state.copy(selectedTerm=it)})}) {padding->
            TimetableScreen(state,{}, {state=state.copy(week=it)}, {}, {},Modifier.fillMaxSize().padding(padding))
        }}}
        compose.onNodeWithText("假期中").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回日程").assertDoesNotExist()
        compose.onNodeWithText("离开学还有 7 天").assertIsDisplayed()
        compose.onNodeWithTag("timetable_pager").performTouchInput {swipeLeft()}
        compose.waitUntil {state.week==1}
        compose.onNodeWithText("第1周").assertIsDisplayed()
        compose.runOnIdle {state=state.copy(settings=state.settings.copy(showTeacher=false,showLocation=false))}
        compose.onNodeWithText("老师B").assertDoesNotExist()
        compose.onNode(hasTestTag("course_B_3") and hasAnyAncestor(hasTestTag("timetable_grid_1"))).performClick()
        compose.onNodeWithText("老师B").assertIsDisplayed()
        compose.onNodeWithText("教室B").assertIsDisplayed()
        compose.onNodeWithText("切换 ⇄").assertDoesNotExist()
        compose.onNodeWithContentDescription("切换课程").assertIsDisplayed()
        compose.onNodeWithText("10:00–10:45",substring=true).assertIsDisplayed()
        compose.onNodeWithTag("course_details_next").performClick()
        compose.onNodeWithText("老师C").assertIsDisplayed()
        compose.onNodeWithText("09:50–10:35",substring=true).assertIsDisplayed()
        compose.onNodeWithText("2/2").assertIsDisplayed()
    }

    @Test fun termPickerWeekendAndFullDayScrollInLightTheme() {
        var state by mutableStateOf(initial(1).copy(settings=TimetableSettings(showWeekend=true)))
        compose.setContent {HDUHelperTheme(darkTheme=false) {Scaffold(topBar={TimetableTopBar(state, {}, {state=state.copy(selectedTerm=it)})}) {padding->
            TimetableScreen(state,{}, {state=state.copy(week=it)}, {}, {},Modifier.fillMaxSize().padding(padding))
        }}}
        compose.onNodeWithText("周六").assertIsDisplayed()
        compose.onNodeWithText("周日").assertIsDisplayed()
        compose.onNodeWithTag("timetable_scroll_1").performTouchInput {swipeUp()}
        compose.onNodeWithText("12",useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithTag("timetable_choose_term").performClick()
        compose.onNodeWithTag("term_3").assertIsSelected()
        compose.onNodeWithTag("term_previous_year").performClick()
        compose.onNodeWithTag("term_3").assertIsNotSelected()
        compose.onNodeWithTag("term_12").performClick()
        compose.runOnIdle {assertEquals("2025-12",state.selectedTerm!!.key)}
        compose.onNodeWithTag("timetable_choose_term").performClick()
        compose.onNodeWithTag("term_12").assertIsSelected()
    }

    @Test fun settingsFiveTogglesAndCampusChoice() {
        var state by mutableStateOf(initial())
        compose.setContent {HDUHelperTheme {Scaffold {padding->TimetableSettingsScreen(state,{state=state.copy(settings=it)},{state=state.copy(selectedCampus=it)},Modifier.fillMaxSize().padding(padding))}}}
        compose.onNodeWithText(state.data!!.term.label).assertIsDisplayed()
        for(tag in listOf("setting_other_weeks","setting_finished","setting_weekend","setting_teacher","setting_location"))compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.runOnIdle {assertEquals(TimetableSettings(false,true,true,false,false),state.settings)}
        compose.onNodeWithTag("campus_2").performScrollTo().performClick()
        compose.runOnIdle {assertEquals("2",state.selectedCampus)}
        compose.onNodeWithTag("campus_auto").performScrollTo().performClick()
        compose.runOnIdle {assertNull(state.selectedCampus)}
    }

    @Test fun refreshRequiresDeliberatePullAndNormalScrollingStillWorks() {
        var refreshes = 0
        compose.setContent { HDUHelperTheme { Scaffold { padding ->
            TimetableScreen(initial(1), { refreshes++ }, {}, {}, {}, Modifier.fillMaxSize().padding(padding))
        } } }
        val grid = compose.onNodeWithTag("timetable_scroll_1")
        grid.performTouchInput {
            swipe(Offset(centerX, height * .2f), Offset(centerX, height * .28f), 600)
        }
        compose.runOnIdle { assertEquals(0, refreshes) }
        grid.performTouchInput {
            swipe(Offset(centerX, height * .2f), Offset(centerX, height * .5f), 600)
        }
        compose.waitUntil { refreshes == 1 }
        compose.waitForIdle()
        grid.performTouchInput { swipeUp() }
        compose.onNodeWithText("12", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test fun settingsPersistAndCampusChoicesAreScoped() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repository=SettingsRepository(context)
        val original=repository.state.value.timetable
        try {
            repository.setTimetable(TimetableSettings(false,true,true,false,false))
            repository.setTimetableCampus("synthetic-settings","2026-3","2")
            val reopened=SettingsRepository(context)
            assertEquals(TimetableSettings(false,true,true,false,false),reopened.state.value.timetable)
            assertEquals("2",reopened.timetableCampus("synthetic-settings","2026-3"))
            assertNull(reopened.timetableCampus("another-synthetic","2026-3"))
            assertNull(reopened.timetableCampus("synthetic-settings","2025-12"))
        }finally {repository.setTimetable(original);repository.setTimetableCampus("synthetic-settings","2026-3",null)}
    }

    @Test fun denseGridVisualReferences() {
        val base=fixture()
        val dense=(1..5).flatMap { day -> listOf(3..5,6..8,10..12).mapIndexed { index, range ->
            val id="visual-$day-$index"
            base.meetings[0].copy(id=id,courseKey=id,name=if(day==1&&index==0)"软件工程原理与团队实践课程" else "示例课程$day · ${index+1}",
                weekday=day,sections=range.toList(),weeks=if(day==3)listOf(2,4,6) else (1..17).toList(),rawWeeks=if(day==3)"2-6周(双)" else "1-17周",
                rawSections="${range.first}-${range.last}",teacher="示例教师",location="示例教学楼北区410")
        }}
        val extra=dense[0].copy(id="visual-overlap",weeks=listOf(7,8),rawWeeks="7-8周",location="另一教室")
        val starts=listOf("08:05","08:55","10:00","10:50","11:40","13:30","14:20","15:15","16:05","18:30","19:20","20:10")
        val ends=listOf("08:50","09:40","10:45","11:35","12:25","14:15","15:05","16:00","16:50","19:15","20:05","20:55")
        val data=base.copy(meetings=dense+extra,clocks=base.clocks.map {c->c.copy(periods=c.periods.mapIndexed {i,p->p.copy(start=starts[i],end=ends[i])})})
        var state by mutableStateOf(initial().copy(data=data))
        var dark by mutableStateOf(true)
        compose.setContent {HDUHelperTheme(darkTheme=dark) {Scaffold(topBar={TimetableTopBar(state, {}, {})}) {padding->
            TimetableScreen(state,{}, {state=state.copy(week=it)}, {}, {},Modifier.fillMaxSize().padding(padding))
        }}}
        fun capture(name:String) {
            compose.waitForIdle()
            // Capture a rendered frame first, then export only this synthetic screen for visual inspection.
            compose.onNodeWithTag("timetable_screen").captureToImage()
            val instrument=InstrumentationRegistry.getInstrumentation()
            val bitmap=instrument.uiAutomation.takeScreenshot()
            instrument.targetContext.cacheDir.resolve(name).outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
        capture("timetable-holiday.png")
        compose.runOnIdle {state=state.copy(week=1)}
        capture("timetable-week-dark.png")
        compose.onNode(hasTestTag("course_visual-1-0_3") and hasAnyAncestor(hasTestTag("timetable_grid_1"))).performClick()
        compose.onNodeWithTag("course_details_next").assertIsDisplayed()
        compose.waitForIdle()
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.uiAutomation.takeScreenshot().also {bitmap->instrument.targetContext.cacheDir.resolve("timetable-details.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        instrument.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitForIdle()
        compose.runOnIdle {dark=false}
        capture("timetable-week-light.png")
    }
}
