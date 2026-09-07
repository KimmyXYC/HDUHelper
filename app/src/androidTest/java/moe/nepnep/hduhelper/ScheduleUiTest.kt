package moe.nepnep.hduhelper

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import top.yukonga.miuix.kmp.basic.Scaffold
import moe.nepnep.hduhelper.data.schedule.*
import moe.nepnep.hduhelper.ui.*
import moe.nepnep.hduhelper.ui.components.AppDatePicker
import moe.nepnep.hduhelper.ui.screens.*
import moe.nepnep.hduhelper.ui.theme.HDUHelperTheme
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class ScheduleUiTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(90)
    @get:Rule val compose = createComposeRule()
    private val date = LocalDate.of(2026, 9, 7)
    private val event = ScheduleEvent("测试安排", start = "2026-09-07T10:00", end = "2026-09-07T11:00", repeat = ScheduleRepeat.DAILY)

    private fun screenshot(name: String, tag: String? = null) {
        if (InstrumentationRegistry.getArguments().getString("scheduleScreenshots") != "true") return
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val file = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve(name)
        val bitmap = (if (tag == null) compose.onRoot() else compose.onNodeWithTag(tag)).captureToImage().asAndroidBitmap()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun signedOutCalendarSwipesSynchronizesDatesAndKeepsLocalActions() {
        var state by mutableStateOf(ScheduleUiState(date, date,
            storage = ScheduleStorageState(ScheduleBook(listOf(ScheduleSeries("sample", event))), loaded = true), courseStatus = TimetableStatus.SIGNED_OUT))
        var fontScale by mutableFloatStateOf(1f)
        var add = false
        var singleEdit = false
        var deleteAll = false
        var refreshes = 0
        compose.setContent { HDUHelperTheme(darkTheme = true) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Scaffold(topBar = { ScheduleTopBar { add = true } }) { padding ->
                    ScheduleScreen(state, { state = state.copy(date = it, detailKey = null) }, { state = state.copy(date = date) },
                        { state = state.copy(detailKey = it) }, { _, single -> singleEdit = single; state = state.copy(detailKey = null) },
                        { _, single -> deleteAll = !single; state = state.copy(detailKey = null, storage = ScheduleStorageState(loaded = true)) },
                        { refreshes++ }, {}, Modifier.fillMaxSize().padding(padding))
                }
            }
        } }
        compose.onNodeWithText("登录后查看当日课程").assertDoesNotExist()
        compose.onNodeWithTag("schedule_login").assertDoesNotExist()
        compose.onNodeWithTag("schedule_today").assertDoesNotExist()
        val addBounds = compose.onNodeWithTag("schedule_add").getUnclippedBoundsInRoot()
        val dateBounds = compose.onNodeWithTag("schedule_date").getUnclippedBoundsInRoot()
        assertTrue(addBounds.top < dateBounds.top && addBounds.right > dateBounds.right)
        screenshot("schedule-dark.png")
        compose.onNodeWithTag("schedule_date").performClick()
        compose.onNodeWithTag("date_picker_dialog").assertIsDisplayed()
        compose.onNodeWithTag("date_picker_summary").assertTextEquals("2026年9月7日星期一")
        screenshot("schedule-date-dark.png", "date_picker_dialog")
        compose.onNodeWithTag("date_picker_cancel").performClick()
        compose.runOnIdle { fontScale = 1.5f }
        compose.onNodeWithTag("schedule_add").assertIsDisplayed()
        compose.onNodeWithTag("schedule_date").assertIsDisplayed()
        screenshot("schedule-large-font.png")
        compose.runOnIdle { fontScale = 1f }
        compose.onNodeWithTag("schedule_add").performClick()
        compose.runOnIdle { assertTrue(add) }
        compose.onNodeWithTag("schedule_pager").performTouchInput { swipeLeft() }
        compose.waitUntil { state.date == date.plusDays(1) }
        compose.onNodeWithTag("schedule_today").assertIsDisplayed()
        compose.onNodeWithTag("schedule_list_${date.plusDays(1)}").assertIsDisplayed()
        screenshot("schedule-next-day.png")
        compose.onNodeWithTag("schedule_pager").performTouchInput { swipeRight() }
        compose.waitUntil { state.date == date }
        compose.onNodeWithTag("schedule_today").assertDoesNotExist()
        compose.onNodeWithTag("schedule_next_day").performClick()
        compose.waitUntil { state.date == date.plusDays(1) }
        compose.runOnIdle { state = state.copy(date = date.plusMonths(1)) }
        compose.onNodeWithTag("schedule_list_${date.plusMonths(1)}").assertIsDisplayed()
        compose.onNodeWithTag("schedule_today").performClick()
        compose.onNodeWithTag("schedule_list_$date").assertIsDisplayed()
        compose.onNodeWithTag("schedule_list_$date").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(0, refreshes) }
        compose.onNodeWithTag("agenda_sample/$date").performClick()
        compose.onNodeWithTag("schedule_edit").performClick()
        compose.onNodeWithTag("schedule_only_this").performClick()
        compose.runOnIdle { assertTrue(singleEdit) }
        compose.onNodeWithTag("agenda_sample/$date").performClick()
        compose.onNodeWithTag("schedule_delete").performClick()
        compose.onNodeWithTag("schedule_entire_series").performClick()
        compose.runOnIdle { assertTrue(deleteAll) }
        compose.onNodeWithText("今天暂无安排").assertIsDisplayed()
    }

    @Test fun editorValidatesUsesIndependentChoicesAndSavesDraft() {
        val initial = event.copy(title = "", repeat = ScheduleRepeat.NEVER)
        var state by mutableStateOf(ScheduleEditorState(ScheduleEditor(null, null, initial)))
        var page by mutableStateOf("editor")
        var saved: ScheduleEvent? = null
        compose.setContent { HDUHelperTheme(darkTheme = false) {
            when (page) {
                "repeat" -> ScheduleRepeatScreen(state.draft.repeat, { state = state.copy(draft = state.draft.copy(repeat = it)); page = "editor" }, { page = "editor" })
                "reminder" -> ScheduleReminderScreen(state.draft.reminderMinutes, { state = state.copy(draft = state.draft.copy(reminderMinutes = it)); page = "editor" }, { page = "editor" })
                else -> ScheduleEditorScreen(state, { state = state.copy(draft = it, error = null) }, {
                    val error = state.draft.validationError()
                    if (error == null) saved = state.draft else state = state.copy(error = error)
                }, { false }, {}, null, {}, { page = "repeat" }, { page = "reminder" })
            }
        } }
        screenshot("schedule-editor-light.png")
        compose.onNodeWithTag("schedule_save").performClick()
        compose.onNodeWithTag("schedule_form_error").assertIsDisplayed()
        compose.onNodeWithTag("schedule_title").performTextInput("读书")
        compose.onNodeWithTag("schedule_repeat").performScrollTo().performClick()
        compose.onNodeWithTag("repeat_NEVER").assertIsSelected()
        screenshot("schedule-repeat-light.png")
        compose.onNodeWithTag("repeat_WEEKLY").performClick()
        compose.onNodeWithTag("schedule_title").assertTextContains("读书")
        compose.onNodeWithTag("schedule_reminder").performScrollTo().performClick()
        compose.onNodeWithTag("reminder_NONE").assertIsSelected()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("schedule_location").performScrollTo().performTextInput("图书馆")
        compose.onNodeWithTag("schedule_all_day").performScrollTo().performClick()
        compose.onNodeWithTag("schedule_start").performClick()
        compose.onNodeWithTag("schedule_picker_hour").assertDoesNotExist()
        compose.onNodeWithTag("date_picker_cancel").performClick()
        compose.onNodeWithTag("schedule_notes").performScrollTo().performTextInput("带笔记本")
        compose.onNodeWithTag("schedule_save").performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved)
            assertEquals("读书", result.title)
            assertEquals("图书馆", result.location)
            assertTrue(result.allDay)
            assertEquals(ScheduleRepeat.WEEKLY, result.repeat)
            assertEquals("带笔记本", result.notes)
            assertEquals("2026-09-08T00:00", result.end)
        }
    }

    @Test fun combinedTimePanelCancelsOrCommitsAtomically() {
        var state by mutableStateOf(ScheduleEditorState(ScheduleEditor(null, null, event)))
        compose.setContent { HDUHelperTheme(darkTheme = true) {
            ScheduleEditorScreen(state, { state = state.copy(draft = it) }, {}, { false }, {}, null, {}, {}, {})
        } }
        compose.onNodeWithTag("schedule_start").performClick()
        screenshot("schedule-time-dark.png", "schedule_datetime_picker")
        compose.onNodeWithTag("schedule_picker_hour").performTouchInput {
            swipe(center, Offset(center.x, center.y - height / 3), durationMillis = 1200)
        }
        compose.onNodeWithTag("schedule_datetime_cancel").performClick()
        compose.runOnIdle { assertEquals(event, state.draft) }
        compose.onNodeWithTag("schedule_start").performClick()
        compose.onNodeWithTag("schedule_picker_hour").performTouchInput {
            swipe(center, Offset(center.x, center.y - height / 3), durationMillis = 1200)
        }
        compose.onNodeWithTag("schedule_datetime_confirm").performClick()
        compose.runOnIdle {
            assertNotEquals(event.start, state.draft.start)
            assertEquals(java.time.Duration.ofHours(1), java.time.Duration.between(state.draft.startTime, state.draft.endTime))
        }
    }

    @Test fun modifiedDraftRequiresDiscardConfirmation() {
        var state by mutableStateOf(ScheduleEditorState(ScheduleEditor(null, null, event)))
        var back = false
        compose.setContent { HDUHelperTheme(darkTheme = true) {
            ScheduleEditorScreen(state, { state = state.copy(draft = it) }, {}, { false }, { back = true }, null, {}, {}, {})
        } }
        compose.onNodeWithTag("schedule_title").performTextReplacement("已修改")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.runOnIdle { assertFalse(back) }
        val continueBounds = compose.onNodeWithTag("schedule_continue_editing").getUnclippedBoundsInRoot()
        val discardBounds = compose.onNodeWithTag("schedule_discard").getUnclippedBoundsInRoot()
        assertTrue("Dialog actions must have visible space between them", discardBounds.top.value - continueBounds.bottom.value >= 12f)
        screenshot("schedule-discard-spaced.png", "schedule_discard_dialog")
        compose.onNodeWithTag("schedule_continue_editing").performClick()
        compose.onNodeWithTag("schedule_title").assertTextContains("已修改")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithTag("schedule_discard").performClick()
        compose.runOnIdle { assertTrue(back) }
    }

    private fun advanceDateWheel(tag: String) {
        compose.onNodeWithTag(tag).performTouchInput {
            swipe(center, Offset(center.x, center.y - height / 3), durationMillis = 1200)
        }
        compose.waitForIdle()
    }

    @Test fun datePickerClampsLeapDayAndMonthEndsAndCancelsPendingChanges() {
        var initial by mutableStateOf(LocalDate.of(2024, 2, 29))
        var show by mutableStateOf(true)
        var selected: LocalDate? = null
        var fontScale by mutableFloatStateOf(1f)
        compose.setContent { HDUHelperTheme(darkTheme = false) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                AppDatePicker(show, initial, { show = false }, { selected = it; show = false })
            }
        } }
        advanceDateWheel("date_picker_year")
        compose.onNodeWithTag("date_picker_summary").assertTextEquals("2025年2月28日星期五")
        compose.onNodeWithTag("date_picker_confirm").performClick()
        compose.runOnIdle { assertEquals(LocalDate.of(2025, 2, 28), selected); initial = LocalDate.of(2026, 1, 31); show = true }
        advanceDateWheel("date_picker_month")
        compose.onNodeWithTag("date_picker_summary").assertTextEquals("2026年2月28日星期六")
        screenshot("schedule-date-light.png", "date_picker_dialog")
        compose.runOnIdle { fontScale = 1.5f }
        screenshot("schedule-date-large-font.png", "date_picker_dialog")
        compose.onNodeWithTag("date_picker_cancel").performClick()
        compose.runOnIdle { assertEquals(LocalDate.of(2025, 2, 28), selected); show = true }
        compose.onNodeWithTag("date_picker_summary").assertTextEquals("2026年1月31日星期六")
    }

    @Test fun allDayFieldsShareDatePickerAndKeepInclusiveDisplayedEnd() {
        var state by mutableStateOf(ScheduleEditorState(ScheduleEditor(null, null,
            event.copy(allDay = true, start = "2026-09-07T00:00", end = "2026-09-08T00:00"))))
        compose.setContent { HDUHelperTheme(darkTheme = true) {
            ScheduleEditorScreen(state, { state = state.copy(draft = it) }, {}, { false }, {}, null, {}, {}, {})
        } }
        compose.onNodeWithTag("schedule_start").performClick()
        compose.onNodeWithTag("date_picker_summary").assertTextEquals("2026年9月7日星期一")
        compose.onNodeWithTag("schedule_picker_hour").assertDoesNotExist()
        screenshot("schedule-all-day-date.png", "date_picker_dialog")
        advanceDateWheel("date_picker_day")
        compose.onNodeWithTag("date_picker_confirm").performClick()
        compose.runOnIdle {
            assertEquals("2026-09-08T00:00", state.draft.start)
            assertEquals("2026-09-09T00:00", state.draft.end)
        }
        compose.onNodeWithTag("schedule_end").performClick()
        compose.onNodeWithTag("date_picker_summary").assertTextEquals("2026年9月8日星期二")
        advanceDateWheel("date_picker_day")
        compose.onNodeWithTag("date_picker_confirm").performClick()
        compose.runOnIdle { assertEquals("2026-09-10T00:00", state.draft.end) }
        compose.onNodeWithTag("schedule_end").performClick()
        compose.onNodeWithTag("date_picker_summary").assertTextEquals("2026年9月9日星期三")
    }

}
