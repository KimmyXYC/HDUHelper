package moe.nepnep.hduhelper.data.schedule

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class ScheduleRepeat(val label: String) {
    NEVER("永不"), DAILY("每天"), WEEKDAYS("周一至周五"), WEEKLY("每周"), MONTHLY("每月"), YEARLY("每年")
}

enum class ScheduleReminder(val minutes: Int?, val label: String) {
    NONE(null, "无"), START(0, "开始时"), FIVE(5, "提前 5 分钟"), TEN(10, "提前 10 分钟"),
    FIFTEEN(15, "提前 15 分钟"), THIRTY(30, "提前 30 分钟"), HOUR(60, "提前 1 小时"), DAY(1440, "提前 1 天")
}

/** Local Beijing times. End is exclusive, including for all-day events. */
@Serializable
data class ScheduleEvent(
    val title: String = "",
    val location: String = "",
    val start: String,
    val end: String,
    val allDay: Boolean = false,
    val repeat: ScheduleRepeat = ScheduleRepeat.NEVER,
    val reminderMinutes: Int? = null,
    val notes: String = "",
) {
    val startTime: LocalDateTime get() = LocalDateTime.parse(start)
    val endTime: LocalDateTime get() = LocalDateTime.parse(end)
    fun validationError(): String? = when {
        title.isBlank() -> "请填写日程标题"
        !endTime.isAfter(startTime) -> "结束时间必须晚于开始时间"
        allDay && (startTime.toLocalTime() != LocalTime.MIDNIGHT || endTime.toLocalTime() != LocalTime.MIDNIGHT) -> "全天日程需要完整日期"
        ScheduleReminder.entries.none { it.minutes == reminderMinutes } -> "请选择有效的提醒时间"
        else -> null
    }
    override fun toString() = "ScheduleEvent([redacted])"
}

@Serializable
data class ScheduleException(val originalDate: String, val replacement: ScheduleEvent? = null)

@Serializable
data class ScheduleSeries(
    val id: String = UUID.randomUUID().toString(),
    val event: ScheduleEvent,
    val exceptions: List<ScheduleException> = emptyList(),
)

@Serializable
data class ScheduleBook(
    val series: List<ScheduleSeries> = emptyList(),
    val revision: Long = 0,
    val deliveredThrough: Long = 0,
)

data class ScheduleOccurrence(val seriesId: String, val originalDate: LocalDate, val event: ScheduleEvent) {
    val key: String get() = "$seriesId/$originalDate"
}

data class ScheduleEditor(val seriesId: String?, val originalDate: LocalDate?, val initial: ScheduleEvent)

fun newScheduleEvent(date: LocalDate, now: LocalDateTime): ScheduleEvent {
    val minutes = now.hour * 60 + now.minute
    val rounded = (minutes / 15 + 1) * 15
    val start = date.atStartOfDay().plusMinutes(rounded.toLong())
    return ScheduleEvent(start = start.toString(), end = start.plusHours(1).toString())
}

fun ScheduleEvent.onDate(date: LocalDate): ScheduleEvent {
    val days = ChronoUnit.DAYS.between(startTime.toLocalDate(), date)
    return copy(start = startTime.plusDays(days).toString(), end = endTime.plusDays(days).toString())
}
