package mihon.domain.sync.crypto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class SyncRecoveryBundle(
    val secret: SyncSecret,
    val data: SyncRecoveryData,
)

/** Strict, bounded recovery material codec. The keyset is raw key material, so it is named as such. */
object SyncRecoveryCodec {
    private val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun generate(
        spaceId: String,
        generation: Long,
        keyId: String,
        randomBytes: () -> ByteArray,
        createdAtMillis: Long,
    ): SyncRecoveryBundle {
        require(spaceId.isNotBlank() && spaceId.length <= 128) { "recovery space is invalid" }
        require(generation >= 0) { "recovery generation is invalid" }
        val raw = randomBytes()
        require(raw.size == 32) { "recovery key must be 256 bits" }
        val secret = SyncSecret.fromBytes(raw)
        return SyncRecoveryBundle(
            secret,
            SyncRecoveryData(
                formatVersion = 1,
                algorithm = SYNC_AEAD_ALGORITHM,
                keyId = keyId,
                rawKeyset = raw.toHex(),
                createdAtMillis = createdAtMillis,
                spaceId = spaceId,
                generation = generation,
            ),
        )
    }

    fun encode(data: SyncRecoveryData): String = json.encodeToString(data)

    fun decode(encoded: String): Result<SyncRecoveryData> = try {
        require(encoded.length <= 16 * 1024) { "recovery data exceeds limit" }
        Result.success(json.decodeFromString<SyncRecoveryData>(encoded).also(::validate))
    } catch (_: Exception) {
        // Serialization failures can echo the raw recovery key in their input excerpt.
        Result.failure(SyncCryptoException("recovery data is malformed or unsupported"))
    }

    fun importSecret(
        data: SyncRecoveryData,
        expectedSpaceId: String,
        expectedGeneration: Long,
    ): Result<SyncSecret> = runCatching {
        validate(data)
        require(data.spaceId == expectedSpaceId && data.generation == expectedGeneration) {
            "recovery binding does not match sync space"
        }
        SyncSecret.fromBytes(data.rawKeyset.decodeHex())
    }

    private fun validate(data: SyncRecoveryData) {
        require(data.formatVersion == 1 && data.algorithm == SYNC_AEAD_ALGORITHM) {
            "unsupported recovery format"
        }
        require(data.keyId.length in 16..128) { "recovery key id is invalid" }
        require(data.spaceId.isNotBlank() && data.spaceId.length <= 128) {
            "recovery space is invalid"
        }
        require(data.generation >= 0) { "recovery generation is invalid" }
        require(data.rawKeyset.length == 64 && data.rawKeyset.all { it.isHexDigit() }) {
            "recovery key material is invalid"
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.decodeHex(): ByteArray {
    require(length == 64 && all { it.isHexDigit() }) { "recovery key material is invalid" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this.lowercaseChar() in 'a'..'f'
