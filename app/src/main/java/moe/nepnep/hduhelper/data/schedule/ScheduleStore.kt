package moe.nepnep.hduhelper.data.schedule

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface ScheduleStore {
    fun load(): ScheduleBook
    fun save(book: ScheduleBook)
}

class EncryptedScheduleStore(private val file: File, private val key: () -> SecretKey) : ScheduleStore {
    private val json = Json { ignoreUnknownKeys = true }
    @Synchronized override fun load(): ScheduleBook {
        if (!file.exists()) return ScheduleBook()
        val bytes = file.readBytes()
        require(bytes.size >= 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD("hduhelper.schedule.v1".toByteArray())
        val plain = cipher.doFinal(bytes, 13, bytes.size - 13)
        // Preserve unreadable data: never silently replace a personal calendar with an empty one.
        return try { json.decodeFromString<ScheduleBook>(plain.decodeToString()) } finally { plain.fill(0) }
    }

    @Synchronized override fun save(book: ScheduleBook) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD("hduhelper.schedule.v1".toByteArray())
        val plain = json.encodeToString(book).encodeToByteArray()
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        try {
            temp.outputStream().use { it.write(byteArrayOf(1) + cipher.iv + encrypted); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
}

fun androidScheduleStore(context: Context): ScheduleStore = EncryptedScheduleStore(File(context.noBackupFilesDir, "schedule/calendar.enc")) {
    val alias = "hduhelper.schedule.v1"
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
    }.generateKey()
}
