package moe.nepnep.hduhelper.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import moe.nepnep.hduhelper.data.schedule.*
import moe.nepnep.hduhelper.data.timetable.*
import kotlinx.coroutines.flow.distinctUntilChanged
import moe.nepnep.hduhelper.ui.components.AppPullToRefresh
import moe.nepnep.hduhelper.ui.components.AppDatePicker
import moe.nepnep.hduhelper.ui.components.AppTopBarIconButton
import moe.nepnep.hduhelper.ui.ScheduleUiState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import moe.nepnep.hduhelper.ui.TimetableStatus
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private data class AgendaRow(val key: String, val title: String, val subtitle: String, val start: LocalDateTime?,
    val allDay: Boolean = false, val occurrence: ScheduleOccurrence? = null, val course: CourseMeeting? = null, val exam: ExamArrangement? = null)

fun scheduleTimeLabel(event: ScheduleEvent): String {
    val start = event.startTime
    val end = event.endTime
    return if (event.allDay) {
        val last = end.toLocalDate().minusDays(1)
        if (last == start.toLocalDate()) "全天" else "全天 · ${start.toLocalDate()} 至 $last"
    } else if (start.toLocalDate() == end.toLocalDate()) "${start.toLocalTime()}–${end.toLocalTime()}"
    else "${start.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))} 至 ${end.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}"
}

@Composable
fun ScheduleScreen(
    state: ScheduleUiState,
    onDate: (LocalDate) -> Unit,
    onToday: () -> Unit,
    onDetail: (String?) -> Unit,
    onEdit: (ScheduleOccurrence, Boolean) -> Unit,
    onDelete: (ScheduleOccurrence, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRetryStorage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chooseDate by remember { mutableStateOf(false) }
    var course by remember(state.courses?.account, state.courses?.term?.key, state.date) { mutableStateOf<CourseMeeting?>(null) }
    var exam by remember(state.courses?.account, state.courses?.term?.key, state.date) { mutableStateOf<ExamArrangement?>(null) }
    var operation by remember(state.detailKey) { mutableStateOf<String?>(null) }
    val detail = state.detail
    val repeated = state.storage.book.series.firstOrNull { it.id == detail?.seriesId }?.event?.repeat?.let { it != ScheduleRepeat.NEVER } == true
    val data = state.courses
    val origin = remember { state.date }
    val middle = Int.MAX_VALUE / 2
    val pager = rememberPagerState(initialPage = middle, pageCount = { Int.MAX_VALUE })
    val selectedDate by rememberUpdatedState(state.date)
    val selectDate by rememberUpdatedState(onDate)
    LaunchedEffect(state.date) {
        val target = (middle.toLong() + java.time.temporal.ChronoUnit.DAYS.between(origin, state.date)).toInt()
        if (pager.currentPage != target) pager.scrollToPage(target)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            val date = origin.plusDays(page.toLong() - middle)
            if (date != selectedDate) selectDate(date)
        }
    }
    Box(modifier.testTag("schedule_screen")) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ onDate(state.date.minusDays(1)) }, Modifier.size(48.dp).semantics { contentDescription = "前一天" }.testTag("schedule_previous_day")) { Text("‹", fontSize = 28.sp) }
                Column(Modifier.weight(1f).clickable { chooseDate = true }.testTag("schedule_date"), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.date.format(DateTimeFormatter.ofPattern("yyyy年M月d日")), fontWeight = FontWeight.SemiBold)
                    Text(listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[state.date.dayOfWeek.value - 1], style = MiuixTheme.textStyles.footnote1)
                }
                IconButton({ onDate(state.date.plusDays(1)) }, Modifier.size(48.dp).semantics { contentDescription = "后一天" }.testTag("schedule_next_day")) { Text("›", fontSize = 28.sp) }
            }
            AppPullToRefresh(state.refreshing, onRefresh, Modifier.weight(1f),
                enabled = state.courseStatus in setOf(TimetableStatus.READY, TimetableStatus.ERROR)) {
                HorizontalPager(pager, Modifier.fillMaxSize().testTag("schedule_pager"), key = { origin.plusDays(it.toLong() - middle).toString() }) { page ->
                    ScheduleDayList(state, origin.plusDays(page.toLong() - middle), onDetail, { course = it }, { exam = it }, onRetryStorage)
                }
            }
        }
        if (state.date != state.today) FloatingActionButton(onToday,
            Modifier.align(Alignment.BottomEnd).padding(20.dp).testTag("schedule_today").semantics { contentDescription = "回到今天" }) {
            Text("今", fontSize = 22.sp, color = MiuixTheme.colorScheme.onPrimary)
        }
    }
    AppDatePicker(chooseDate, state.date, { chooseDate = false }, { chooseDate = false; onDate(it) })
    val linkedCourse = data?.let { ScheduleRules.courses(it, state.date) }?.firstOrNull { it.id == state.courseDetailId }
    val linkedExam = data?.let { ExamRules.onDate(it, state.date) }?.firstOrNull { it.id == state.examDetailId }
    ExamDetails(exam ?: linkedExam, { exam = null; onDetail(null) })
    CourseDetails(course ?: linkedCourse, data?.clocks.orEmpty(), 1, 0, { course = null; onDetail(null) }, {})
    WindowDialog(show = detail != null && operation == null, title = detail?.event?.title, onDismissRequest = { onDetail(null) }, modifier = Modifier.testTag("schedule_details")) {
        detail?.let { occurrence ->
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(scheduleTimeLabel(occurrence.event))
                Text(occurrence.event.startTime.toLocalDate().toString())
                if (occurrence.event.location.isNotBlank()) Text("位置：${occurrence.event.location}")
                if (repeated) Text("重复：${state.storage.book.series.first { it.id == occurrence.seriesId }.event.repeat.label}")
                Text("提醒：${ScheduleReminder.entries.first { it.minutes == occurrence.event.reminderMinutes }.label}")
                if (occurrence.event.notes.isNotBlank()) Text(occurrence.event.notes)
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("删除", { operation = "delete" }, Modifier.weight(1f).testTag("schedule_delete"))
                TextButton("编辑", { if (repeated) operation = "edit" else onEdit(occurrence, false) }, Modifier.weight(1f).testTag("schedule_edit"))
            }
        }
    }
    WindowDialog(show = operation != null && detail != null, title = if (operation == "edit") "编辑重复日程" else "删除日程", onDismissRequest = { operation = null }) {
        detail?.let { occurrence ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (repeated) "请选择操作范围" else "确定删除这条日程吗？")
                if (repeated) TextButton("仅本次", {
                    if (operation == "edit") onEdit(occurrence, true) else onDelete(occurrence, true)
                    operation = null
                }, Modifier.fillMaxWidth().testTag("schedule_only_this"))
                TextButton(if (repeated) "整个系列" else "确认删除", {
                    if (operation == "edit") onEdit(occurrence, false) else onDelete(occurrence, false)
                    operation = null
                }, Modifier.fillMaxWidth().testTag("schedule_entire_series"))
                TextButton("取消", { operation = null }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun ScheduleTopBar(onAdd: () -> Unit) {
    TopAppBar("日程", actions = {
        AppTopBarIconButton(onAdd, Modifier.testTag("schedule_add")) {
            Icon(MiuixIcons.Add, "添加日程", it)
        }
    })
}

@Composable
private fun ScheduleDayList(state: ScheduleUiState, date: LocalDate, onDetail: (String?) -> Unit,
    onCourse: (CourseMeeting) -> Unit, onExam: (ExamArrangement) -> Unit, onRetryStorage: () -> Unit) {
    val data = state.courses
    val rows = remember(state.storage.book, data, date) {
        val custom = state.storage.book.series.flatMap { ScheduleRules.occurrences(it, date) }.map { occurrence ->
            val event = occurrence.event
            AgendaRow(occurrence.key, event.title, listOf(scheduleTimeLabel(event), event.location).filter { it.isNotBlank() }.joinToString(" · "),
                event.startTime, event.allDay, occurrence = occurrence)
        }
        val courses = data?.let { d -> ScheduleRules.courses(d, date).map { meeting ->
            AgendaRow("course/${meeting.id}", meeting.name,
                listOf(TimetableRules.timeText(meeting, d.clocks), "第${meeting.rawSections.removeSuffix("节")}节", meeting.location, meeting.teacher)
                    .filter { it.isNotBlank() }.joinToString(" · "), ScheduleRules.courseStart(meeting, d.clocks)?.atDate(date), course = meeting)
        } }.orEmpty()
        val exams = data?.let { ExamRules.onDate(it, date) }.orEmpty().map {
            AgendaRow("exam/${it.id}", it.name, listOf(it.rawTime, it.place).filter { text -> text.isNotBlank() }.joinToString(" · "), it.startTime, exam = it)
        }
        (custom + courses + exams).sortedWith(compareByDescending<AgendaRow> { it.allDay }.thenBy { it.start ?: LocalDateTime.MAX }.thenBy { it.key })
    }
    val message = when (state.courseStatus) {
        TimetableStatus.SIGNED_OUT -> null
        TimetableStatus.LOGIN_REQUIRED -> "课程授权已失效，可在“我的”重新登录"
        TimetableStatus.VERIFICATION_REQUIRED -> "课程需要官方验证，可在“我的”处理"
        TimetableStatus.LOADING -> "正在读取课程…"
        TimetableStatus.ERROR -> state.courseMessage ?: "课程读取失败，下拉重试"
        TimetableStatus.READY -> when {
            data != null && data.weeks.none { date in it.startDate..it.endDate } -> null
            state.courseMessage != null -> state.courseMessage
            state.offline -> "离线课程 · 显示最近成功更新的数据"
            else -> data?.term?.label
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("schedule_list_$date"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (message != null) item { Text(message, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        data?.exams?.message?.let { item { Text(it, style = MiuixTheme.textStyles.footnote1) } }
        val untimed = data?.exams?.items.orEmpty().filterNot { it.timed }
        if (untimed.isNotEmpty()) item {
            Text("考试时间待确认", style = MiuixTheme.textStyles.title4)
            untimed.forEach { exam -> TextButton("${exam.name} · ${exam.rawTime.ifBlank { "时间待定" }}", { onExam(exam) }) }
        }
        if (state.storage.error != null) item {
            Text(state.storage.error, color = MiuixTheme.colorScheme.error)
            TextButton("重试读取日程", onRetryStorage)
        }
        if (state.error != null) item { Text(state.error, color = MiuixTheme.colorScheme.error) }
        if (state.reminderStatus != null && state.storage.book.series.any { it.event.reminderMinutes != null || it.exceptions.any { ex -> ex.replacement?.reminderMinutes != null } }) item {
            Text(state.reminderStatus, style = MiuixTheme.textStyles.footnote1)
        }
        if (rows.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (date == state.today) "今天暂无安排" else "当天暂无安排", style = MiuixTheme.textStyles.title4)
                Spacer(Modifier.height(8.dp))
                Text("点击右上角 ＋ 添加日程", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        items(rows, key = { it.key }) { row ->
            Card(Modifier.fillMaxWidth().clickable {
                if (row.exam != null) onExam(row.exam) else if (row.course != null) onCourse(row.course) else onDetail(row.occurrence?.key)
            }.testTag("agenda_${row.key}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (row.exam != null) "考试" else if (row.course != null) "课程" else "自定义日程", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                    Text(row.title, style = MiuixTheme.textStyles.title4)
                    Text(row.subtitle, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.body2)
                }
            }
        }
    }
}
