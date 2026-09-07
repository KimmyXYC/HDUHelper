package moe.nepnep.hduhelper.data.timetable

import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TimetableStoreTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun encryptionIsolationCorruptionAndClear() {
        val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val dir=temp.newFolder()
        val store=EncryptedTimetableStore(dir) { key }
        val first=data(meeting("a")).copy(account="synthetic-account")
        store.save(first)
        val file=dir.listFiles()!!.single()
        val bytes=file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains(first.account))
        assertFalse(file.name.contains(first.account))
        assertEquals(first,store.load(first.account,term))
        assertEquals(first,EncryptedTimetableStore(dir) { key }.load(first.account,term))
        assertNull(store.load("someone-else",term))
        val old=first.copy(term=AcademicTerm("2025","12"),updatedAt=2000)
        store.save(old)
        assertEquals(old,store.load(first.account))
        assertEquals(first,store.load(first.account,term))
        val damaged=file.readBytes();damaged[damaged.lastIndex]=(damaged.last().toInt() xor 1).toByte();file.writeBytes(damaged)
        assertNull(store.load(first.account,term))
        assertEquals(old,store.load(first.account))
        store.clear()
        assertNull(store.load(first.account))
        assertTrue(dir.listFiles()!!.isEmpty())
    }
}
