package moe.nepnep.hduhelper.data.grades

import kotlinx.serialization.json.*
import moe.nepnep.hduhelper.data.timetable.*
import org.jsoup.Jsoup

object GradeParser {
    data class Page(val page: Int, val pages: Int, val total: Int, val items: List<CourseGrade>)

    fun page(body: String, account: String, term: AcademicTerm): Page {
        val root = try { Json.parseToJsonElement(body.removePrefix("\uFEFF")) } catch (_: IllegalArgumentException) { fail() }
        if (root is JsonPrimitive && root.isString) {
            if (root.content.contains("登录")) throw TimetableException(TimetableFailure.AUTHORIZATION, "教务登录已过期")
            if (root.content.contains("权限")) throw TimetableException(TimetableFailure.PERMISSION, "没有访问成绩的权限")
        }
        val obj = root as? JsonObject ?: fail()
        fun integer(key: String) = obj.text(key).toIntOrNull() ?: fail()
        val rows = obj["items"] as? JsonArray ?: fail()
        val items = rows.map { element ->
            val row = element as? JsonObject ?: fail()
            if (row.text("xh") != account || row.text("xnm") != term.year || row.text("xqm") != term.code) fail()
            val key = row.text("key").ifBlank { row.text("row_id") }
            if (key.isBlank()) fail()
            CourseGrade(TimetableParser.stableId(account, term.key, key), row.text("jxb_id"), row.text("xh_id"),
                row.text("kcmc").ifBlank { "未提供课程名称" }, row.text("xf"), row.text("cj"), row.text("jd"),
                row.text("kcxzmc"), when (row.text("cjsfzf")) {
                    "是", "1" -> true
                    "否", "0", "" -> false
                    else -> fail()
                })
        }
        return Page(integer("currentPage"), integer("totalPage"), integer("totalResult"), items)
    }

    fun components(html: String): List<GradeComponent> {
        val doc = Jsoup.parse(html)
        val table = doc.selectFirst("table#subtab") ?: fail()
        val rows = table.select("tbody tr").mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size < 3) fail()
            val label = cells[0].text().replace("【", "").replace("】", "").trim()
            val score = cells[2].text().replace('\u00a0', ' ').trim()
            if (label.isBlank() || score.isBlank() || label in setOf("总评", "总评成绩", "最终成绩", "绩点", "最终绩点")) null
            else GradeComponent(if (label.endsWith("成绩")) label else "${label}成绩", score)
        }
        val order = listOf("平时成绩", "期中成绩", "期末成绩")
        return rows.sortedBy { order.indexOf(it.name).takeIf { i -> i >= 0 } ?: order.size }
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim().orEmpty()
    internal fun fail(): Nothing = throw TimetableException(TimetableFailure.PROTOCOL, "成绩响应格式发生变化，请稍后重试")
}
