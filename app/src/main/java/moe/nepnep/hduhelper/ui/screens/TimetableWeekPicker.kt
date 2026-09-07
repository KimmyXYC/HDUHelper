package moe.nepnep.hduhelper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.nepnep.hduhelper.data.timetable.TimetableWeekRules
import moe.nepnep.hduhelper.ui.TimetableUiState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun TimetableWeekPicker(show: Boolean, state: TimetableUiState, onDismiss: () -> Unit,
    onWeek: (Int) -> Unit, onCurrent: () -> Unit,
) {
    val data = state.data ?: return
    val weeks = TimetableWeekRules.available(data, state.today, state.settings.showExams)
    if (weeks.isEmpty()) return
    val current = TimetableWeekRules.current(data, state.today, state.settings.showExams)
    val maxGridHeight = (LocalConfiguration.current.screenHeightDp * .45f).dp
    WindowDialog(show, title = "点击查看该周课表", onDismissRequest = onDismiss,
        modifier = Modifier.testTag("timetable_week_picker")) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = maxGridHeight).verticalScroll(rememberScrollState())
                .testTag("week_choices"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                weeks.chunked(6).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { week ->
                            val isCurrent = week == current
                            val browsing = week == state.week
                            val shape = RoundedCornerShape(12.dp)
                            val label = TimetableWeekRules.label(data, week, state.settings.showExams)
                            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(shape)
                                .background(MiuixTheme.colorScheme.surfaceContainer)
                                .then(if (browsing && !isCurrent) Modifier.border(1.dp, MiuixTheme.colorScheme.onSurfaceVariantSummary, shape) else Modifier)
                                .clickable(role = Role.Button) { onDismiss(); onWeek(week) }
                                .semantics { selected = browsing; contentDescription = "$label${if (isCurrent) "，当前周" else ""}" }
                                .testTag("week_choice_$week").padding(horizontal = 2.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                                Text(label, color = if (isCurrent) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontSize = if (label.length > 2) 13.sp else 16.sp, textAlign = TextAlign.Center)
                            }
                        }
                        repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("取消", onDismiss, Modifier.weight(1f).testTag("week_picker_cancel"))
                TextButton("返回当前周", { onDismiss(); onCurrent() }, Modifier.weight(1f).testTag("week_picker_current"),
                    colors = ButtonDefaults.textButtonColors(color = MiuixTheme.colorScheme.primary, textColor = MiuixTheme.colorScheme.onPrimary))
            }
        }
    }
}
