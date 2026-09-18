package mihon.data.sync.crypto

import mihon.domain.sync.crypto.SyncAeadCiphertext
import mihon.domain.sync.crypto.SyncCryptoException
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.crypto.SyncSpaceDescriptor
import mihon.domain.sync.crypto.SyncSpaceDescriptorCodec
import mihon.domain.sync.crypto.SyncSpaceMaterial
import mihon.domain.sync.crypto.SyncSpaceProtection
import mihon.domain.sync.crypto.authenticationData
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class SyncPasswordInputIssue { TOO_LONG, INVALID }

class SyncPasswordInputException(val issue: SyncPasswordInputIssue) : IllegalArgumentException(
    "invalid sync password input",
)

object SyncSpaceCrypto {
    fun validatePassword(password: String) {
        passwordBytes(password).fill(0)
    }
    fun create(spaceId: String, generation: Long, password: String): SyncSpaceMaterial {
        val input = passwordBytes(password)
        try {
            if (input.isEmpty()) {
                return SyncSpaceMaterial(
                    SyncSpaceDescriptor(spaceId, generation, SyncSpaceProtection.None),
                    null,
                )
            }
            val random = SecureRandom()
            val salt = ByteArray(16).also(random::nextBytes)
            val raw = ByteArray(32).also(random::nextBytes)
            val secret = SyncSecret.fromBytes(raw)
            raw.fill(0)
            val protection = SyncSpaceProtection.Password(
                "PBKDF2-HMAC-SHA256",
                600_000,
                salt.toByteString().hex(),
                "AES-256-GCM",
                "00".repeat(60),
            )
            val descriptor = SyncSpaceDescriptor(spaceId, generation, protection)
            SyncSpaceDescriptorCodec.validate(descriptor)
            val kek = derive(input, salt)
            try {
                val wrapped = SyncAeadEngineFactory.create().encrypt(
                    SyncSecret.fromBytes(kek),
                    secret.bytes,
                    wrappingAad(descriptor),
                ).bytes
                return SyncSpaceMaterial(
                    descriptor.copy(protection = protection.copy(wrappedKeyHex = wrapped.toByteString().hex())),
                    secret,
                )
            } finally {
                kek.fill(0)
            }
        } finally {
            input.fill(0)
        }
    }

    fun unlock(descriptor: SyncSpaceDescriptor, password: String): Result<SyncSpaceMaterial> = try {
        SyncSpaceDescriptorCodec.validate(descriptor)
        val input = passwordBytes(password)
        try {
            val protection = descriptor.protection
            if (protection is SyncSpaceProtection.None) {
                require(input.isEmpty())
                Result.success(SyncSpaceMaterial(descriptor, null))
            } else {
                require(input.isNotEmpty())
                protection as SyncSpaceProtection.Password
                val kek = derive(input, protection.saltHex.decodeHex().toByteArray())
                try {
                    val raw = SyncAeadEngineFactory.create().decrypt(
                        SyncSecret.fromBytes(kek),
                        SyncAeadCiphertext(protection.wrappedKeyHex.decodeHex().toByteArray()),
                        wrappingAad(descriptor),
                    )
                    try {
                        Result.success(SyncSpaceMaterial(descriptor, SyncSecret.fromBytes(raw)))
                    } finally {
                        raw.fill(0)
                    }
                } finally {
                    kek.fill(0)
                }
            }
        } finally {
            input.fill(0)
        }
    } catch (_: Exception) {
        Result.failure(SyncCryptoException("password or space verification failed"))
    }

    private fun passwordBytes(password: String): ByteArray {
        if (password.length > 1024) throw SyncPasswordInputException(SyncPasswordInputIssue.TOO_LONG)
        val bytes = try {
            password.encodeToByteArray(throwOnInvalidSequence = true)
        } catch (_: Exception) {
            throw SyncPasswordInputException(SyncPasswordInputIssue.INVALID)
        }
        if (bytes.size > 1024) {
            bytes.fill(0)
            throw SyncPasswordInputException(SyncPasswordInputIssue.TOO_LONG)
        }
        return bytes
    }

    private fun wrappingAad(descriptor: SyncSpaceDescriptor): ByteArray {
        val protection = descriptor.protection as SyncSpaceProtection.Password
        return descriptor.authenticationData(
            "mihon-sync-wrap-v2",
            protection.kdf,
            protection.iterations.toString(),
            protection.saltHex,
            protection.aead,
        )
    }

    /** HMAC consumes explicit UTF-8 bytes; provider-specific PBE character conversion is not used. */
    internal fun derive(password: ByteArray, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        var previous = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val result = previous.copyOf()
        try {
            repeat(599_999) {
                val next = mac.doFinal(previous)
                previous.fill(0)
                previous = next
                for (index in result.indices) {
                    result[index] =
                        (result[index].toInt() xor previous[index].toInt()).toByte()
                }
            }
            return result
        } finally {
            previous.fill(0)
        }
    }
}
