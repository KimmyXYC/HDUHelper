package moe.nepnep.hduhelper

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import moe.nepnep.hduhelper.data.grades.*
import moe.nepnep.hduhelper.data.timetable.*
import moe.nepnep.hduhelper.ui.*
import moe.nepnep.hduhelper.ui.screens.*
import moe.nepnep.hduhelper.ui.theme.HDUHelperTheme
import moe.nepnep.hduhelper.ui.theme.ExamPageTheme
import androidx.compose.foundation.background
import top.yukonga.miuix.kmp.theme.MiuixTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GradesUiTest {
    @get:Rule val compose = createComposeRule()
    private val term = AcademicTerm("2026", "3", "1")
    private val catalog = TimetableCatalog(listOf("2026", "2025"), listOf(TermOption("3", "1"), TermOption("12", "2")), term)
    private val grade = CourseGrade("synthetic", "class", "student", "合成课程与较长名称的换行示例", "2.00", "90", "4.5", "通识必修", false,
        listOf(GradeComponent("平时成绩", "95"), GradeComponent("期中成绩", "0"), GradeComponent("期末成绩", "优秀")), true)
    private fun ready(items: List<CourseGrade> = listOf(grade)) = GradesUiState(TimetableStatus.READY, catalog, term,
        GradeSnapshot("synthetic", term, catalog, items, 1))

    @Test fun componentsWrapInDarkLargeFontAndExplanationIsComplete() {
        var dark by mutableStateOf(false)
        var scale by mutableFloatStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                HDUHelperTheme(darkTheme = dark) {
                    ExamPageTheme { GradesScreen(ready(), {}, {}, {}, {}, Modifier.width(340.dp).fillMaxHeight().background(MiuixTheme.colorScheme.surface)) }
                }
            }
        }
        compose.onNodeWithText("最终成绩：90").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("期中成绩：0").assertIsDisplayed()
        compose.onNodeWithText("期末成绩：优秀").assertIsDisplayed()
        val ordinary = textLayout("平时成绩：95").layoutInput.style
        val finalScore = textLayout("最终成绩：90").layoutInput.style
        val finalPoint = textLayout("最终绩点：4.5").layoutInput.style
        assertEquals(12.sp, ordinary.fontSize)
        assertEquals(ordinary.fontSize, finalScore.fontSize)
        assertEquals(ordinary.fontSize, finalPoint.fontSize)
        assertEquals(FontWeight.SemiBold, finalScore.fontWeight)
        assertEquals(FontWeight.SemiBold, finalPoint.fontWeight)
        saveScreenshot("grades-light.png")
        compose.runOnIdle { dark = true; scale = 1.5f }
        compose.onNodeWithText("最终绩点：4.5").performScrollTo().assertIsDisplayed()
        val mid = compose.onNodeWithText("期中成绩：0").fetchSemanticsNode().boundsInRoot
        val final = compose.onNodeWithText("最终绩点：4.5").fetchSemanticsNode().boundsInRoot
        assertTrue(final.top > mid.top)
        saveScreenshot("grades-dark-large.png")
        compose.onNodeWithText(GRADE_DISCLAIMER).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("平均学分绩点 = 有效课程学分绩点总和 ÷ 有效课程学分总和").performScrollTo().assertIsDisplayed()
    }

    @Test fun termPickerEmptyStateAndFailuresHaveNoRetryButtons() {
        var state by mutableStateOf(ready(emptyList()))
        var selected: AcademicTerm? = null
        compose.setContent { HDUHelperTheme { ExamPageTheme { GradesScreen(state, { selected = it }, {}, {}, {}) } } }
        compose.onNodeWithTag("grades_empty").assertIsDisplayed()
        compose.onNodeWithTag("grades_choose_term").performClick()
        compose.onNodeWithTag("term_previous_year").performClick()
        compose.onNodeWithTag("term_12").performClick()
        compose.runOnIdle { assertEquals("2025-12", selected!!.key); state = ready().let { it.copy(grades = it.grades!!.copy(detailsFailed = true)) } }
        compose.onNodeWithText("部分成绩分项未更新，最终成绩和绩点不受影响").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("重试").assertDoesNotExist()
        compose.runOnIdle { state = GradesUiState(status = TimetableStatus.ERROR, message = "无法读取考试成绩，请重试") }
        compose.onNodeWithText("无法读取考试成绩，请重试").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("重试").assertDoesNotExist()
    }

    @Test fun unavailableFieldsAreOmittedAndAuthenticationHidesCachedData() {
        var state by mutableStateOf(ready(listOf(grade.copy(components = emptyList(), score = "", gradePoint = ""))))
        var logins = 0
        compose.setContent { HDUHelperTheme { ExamPageTheme { GradesScreen(state, {}, {}, { logins++ }, {}) } } }
        compose.onNodeWithTag("grade_synthetic").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("最终成绩：").assertDoesNotExist()
        compose.onNodeWithText("期中成绩：0").assertDoesNotExist()
        compose.runOnIdle { state = ready().copy(status = TimetableStatus.LOGIN_REQUIRED) }
        compose.onNodeWithTag("grade_synthetic").assertDoesNotExist()
        compose.onNodeWithTag("grades_login").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, logins) }
    }

    @Test fun bothAcademicPagesShareStateTypographyAndSpacing() {
        var gradesPage by mutableStateOf(true)
        var status by mutableStateOf(TimetableStatus.READY)
        var dark by mutableStateOf(false)
        compose.setContent {
            HDUHelperTheme(darkTheme = dark) { ExamPageTheme {
                val message = "更新失败，请下拉刷新"
                if (gradesPage) GradesScreen(
                    if (status == TimetableStatus.READY) ready(emptyList()) else GradesUiState(status = status, message = message.takeIf { status == TimetableStatus.ERROR }),
                    {}, {}, {}, {})
                else ExamsScreen(ExamsUiState(status = status, catalog = catalog, selectedTerm = term,
                    exams = if (status == TimetableStatus.READY) ExamSnapshot(updatedAt = 1) else null,
                    message = message.takeIf { status == TimetableStatus.ERROR }), {}, {}, {}, {})
            } }
        }
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark = isDark }
            for ((phase, labels) in listOf(
                TimetableStatus.READY to ("该学期暂无考试成绩" to "该学期暂无考试安排"),
                TimetableStatus.LOADING to ("正在读取考试成绩…" to "正在读取考试安排…"),
                TimetableStatus.ERROR to ("更新失败，请下拉刷新" to "更新失败，请下拉刷新"),
                TimetableStatus.SIGNED_OUT to ("登录后查看考试成绩" to "登录后查看考试安排"),
            )) {
                compose.runOnIdle { status = phase; gradesPage = true }
                compose.onNodeWithText(labels.first).performScrollTo().assertIsDisplayed()
                val gradeStyle = textLayout(labels.first).layoutInput.style
                val gradeBounds = compose.onNodeWithText(labels.first).getUnclippedBoundsInRoot()
                compose.runOnIdle { gradesPage = false }
                compose.onNodeWithText(labels.second).performScrollTo().assertIsDisplayed()
                val examStyle = textLayout(labels.second).layoutInput.style
                val examBounds = compose.onNodeWithText(labels.second).getUnclippedBoundsInRoot()
                assertEquals(gradeStyle.fontSize, examStyle.fontSize)
                assertEquals(gradeStyle.color, examStyle.color)
                assertEquals(gradeStyle.textAlign, examStyle.textAlign)
                assertEquals(gradeBounds.left.value, examBounds.left.value, 0.01f)
                assertEquals((gradeBounds.bottom - gradeBounds.top).value, (examBounds.bottom - examBounds.top).value, 0.01f)
                compose.onNodeWithText("重试").assertDoesNotExist()
            }
        }
    }

    private fun textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun saveScreenshot(name: String) {
        compose.onNodeWithTag("grades_screen").captureToImage().asAndroidBitmap().let { bitmap ->
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve(name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
