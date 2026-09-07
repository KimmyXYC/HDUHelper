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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
fun TimetableTopBar(state: TimetableUiState, onDefault: () -> Unit, onTerm: (AcademicTerm) -> Unit) {
    var chooseTerm by remember { mutableStateOf(false) }
    val title = if (state.data == null) "课表" else if (state.week == 0) "假期中" else "第${state.week}周"
    val first = state.data?.weeks?.minByOrNull { it.week }?.startDate
    val subtitle = if (state.week == 0 && first != null) "离开学还有 ${ChronoUnit.DAYS.between(state.today, first).coerceAtLeast(0)} 天" else state.selectedTerm?.label.orEmpty()
    Row(Modifier.fillMaxWidth().background(gridBackground()).statusBarsPadding().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(48.dp))
        Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = onDefault).testTag("timetable_week_title").padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { chooseTerm = true }, enabled = state.catalog != null, modifier = Modifier.size(48.dp).testTag("timetable_choose_term")) {
            Icon(painterResource(R.drawable.ic_swap_horizontal), "切换学期", Modifier.size(24.dp))
        }
    }
    state.catalog?.let { catalog ->
        TermPicker(chooseTerm, catalog, state.selectedTerm ?: catalog.current, { chooseTerm = false }) { chooseTerm = false; onTerm(it) }
    }
}

@Composable
private fun TermPicker(show: Boolean, catalog: TimetableCatalog, selected: AcademicTerm, onDismiss: () -> Unit, onSelect: (AcademicTerm) -> Unit) {
    var year by remember(show, selected.key) { mutableStateOf(selected.year) }
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
            val weeks = remember(data.weeks, state.today) { TimetableRules.availableWeeks(data.weeks, state.today) }
            if (weeks.isNotEmpty()) {
                // Miuix does not expose its refresh threshold. Add resistance only to
                // downward overscroll so refreshing requires twice the finger travel.
                val refreshResistance = remember {
                    object : NestedScrollConnection {
                        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                            if (source == NestedScrollSource.UserInput && available.y > 0f) Offset(0f, available.y * 0.5f) else Offset.Zero
                    }
                }
                val pager = rememberPagerState(initialPage = weeks.indexOf(state.week).coerceAtLeast(0), pageCount = { weeks.size })
                LaunchedEffect(state.week, weeks) {
                    val target = weeks.indexOf(state.week)
                    if (target >= 0 && target != pager.currentPage) pager.scrollToPage(target)
                }
                LaunchedEffect(pager, weeks) {
                    snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { weeks.getOrNull(it)?.let(onWeek) }
                }
                PullToRefresh(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
                    HorizontalPager(pager, Modifier.fillMaxSize().nestedScroll(refreshResistance).testTag("timetable_pager"), key = { weeks[it] }) { index ->
                        val week = weeks[index]
                        Column(Modifier.fillMaxSize()) {
                            DateHeader(data, week, state.today, state.settings.showWeekend)
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("timetable_scroll_$week")) {
                                WeekGrid(data, week, state.settings, state.clock) { card ->
                                    details = card.overlaps
                                    detailIndex = details.indexOfFirst { it.id == card.meeting.id }.coerceAtLeast(0)
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
            }
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
private fun DateHeader(data: TimetableData, week: Int, today: LocalDate, weekend: Boolean) {
    val monday = if (week == 0) today.with(DayOfWeek.MONDAY) else data.weeks.firstOrNull { it.week == week }?.startDate?.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)) ?: today.with(DayOfWeek.MONDAY)
    Row(Modifier.fillMaxWidth().height(48.dp)) {
        Spacer(Modifier.width(36.dp))
        repeat(if (weekend) 7 else 5) { day ->
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
            breaks += height to when { next.contains("下午") -> "午休"; next.contains("晚") -> "晚休"; else -> "休息" }
            height += 24.dp
        }
        rows[section] = height
        height += rowHeight
    }
    val dark = MiuixTheme.colorScheme.surface.luminance() < .4f
    val line = if (dark) Color(0xFF28292D) else Color(0xFFE2E5EB)
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val days = if (settings.showWeekend) 7 else 5
    BoxWithConstraints(Modifier.fillMaxWidth().height(height).testTag("timetable_grid_$week")) {
        val columnWidth = (maxWidth - 36.dp) / days
        Canvas(Modifier.matchParentSize()) {
            for (column in 0..days) {
                val x = (36.dp + columnWidth * column).toPx()
                drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            }
            for (y in rows.values) drawLine(line, Offset(0f, y.toPx()), Offset(size.width, y.toPx()), 1.dp.toPx())
            drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        }
        for (section in 1..count) {
            Column(Modifier.offset(y = rows.getValue(section)).width(36.dp).height(rowHeight).padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(section.toString(), fontSize = 14.sp)
                periods[section]?.let { p ->
                    Text(p.start, fontSize = 9.sp, color = muted, lineHeight = 12.sp)
                    Text(p.end, fontSize = 9.sp, color = muted, lineHeight = 12.sp)
                }
            }
        }
        for ((top, label) in breaks) {
            Box(Modifier.offset(y = top).fillMaxWidth().height(24.dp).background(if (dark) Color(0xFF202125) else Color(0xFFEEF0F4)), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 10.sp, color = muted)
            }
        }
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
                CourseCard(card, dark, settings, Modifier.offset(x = 36.dp + columnWidth * (card.meeting.weekday - 1), y = top)
                    .width(columnWidth).height(bottom - top).padding(2.dp).testTag("course_${card.meeting.id}_${run.first}")) { onClick(card) }
            }
        }
    }
}

@Composable
private fun CourseCard(card: MeetingCard, dark: Boolean, settings: TimetableSettings, modifier: Modifier, onClick: () -> Unit) {
    val active = card.state == MeetingState.CURRENT
    val accent = Color(palette[Math.floorMod(card.meeting.courseKey.hashCode(), palette.size)])
    val text = if (active) (if (dark) accent else Color(androidx.core.graphics.ColorUtils.blendARGB(accent.toArgb(), 0xFF172038.toInt(), .25f))) else if (dark) Color(0xFF777A82) else Color(0xFF9699A1)
    val background = if (active) accent.copy(alpha = if (dark) .18f else .13f).compositeOver(gridBackground()) else if (dark) Color(0xFF1B1D21) else Color(0xFFEBEDF1)
    BoxWithConstraints(modifier.clip(RoundedCornerShape(7.dp)).background(background).clickable(role = Role.Button, onClick = onClick).clipToBounds()) {
        val compact = maxHeight < 85.dp
        Column(Modifier.padding(horizontal = 5.dp, vertical = 8.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(card.meeting.name, color = text, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium,
                maxLines = if (compact) 2 else 5, overflow = TextOverflow.Ellipsis)
            if (settings.showLocation && card.meeting.location.isNotBlank()) Text("@${card.meeting.location}", color = text.copy(alpha = .85f), fontSize = 11.sp, lineHeight = 14.sp, maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis)
            if (settings.showTeacher && card.meeting.teacher.isNotBlank()) Text(card.meeting.teacher, color = text.copy(alpha = .85f), fontSize = 11.sp, lineHeight = 14.sp, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
        }
        if (card.overlaps.size > 1) Canvas(Modifier.align(Alignment.BottomEnd).size(13.dp).semantics { contentDescription = "此时间段有${card.overlaps.size}项安排" }) {
            drawPath(Path().apply { moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }, text)
        }
    }
}

@Composable
private fun CourseDetails(meeting: CourseMeeting?, clocks: List<CampusClock>, total: Int, index: Int, onDismiss: () -> Unit, onNext: () -> Unit) {
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

@Composable private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.width(40.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
        Text(value.ifBlank { "未提供" }, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 22.sp)
    }
}
