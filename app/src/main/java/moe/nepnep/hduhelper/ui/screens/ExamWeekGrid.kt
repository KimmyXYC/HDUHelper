package moe.nepnep.hduhelper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.nepnep.hduhelper.data.timetable.*
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ExamWeekGrid(data: TimetableData, week: Int, settings: TimetableSettings, clock: CampusClock?, onClick: (List<ExamGridItem>) -> Unit) {
    val items = remember(data, week, settings) { ExamGridRules.items(data, week, settings) }
    val axis = remember(clock, items) { ExamGridRules.axis(clock, items) }
    val fragments = remember(items, axis) { ExamGridRules.fragments(items, axis) }
    val days = if (settings.showWeekend) 7 else 5
    val rows = axis.periods.associate { it.section to axis.position(ExamGridRules.minutes(it.start)!!).dp }
    val breaks = axis.periods.zipWithNext().mapNotNull { (previous, next) ->
        if (previous.group.isNotBlank() && next.group.isNotBlank() && previous.group != next.group) {
            (rows.getValue(next.section) - 24.dp) to timetableBreakLabel(next.group)
        } else null
    }
    val height = maxOf(axis.height, fragments.values.flatten().maxOfOrNull { it.second } ?: 0f).dp
    val labels = if (axis.periods.isEmpty()) axis.points.map { (minute, top) ->
        top.dp to "%02d:%02d".format(minute / 60, minute % 60)
    } else emptyList()
    TimetableGridFrame(days, height, rows, axis.periods.associateBy { it.section }, breaks,
        Modifier.testTag("timetable_grid_$week"), timeLabels = labels) { column ->
        for (item in items) for ((top, bottom) in fragments[item.key].orEmpty()) {
            val height = bottom - top
            val conflicts = ExamGridRules.visibleConflicts(item, items, axis)
            val dark = MiuixTheme.colorScheme.surface.luminance() < .4f
            val exam = item.exam
            TimetableArrangementCard(item.title,
                item.course?.courseKey ?: data.meetings.firstOrNull { it.name == exam?.name }?.courseKey ?: exam!!.name,
                exam?.location ?: item.course?.location.orEmpty(), item.course?.teacher.orEmpty(),
                exam != null || item.state == MeetingState.CURRENT, dark, settings,
                Modifier.offset(x = 36.dp + column * (item.weekday - 1), y = top.dp)
                    .width(column).height(height.dp).padding(2.dp).testTag("grid_${item.key}"), conflicts.size,
                badge = if (exam != null) "考试" else null,
                time = exam?.let { "${it.startTime!!.toLocalTime()}–${it.endTime!!.toLocalTime()}" },
                onClick = { onClick(conflicts) })
        }
    }
    // Invalid campus clocks must not silently hide otherwise readable courses.
    val shown = items.mapNotNull { it.course?.id }.toSet()
    for (card in TimetableRules.cards(data, week, settings).filter { it.meeting.id !in shown }) {
        TextButton("${card.meeting.name} · 时间暂不可用", {
            onClick(listOf(ExamGridItem("course/${card.meeting.id}", card.meeting.weekday, 0, 0, course = card.meeting)))
        })
    }
}
