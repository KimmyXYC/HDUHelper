package moe.nepnep.hduhelper.data.auth

import java.nio.file.Files
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class EncryptedSessionStoreTest {
    @Test fun roundTripUsesCiphertextAndNewNonceEachWrite() {
        val directory = Files.createTempDirectory("hdu-auth-test").toFile()
        try {
            val file = directory.resolve("session.enc")
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val store = EncryptedSessionStore(file) { key }
            val cookie = StoredCookie("session", "synthetic-cookie-value", "i.hdu.edu.cn", "/", Long.MAX_VALUE, true, true, true, false)
            val session = StoredSession("student01", "synthetic-password", UserProfile("student01", "测试用户"), listOf(cookie), 2, "notice")
            store.save(session)
            val first = file.readBytes()
            assertFalse(first.toString(Charsets.ISO_8859_1).contains("synthetic-password"))
            assertFalse(first.toString(Charsets.ISO_8859_1).contains("synthetic-cookie-value"))
            assertEquals(session, store.load())
            store.save(session)
            assertFalse(first.contentEquals(file.readBytes()))
            assertFalse(directory.resolve("session.enc.tmp").exists())
            store.clear()
            assertNull(store.load())
        } finally { directory.deleteRecursively() }
    }

    @Test fun changedKeyOrCorruptedCiphertextCannotBeRead() {
        val directory = Files.createTempDirectory("hdu-auth-test").toFile()
        try {
            val file = directory.resolve("session.enc")
            fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val firstKey = key()
            val store = EncryptedSessionStore(file) { firstKey }
            store.save(StoredSession(password = "synthetic-password"))
            try { EncryptedSessionStore(file) { key() }.load(); fail("Wrong key must fail") }
            catch (_: java.security.GeneralSecurityException) { }
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            try { store.load(); fail("Tampering must fail") }
            catch (_: java.security.GeneralSecurityException) { }
        } finally { directory.deleteRecursively() }
    }
}
