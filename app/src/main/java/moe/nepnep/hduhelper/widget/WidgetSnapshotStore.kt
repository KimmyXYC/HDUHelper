package moe.nepnep.hduhelper.widget

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.nepnep.hduhelper.data.schedule.ScheduleBook
import moe.nepnep.hduhelper.data.settings.ThemeMode
import moe.nepnep.hduhelper.data.timetable.TimetableData

/** Calendar-only snapshot: the widget process never opens credentials or starts the application graph. */
@Serializable
internal data class WidgetSnapshot(
    val revision: String = UUID.randomUUID().toString(),
    val account: String? = null,
    val book: ScheduleBook = ScheduleBook(),
    val terms: List<TimetableData> = emptyList(),
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val error: String? = null,
) {
    override fun toString() = "WidgetSnapshot([redacted])"
}

internal class WidgetSnapshotStore(private val file: File, private val keyProvider: (Boolean) -> SecretKey) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "widgets/snapshot.enc"), ::androidKey)
    private val json = Json { ignoreUnknownKeys = true }
    @Synchronized fun load(): WidgetSnapshot? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        require(bytes.size >= 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD("hduhelper.widgets.v1".toByteArray())
        val plain = cipher.doFinal(bytes, 13, bytes.size - 13)
        return try { json.decodeFromString<WidgetSnapshot>(plain.decodeToString()) } finally { plain.fill(0) }
    }
    @Synchronized fun save(snapshot: WidgetSnapshot) {
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider(true))
        cipher.updateAAD("hduhelper.widgets.v1".toByteArray())
        val plain = json.encodeToString(snapshot).encodeToByteArray()
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        try {
            temporary.outputStream().use { it.write(byteArrayOf(1) + cipher.iv + encrypted); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    @Synchronized fun clear() { Files.deleteIfExists(file.toPath()) }
    companion object {
        private fun androidKey(create: Boolean): SecretKey {
            val alias = "hduhelper.widgets.v1"
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            check(create) { "Widget cache key unavailable" }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
        }
    }

}
