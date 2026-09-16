package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import mihon.data.sync.security.EncryptedFileSyncSecureStore
import mihon.data.sync.security.SyncRecordCipher
import mihon.domain.sync.security.SyncSecureStore
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidSyncSecureStore internal constructor(
    private val context: Context,
    private val cipher: SyncRecordCipher?,
) : SyncSecureStore {
    constructor(context: Context) : this(context.applicationContext, null)
    private val directory = File(context.noBackupFilesDir, "sync-secrets")
    private val delegate = EncryptedFileSyncSecureStore(directory, cipher ?: AndroidKeyStoreCipher(directory))

    override suspend fun read(key: String): String? = delegate.read(key)
    override suspend fun compareAndSet(key: String, expected: String?, value: String?): Boolean =
        delegate.compareAndSet(key, expected, value)

    override fun toString(): String = "AndroidSyncSecureStore(<redacted>)"
}

private class AndroidKeyStoreCipher(private val directory: File) : SyncRecordCipher {
    private val alias = "mihon-sync-wrapping-v1-" + MessageDigest.getInstance("SHA-256")
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

    // Directory lock covers get-or-create across instances and application processes.
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        check(create && directory.listFiles()!!.none { it.extension == "record" })
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}
