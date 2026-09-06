package moe.nepnep.hduhelper.data.auth

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The key provider is injectable so encryption and corruption recovery can be tested on the JVM. */
class EncryptedSessionStore(
    private val file: File,
    private val keyProvider: () -> SecretKey,
) : SessionStore {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun load(): StoredSession? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.size < 29 || bytes[0] != 1.toByte()) throw GeneralSecurityException("Invalid session file")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(AAD)
        val plaintext = cipher.doFinal(bytes, 13, bytes.size - 13)
        return try { json.decodeFromString<StoredSession>(plaintext.decodeToString()) }
        finally { plaintext.fill(0) }
    }

    @Synchronized
    override fun save(session: StoredSession) {
        file.parentFile?.mkdirs()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(AAD)
        val plaintext = json.encodeToString(session).encodeToByteArray()
        val ciphertext = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        val temporary = File(file.parentFile, file.name + ".tmp")
        try {
            temporary.outputStream().use { out ->
                out.write(byteArrayOf(1) + cipher.iv + ciphertext)
                out.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    @Synchronized
    override fun clear() {
        Files.deleteIfExists(file.toPath())
        Files.deleteIfExists(File(file.parentFile, file.name + ".tmp").toPath())
    }

    companion object { private val AAD = "HDUHelper/session/v1".encodeToByteArray() }
}
