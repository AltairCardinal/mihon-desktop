package mihon.domain.sync.crypto

import kotlinx.serialization.Serializable
import mihon.domain.sync.SyncBatch

const val SYNC_AEAD_ALGORITHM = "AES-256-GCM"
const val SYNC_AEAD_NONCE_BYTES = 12
const val SYNC_AEAD_TAG_BYTES = 16
const val SYNC_MAX_CIPHERTEXT_BYTES = 1024 * 1024

class SyncCryptoException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class SyncSecret private constructor(private val value: ByteArray) {
    val bytes: ByteArray get() = value.copyOf()

    init {
        require(value.size == 32) { "sync key must be 256 bits" }
    }

    override fun equals(other: Any?): Boolean = other is SyncSecret && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "SyncSecret(<redacted>)"

    companion object {
        fun fromBytes(value: ByteArray): SyncSecret = SyncSecret(value.copyOf())
    }
}

data class SyncCryptoBinding(
    val protocolVersion: Int,
    val spaceId: String,
    val generation: Long,
    val batchId: String,
    val path: String,
) {
    init {
        require(protocolVersion > 0) { "protocol version must be positive" }
        require(generation >= 0) { "generation must be non-negative" }
        require(spaceId.isNotBlank() && spaceId.length <= 128) { "space id is invalid" }
        require(batchId.isNotBlank() && batchId.length <= 128) { "batch id is invalid" }
        require(path.isNotBlank() && path.length <= 512 && !path.startsWith("/")) {
            "batch path is invalid"
        }
    }

    fun canonicalAad(): ByteArray =
        listOf(
            "mihon-sync-aad-v1",
            protocolVersion.toString(),
            spaceId,
            generation.toString(),
            batchId,
            path,
        ).joinToString("\u0000") { it.length.toString() + ":" + it }.encodeToByteArray()
}

class SyncAeadCiphertext(input: ByteArray) {
    private val value = input.copyOf()
    val bytes: ByteArray get() = value.copyOf()

    init {
        require(value.size in SYNC_AEAD_NONCE_BYTES + SYNC_AEAD_TAG_BYTES..SYNC_MAX_CIPHERTEXT_BYTES) {
            "ciphertext exceeds sync limit"
        }
    }

    override fun equals(other: Any?): Boolean = other is SyncAeadCiphertext && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "SyncAeadCiphertext(${value.size} bytes)"
}

interface SyncAeadEngine {
    fun sha256(value: ByteArray): ByteArray

    fun encrypt(
        secret: SyncSecret,
        plaintext: ByteArray,
        aad: ByteArray,
    ): SyncAeadCiphertext

    fun decrypt(
        secret: SyncSecret,
        ciphertext: SyncAeadCiphertext,
        aad: ByteArray,
    ): ByteArray
}

@Serializable
data class SyncRecoveryData(
    val formatVersion: Int,
    val algorithm: String,
    val keyId: String,
    val rawKeyset: String,
    val createdAtMillis: Long,
    val spaceId: String = "",
    val generation: Long = 0,
) {
    constructor(
        formatVersion: Int,
        algorithm: String,
        keyId: String,
        wrappedKey: String,
        createdAtMillis: Long,
    ) : this(formatVersion, algorithm, keyId, wrappedKey, createdAtMillis, "", 0)

    @Deprecated("Use rawKeyset; this value is not wrapped")
    val wrappedKey: String get() = rawKeyset

    init {
        require(formatVersion == 1) { "unsupported recovery format" }
        require(algorithm == SYNC_AEAD_ALGORITHM) { "unsupported recovery algorithm" }
        require(keyId.length in 16..128 && rawKeyset.length in 16..4096) {
            "invalid recovery data"
        }
        require(spaceId.isEmpty() || spaceId.length <= 128) { "invalid recovery space" }
        require(generation >= 0) { "invalid recovery generation" }
    }

    override fun toString(): String =
        "SyncRecoveryData(formatVersion=$formatVersion, algorithm=$algorithm, keyId=$keyId, spaceId=$spaceId, generation=$generation, createdAtMillis=$createdAtMillis, rawKeyset=<redacted>)"
}

class SyncEncryptedBatch(
    val protocolVersion: Int,
    val spaceId: String,
    val generation: Long,
    val batchId: String,
    val path: String,
    plaintextDigest: ByteArray,
    val ciphertext: SyncAeadCiphertext,
    val actorId: String = "unknown",
    val epoch: Long = 0,
    val firstSeq: Long = 0,
    val lastSeq: Long = 0,
) {
    private val digest = plaintextDigest.copyOf()
    val plaintextDigest: ByteArray get() = digest.copyOf()

    init {
        require(digest.size == 32) { "invalid plaintext digest" }
        require(ciphertext.bytes.size <= SYNC_MAX_CIPHERTEXT_BYTES) { "ciphertext too large" }
        require(actorId.isNotBlank() && epoch >= 0 && firstSeq <= lastSeq) { "invalid batch sequence metadata" }
    }

    fun binding(): SyncCryptoBinding =
        SyncCryptoBinding(protocolVersion, spaceId, generation, batchId, path)

    fun copy(
        protocolVersion: Int = this.protocolVersion,
        spaceId: String = this.spaceId,
        generation: Long = this.generation,
        batchId: String = this.batchId,
        path: String = this.path,
        plaintextDigest: ByteArray = this.plaintextDigest,
        ciphertext: SyncAeadCiphertext = this.ciphertext,
        actorId: String = this.actorId,
        epoch: Long = this.epoch,
        firstSeq: Long = this.firstSeq,
        lastSeq: Long = this.lastSeq,
    ): SyncEncryptedBatch = SyncEncryptedBatch(
        protocolVersion,
        spaceId,
        generation,
        batchId,
        path,
        plaintextDigest,
        ciphertext,
        actorId,
        epoch,
        firstSeq,
        lastSeq,
    )

    override fun equals(other: Any?): Boolean =
        other is SyncEncryptedBatch &&
            protocolVersion == other.protocolVersion &&
            spaceId == other.spaceId &&
            generation == other.generation &&
            batchId == other.batchId &&
            path == other.path &&
            plaintextDigest.contentEquals(other.plaintextDigest) &&
            ciphertext == other.ciphertext &&
            actorId == other.actorId && epoch == other.epoch && firstSeq == other.firstSeq && lastSeq == other.lastSeq

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + spaceId.hashCode()
        result = 31 * result + generation.hashCode()
        result = 31 * result + batchId.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + plaintextDigest.contentHashCode()
        result = 31 * result + ciphertext.hashCode()
        result = 31 * result + actorId.hashCode()
        result = 31 * result + epoch.hashCode()
        result = 31 * result + firstSeq.hashCode()
        result = 31 * result + lastSeq.hashCode()
        return result
    }
}

object SyncBatchEncryption {
    fun encrypt(
        engine: SyncAeadEngine,
        secret: SyncSecret,
        batch: SyncBatch,
        path: String,
    ): SyncEncryptedBatch {
        require(batch.events.isNotEmpty()) { "sync batch cannot be empty" }
        require(batch.events.map { it.actorId }.toSet().size == 1) { "sync batch mixes actors" }
        require(batch.events.map { it.epoch }.toSet().size == 1) { "sync batch mixes epochs" }
        require(
            batch.events.all {
                it.protocolVersion == batch.protocolVersion &&
                    it.spaceId == batch.spaceId &&
                    it.generation == batch.generation &&
                    it.batchId == batch.batchId
            },
        ) { "sync batch metadata does not match events" }
        val actor = batch.events.first().actorId
        val epoch = batch.events.first().epoch
        require(isStorageId(actor) && isStorageId(batch.batchId)) { "invalid sync storage identifier" }
        require(path == ".mihon-sync/batches/$actor/$epoch/${batch.batchId}.json") {
            "sync batch path is not bound to actor and epoch"
        }
        requireContiguousSequences(batch)
        val plaintext = mihon.domain.sync.SyncBatchCodec.encode(
            batch.events,
            batch.batchId,
            batch.spaceId,
            batch.generation,
            batch.protocolVersion,
        ).encodeToByteArray()
        require(plaintext.size <= 512 * 1024) { "plaintext batch exceeds sync limit" }
        val binding = SyncCryptoBinding(batch.protocolVersion, batch.spaceId, batch.generation, batch.batchId, path)
        val digest = engine.sha256(plaintext)
        val ciphertext = engine.encrypt(secret, plaintext, binding.canonicalAad())
        return SyncEncryptedBatch(
            batch.protocolVersion,
            batch.spaceId,
            batch.generation,
            batch.batchId,
            path,
            digest,
            ciphertext,
            actor,
            epoch,
            batch.events.minOf { it.seq },
            batch.events.maxOf { it.seq },
        )
    }

    fun decrypt(
        engine: SyncAeadEngine,
        secret: SyncSecret,
        encrypted: SyncEncryptedBatch,
    ): SyncBatch {
        val plaintext = engine.decrypt(secret, encrypted.ciphertext, encrypted.binding().canonicalAad())
        require(plaintext.size <= 512 * 1024) { "plaintext batch exceeds sync limit" }
        require(engine.sha256(plaintext).contentEquals(encrypted.plaintextDigest)) {
            throw SyncCryptoException("plaintext digest mismatch")
        }
        return when (val decoded = mihon.domain.sync.SyncBatchCodec.decode(plaintext.decodeToString())) {
            is mihon.domain.sync.SyncBatchDecodeResult.Accepted -> {
                require(decoded.batch.protocolVersion == encrypted.protocolVersion)
                require(decoded.batch.spaceId == encrypted.spaceId)
                require(decoded.batch.generation == encrypted.generation)
                require(decoded.batch.batchId == encrypted.batchId)
                require(
                    decoded.batch.events.all {
                        it.actorId == encrypted.actorId && it.epoch == encrypted.epoch &&
                            it.batchId == encrypted.batchId
                    },
                )
                require(isStorageId(encrypted.actorId) && isStorageId(encrypted.batchId))
                val expectedPath = ".mihon-sync/batches/${encrypted.actorId}/${encrypted.epoch}/" +
                    "${encrypted.batchId}.json"
                require(encrypted.path == expectedPath)
                requireContiguousSequences(decoded.batch)
                require(decoded.batch.events.minOfOrNull { it.seq } == encrypted.firstSeq)
                require(decoded.batch.events.maxOfOrNull { it.seq } == encrypted.lastSeq)
                decoded.batch
            }
            is mihon.domain.sync.SyncBatchDecodeResult.Rejected ->
                throw SyncCryptoException("decrypted batch rejected")
        }
    }

    private fun isStorageId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,128}"))

    private fun requireContiguousSequences(batch: SyncBatch) {
        val sequences = batch.events.map { it.seq }.sorted()
        require(sequences.isNotEmpty() && sequences.first() > 0) { "invalid batch sequence" }
        require(
            sequences.zipWithNext().all { (previous, current) ->
                previous < Long.MAX_VALUE && current == previous + 1
            },
        ) {
            "sync batch sequence is duplicated or incomplete"
        }
    }
}
