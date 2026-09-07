package moe.nepnep.hduhelper.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import moe.nepnep.hduhelper.data.timetable.*
import moe.nepnep.hduhelper.ui.ExamsUiState
import moe.nepnep.hduhelper.ui.TimetableStatus
import moe.nepnep.hduhelper.ui.components.AppPullToRefresh
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ExamsScreen(
    state: ExamsUiState,
    onTerm: (AcademicTerm) -> Unit,
    onRefresh: () -> Unit,
    onLogin: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chooseTerm by remember { mutableStateOf(false) }
    var detailId by remember(state.selectedTerm?.key, state.exams) { mutableStateOf<String?>(null) }
    val agenda = remember(state.exams, state.now) { ExamAgendaRules.arrange(state.exams?.items.orEmpty(), state.now) }
    val accessible = state.status !in listOf(TimetableStatus.SIGNED_OUT, TimetableStatus.LOGIN_REQUIRED, TimetableStatus.VERIFICATION_REQUIRED)
    AppPullToRefresh(state.refreshing, onRefresh, modifier.testTag("exams_screen"), enabled = accessible && state.status != TimetableStatus.LOADING) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cardHeight = compactExamCardHeight(agenda.upcoming + agenda.untimed + agenda.ended,
                state.now, ((maxWidth - 52.dp) / 2 - 32.dp).coerceAtLeast(1.dp))
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().testTag("exams_grid"),
                contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.catalog != null) item(key = "term", span = { GridItemSpan(maxLineSpan) }) {
                    val colors = ButtonDefaults.textButtonColors()
                    Button(onClick = { chooseTerm = true }, modifier = Modifier.fillMaxWidth().testTag("exams_choose_term"),
                        colors = ButtonDefaults.buttonColors(color = colors.color, contentColor = colors.textColor)) {
                        Text("${state.selectedTerm?.label ?: state.catalog.current.label}  ▾", Modifier.fillMaxWidth(),
                            style = MiuixTheme.textStyles.button, textAlign = TextAlign.Start)
                    }
                }
                if (!accessible) item(key = "auth", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        val verification = state.status == TimetableStatus.VERIFICATION_REQUIRED
                        Text(if (verification) "请完成官方验证后查看考试安排" else "登录后查看考试安排")
                        TextButton(if (verification) "完成官方验证" else "登录", if (verification) onVerify else onLogin,
                            Modifier.testTag(if (verification) "exams_verify" else "exams_login"))
                    }
                }
                if (accessible) {
                    val notice = state.message ?: state.exams?.message ?: if (state.offline) "离线显示最近同步的考试安排" else null
                    if (notice != null) item(key = "notice", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(notice, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            if (state.status == TimetableStatus.ERROR || state.exams?.failed == true) TextButton("重试", onRefresh, Modifier.testTag("exams_retry"))
                        }
                    }
                    if (state.status == TimetableStatus.LOADING && state.exams == null) item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                        Text("正在读取考试安排…", Modifier.fillMaxWidth().padding(vertical = 32.dp), textAlign = TextAlign.Start)
                    }
                    if (state.exams?.let { it.items.isEmpty() && !it.failed && it.updatedAt != null } == true) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Text("该学期暂无考试安排", Modifier.fillMaxWidth().padding(vertical = 48.dp).testTag("exams_empty"), textAlign = TextAlign.Start,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    agenda.featured?.let { featured ->
                        item(key = "countdown", span = { GridItemSpan(maxLineSpan) }) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.Start,
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (ExamAgendaRules.ongoing(featured, state.now)) "距离本场考试结束还剩" else "距离下一场考试开始还剩",
                                    style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                Text(ExamAgendaRules.countdown(featured, state.now), style = MiuixTheme.textStyles.title2,
                                    textAlign = TextAlign.Start, modifier = Modifier.testTag("exams_countdown"))
                            }
                        }
                        item(key = "exam/${featured.id}", span = { GridItemSpan(maxLineSpan) }) {
                            ExamAgendaCard(featured, state.now, featured = true, onClick = { detailId = featured.id })
                        }
                    }
                    items(agenda.upcoming, key = { "exam/${it.id}" }) { exam ->
                        ExamAgendaCard(exam, state.now, height = cardHeight, onClick = { detailId = exam.id })
                    }
                    if (agenda.untimed.isNotEmpty()) item(key = "untimed", span = { GridItemSpan(maxLineSpan) }) {
                        Text("时间待确认", Modifier.padding(top = 12.dp), style = MiuixTheme.textStyles.title4)
                    }
                    items(agenda.untimed, key = { "exam/${it.id}" }) { exam ->
                        ExamAgendaCard(exam, state.now, height = cardHeight, onClick = { detailId = exam.id })
                    }
                    if (agenda.ended.isNotEmpty()) item(key = "ended", span = { GridItemSpan(maxLineSpan) }) {
                        Text("已结束的考试", Modifier.padding(top = 12.dp).testTag("exams_ended"), style = MiuixTheme.textStyles.title4,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    items(agenda.ended, key = { "exam/${it.id}" }) { exam ->
                        ExamAgendaCard(exam, state.now, ended = true, height = cardHeight, onClick = { detailId = exam.id })
                    }
                }
            }
        }
    }
    state.catalog?.let { catalog ->
        TermPicker(chooseTerm, catalog, state.selectedTerm ?: catalog.current, { chooseTerm = false }) {
            chooseTerm = false
            onTerm(it)
        }
    }
    ExamDetails(state.exams?.items?.firstOrNull { it.id == detailId }.takeIf { accessible }, { detailId = null })
}

@Composable
private fun ExamAgendaCard(exam: ExamArrangement, now: LocalDateTime, featured: Boolean = false, ended: Boolean = false, height: Dp? = null, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val foreground = when { featured -> colors.onPrimary; ended -> colors.onSurfaceVariantSummary; else -> colors.onSurface }
    val background = when { featured -> colors.primary; ended -> colors.surfaceContainerHigh; else -> colors.surfaceContainer }
    val time = examAgendaTime(exam)
    Card(Modifier.fillMaxWidth().then(if (height != null) Modifier.height(height) else Modifier)
        .clickable(role = Role.Button, onClick = onClick).testTag("exam_agenda_${exam.id}"),
        colors = CardDefaults.defaultColors(color = background, contentColor = foreground)) {
        Column(Modifier.fillMaxWidth().padding(if (featured) 20.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ExamAgendaRules.ongoing(exam, now)) Text("正在考试", style = MiuixTheme.textStyles.footnote1, color = foreground)
            if (featured) Text(time, style = MiuixTheme.textStyles.title4, color = foreground)
            Text(exam.name, style = if (featured) MiuixTheme.textStyles.title2 else MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.SemiBold, color = foreground)
            if (!featured) Text(time, style = MiuixTheme.textStyles.body2, color = foreground)
            if (featured && exam.examName.isNotBlank()) Text(exam.examName, style = MiuixTheme.textStyles.body2, color = foreground)
            if (exam.place.isNotBlank()) Text(exam.place, Modifier.fillMaxWidth(), style = MiuixTheme.textStyles.body2,
                textAlign = TextAlign.Start, color = foreground)
        }
    }
}

private fun examAgendaTime(exam: ExamArrangement): String = if (exam.timed)
    "${exam.startTime!!.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}–${exam.endTime!!.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    else exam.rawTime.ifBlank { "时间待确认" }

/** Measure every compact card at the actual column width, including offscreen exams. */
@Composable
private fun compactExamCardHeight(exams: List<ExamArrangement>, now: LocalDateTime, textWidth: Dp): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val title = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold)
    val body = MiuixTheme.textStyles.body2
    val status = MiuixTheme.textStyles.footnote1
    val ongoing = exams.filter { ExamAgendaRules.ongoing(it, now) }.map { it.id }.toSet()
    return remember(exams, ongoing, textWidth, density, measurer, title, body, status) {
        with(density) {
            val constraints = Constraints(maxWidth = textWidth.toPx().toInt().coerceAtLeast(1))
            val maximum = exams.maxOfOrNull { exam ->
                val lines = buildList {
                    if (exam.id in ongoing) add("正在考试" to status)
                    add(exam.name to title)
                    add(examAgendaTime(exam) to body)
                    if (exam.place.isNotBlank()) add(exam.place to body)
                }
                lines.sumOf { (text, style) -> measurer.measure(text, style, constraints = constraints).size.height } +
                    (lines.size - 1) * 10.dp.roundToPx() + 2 * 16.dp.roundToPx()
            } ?: 0
            maximum.toDp()
        }
    }
}
