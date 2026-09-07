package moe.nepnep.hduhelper.data.timetable

import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup

object TimetableParser {
    fun json(body: String): JsonElement = try { Json.parseToJsonElement(body.removePrefix("\uFEFF")) }
    catch (_: IllegalArgumentException) { fail("课表响应格式发生变化") }

    fun objectResponse(body: String): JsonObject {
        val root = json(body)
        if (root is JsonPrimitive && root.isString) {
            if (root.content.contains("权限")) throw TimetableException(TimetableFailure.PERMISSION, "没有访问此课表的权限")
            if (root.content.contains("登录")) throw TimetableException(TimetableFailure.AUTHORIZATION, "教务登录已过期")
        }
        return root as? JsonObject ?: fail("课表响应不是有效的数据对象")
    }

    fun catalog(html: String): TimetableCatalog {
        val doc = Jsoup.parse(html)
        val years = doc.select("select#xnm option").map { it.attr("value") }.filter { it.matches(Regex("\\d{4}")) }.distinct()
        val terms = doc.select("select#xqm option").filter { it.attr("value").isNotBlank() }.map { TermOption(it.attr("value"), it.text()) }
        val year = doc.selectFirst("#xnm option[selected]")?.attr("value") ?: years.firstOrNull()
        val code = doc.selectFirst("#xqm option[selected]")?.attr("value") ?: terms.firstOrNull()?.code
        val term = terms.firstOrNull { it.code == code }
        if (year !in years || term == null) fail("无法读取学校学期选项")
        return TimetableCatalog(years, terms, AcademicTerm(year!!, term.code, term.name))
    }

    fun weeks(expression: String): List<Int> = numbers(expression, true)
    fun sections(expression: String): List<Int> = numbers(expression, false)

    private fun numbers(expression: String, weeks: Boolean): List<Int> {
        val normal = expression.replace('（', '(').replace('）', ')').replace('，', ',').replace('、', ',')
            .replace('－', '-').replace('—', '-').replace('–', '-').replace(Regex("\\s+"), "")
        if (normal.isBlank()) throw IllegalArgumentException("Empty range")
        val pattern = if (weeks) Regex("^(\\d+)(?:-(\\d+))?周?(?:\\((单|双)周?\\))?$") else Regex("^(\\d+)(?:-(\\d+))?节?$")
        val result = mutableSetOf<Int>()
        for (part in normal.split(',')) {
            val match = pattern.matchEntire(part) ?: throw IllegalArgumentException("Unknown range")
            val first = match.groupValues[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid range")
            val last = match.groupValues[2].ifEmpty { match.groupValues[1] }.toIntOrNull() ?: throw IllegalArgumentException("Invalid range")
            // Protocol sanity bounds, not a hard-coded semester or timetable length.
            require(first >= 1 && last >= first && last <= if (weeks) 60 else 48)
            val parity = if (weeks) match.groupValues[3] else ""
            for (value in first..last) if (parity.isEmpty() || (parity == "单" && value % 2 == 1) || (parity == "双" && value % 2 == 0)) result += value
        }
        require(result.isNotEmpty())
        return result.sorted()
    }

    data class Parsed(val meetings: List<CourseMeeting>, val others: List<OtherArrangement>, val warnings: List<String>)

    fun courses(root: JsonObject, account: String, term: AcademicTerm): Parsed {
        val student = root["xsxx"] as? JsonObject ?: throw TimetableException(TimetableFailure.CLOSED, "学校暂无该学期的注册信息")
        if (student.text("XH") != account) fail("课表账号与当前登录账号不一致")
        if (student.text("XNM") != term.year || student.text("XQM") != term.code) fail("学校返回的学期与查询学期不一致")
        if (root.flag("xnxqsfkz")) throw TimetableException(TimetableFailure.CLOSED, "学校暂未开放该学期课表")
        if (!root.flag("xkkg")) throw TimetableException(TimetableFailure.CLOSED, "学校暂未开放课表查询")
        if (!root.flag("jfckbkg")) throw TimetableException(TimetableFailure.CLOSED, "学校要求完成缴费后查询课表")
        val meetings = mutableListOf<CourseMeeting>()
        val others = mutableListOf<OtherArrangement>()
        val warnings = mutableListOf<String>()
        val items = root["kbList"] as? JsonArray ?: fail("学校课表缺少课程列表")
        for (element in items) {
            val row = element as? JsonObject ?: fail("学校课程记录无法识别")
            val name = row.text("kcmc").ifBlank { "未提供课程名称" }
            val teacher = row.text("xm")
            val place = row.text("cdmc")
            val campus = row.text("xqmc")
            val campusId = row.text("xqh_id")
            val weekText = row.text("zcd")
            val sectionText = row.text("jcs").ifBlank { row.text("jc") }
            val weekday = row.text("xqj").toIntOrNull()
            val parsedWeeks = runCatching { weeks(weekText) }.getOrNull()
            val parsedSections = runCatching { sections(sectionText) }.getOrNull()
            val id = stableId(row.text("kch_id"), row.text("jxb_id"), name, teacher, place, campusId, weekText, sectionText, weekday.toString())
            if (weekday !in 1..7 || parsedWeeks == null || parsedSections == null) {
                others += OtherArrangement(id, name, listOf(weekText, row.text("xqjmc"), sectionText).filter { it.isNotBlank() }.joinToString(" · "), teacher, place, campus, row.text("xf"), weekText)
                warnings += "部分课程的时间无法定位，已放入其他安排"
                continue
            }
            val code = row.text("kch_id").ifBlank { row.text("kch") }
            val key = if (code.isBlank()) id else stableId(code, row.text("jxb_id").ifBlank { row.text("jxbmc") })
            meetings += CourseMeeting(id, key, name, teacher, place, campusId, campus, row.text("xf"), weekday!!, parsedSections, parsedWeeks, weekText, sectionText)
        }
        for (key in listOf("sjkList", "jxhjkcList")) {
            val list = root[key]
            if (list != null && list != JsonNull && list !is JsonArray) fail("学校其他课程列表无法识别")
            for (element in (list as? JsonArray).orEmpty()) {
                val row = element as? JsonObject ?: continue
                val description = row.text(if (row.text("sfsjk") == "1") "sjkcgs" else "qtkcgs")
                    .ifBlank { row.text("sjkcgs") }.ifBlank { row.text("qsjsz") }
                val name = row.text("kcmc").ifBlank { if (key == "sjkList") "其他课程" else "教学环节" }
                val rawWeeks = row.text("qsjsz").ifBlank { row.text("zcd") }
                val teacher = row.text("jsxm").ifBlank { row.text("xm") }
                others += OtherArrangement(stableId(key, name, description, teacher, rawWeeks), name, description, teacher, row.text("cdmc"), row.text("xqmc"), row.text("xf"), rawWeeks)
            }
        }
        return Parsed(meetings.distinctBy { it.id }, others.distinctBy { it.id }, warnings.distinct())
    }

    fun calendar(body: String): List<WeekRange> {
        val array = json(body) as? JsonArray ?: fail("学校校历响应无法识别")
        val result = array.map { element ->
            val row = element as? JsonObject ?: fail("学校校历记录无法识别")
            val number = row.text("zs").toIntOrNull()?.takeIf { it in 1..60 } ?: fail("学校校历周次无法识别")
            val dates = row.text("rq").split('/')
            if (dates.size != 2) fail("学校校历日期无法识别")
            val start = runCatching { LocalDate.parse(dates[0]) }.getOrNull() ?: fail("学校校历日期无法识别")
            val end = runCatching { LocalDate.parse(dates[1]) }.getOrNull() ?: fail("学校校历日期无法识别")
            if (end != start.plusDays(6)) fail("学校校历周区间无法识别")
            WeekRange(number, start.toString(), end.toString())
        }.sortedBy { it.week }
        if (result.isEmpty() || result.map { it.week }.distinct().size != result.size) fail("学校尚未提供有效校历")
        if (result.zipWithNext().any { (a, b) -> b.startDate <= a.endDate }) fail("学校校历区间重叠")
        return result
    }

    fun periods(body: String): List<CampusPeriod> {
        val array = json(body) as? JsonArray ?: fail("学校作息响应无法识别")
        return array.map { element ->
            val row = element as? JsonObject ?: fail("学校作息记录无法识别")
            val section = row.text("jcmc").toIntOrNull()?.takeIf { it in 1..48 } ?: fail("学校作息节次无法识别")
            val start = row.text("qssj")
            val end = row.text("jssj")
            if (runCatching { LocalTime.parse(start) < LocalTime.parse(end) }.getOrDefault(false).not()) fail("学校作息时间无法识别")
            CampusPeriod(section, start, end, row.text("rsdmc"))
        }.sortedBy { it.section }.also { if (it.map { p -> p.section }.distinct().size != it.size) fail("学校作息节次重复") }
    }

    private fun JsonObject.flag(key: String): Boolean = when (text(key)) {
        "true", "1" -> true
        "false", "0" -> false
        else -> fail("学校课表状态无法识别")
    }
    internal fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.takeUnless { it.content == "null" }?.content?.let { Jsoup.parse(it.replace("<br>", " ")).text() }.orEmpty()
    internal fun stableId(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun fail(message: String): Nothing = throw TimetableException(TimetableFailure.PROTOCOL, message)
}
