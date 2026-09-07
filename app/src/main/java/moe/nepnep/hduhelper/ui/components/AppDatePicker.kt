package moe.nepnep.hduhelper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** Date-only picker shared by the agenda and all-day event start/end fields. */
@Composable
fun AppDatePicker(
    show: Boolean,
    date: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
    title: String = "选择日期",
) {
    var selected by remember(show, date) { mutableStateOf(date) }
    val weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
    WindowDialog(show = show, title = title, onDismissRequest = onDismiss, modifier = Modifier.testTag("date_picker_dialog")) {
        Text("${selected.year}年${selected.monthValue}月${selected.dayOfMonth}日${weekdays[selected.dayOfWeek.value - 1]}",
            Modifier.fillMaxWidth().testTag("date_picker_summary"), textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DateWheel(selected.year, { selected = selected.withYear(it) }, 1900..2200, "年", Modifier.weight(1.35f).testTag("date_picker_year"))
            DateWheel(selected.monthValue, { selected = selected.withMonth(it) }, 1..12, "月", Modifier.weight(1f).testTag("date_picker_month"))
            DateWheel(selected.dayOfMonth, { selected = selected.withDayOfMonth(it) }, 1..YearMonth.from(selected).lengthOfMonth(), "日",
                Modifier.weight(1f).testTag("date_picker_day"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton("取消", onDismiss, Modifier.weight(1f).testTag("date_picker_cancel"))
            TextButton("确定", { onSelect(selected) }, Modifier.weight(1f).testTag("date_picker_confirm"),
                colors = ButtonDefaults.textButtonColors(color = MiuixTheme.colorScheme.primary, textColor = MiuixTheme.colorScheme.onPrimary))
        }
    }
}

@Composable
private fun DateWheel(value: Int, onChange: (Int) -> Unit, range: IntRange, unit: String, modifier: Modifier) {
    val style = MiuixTheme.textStyles.main.copy(fontSize = 26.sp, fontWeight = FontWeight.Normal)
    val label = { number: Int -> number.toString().padStart(2, '0') }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val numberWidth = with(density) { measurer.measure(label(value), style).size.width.toDp() }
    Box(modifier, contentAlignment = Alignment.Center) {
        NumberPicker(value, onChange, range = range, label = label, visibleItemCount = 3, itemHeight = 64.dp,
            textStyle = style, colors = NumberPickerDefaults.colors(selectedTextColor = MiuixTheme.colorScheme.primary))
        // Keep the small unit beside the selected row, separate from the scrolling numbers.
        Box(Modifier.width(numberWidth)) {
            Text(unit, Modifier.align(Alignment.CenterEnd).offset(x = 16.dp, y = (-6).dp), fontSize = 11.sp,
                color = MiuixTheme.colorScheme.primary)
        }
    }
}
