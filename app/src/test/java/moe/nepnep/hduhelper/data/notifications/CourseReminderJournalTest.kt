package moe.nepnep.hduhelper.data.notifications

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CourseReminderJournalTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun restartPreservesDeliveryDismissalAndRejectsStaleAlarm() {
        val file = File(directory.root, "journal.json")
        val key = CourseReminderRules.hash("synthetic occurrence")
        val journal = ReminderJournal(owner = CourseReminderRules.hash("synthetic account"), live = true,
            records = mapOf(key to CourseReminderRecord(2000, true, true)), posted = mapOf(key to 400001),
            nextId = 400002, token = "new", alarmAt = 1500)
        CourseReminderJournalStore(file).save(journal)
        val restored = CourseReminderJournalStore(file).read()
        assertEquals(journal, restored)
        assertTrue(restored.accepts("new", 1500))
        assertFalse(restored.accepts("old", 1500))
        assertFalse(restored.accepts("new", 1499))
        assertFalse(file.readText().contains("synthetic"))
    }

    @Test fun disablingLiveCancelsAllOngoingNotificationsAndRetainsDismissalAcrossToggles() {
        val record = CourseReminderRecord(2000, true, true)
        val journal = ReminderJournal(owner = "owner", live = true, records = mapOf("key" to record), posted = mapOf("key" to 400000))
        val disabled = journal.reconcile("owner", setOf("key"), emptySet(), false, 1000)
        assertTrue(disabled.posted.isEmpty())
        assertEquals(record, disabled.records["key"])
        val allOff = disabled.reconcile("owner", emptySet(), emptySet(), false, 1001)
        assertEquals(record, allOff.records["key"])
        assertEquals(record, allOff.reconcile("owner", setOf("key"), emptySet(), true, 1002).records["key"])
    }

    @Test fun accountInvalidationClearsOldRecordsAndPostedNotifications() {
        val journal = ReminderJournal(owner = "old", records = mapOf("key" to CourseReminderRecord(2000, true)), posted = mapOf("key" to 400000))
        for (owner in listOf(null, "new")) {
            val result = journal.reconcile(owner, setOf("key"), setOf("key"), true, 1000)
            assertTrue(result.posted.isEmpty()); assertTrue(result.records.isEmpty())
        }
        assertTrue(journal.reconcile("old", emptySet(), emptySet(), true, 2000).records.isEmpty())
    }

    @Test fun coldProcessStartupCanClaimDueAlarmBeforeReceiverAndInvalidatesOldToken() {
        val waiting = ReminderJournal(token = "pending", alarmAt = 1000)
        assertNull(waiting.dueAlarmAt(999))
        assertEquals(1000L, waiting.dueAlarmAt(1000))
        assertEquals(1000L, waiting.dueAlarmAt(1050))
        val rescheduled = waiting.copy(token = "next", alarmAt = 2000)
        assertFalse(rescheduled.accepts("pending", 1000))
        assertNull(rescheduled.dueAlarmAt(1050))
    }

    @Test fun missingFileStartsEmptyButCorruptionIsNotSilentlyReset() {
        val file = File(directory.root, "journal.json")
        val store = CourseReminderJournalStore(file)
        assertEquals(ReminderJournal(), store.read())
        file.writeText("invalid")
        assertTrue(runCatching { store.read() }.isFailure)
    }
}
