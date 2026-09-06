package moe.nepnep.hduhelper.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

fun androidSessionStore(context: Context): SessionStore {
    val delegate = EncryptedSessionStore(
    File(context.noBackupFilesDir, "auth/session.enc"),
) {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keyStore.getKey("hduhelper.session.v1", null) as? SecretKey) ?: KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
    ).apply {
        init(
            KeyGenParameterSpec.Builder(
                "hduhelper.session.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
    }.generateKey()
}

    return object : SessionStore {
        override fun load(): StoredSession? = try { delegate.load() } catch (e: Exception) {
            // Remove unusable key material as well, so the next explicit login can create a fresh key.
            delegate.clear()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("hduhelper.session.v1") }
            throw e
        }
        override fun save(session: StoredSession) = delegate.save(session)
        override fun clear() = delegate.clear()
    }
}
