package moe.nepnep.hduhelper.data.grades

import moe.nepnep.hduhelper.data.timetable.AcademicTerm
import moe.nepnep.hduhelper.data.timetable.TimetableParser

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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface GradeStore {
    fun loadAll(account: String): List<GradeSnapshot> = listOfNotNull(load(account))
    fun load(account: String, term: AcademicTerm? = null): GradeSnapshot?
    fun save(data: GradeSnapshot)
    fun clear()
}

class EncryptedGradeStore(private val directory: File, private val keyProvider: () -> SecretKey) : GradeStore {
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(account: String, term: AcademicTerm) = File(directory, "${TimetableParser.stableId(account, term.key)}.enc")
    @Synchronized override fun load(account: String, term: AcademicTerm?): GradeSnapshot? {
        val files = if (term == null) directory.listFiles()?.filter { it.extension == "enc" }.orEmpty() else listOf(file(account, term))
        return readFiles(files, account, term).maxByOrNull { it.updatedAt }
    }
    @Synchronized override fun loadAll(account: String): List<GradeSnapshot> =
        readFiles(directory.listFiles()?.filter { it.extension == "enc" }.orEmpty(), account, null)

    private fun readFiles(files: List<File>, account: String, term: AcademicTerm?): List<GradeSnapshot> = files.mapNotNull { file ->
        if (!file.isFile) return@mapNotNull null
        try {
            val bytes = file.readBytes()
            require(bytes.size >= 29 && bytes[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(file.name.toByteArray())
            val plaintext = cipher.doFinal(bytes, 13, bytes.size - 13)
            val data = try { json.decodeFromString<GradeSnapshot>(plaintext.decodeToString()) } finally { plaintext.fill(0) }
            data.takeIf { it.account == account && (term == null || it.term.key == term.key) }
        } catch (_: Exception) { file.delete(); null }
    }

    @Synchronized override fun save(data: GradeSnapshot) {
        directory.mkdirs()
        val target = file(data.account, data.term)
        val temporary = File(directory, target.name + ".tmp")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(target.name.toByteArray())
        val plaintext = json.encodeToString(data).encodeToByteArray()
        val encrypted = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        try {
            temporary.outputStream().use { it.write(byteArrayOf(1) + cipher.iv + encrypted); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }
    @Synchronized override fun clear() { directory.listFiles()?.filter { it.extension in listOf("enc", "tmp") }?.forEach { Files.deleteIfExists(it.toPath()) } }
}

fun androidGradeStore(context: Context): GradeStore = EncryptedGradeStore(File(context.noBackupFilesDir, "grades")) {
    val alias = "hduhelper.grades.v1"
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
    }.generateKey()
}
