package moe.nepnep.hduhelper.widget

import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import moe.nepnep.hduhelper.data.schedule.*
import moe.nepnep.hduhelper.data.settings.ThemeMode
import org.junit.Assert.*
import org.junit.Test

class WidgetSnapshotStoreTest {
    @Test fun independentReadersSeeNewEncryptedSnapshotsAndAccountRemoval() {
        val directory = Files.createTempDirectory("widget-snapshot").toFile()
        try {
            val file = File(directory, "snapshot.enc")
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val writer = WidgetSnapshotStore(file) { key }
            val reader = WidgetSnapshotStore(file) { create -> assertFalse(create); key }
            val event = ScheduleEvent(title = "synthetic private calendar entry", start = "2026-09-08T09:00", end = "2026-09-08T10:00")
            val first = WidgetSnapshot(account = "synthetic-account", book = ScheduleBook(listOf(ScheduleSeries("local", event))))
            writer.save(first)
            assertEquals(first, reader.load())
            assertFalse(file.readBytes().decodeToString().contains(event.title))
            val next = WidgetSnapshot(book = first.book, theme = ThemeMode.DARK)
            writer.save(next)
            assertEquals(next, reader.load())
            assertNull(reader.load()!!.account)
            assertEquals(first.book, reader.load()!!.book)
            assertNotEquals(first.revision, next.revision)
            writer.clear()
            assertNull(reader.load())
        } finally { directory.deleteRecursively() }
    }
    @Test fun unreadableSnapshotIsPreservedAndNeverCreatesAKeyOnRead() {
        val directory = Files.createTempDirectory("widget-corrupt").toFile()
        try {
            val file = File(directory, "snapshot.enc").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val reader = WidgetSnapshotStore(file) { error("A corrupt snapshot must not create keys") }
            assertTrue(runCatching { reader.load() }.isFailure)
            assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        } finally { directory.deleteRecursively() }
    }
}
