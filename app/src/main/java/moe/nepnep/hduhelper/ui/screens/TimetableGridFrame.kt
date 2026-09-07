package moe.nepnep.hduhelper.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.nepnep.hduhelper.data.timetable.CampusPeriod
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun timetableBreakLabel(group: String) = when {
    group.contains("下午") -> "午休"
    group.contains("晚") -> "晚休"
    else -> "休息"
}

/** Shared grid chrome for course-only and exam weeks. All dimensions match the course grid. */
@Composable
internal fun TimetableGridFrame(
    days: Int,
    height: Dp,
    rows: Map<Int, Dp>,
    periods: Map<Int, CampusPeriod>,
    breaks: List<Pair<Dp, String>>,
    modifier: Modifier = Modifier,
    timeLabels: List<Pair<Dp, String>> = emptyList(),
    content: @Composable BoxWithConstraintsScope.(Dp) -> Unit,
) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < .4f
    val line = if (dark) Color(0xFF28292D) else Color(0xFFE2E5EB)
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    BoxWithConstraints(modifier.fillMaxWidth().height(height)) {
        val columnWidth = (maxWidth - 36.dp) / days
        Canvas(Modifier.matchParentSize()) {
            for (column in 0..days) {
                val x = (36.dp + columnWidth * column).toPx()
                drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            }
            for (y in rows.values) drawLine(line, Offset(0f, y.toPx()), Offset(size.width, y.toPx()), 1.dp.toPx())
            drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        }
        for ((section, top) in rows) {
            Column(Modifier.offset(y = top).width(36.dp).height(60.dp).padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(section.toString(), fontSize = 14.sp)
                periods[section]?.let { period ->
                    Text(period.start, fontSize = 9.sp, color = muted, lineHeight = 12.sp)
                    Text(period.end, fontSize = 9.sp, color = muted, lineHeight = 12.sp)
                }
            }
        }
        for ((top, label) in breaks) {
            Box(Modifier.offset(y = top).fillMaxWidth().height(24.dp).background(if (dark) Color(0xFF202125) else Color(0xFFEEF0F4)), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 10.sp, color = muted)
            }
        }
        for ((top, label) in timeLabels) Text(label, Modifier.offset(y = top).width(36.dp).padding(top = 6.dp), fontSize = 9.sp, color = muted)
        content(columnWidth)
    }
}
