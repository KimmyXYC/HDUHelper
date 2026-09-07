package moe.nepnep.hduhelper.data.notifications

import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.Serializable
import moe.nepnep.hduhelper.data.timetable.*

val reminderMinutes = 0..30
fun reminderTimeLabel(minutes: Int) = if (minutes == 0) "准点" else "提前 $minutes 分钟"

@Serializable
data class NotificationSettings(
    val island: Boolean = false,
    val beforeClass: Boolean = true,
    val afterClass: Boolean = false,
    val beforeMinutes: Int = 10,
    val afterMinutes: Int = 1,
    val beforeExam: Boolean = true,
    val examMinutes: Int = 30,
) {
    fun normalized() = copy(
        examMinutes = examMinutes.takeIf { it in reminderMinutes } ?: 30,
        beforeMinutes = beforeMinutes.takeIf { it in reminderMinutes } ?: 10,
        afterMinutes = afterMinutes.takeIf { it in reminderMinutes } ?: 1,
    )
}

enum class CourseReminderKind(val label: String) { START("上课"), END("下课"), EXAM_START("考试开始") }
enum class CourseReminderPhase { UPCOMING, ELAPSED, EXPIRED }

data class CourseReminder(
    val key: String,
    val accountKey: String,
    val termKey: String,
    val meeting: CourseMeeting?,
    val date: LocalDate,
    val kind: CourseReminderKind,
    val target: Long,
    val begins: Long,
    val courseStart: Long = target,
    val courseEnd: Long = target,
    val exam: ExamArrangement? = null,
) {
    val itemId: String get() = exam?.id ?: requireNotNull(meeting).id
    val title: String get() = exam?.name ?: requireNotNull(meeting).name
    val location: String get() = exam?.place ?: requireNotNull(meeting).location
    val elapsedLabel: String get() = if (exam != null) "考试已开始" else "已${kind.label}"
    val upcomingLabel: String get() = "距${kind.label}"
    val expires: Long get() = target + 60_000
    fun phase(now: Long) = when {
        now >= expires -> CourseReminderPhase.EXPIRED
        now >= target -> CourseReminderPhase.ELAPSED
        else -> CourseReminderPhase.UPCOMING
    }
    override fun toString() = "CourseReminder([redacted])"
}

object CourseReminderRules {
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun reminders(data: TimetableData, settings: NotificationSettings, now: Long): List<CourseReminder> {
        if (data.term.key != data.catalog.current.key) return emptyList()
        val prefs = settings.normalized()
        val result = mutableListOf<CourseReminder>()
        for (week in data.weeks) for (meeting in data.meetings) {
            if (week.week !in meeting.weeks || meeting.weekday !in 1..7) continue
            val first = runCatching { week.startDate }.getOrNull() ?: continue
            val last = runCatching { week.endDate }.getOrNull() ?: continue
            val date = first.plusDays(((meeting.weekday - first.dayOfWeek.value + 7) % 7).toLong())
            if (date > last) continue
            val periods = data.clocks.firstOrNull { it.id == meeting.campusId }?.periods?.associateBy { it.section } ?: continue
            for (run in TimetableRules.runs(meeting.sections)) {
                val times = run.map { section -> periods[section]?.let { p -> runCatching {
                    LocalTime.parse(p.start) to LocalTime.parse(p.end)
                }.getOrNull() } }
                if (times.any { it == null || it.first >= it.second }) continue
                val valid = times.filterNotNull()
                if (valid.zipWithNext().any { (a, b) -> a.second > b.first }) continue
                val start = date.atTime(valid.first().first).atZone(campusZone).toInstant().toEpochMilli()
                val end = date.atTime(valid.last().second).atZone(campusZone).toInstant().toEpochMilli()
                for (kind in listOf(CourseReminderKind.START, CourseReminderKind.END)) {
                    if (kind == CourseReminderKind.START && !prefs.beforeClass || kind == CourseReminderKind.END && !prefs.afterClass) continue
                    val target = if (kind == CourseReminderKind.START) start else end
                    if (target + 60_000 <= now) continue
                    val minutes = if (kind == CourseReminderKind.START) prefs.beforeMinutes else prefs.afterMinutes
                    val key = hash("${data.account}/${data.term.key}/${meeting.id}/$date/$run/$kind/$target")
                    result += CourseReminder(key, hash(data.account), data.term.key, meeting, date, kind, target, target - minutes * 60_000L, start, end)
                }
            }
        }
        if (prefs.beforeExam) for (exam in data.exams.items.filter { it.timed }) {
            val start = exam.startTime!!.atZone(campusZone).toInstant().toEpochMilli()
            val end = exam.endTime!!.atZone(campusZone).toInstant().toEpochMilli()
            if (start + 60_000 <= now) continue
            val key = hash("exam/${data.account}/${data.term.key}/${exam.id}/$start")
            result += CourseReminder(key, hash(data.account), data.term.key, null, exam.startTime!!.toLocalDate(),
                CourseReminderKind.EXAM_START, start, start - prefs.examMinutes * 60_000L, start, end, exam)
        }
        return result.distinctBy { it.key }.sortedWith(compareBy<CourseReminder> { it.begins }.thenBy { it.key })
    }
}

/** Only opaque occurrence hashes and deadlines are persisted, never course/account content. */
@Serializable
data class CourseReminderRecord(val expires: Long, val delivered: Boolean = false, val dismissed: Boolean = false)

object CourseReminderDelivery {
    fun active(reminders: List<CourseReminder>, records: Map<String, CourseReminderRecord>, now: Long) =
        reminders.filter { now >= it.begins && now < it.expires && records[it.key]?.dismissed != true }

    fun ordinaryDue(reminders: List<CourseReminder>, records: Map<String, CourseReminderRecord>, now: Long, alarmAt: Long) =
        active(reminders, records, now).filter { it.begins >= alarmAt && records[it.key]?.delivered != true }

    fun nextBoundary(reminders: List<CourseReminder>, now: Long, island: Boolean): Long? = reminders.asSequence()
        .flatMap { if (island) sequenceOf(it.begins, it.target, it.expires) else sequenceOf(it.begins) }
        .filter { it > now }.minOrNull()
}
