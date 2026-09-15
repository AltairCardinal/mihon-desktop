package mihon.desktop.sync

import mihon.data.sync.security.EncryptedFileSyncSecureStore
import mihon.data.sync.security.SyncRecordCipher
import mihon.desktop.platform.CredentialBackend
import mihon.desktop.platform.CredentialNamespace
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.desktop.platform.OsCredentialBackend
import mihon.domain.sync.security.SyncSecureStore
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class DesktopSyncSecureStore(
    private val directory: File = File(DesktopPlatformPaths.current().configDir, "sync-secrets"),
    private val backend: CredentialBackend = OsCredentialBackend(namespace = CredentialNamespace.SYNC_V1),
) : SyncSecureStore {
    private val delegate = EncryptedFileSyncSecureStore(directory, DesktopRecordCipher(directory, backend))

    override suspend fun read(key: String): String? = delegate.read(key)
    override suspend fun compareAndSet(key: String, expected: String?, value: String?): Boolean =
        delegate.compareAndSet(key, expected, value)

    override fun toString(): String = "DesktopSyncSecureStore(<redacted>)"
}

/** OS credentials contain only the wrapping key; records may exceed OS credential value limits. */
private class DesktopRecordCipher(
    private val directory: File,
    private val backend: CredentialBackend,
) : SyncRecordCipher {
    private val account = "wrapping-" + MessageDigest.getInstance("SHA-256")
        .digest(directory.canonicalPath.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
    }

    // Called only while the shared directory/process lock is held.
    private fun key(create: Boolean): SecretKeySpec {
        val stored = backend.load(account)
        val raw = if (stored != null) {
            try {
                check(stored.size == 64 && stored.all { it.digitToIntOrNull(16) != null })
                ByteArray(32) { (stored[it * 2].digitToInt(16) * 16 + stored[it * 2 + 1].digitToInt(16)).toByte() }
            } finally {
                stored.fill('\u0000')
            }
        } else {
            check(create && directory.listFiles()!!.none { it.extension == "record" })
            val generated = ByteArray(32).also(SecureRandom()::nextBytes)
            val hex = "0123456789abcdef"
            val encoded = CharArray(64) { index ->
                val value = generated[index / 2].toInt() and 0xff
                hex[if (index % 2 == 0) value ushr 4 else value and 15]
            }
            try {
                backend.save(account, encoded)
            } catch (error: Exception) {
                generated.fill(0)
                throw error
            } finally {
                encoded.fill('\u0000')
            }
            generated
        }
        return try {
            SecretKeySpec(raw, "AES")
        } finally {
            raw.fill(0)
        }
    }
}
