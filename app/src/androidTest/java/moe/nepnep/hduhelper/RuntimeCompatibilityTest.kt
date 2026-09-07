package moe.nepnep.hduhelper

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.File
import java.math.BigInteger
import java.security.KeyStore
import java.time.Instant
import javax.crypto.KeyGenerator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.nepnep.hduhelper.data.auth.EncryptedSessionStore
import moe.nepnep.hduhelper.data.auth.StoredSession
import moe.nepnep.hduhelper.data.auth.UserProfile
import moe.nepnep.hduhelper.data.campuscode.QrCodeEncoder
import moe.nepnep.hduhelper.data.campuscode.YmtSigner
import moe.nepnep.hduhelper.data.timetable.AcademicTerm
import moe.nepnep.hduhelper.data.timetable.CourseMeeting
import moe.nepnep.hduhelper.data.timetable.EncryptedTimetableStore
import moe.nepnep.hduhelper.data.timetable.TimetableCatalog
import moe.nepnep.hduhelper.data.timetable.TimetableData
import org.junit.Assert.*
import org.junit.Test

/** Synthetic, offline checks; never reads or changes the user's saved account. */
class RuntimeCompatibilityTest {
    @Test
    fun keystoreAndSerializationRoundTripOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "runtime-smoke-${System.nanoTime()}").apply { mkdirs() }
        val alias = "hduhelper.runtime.smoke.${System.nanoTime()}"
        val key = KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
        try {
            val session = StoredSession(account = "synthetic", profile = UserProfile("synthetic", "测试用户"))
            val file = File(directory, "session.enc")
            EncryptedSessionStore(file) { key }.save(session)
            assertEquals(session, EncryptedSessionStore(file) { key }.load())
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("synthetic"))
            val term = AcademicTerm("2026", "3")
            val course = CourseMeeting("1", "course", "测试课程", weekday = 1, sections = listOf(1, 2),
                weeks = listOf(1, 2), rawWeeks = "1-2周", rawSections = "1-2节")
            val data = TimetableData("synthetic", term, TimetableCatalog(listOf("2026"), emptyList(), term),
                listOf(course), emptyList(), emptyList(), emptyList(), 1234)
            val timetableDirectory = File(directory, "timetable")
            EncryptedTimetableStore(timetableDirectory) { key }.save(data)
            val restored = EncryptedTimetableStore(timetableDirectory) { key }.load("synthetic", term)
            assertEquals(data, restored)
            assertEquals(data, Json.decodeFromString<TimetableData>(Json.encodeToString(data)))
        } finally {
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    @Test
    fun campusCodeCryptographyAndQrEncodingMatchFixtures() {
        val canonical = YmtSigner.canonical("/uias/authentication/index/token-h5",
            mapOf("appUrl" to "", "isCas" to "1", "isAppEnter" to "0"), "abcdef123456",
            Instant.parse("2026-09-06T12:00:00Z").toEpochMilli())
        assertEquals("eaa9210082e508b2fb8ae8877ac93a5ef3be9ab6ed6c7151a3352335347cd922555bf7006686853e444f08d63e9d7eb500ae1c829ff5df224182efd7b2b9644c",
            YmtSigner.sign(canonical, BigInteger.ONE))
        val raw = "synthetic-测试,+/%& 001"
        val qr = QrCodeEncoder.encode(raw)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(qr.size, qr.size, qr.pixels))))
        assertEquals(raw, decoded.text)
    }
}
