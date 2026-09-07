package moe.nepnep.hduhelper.data.timetable

import java.time.Duration
import java.time.LocalDateTime

data class ExamAgenda(
    val featured: ExamArrangement?,
    val upcoming: List<ExamArrangement>,
    val untimed: List<ExamArrangement>,
    val ended: List<ExamArrangement>,
)

object ExamAgendaRules {
    fun ongoing(exam: ExamArrangement, now: LocalDateTime): Boolean =
        exam.timed && now >= exam.startTime!! && now < exam.endTime!!

    fun arrange(items: List<ExamArrangement>, now: LocalDateTime): ExamAgenda {
        val timed = items.filter { it.timed }
        val pending = timed.filter { it.endTime!! > now }
            .sortedWith(compareBy<ExamArrangement> { it.startTime }.thenBy { it.id })
        return ExamAgenda(
            pending.firstOrNull(), pending.drop(1),
            items.filterNot { it.timed }.sortedWith(compareBy<ExamArrangement> { it.name }.thenBy { it.id }),
            timed.filter { it.endTime!! <= now }
                .sortedWith(compareBy<ExamArrangement> { it.startTime }.thenBy { it.id }),
        )
    }

    fun countdown(exam: ExamArrangement, now: LocalDateTime): String {
        val target = if (ongoing(exam, now)) exam.endTime else exam.startTime
        val seconds = target?.let { Duration.between(now, it).seconds.coerceAtLeast(0) } ?: 0
        return "${seconds / 86400}天${seconds / 3600 % 24}小时${seconds / 60 % 60}分钟${seconds % 60}秒"
    }
}
