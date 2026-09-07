package moe.nepnep.hduhelper.data.timetable

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ExamArrangement(
    val id: String,
    val name: String,
    val examName: String = "",
    val rawTime: String = "",
    val start: String? = null,
    val end: String? = null,
    val location: String = "",
    val campus: String = "",
    val seat: String = "",
    val notes: String = "",
) {
    val startTime: LocalDateTime? get() = start?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    val endTime: LocalDateTime? get() = end?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    val timed: Boolean get() = startTime?.let { s -> endTime?.let { it > s && it.toLocalDate() == s.toLocalDate() } } == true
    val place: String get() = listOf(campus, location, seat.takeIf { it.isNotBlank() }?.let { "座号 $it" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
    override fun toString() = "ExamArrangement([redacted])"
}

@Serializable
data class ExamSnapshot(val items: List<ExamArrangement> = emptyList(), val updatedAt: Long? = null, val failed: Boolean = false) {
    val message: String? get() = when {
        failed -> if (updatedAt == null) "考试同步失败，请下拉重试" else "考试更新失败，显示上次同步的安排"
        updatedAt == null -> "考试尚未同步，请下拉刷新"
        else -> null
    }
    override fun toString() = "ExamSnapshot([redacted])"
}

object ExamRules {
    fun onDate(data: TimetableData, date: LocalDate) = data.exams.items.filter { it.timed && it.startTime?.toLocalDate() == date }
    fun week(data: TimetableData, week: Int): List<ExamArrangement> {
        val range = weeks(data).firstOrNull { it.week == week } ?: return emptyList()
        return data.exams.items.filter { it.timed && it.startTime!!.toLocalDate() in range.startDate..range.endDate }
    }
    fun weeks(data: TimetableData, showExams: Boolean = true): List<WeekRange> {
        if (!showExams) return data.weeks
        val dates = data.exams.items.filter { it.timed }.map { it.startTime!!.toLocalDate() }
        if (dates.isEmpty()) return data.weeks
        val anchor = data.weeks.minByOrNull { it.week }
        val first = anchor?.startDate ?: dates.min().with(java.time.DayOfWeek.MONDAY)
        val number = anchor?.week ?: 1
        val extra = dates.filter { date -> data.weeks.none { date in it.startDate..it.endDate } }.map { date ->
            val offset = Math.floorDiv(ChronoUnit.DAYS.between(first, date), 7L)
            val start = first.plusWeeks(offset)
            WeekRange(number + offset.toInt(), start.toString(), start.plusDays(6).toString())
        }
        return (data.weeks + extra).distinctBy { it.week }.sortedBy { it.week }
    }
}

internal data class ExamPage(val items: List<ExamArrangement>, val page: Int, val pages: Int, val total: Int)

internal object ExamParser {
    fun page(body: String, account: String, term: AcademicTerm): ExamPage {
        val root = TimetableParser.objectResponse(body)
        fun number(key: String) = root[key]?.jsonPrimitive?.intOrNull
            ?: throw TimetableException(TimetableFailure.PROTOCOL, "考试分页信息不可用")
        val items = root["items"] as? JsonArray ?: throw TimetableException(TimetableFailure.PROTOCOL, "考试响应格式发生变化")
        val parsed = items.map { element ->
            val row = element as? JsonObject ?: throw TimetableException(TimetableFailure.PROTOCOL, "考试记录格式发生变化")
            fun text(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            if (text("xh").let { it.isNotBlank() && it != account } || text("xnm").let { it.isNotBlank() && it != term.year } ||
                text("xqm").let { it.isNotBlank() && it != term.code }) throw TimetableException(TimetableFailure.PROTOCOL, "考试账号或学期不匹配")
            val raw = text("kssj")
            val normalized = raw.replace('（', '(').replace('）', ')').replace('：', ':').replace('—', '-').replace('–', '-').replace(Regex("\\s+"), "")
            val match = Regex("^(\\d{4}-\\d{2}-\\d{2})\\((\\d{2}:\\d{2})-(\\d{2}:\\d{2})\\)$").matchEntire(normalized)
            val times = match?.let { m -> runCatching {
                val start = LocalDateTime.parse("${m.groupValues[1]}T${m.groupValues[2]}")
                val end = LocalDateTime.parse("${m.groupValues[1]}T${m.groupValues[3]}")
                require(end > start)
                start.toString() to end.toString()
            }.getOrNull() }
            val identity = listOf(text("kch"), text("jxbmc"), text("ksmc"), text("sjbh"), raw, text("cdmc"), text("zwh"))
            ExamArrangement(TimetableParser.stableId(*identity.toTypedArray()), text("kcmc").ifBlank { "未命名考试" }, text("ksmc"), raw,
                times?.first, times?.second, text("cdmc").ifBlank { text("cdjc") }, text("cdxqmc"), text("zwh"), text("ksbz"))
        }
        return ExamPage(parsed, number("currentPage"), number("totalPage"), number("totalCount"))
    }
}
