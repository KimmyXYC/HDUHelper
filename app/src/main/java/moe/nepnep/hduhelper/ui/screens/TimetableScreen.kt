package moe.nepnep.hduhelper.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.distinctUntilChanged
import moe.nepnep.hduhelper.data.timetable.*
import moe.nepnep.hduhelper.ui.components.AppPullToRefresh
import moe.nepnep.hduhelper.ui.components.AppTopBarIconButton
import moe.nepnep.hduhelper.R
import moe.nepnep.hduhelper.ui.TimetableStatus
import moe.nepnep.hduhelper.ui.TimetableUiState
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private val weekdayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val palette = listOf(0xFF2196F3, 0xFF9575CD, 0xFFEF6C35, 0xFFDAA12D, 0xFF37A995, 0xFFCF5183, 0xFF6484DE, 0xFF9F63CE)
@Composable private fun gridBackground(): Color = if (MiuixTheme.colorScheme.surface.luminance() < .4f) Color(0xFF08090B) else Color(0xFFFAFBFD)

@Composable
fun TimetableTopBar(state: TimetableUiState, onDefault: () -> Unit, onTerm: (AcademicTerm) -> Unit, onWeek: (Int) -> Unit = {}) {
    var chooseTerm by remember { mutableStateOf(false) }
    var chooseWeek by remember(state.data?.account, state.data?.term?.key) { mutableStateOf(false) }
    val extraWeek = state.data?.let { d -> ExamRules.weeks(d, state.settings.showExams).firstOrNull { it.week == state.week && d.weeks.none { original -> original.week == it.week } } }
    val title = if (extraWeek != null) "考试周 · ${extraWeek.startDate.monthValue}/${extraWeek.startDate.dayOfMonth}" else if (state.data == null) "课表" else if (state.week == 0) "假期中" else "第${state.week}周"
    val first = state.data?.weeks?.minByOrNull { it.week }?.startDate
    val subtitle = if (state.week == 0 && first != null && extraWeek == null) "离开学还有 ${ChronoUnit.DAYS.between(state.today, first).coerceAtLeast(0)} 天" else state.selectedTerm?.label.orEmpty()
    Row(Modifier.fillMaxWidth().background(gridBackground()).statusBarsPadding().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(48.dp))
        Column(Modifier.weight(1f).clickable(enabled = state.data?.let { TimetableWeekRules.available(it, state.today, state.settings.showExams).isNotEmpty() } == true, role = Role.Button, onClick = { chooseWeek = true }).testTag("timetable_week_title").padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AppTopBarIconButton(onClick = { chooseTerm = true }, enabled = state.catalog != null, modifier = Modifier.testTag("timetable_choose_term")) {
            Icon(painterResource(R.drawable.ic_swap_horizontal), "切换学期", it)
        }
    }
    TimetableWeekPicker(chooseWeek, state, { chooseWeek = false }, onWeek, onDefault)
    state.catalog?.let { catalog ->
        TermPicker(chooseTerm, catalog, state.selectedTerm ?: catalog.current, { chooseTerm = false }) { chooseTerm = false; onTerm(it) }
    }
}

@Composable
internal fun TermPicker(show: Boolean, catalog: TimetableCatalog, selected: AcademicTerm, onDismiss: () -> Unit, onSelect: (AcademicTerm) -> Unit) {
    var year by remember(show, selected.key) { mutableStateOf(selected.year) }
    moe.nepnep.hduhelper.ui.theme.FullSizeComponentTheme {
        WindowDialog(show = show, title = "切换学期", onDismissRequest = onDismiss) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton("‹", { val i = catalog.years.indexOf(year); if (i + 1 < catalog.years.size) year = catalog.years[i + 1] }, Modifier.testTag("term_previous_year"))
                    Text("$year–${year.toIntOrNull()?.plus(1) ?: ""}", Modifier.weight(1f), textAlign = TextAlign.Center)
                    TextButton("›", { val i = catalog.years.indexOf(year); if (i > 0) year = catalog.years[i - 1] }, Modifier.testTag("term_next_year"))
                }
                for (term in catalog.terms) {
                    val choice = AcademicTerm(year, term.code, term.name)
                    val isSelected = choice.key == selected.key
                    TextButton("第${term.name}学期${if (choice.key == catalog.current.key) " · 当前学期" else ""}${if (isSelected) " ✓" else ""}",
                        onClick = { onSelect(choice) }, modifier = Modifier.fillMaxWidth().testTag("term_${term.code}").semantics { this.selected = isSelected },
                        colors = if (isSelected) ButtonDefaults.textButtonColors(color = MiuixTheme.colorScheme.primary.copy(alpha = .12f), textColor = MiuixTheme.colorScheme.primary)
                            else ButtonDefaults.textButtonColors())
                }
            }
        }
    }
}

@Composable
fun TimetableScreen(
    state: TimetableUiState,
    onRefresh: () -> Unit,
    onWeek: (Int) -> Unit,
    onLogin: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var details by remember(state.data?.account, state.data?.term?.key) { mutableStateOf<List<CourseMeeting>>(emptyList()) }
    var detailIndex by remember(state.data?.account, state.data?.term?.key) { mutableIntStateOf(0) }
    var other by remember(state.data?.account, state.data?.term?.key) { mutableStateOf<OtherArrangement?>(null) }
    var exam by remember(state.data?.account, state.data?.term?.key) { mutableStateOf<ExamArrangement?>(null) }
    var conflicts by remember(state.data?.account, state.data?.term?.key) { mutableStateOf<List<ExamGridItem>>(emptyList()) }
    fun openItem(item: ExamGridItem) {
        if (item.exam != null) exam = item.exam else { details = listOfNotNull(item.course); detailIndex = 0 }
    }
    val data = state.data
    Column(modifier.background(gridBackground()).testTag("timetable_screen")) {
        if (state.message != null || state.offline) {
            Text(state.message ?: "离线课表 · 显示最近成功更新的数据", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (state.status in listOf(TimetableStatus.LOGIN_REQUIRED, TimetableStatus.VERIFICATION_REQUIRED)) {
            TextButton(if (state.status == TimetableStatus.VERIFICATION_REQUIRED) "完成官方验证" else "重新登录", if (state.status == TimetableStatus.VERIFICATION_REQUIRED) onVerify else onLogin, Modifier.fillMaxWidth())
        }
        if (data == null) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when (state.status) {
                    TimetableStatus.SIGNED_OUT -> "登录后查看课表"
                    TimetableStatus.LOADING -> "正在读取课表…"
                    else -> state.message ?: "暂时无法显示课表"
                }, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                if (state.status == TimetableStatus.SIGNED_OUT) TextButton("登录数字杭电", onLogin, Modifier.testTag("timetable_login"))
                else if (!state.refreshing && state.status != TimetableStatus.LOADING) TextButton("重试", onRefresh)
            }
        } else {
            val weeks = remember(data, state.today, state.settings.showExams) { TimetableWeekRules.available(data, state.today, state.settings.showExams) }
            if (weeks.isNotEmpty()) {
                val pager = rememberPagerState(initialPage = weeks.indexOf(state.week).coerceAtLeast(0), pageCount = { weeks.size })
                LaunchedEffect(state.week, weeks) {
                    val target = weeks.indexOf(state.week)
                    if (target >= 0 && target != pager.currentPage) pager.scrollToPage(target)
                }
                LaunchedEffect(pager, weeks) {
                    snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { weeks.getOrNull(it)?.let(onWeek) }
                }
                AppPullToRefresh(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
                    HorizontalPager(pager, Modifier.fillMaxSize().testTag("timetable_pager"), key = { weeks[it] }) { index ->
                        val week = weeks[index]
                        Column(Modifier.fillMaxSize()) {
                            val weekExams = if (state.settings.showExams) ExamRules.week(data, week) else emptyList()
                            val settings = state.settings.copy(
                                showSaturday = state.settings.showSaturday || weekExams.any { it.startTime!!.dayOfWeek.value == 6 },
                                showSunday = state.settings.showSunday || weekExams.any { it.startTime!!.dayOfWeek.value == 7 })
                            DateHeader(data, week, state.today, settings.visibleDays, settings.showExams)
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("timetable_scroll_$week")) {
                                if (weekExams.isNotEmpty()) ExamWeekGrid(data, week, settings, state.clock) { items ->
                                    if (items.size == 1) openItem(items.single()) else conflicts = items
                                } else WeekGrid(data, week, settings, state.clock) { card ->
                                    details = card.overlaps
                                    detailIndex = details.indexOfFirst { it.id == card.meeting.id }.coerceAtLeast(0)
                                }
                                if (settings.showExams) data.exams.message?.let { Text(it, Modifier.padding(16.dp), fontSize = 12.sp) }
                                val untimed = if (settings.showExams) data.exams.items.filterNot { it.timed } else emptyList()
                                if (untimed.isNotEmpty()) {
                                    Text("考试时间待确认", Modifier.padding(16.dp), fontWeight = FontWeight.SemiBold)
                                    untimed.forEach { item -> TextButton("${item.name} · ${item.rawTime.ifBlank { "时间待定" }}", { exam = item }) }
                                }
                                if (data.meetings.isEmpty() && data.others.isEmpty()) Text("该学期暂无课程", Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center)
                                if (data.others.isNotEmpty()) {
                                    Text("其他安排", Modifier.padding(16.dp), fontWeight = FontWeight.SemiBold)
                                    for (item in data.others) {
                                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                                            .background(MiuixTheme.colorScheme.surfaceContainer).clickable { other = item }.padding(12.dp)) {
                                            Text(item.name, fontSize = 14.sp)
                                            // School-formatted descriptions may embed teachers/locations; show them only in details.
                                            if (item.rawWeeks.isNotBlank()) Text(item.rawWeeks, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                            if (state.settings.showTeacher && item.teacher.isNotBlank()) Text(item.teacher, fontSize = 12.sp)
                                            if (state.settings.showLocation && item.location.isNotBlank()) Text(item.location, fontSize = 12.sp)
                                        }
                                    }
                                }
                                val updated = Instant.ofEpochMilli(data.updatedAt).atZone(campusZone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                                Text("更新于 $updated${state.clock?.name?.let { " · $it 作息" }.orEmpty()}", Modifier.fillMaxWidth().padding(16.dp), fontSize = 10.sp,
                                    textAlign = TextAlign.Center, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                }
            } else {
                AppPullToRefresh(state.refreshing, onRefresh, Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text("暂无可显示的课表周次")
                        if (state.settings.showExams) {
                            data.exams.message?.let { Text(it) }
                            data.exams.items.filterNot { it.timed }.forEach { item ->
                                TextButton("考试时间待确认 · ${item.name}", { exam = item })
                            }
                        }
                    }
                }
            }
        }
    }
    ExamDetails(exam, { exam = null })
    WindowDialog(show = conflicts.isNotEmpty(), title = "重叠安排（${conflicts.size}项）", onDismissRequest = { conflicts = emptyList() }, modifier = Modifier.testTag("exam_conflicts")) {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            conflicts.forEach { item -> TextButton("${if (item.exam != null) "考试" else "课程"} · ${item.title}", { conflicts = emptyList(); openItem(item) }) }
        }
    }
    CourseDetails(details.getOrNull(detailIndex), data?.clocks.orEmpty(), details.size, detailIndex, { details = emptyList() }) {
        if (details.isNotEmpty()) detailIndex = (detailIndex + 1) % details.size
    }
    WindowDialog(show = other != null, title = other?.name, onDismissRequest = { other = null }) {
        other?.let { item -> Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DetailLine("安排", item.description)
            DetailLine("周次", item.rawWeeks)
            DetailLine("校区", item.campusName)
            DetailLine("地点", item.location)
            DetailLine("学分", item.credits)
            DetailLine("教师", item.teacher)
        } }
    }
}

@Composable
private fun DateHeader(data: TimetableData, week: Int, today: LocalDate, days: List<Int>, showExams: Boolean) {
    val monday = ExamRules.weeks(data, showExams).firstOrNull { it.week == week }?.startDate?.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)) ?: today.with(DayOfWeek.MONDAY)
    Row(Modifier.fillMaxWidth().height(48.dp)) {
        Spacer(Modifier.width(36.dp))
        for (weekday in days) {
            val day = weekday - 1
            val date = monday.plusDays(day.toLong())
            val tint = if (date == today) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(weekdayNames[day], fontSize = 12.sp, color = tint)
                Text("${date.monthValue}/${date.dayOfMonth}", fontSize = 10.sp, color = tint)
            }
        }
    }
}

@Composable
private fun WeekGrid(data: TimetableData, week: Int, settings: TimetableSettings, clock: CampusClock?, onClick: (MeetingCard) -> Unit) {
    val cards = remember(data, week, settings) { TimetableRules.cards(data, week, settings) }
    val visible = remember(cards) { TimetableRules.visibleSections(cards) }
    val periods = clock?.periods.orEmpty().associateBy { it.section }
    val count = maxOf(periods.keys.maxOrNull() ?: 0, data.meetings.flatMap { it.sections }.maxOrNull() ?: 0)
    if (count == 0) return
    val rowHeight = 60.dp
    val rows = mutableMapOf<Int, Dp>()
    val breaks = mutableListOf<Pair<Dp, String>>()
    var height = 0.dp
    for (section in 1..count) {
        val previous = periods[section - 1]?.group
        val next = periods[section]?.group
        if (section > 1 && !previous.isNullOrBlank() && !next.isNullOrBlank() && previous != next) {
            breaks += height to timetableBreakLabel(next)
            height += 24.dp
        }
        rows[section] = height
        height += rowHeight
    }
    val dark = MiuixTheme.colorScheme.surface.luminance() < .4f
    val days = settings.visibleDays
    TimetableGridFrame(days.size, height, rows, periods, breaks, Modifier.testTag("timetable_grid_$week")) { columnWidth ->
        for (card in cards) {
            // Visible fragments retain the original card's time positions after higher-priority cards cover it.
            val fragments = mutableListOf<IntRange>()
            for (section in visible[card.meeting.id].orEmpty()) {
                val last = fragments.lastOrNull()
                if (last != null && section == last.last + 1 && rows.getValue(section) == rows.getValue(last.last) + rowHeight) {
                    fragments[fragments.lastIndex] = last.first..section
                } else fragments += section..section
            }
            for (run in fragments) {
                val top = rows.getValue(run.first)
                val bottom = rows.getValue(run.last) + rowHeight
                CourseCard(card, dark, settings, Modifier.offset(x = 36.dp + columnWidth * days.indexOf(card.meeting.weekday), y = top)
                    .width(columnWidth).height(bottom - top).padding(2.dp).testTag("course_${card.meeting.id}_${run.first}")) { onClick(card) }
            }
        }
    }
}

@Composable
private fun CourseCard(card: MeetingCard, dark: Boolean, settings: TimetableSettings, modifier: Modifier, onClick: () -> Unit) {
    TimetableArrangementCard(card.meeting.name, card.meeting.courseKey, card.meeting.location, card.meeting.teacher,
        card.state == MeetingState.CURRENT, dark, settings, modifier, card.overlaps.size, onClick = onClick)
}

@Composable
internal fun TimetableArrangementCard(title: String, colorKey: String, location: String, teacher: String,
    active: Boolean, dark: Boolean, settings: TimetableSettings, modifier: Modifier, overlaps: Int,
    badge: String? = null, time: String? = null, onClick: () -> Unit,
) {
    val accent = Color(palette[Math.floorMod(colorKey.hashCode(), palette.size)])
    val text = if (active) (if (dark) accent else Color(androidx.core.graphics.ColorUtils.blendARGB(accent.toArgb(), 0xFF172038.toInt(), .25f))) else if (dark) Color(0xFF777A82) else Color(0xFF9699A1)
    val background = if (active) accent.copy(alpha = if (dark) .18f else .13f).compositeOver(gridBackground()) else if (dark) Color(0xFF1B1D21) else Color(0xFFEBEDF1)
    BoxWithConstraints(modifier.clip(RoundedCornerShape(7.dp)).background(background).clickable(role = Role.Button, onClick = onClick).clipToBounds()) {
        val compact = maxHeight < 85.dp
        Column(Modifier.padding(horizontal = 5.dp, vertical = 8.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = text, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium,
                maxLines = if (compact) 2 else 5, overflow = TextOverflow.Ellipsis)
            if (badge != null) Text(badge, color = text, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
            if (time != null && !compact) Text(time, color = text.copy(alpha = .85f), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
            if (settings.showLocation && location.isNotBlank()) Text("@$location", color = text.copy(alpha = .85f), fontSize = 11.sp, lineHeight = 14.sp, maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis)
            if (settings.showTeacher && teacher.isNotBlank()) Text(teacher, color = text.copy(alpha = .85f), fontSize = 11.sp, lineHeight = 14.sp, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
        }
        if (overlaps > 1) Box(Modifier.align(Alignment.BottomEnd).semantics { contentDescription = "此时间段有${overlaps}项安排" }) {
            Text(overlaps.toString(), Modifier.padding(horizontal = 3.dp), color = text, fontSize = 9.sp)
        }
    }
}

@Composable
fun CourseDetails(meeting: CourseMeeting?, clocks: List<CampusClock>, total: Int, index: Int, onDismiss: () -> Unit, onNext: () -> Unit) {
    WindowDialog(show = meeting != null, title = meeting?.name, onDismissRequest = onDismiss, modifier = Modifier.testTag("course_details")) {
        meeting?.let { m ->
            Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DetailLine("周次", m.rawWeeks)
                DetailLine("时间", "${weekdayNames[m.weekday - 1]} · 第${m.rawSections.removeSuffix("节")}节\n${TimetableRules.timeText(m, clocks)}")
                DetailLine("校区", m.campusName)
                DetailLine("地点", m.location)
                DetailLine("学分", m.credits)
                DetailLine("教师", m.teacher)
            }
            if (total > 1) Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                Text("${index + 1}/$total", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                IconButton(onNext, Modifier.size(48.dp).testTag("course_details_next")) {
                    Icon(painterResource(R.drawable.ic_swap_horizontal), "切换课程", Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable internal fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.width(40.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
        Text(value.ifBlank { "未提供" }, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 22.sp)
    }
}
