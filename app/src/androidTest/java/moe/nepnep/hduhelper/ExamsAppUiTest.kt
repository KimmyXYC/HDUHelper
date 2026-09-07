package moe.nepnep.hduhelper

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.flow.MutableStateFlow
import moe.nepnep.hduhelper.data.auth.*
import moe.nepnep.hduhelper.data.campuscode.*
import moe.nepnep.hduhelper.data.schedule.*
import moe.nepnep.hduhelper.data.settings.SettingsRepository
import moe.nepnep.hduhelper.data.timetable.*
import moe.nepnep.hduhelper.ui.*
import moe.nepnep.hduhelper.ui.screens.*
import moe.nepnep.hduhelper.ui.theme.HDUHelperTheme
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import java.time.LocalDateTime

class ExamsAppUiTest {
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
    private val now = LocalDateTime.of(2026, 9, 8, 10, 0)
    private val term = AcademicTerm("2026", "3", "1")
    private val previous = AcademicTerm("2025", "12", "2")
    private val catalog = TimetableCatalog(listOf("2026", "2025"), listOf(TermOption("3", "1"), TermOption("12", "2")), term)
    private fun exam(id: String, name: String, day: Int, hour: Int) = ExamArrangement(id, name, "2026–2027 学年期末考试",
        "9月${day}日 $hour:00–${hour + 2}:00", now.withDayOfMonth(day).withHour(hour).toString(),
        now.withDayOfMonth(day).withHour(hour + 2).toString(), "第6教研楼中225", "下沙校区", "39")
    private fun snapshot() = ExamSnapshot(listOf(
        exam("next", "高等数学 A2", 9, 13), exam("second", "计算机系统及安全", 10, 9),
        exam("third", "物理学原理及工程应用 B1", 11, 13),
        ExamArrangement("unknown", "大学英语口试", rawTime = "具体时间另行通知"),
        exam("old", "工程经济学", 7, 9),
    ), 1)
    private fun data(selected: AcademicTerm = term) = TimetableData("synthetic", selected, catalog,
        emptyList(), emptyList(), emptyList(), emptyList(), 1, exams = snapshot())

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve(name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun featuredGridDetailsTermsAndEndedSectionInBothThemesAndLargeFont() {
        var state by mutableStateOf(ExamsUiState(TimetableStatus.READY, catalog, term, snapshot(), now))
        var dark by mutableStateOf(false)
        var scale by mutableFloatStateOf(1f)
        var refreshes = 0
        show {
            HDUHelperTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                    Scaffold(topBar = { TopAppBar("考试安排") }) { padding ->
                        ExamsScreen(state, { state = state.copy(selectedTerm = it) }, { refreshes++ }, {}, {}, Modifier.fillMaxSize().padding(padding))
                    }
                }
            }
        }
        compose.onNodeWithTag("exams_countdown").assertIsDisplayed()
        val first = compose.onNodeWithTag("exam_agenda_next").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithTag("exam_agenda_second").getUnclippedBoundsInRoot()
        val third = compose.onNodeWithTag("exam_agenda_third").getUnclippedBoundsInRoot()
        assertTrue((first.right - first.left) > (second.right - second.left) * 1.8f)
        assertTrue(third.left > second.left)
        assertEquals(second.top, third.top)
        capture("exams-app-light.png")
        compose.onNodeWithTag("exam_agenda_next").performClick()
        compose.onNodeWithTag("exam_details").assertIsDisplayed()
        compose.onNodeWithText("第6教研楼中225").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("exams_choose_term").performClick()
        compose.onNodeWithTag("term_previous_year").performClick()
        compose.onNodeWithTag("term_12").performClick()
        compose.runOnIdle { assertEquals(previous, state.selectedTerm) }
        compose.onNodeWithTag("exams_grid").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, refreshes); dark = true }
        capture("exams-app-dark.png")
        compose.onNodeWithTag("exams_grid").performScrollToNode(hasTestTag("exams_ended"))
        compose.onNodeWithTag("exams_ended").assertIsDisplayed()
        compose.onNodeWithTag("exam_agenda_old").performClick()
        compose.onNodeWithTag("exam_details").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        capture("exams-app-ended.png")
        compose.runOnIdle { scale = 1.5f }
        compose.onNodeWithTag("exams_grid").performScrollToIndex(0)
        compose.onNodeWithTag("exams_choose_term").assertIsDisplayed()
        compose.onNodeWithTag("exams_grid").performScrollToNode(hasTestTag("exam_agenda_third"))
        compose.onNodeWithText("物理学原理及工程应用 B1").assertIsDisplayed()
        capture("exams-app-large-font.png")
    }

    @Test fun emptyOngoingEndedAndVerificationHaveCorrectActions() {
        val sample = exam("active", "合成进行中考试", 8, 9)
        var state by mutableStateOf(ExamsUiState(TimetableStatus.READY, catalog, term, ExamSnapshot(listOf(sample), 1), now))
        var verifies = 0
        show { HDUHelperTheme { Scaffold { padding ->
            ExamsScreen(state, {}, {}, {}, { verifies++ }, Modifier.fillMaxSize().padding(padding))
        } } }
        compose.onNodeWithText("正在考试").assertIsDisplayed()
        compose.onNodeWithText("距离本场考试结束还剩").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(now = now.withHour(11)) }
        compose.onNodeWithTag("exams_countdown").assertDoesNotExist()
        compose.onNodeWithTag("exams_ended").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(exams = ExamSnapshot(updatedAt = 1)) }
        compose.onNodeWithTag("exams_empty").assertIsDisplayed()
        compose.onNodeWithTag("exams_ended").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(status = TimetableStatus.VERIFICATION_REQUIRED) }
        compose.onNodeWithTag("exams_verify").performClick()
        compose.runOnIdle { assertEquals(1, verifies) }
    }

    @Test fun compactCardsShareHeightAcrossRowsWithoutClippingAtLargeFont() {
        val entries = listOf(
            exam("long-title", "中国近现代史纲要", 1, 9),
            exam("short-title", "高等数学 A1", 2, 9),
            exam("long-place", "C语言程序设计", 3, 9).copy(location = "第4教研楼341-343-345-347"),
            exam("short-place", "线性代数", 4, 9).copy(location = "教室101", campus = "", seat = ""),
        )
        val state = ExamsUiState(TimetableStatus.READY, catalog, term, ExamSnapshot(entries, 1), now)
        var scale by mutableFloatStateOf(1f)
        show { HDUHelperTheme(darkTheme = true) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                Scaffold(topBar = { TopAppBar("考试安排") }) { padding ->
                    ExamsScreen(state, {}, {}, {}, {}, Modifier.fillMaxSize().padding(padding))
                }
            }
        } }
        for (fontScale in listOf(1f, 1.5f)) {
            compose.runOnIdle { scale = fontScale }
            var expectedHeight: Float? = null
            for (entry in entries) {
                compose.onNodeWithTag("exams_grid").performScrollToNode(hasTestTag("exam_agenda_${entry.id}"))
                val card = compose.onNodeWithTag("exam_agenda_${entry.id}").getUnclippedBoundsInRoot()
                val height = (card.bottom - card.top).value
                expectedHeight?.let { assertEquals(it, height, 0.5f) } ?: run { expectedHeight = height }
                val place = compose.onNode(hasText(entry.place) and hasAnyAncestor(hasTestTag("exam_agenda_${entry.id}")), useUnmergedTree = true).getUnclippedBoundsInRoot()
                assertTrue("Exam location must fit inside its card", place.bottom <= card.bottom)
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.onNode(hasText(entry.place) and hasAnyAncestor(hasTestTag("exam_agenda_${entry.id}")), useUnmergedTree = true)
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("Location text must remain complete", layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow })
            }
            compose.onNodeWithTag("exams_grid").performScrollToIndex(0)
            capture("exams-equal-height-$fontScale.png")
        }
    }

    @Test fun applicationNavigationLoginReturnAndTermSelectionUseIndependentState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "exams-ui-${System.nanoTime()}-"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        val preferences = SettingsRepository(isolated)
        val memory = object : SessionStore {
            var value: StoredSession? = null
            override fun load() = value
            override fun save(session: StoredSession) { value = session }
            override fun clear() { value = null }
        }
        val auth = AuthRepository(memory, preferences, { _ -> object : AuthSession {
            override val cookies = emptyList<StoredCookie>()
            override val verificationUrl = "https://example.invalid/"
            override suspend fun check() = UserProfile("synthetic", "合成用户")
            override suspend fun renewSso() = check()
            override suspend fun login(account: String, password: String) = UserProfile(account, "合成用户")
            override suspend fun get(url: HttpUrl) = error("No real service requests")
        } })
        val source = object : TimetableSource {
            override suspend fun cached(account: String, term: AcademicTerm?) = data(term ?: this@ExamsAppUiTest.term)
            override suspend fun catalog() = this@ExamsAppUiTest.catalog
            override suspend fun refresh(term: AcademicTerm, catalog: TimetableCatalog) = data(term)
            override fun clear() = Unit
        }
        val models = ViewModelStore()
        lateinit var app: AppViewModel
        lateinit var campus: CampusCodeViewModel
        lateinit var timetable: TimetableViewModel
        lateinit var schedule: ScheduleViewModel
        lateinit var exams: ExamsViewModel
        compose.runOnUiThread {
            val online = MutableStateFlow(true)
            app = AppViewModel(auth, preferences, online).also { models.put("app", it) }
            campus = CampusCodeViewModel(object : CampusCodeSource {
                override suspend fun refresh(): CampusCode = error("No campus request expected")
                override fun clear() = Unit
            }, auth.state, auth.sessionGeneration, online).also { models.put("campus", it) }
            timetable = TimetableViewModel(source, auth.state, auth.sessionGeneration, online, preferences.state,
                { _, _ -> null }, {}, { _, _, _ -> }).also { models.put("timetable", it) }
            schedule = ScheduleViewModel(ScheduleRepository(object : ScheduleStore {
                override fun load() = ScheduleBook()
                override fun save(book: ScheduleBook) = Unit
            }), source, auth.state, auth.sessionGeneration, online, object : ScheduleReminderScheduler {
                override fun reschedule() = Unit
                override fun status(): String? = null
            }).also { models.put("schedule", it) }
            exams = ExamsViewModel(source, auth.state, auth.sessionGeneration, online, { now }).also { models.put("exams", it) }
        }
        try {
            show { HDUHelperTheme { HDUHelperApp(app, campus, timetable, schedule, exams) } }
            compose.onNodeWithText("应用").performClick()
            compose.onNodeWithTag("application_exams").assertIsDisplayed()
            val grid = compose.onNodeWithTag("applications_grid").getUnclippedBoundsInRoot()
            val tile = compose.onNodeWithTag("application_exams").getUnclippedBoundsInRoot()
            assertTrue(tile.right < (grid.left + grid.right) / 2)
            capture("applications-exams.png")
            compose.onNodeWithTag("application_exams").performClick()
            compose.onNodeWithTag("exams_login").performClick()
            compose.onNodeWithTag("login_account").performTextInput("synthetic")
            compose.onNodeWithTag("login_password").performTextInput("synthetic-test-only")
            compose.onNodeWithTag("submit_login").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("exams_countdown").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("applications_grid").assertDoesNotExist()
            compose.onNodeWithText("我的").assertDoesNotExist()
            compose.onNodeWithTag("exams_choose_term").performClick()
            compose.onNodeWithTag("term_previous_year").performClick()
            compose.onNodeWithTag("term_12").performClick()
            compose.waitUntil { exams.state.value.selectedTerm == previous }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("application_exams").performClick()
            compose.runOnIdle {
                assertEquals(previous, exams.state.value.selectedTerm)
                assertNull(timetable.state.value.selectedTerm)
            }
        } finally {
            compose.runOnUiThread { models.clear() }
            context.deleteSharedPreferences(prefix + "settings")
        }
    }
}
