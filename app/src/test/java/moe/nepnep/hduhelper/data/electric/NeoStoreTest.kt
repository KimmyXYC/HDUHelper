package moe.nepnep.hduhelper.data.electric

import java.io.File
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NeoStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun encryptedTokensRoundTripTamperDetectionAndDeletion() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val file = File(temporary.root, "neo.enc")
        val store = EncryptedNeoStore(file) { key }
        store.save(NeoTokens("synthetic", "private-access-token", "private-refresh-token", 100))
        assertFalse(file.readBytes().decodeToString().contains("private-access-token"))
        assertEquals("private-refresh-token", store.load()!!.refreshToken)
        val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        try { store.load(); fail() } catch (_: java.security.GeneralSecurityException) { }
        store.clear(); assertNull(store.load()); assertFalse(file.exists())
    }
}
