package moe.nepnep.hduhelper.data.island

import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*
import moe.nepnep.hduhelper.data.notifications.CourseReminder
import moe.nepnep.hduhelper.data.notifications.CourseReminderPhase
import moe.nepnep.hduhelper.data.timetable.campusZone

/** Xiaomi's documented OS3 template 9, independently built from the notification protocol. */
object CourseIslandTemplate {
    const val PARAM = "miui.focus.param"
    const val ICON = "miui.focus.pic_hduhelper"
    const val ACTION = "miui.focus.action_hduhelper"
    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    private fun time(value: Long) = Instant.ofEpochMilli(value).atZone(campusZone).format(clock)

    fun json(reminder: CourseReminder, now: Long): String {
        val elapsed = reminder.phase(now) == CourseReminderPhase.ELAPSED
        val state = if (elapsed) "已${reminder.kind.label}" else "距${reminder.kind.label}"
        val location = reminder.meeting.location.ifBlank { "地点待定" }
        return buildJsonObject {
            putJsonObject("param_v2") {
                put("protocol", 1)
                put("business", "hduhelper_course")
                put("updatable", true)
                put("islandFirstFloat", true)
                put("enableFloat", false)
                put("filterWhenNoPermission", false)
                put("timeout", ((reminder.expires - now + 59_999) / 60_000).coerceAtLeast(1))
                put("aodTitle", "$location · ${if (elapsed) state else time(reminder.target) + reminder.kind.label}")
                put("aodPic", ICON)
                putJsonObject("baseInfo") {
                    put("type", 2)
                    put("title", reminder.meeting.name)
                    put("content", "${time(reminder.courseStart)} | ${time(reminder.courseEnd)}")
                }
                putJsonObject("picInfo") { put("type", 1); put("pic", ICON) }
                putJsonObject("hintInfo") {
                    put("type", 2)
                    put("content", if (elapsed) state else "即将${reminder.kind.label}")
                    put("subContent", "地点")
                    put("subTitle", location)
                    putJsonObject("timerInfo") {
                        put("timerType", if (elapsed) 1 else -1)
                        put("timerWhen", reminder.target)
                        put("timerSystemCurrent", now)
                    }
                    putJsonObject("actionInfo") { put("action", ACTION) }
                }
                putJsonObject("param_island") {
                    put("islandProperty", 1)
                    put("islandTimeout", ((reminder.expires - now + 999) / 1000).coerceAtLeast(1))
                    putJsonObject("bigIslandArea") {
                        putJsonObject("imageTextInfoLeft") {
                            put("type", 1)
                            putJsonObject("picInfo") { put("type", 1); put("pic", ICON) }
                            putJsonObject("textInfo") { put("title", location) }
                        }
                        putJsonObject("textInfo") {
                            put("title", if (elapsed) state else time(reminder.target))
                            if (!elapsed) put("content", reminder.kind.label)
                        }
                    }
                    putJsonObject("smallIslandArea") {
                        putJsonObject("picInfo") { put("type", 1); put("pic", ICON) }
                    }
                }
            }
        }.toString()
    }
}
