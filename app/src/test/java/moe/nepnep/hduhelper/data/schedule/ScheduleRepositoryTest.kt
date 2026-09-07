package moe.nepnep.hduhelper.data.schedule

import java.nio.file.Files
import java.time.LocalDate
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScheduleRepositoryTest {
    private val event = ScheduleEvent("私人标题", start = "2026-09-07T10:00", end = "2026-09-07T11:00", repeat = ScheduleRepeat.DAILY, reminderMinutes = 0)
    private class Store(var book: ScheduleBook = ScheduleBook()) : ScheduleStore {
        var failWrite = false
        var failRead = false
        override fun load(): ScheduleBook { check(!failRead); return book }
        override fun save(book: ScheduleBook) { check(!failWrite); this.book = book }
    }

    @Test fun failedSaveAndFailedLoadPreserveExistingData() = runTest {
        val store = Store(ScheduleBook(listOf(ScheduleSeries("id", event))))
        val repo = ScheduleRepository(store, StandardTestDispatcher(testScheduler))
        repo.load()
        store.failWrite = true
        assertTrue(runCatching { repo.delete("id", null) }.isFailure)
        assertEquals(1, repo.state.value.book.series.size)
        assertEquals(1, store.book.series.size)
        val failed = ScheduleRepository(store.apply { failRead = true }, StandardTestDispatcher(testScheduler))
        assertTrue(runCatching { failed.save(ScheduleEditor(null, null, event), event) }.isFailure)
        assertFalse(failed.state.value.loaded)
        assertNotNull(failed.state.value.error)
        assertEquals(1, store.book.series.size)
    }

    @Test fun singleChangesAndSeriesResetRequireConfirmation() = runTest {
        val store = Store(ScheduleBook(listOf(ScheduleSeries("id", event))))
        val repo = ScheduleRepository(store, StandardTestDispatcher(testScheduler))
        val date = LocalDate.of(2026, 9, 8)
        val exception = event.onDate(date).copy(title = "单次修改", repeat = ScheduleRepeat.NEVER)
        repo.save(ScheduleEditor("id", date, exception), exception)
        repo.save(ScheduleEditor("id", null, event), event.copy(title = "整组标题"))
        assertEquals("单次修改", repo.state.value.book.series.single().exceptions.single().replacement!!.title)
        val changedRule = event.copy(repeat = ScheduleRepeat.WEEKLY)
        assertTrue(runCatching { repo.save(ScheduleEditor("id", null, event), changedRule) }.isFailure)
        repo.save(ScheduleEditor("id", null, event), changedRule, clearExceptions = true)
        assertTrue(repo.state.value.book.series.single().exceptions.isEmpty())
        repo.delete("id", date)
        assertNull(repo.state.value.book.series.single().exceptions.single().replacement)
        repo.delete("id", null)
        assertTrue(repo.state.value.book.series.isEmpty())
    }

    @Test fun staleAndDuplicateAlarmsCannotClaimNotification() = runTest {
        val store = Store(ScheduleBook(listOf(ScheduleSeries("id", event), ScheduleSeries("second", event))))
        val repo = ScheduleRepository(store, StandardTestDispatcher(testScheduler))
        val at = ScheduleRules.reminderTime(event)!!
        assertEquals(2, repo.claimReminder(0, at).size)
        assertTrue(repo.claimReminder(0, at).isEmpty())
        val restarted = ScheduleRepository(store, StandardTestDispatcher(testScheduler))
        assertTrue(restarted.claimReminder(0, at).isEmpty())
        restarted.save(ScheduleEditor("id", null, event), event.copy(title = "changed"))
        assertTrue(restarted.claimReminder(0, at + 86_400_000).isEmpty())
        assertEquals(2, restarted.claimReminder(1, at + 86_400_000).size)
        restarted.delete("id", null)
        restarted.delete("second", null)
        assertTrue(restarted.claimReminder(3, at + 2 * 86_400_000).isEmpty())
    }

    @Test fun encryptionRoundTripAndCorruptionNeverErasesFile() {
        val dir = Files.createTempDirectory("schedule-store-test").toFile()
        try {
            val file = dir.resolve("calendar.enc")
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val store = EncryptedScheduleStore(file) { key }
            val book = ScheduleBook(listOf(ScheduleSeries("id", event)))
            store.save(book)
            assertEquals(book, store.load())
            assertFalse(file.readBytes().decodeToString().contains(event.title))
            val corrupt = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            file.writeBytes(corrupt)
            assertTrue(runCatching { store.load() }.isFailure)
            assertArrayEquals(corrupt, file.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
