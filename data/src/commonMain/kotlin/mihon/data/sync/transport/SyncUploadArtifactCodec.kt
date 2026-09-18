package mihon.data.sync.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.domain.sync.crypto.SyncAeadCiphertext
import mihon.domain.sync.crypto.SyncEncryptedBatch
import mihon.domain.sync.crypto.SyncSpacePayload
import mihon.domain.sync.transport.SyncPreparedUpload
import mihon.domain.sync.transport.SyncRepository
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString

/** The persisted format holds all random ciphertext, never credentials or a data key. */
object SyncUploadArtifactCodec {
    private val json = Json { explicitNulls = false }

    fun encode(value: SyncPreparedUpload): String = json.encodeToString(
        StoredUpload(
            if (value.encryptedBatch.ciphertext is SyncSpacePayload) 2 else 1,
            value.repository.owner, value.repository.name, value.repository.branch, value.baseHead,
            StoredSyncBatch.fromDomain(value.encryptedBatch), value.previousIndexPath, value.previousLastSeq,
            value.indexCiphertext.bytes.toByteString().base64(), value.headCiphertext.bytes.toByteString().base64(),
        ),
    )

    fun decode(encoded: String): SyncPreparedUpload = try {
        require(encoded.length <= 4 * 1024 * 1024) { "upload artifact exceeds limit" }
        val value = json.decodeFromString<StoredUpload>(encoded)
        require(
            value.version in 1..2 && value.batch.spaceFormatVersion == value.version && value.previousLastSeq >= 0 &&
                value.baseHead.length in 1..128,
        )
        SyncPreparedUpload(
            SyncRepository(value.owner, value.repository, value.branch),
            value.baseHead,
            value.batch.toDomain(),
            value.previousIndexPath,
            value.previousLastSeq,
            requireNotNull(value.index.decodeBase64()).toByteArray().let {
                if (value.version ==
                    2
                ) {
                    SyncSpacePayload(it)
                } else {
                    SyncAeadCiphertext(it)
                }
            },
            requireNotNull(value.head.decodeBase64()).toByteArray().let {
                if (value.version ==
                    2
                ) {
                    SyncSpacePayload(it)
                } else {
                    SyncAeadCiphertext(it)
                }
            },
        )
    } catch (_: Exception) {
        throw IllegalArgumentException("saved upload artifact is invalid")
    }

    @Serializable
    private data class StoredUpload(
        val version: Int,
        val owner: String,
        val repository: String,
        val branch: String,
        val baseHead: String,
        val batch: StoredSyncBatch,
        val previousIndexPath: String?,
        val previousLastSeq: Long,
        val index: String,
        val head: String,
    )
}

@Serializable
internal data class StoredSyncBatch(
    val protocolVersion: Int,
    val spaceId: String,
    val generation: Long,
    val batchId: String,
    val path: String,
    val plaintextDigestHex: String,
    val ciphertextBase64: String,
    val actorId: String,
    val epoch: Long,
    val firstSeq: Long,
    val lastSeq: Long,
    val spaceFormatVersion: Int = 1,
) {
    fun body(): ByteArray = Json.encodeToString(this).encodeToByteArray()

    fun toDomain(): SyncEncryptedBatch {
        require(spaceFormatVersion in 1..2) { "unsupported stored batch format" }
        return SyncEncryptedBatch(
            protocolVersion, spaceId, generation, batchId, path, plaintextDigestHex.decodeHex().toByteArray(),
            requireNotNull(ciphertextBase64.decodeBase64()).toByteArray().let {
                if (spaceFormatVersion ==
                    2
                ) {
                    SyncSpacePayload(it)
                } else {
                    SyncAeadCiphertext(it)
                }
            },
            actorId, epoch, firstSeq, lastSeq,
        )
    }

    companion object {
        fun fromDomain(value: SyncEncryptedBatch): StoredSyncBatch = StoredSyncBatch(
            value.protocolVersion, value.spaceId, value.generation, value.batchId, value.path,
            value.plaintextDigest.toByteString().hex(), value.ciphertext.bytes.toByteString().base64(),
            value.actorId, value.epoch, value.firstSeq, value.lastSeq,
            if (value.ciphertext is SyncSpacePayload) 2 else 1,
        )
    }
}
