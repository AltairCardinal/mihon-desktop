@file:Suppress("ktlint:standard:filename")

package mihon.data.sync.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKey
import com.google.crypto.tink.aead.AesGcmParameters
import com.google.crypto.tink.util.SecretBytes
import mihon.domain.sync.crypto.SyncAeadCiphertext
import mihon.domain.sync.crypto.SyncAeadEngine
import mihon.domain.sync.crypto.SyncCryptoException
import mihon.domain.sync.crypto.SyncSecret
import java.security.MessageDigest

actual object SyncAeadEngineFactory {
    actual fun create(): SyncAeadEngine = TinkAeadEngine
}

private object TinkAeadEngine : SyncAeadEngine {
    init {
        AeadConfig.register()
    }

    override fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    override fun encrypt(secret: SyncSecret, plaintext: ByteArray, aad: ByteArray): SyncAeadCiphertext =
        try {
            SyncAeadCiphertext(aead(secret).encrypt(plaintext, aad))
        } catch (error: Exception) {
            throw SyncCryptoException("authenticated encryption failed")
        }

    override fun decrypt(secret: SyncSecret, ciphertext: SyncAeadCiphertext, aad: ByteArray): ByteArray =
        try {
            aead(secret).decrypt(ciphertext.bytes, aad)
        } catch (error: Exception) {
            throw SyncCryptoException("authenticated decryption failed")
        }

    private fun aead(secret: SyncSecret): Aead {
        val parameters = AesGcmParameters.builder()
            .setKeySizeBytes(32)
            .setIvSizeBytes(12)
            .setTagSizeBytes(16)
            .setVariant(AesGcmParameters.Variant.NO_PREFIX)
            .build()
        val key = AesGcmKey.builder()
            .setParameters(parameters)
            .setKeyBytes(SecretBytes.copyFrom(secret.bytes, InsecureSecretKeyAccess.get()))
            .build()
        return KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
            .build()
            .getPrimitive(Aead::class.java)
    }
}
