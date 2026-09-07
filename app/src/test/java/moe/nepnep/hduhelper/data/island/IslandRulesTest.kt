package moe.nepnep.hduhelper.data.island

import kotlinx.serialization.json.*
import moe.nepnep.hduhelper.data.notifications.*
import moe.nepnep.hduhelper.data.timetable.CourseMeeting
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class IslandRulesTest {
    @Test fun authorizationAndLoadedHooksAreSeparateRequirements() {
        val allowed = IslandCapability(supported = true, framework = true, scoped = true)
        assertTrue(allowed.visible)
        assertFalse(allowed.ready)
        assertTrue(allowed.copy(hookReady = true).ready)
        assertFalse(allowed.copy(framework = false, hookReady = true).visible)
        assertFalse(allowed.copy(scoped = false, hookReady = true).visible)
        assertFalse(allowed.copy(supported = false, hookReady = true).ready)
    }

    @Test fun overlappingMuteRequestsRestoreTheOriginalModeAfterTheLastCourse() {
        val first = MuteOwnership().acquire("a", 100, 1, 1)
        val both = first.acquire("b", 200, 2, 0)
        assertEquals(1, both.originalMode)
        val remaining = both.expire(100)
        assertTrue(remaining.owned)
        assertEquals(setOf("b"), remaining.ends.keys)
        val ended = remaining.expire(200)
        assertFalse(ended.owned)
        assertTrue(ended.shouldRestore(0))
        assertEquals(1, ended.originalMode)
    }

    @Test fun existingSilenceAndUserInterventionNeverBecomeOwnedSoundChanges() {
        assertFalse(MuteOwnership().acquire("a", 100, 1, 0).owned)
        val active = MuteOwnership().acquire("a", 100, 1, 2)
        val userChanged = active.userChangedMode()
        assertFalse(userChanged.shouldRestore(0))
        assertFalse(active.shouldRestore(2))
        assertFalse(active.acquire("b", 200, 2, 1).owned)
    }

    @Test fun staleAndUnboundedMuteRequestsAreRejected() {
        assertFalse(MuteOwnership().acquire("a", 1, 1, 2).owned)
        assertFalse(MuteOwnership().acquire("a", 24 * 60 * 60_000L + 2, 1, 2).owned)
        assertFalse(MuteOwnership().acquire("", 100, 1, 2).owned)
    }

    private fun reminder(kind: CourseReminderKind) = CourseReminder("opaque", "accountHash", "term",
        CourseMeeting("id", "course", "高等数学\"\\\n", location = "教科A-101", weekday = 1,
            sections = listOf(1, 2), weeks = listOf(1), rawWeeks = "1", rawSections = "1-2"),
        LocalDate.of(2026, 9, 14), kind, 1_000_000, 940_000, 1_000_000, 1_060_000)

    @Test fun templateHasIndependentLocationTimeAndRealSystemTimer() {
        val reminder = reminder(CourseReminderKind.START)
        val payload = Json.parseToJsonElement(CourseIslandTemplate.json(reminder, 940_000)).jsonObject["param_v2"]!!.jsonObject
        assertEquals(reminder.title, payload["baseInfo"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        val hint = payload["hintInfo"]!!.jsonObject
        assertEquals(2, hint["type"]!!.jsonPrimitive.int)
        assertEquals("教科A-101", hint["subTitle"]!!.jsonPrimitive.content)
        assertEquals(-1, hint["timerInfo"]!!.jsonObject["timerType"]!!.jsonPrimitive.int)
        assertEquals(reminder.target, hint["timerInfo"]!!.jsonObject["timerWhen"]!!.jsonPrimitive.long)
        val island = payload["param_island"]!!.jsonObject
        assertEquals(120, island["islandTimeout"]!!.jsonPrimitive.int)
        val big = island["bigIslandArea"]!!.jsonObject
        assertEquals("教科A-101", big["imageTextInfoLeft"]!!.jsonObject["textInfo"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("上课", big["textInfo"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse(payload.toString().contains("accountHash"))
    }

    @Test fun startAndEndWindowsChangePhaseWithoutExtendingTheExpiry() {
        for (kind in CourseReminderKind.entries) {
            val reminder = reminder(kind)
            val payload = Json.parseToJsonElement(CourseIslandTemplate.json(reminder, reminder.target + 5000)).jsonObject["param_v2"]!!.jsonObject
            val hint = payload["hintInfo"]!!.jsonObject
            assertEquals("已${kind.label}", hint["content"]!!.jsonPrimitive.content)
            assertEquals(1, hint["timerInfo"]!!.jsonObject["timerType"]!!.jsonPrimitive.int)
            assertEquals(55, payload["param_island"]!!.jsonObject["islandTimeout"]!!.jsonPrimitive.int)
        }
    }
}
