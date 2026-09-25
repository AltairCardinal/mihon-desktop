package mihon.data.sync.transport

import kotlinx.coroutines.CancellationException
import mihon.data.sync.crypto.SyncAeadEngineFactory
import mihon.data.sync.http.SyncHttpException
import mihon.data.sync.http.SyncHttpFailureClass
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.crypto.SyncBatchEncryption
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.crypto.SyncSpaceMaterial
import mihon.domain.sync.transport.SyncBatchIndexEntry
import mihon.domain.sync.transport.SyncPreparedUpload
import mihon.domain.sync.transport.SyncReceiveResult
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncSnapshot
import mihon.domain.sync.transport.SyncTransportPort
import mihon.domain.sync.transport.SyncUploadResult

/** The upload artifact is retained by callers so a retry reuses its exact ciphertext bytes. */
class SyncBatchSyncService(
    private val transport: SyncTransportPort,
    private val secret: SyncSecret? = null,
    private val engine: mihon.domain.sync.crypto.SyncAeadEngine = SyncAeadEngineFactory.create(),
    private val spaceMaterial: SyncSpaceMaterial? = null,
    private val onEncryptedBatchReceived: (SyncBatchIndexEntry) -> Unit = {},
) {
    /** Freeze the complete upload artifact before any network side effect. */
    fun prepare(snapshot: SyncSnapshot, batch: SyncBatch, path: String): SyncPreparedUpload =
        transport.prepare(snapshot, SyncBatchEncryption.encrypt(engine, secret, batch, path, spaceMaterial))

    suspend fun upload(
        repository: SyncRepository,
        snapshot: SyncSnapshot,
        batch: SyncBatch,
        path: String,
        persist: suspend (SyncPreparedUpload) -> Unit,
    ): SyncUploadResult {
        val prepared = prepare(snapshot, batch, path)
        persist(prepared)
        return uploadPrepared(repository, snapshot, prepared)
    }

    /** Publishes an artifact that the caller has already persisted durably. */
    suspend fun uploadPrepared(
        repository: SyncRepository,
        snapshot: SyncSnapshot,
        prepared: SyncPreparedUpload,
        observeSnapshot: suspend (SyncSnapshot) -> Unit = {},
    ): SyncUploadResult = SyncUploadResult(
        prepared,
        transport.publish(repository, snapshot, prepared, observeSnapshot),
    )

    suspend fun receive(
        snapshot: SyncSnapshot,
        entry: SyncBatchIndexEntry,
    ): SyncReceiveResult {
        // A transport failure is retryable and must leave the durable discovery
        // row intact. Only authenticated/decode failures become terminal null.
        val encrypted = try {
            transport.readBatch(snapshot, entry).getOrThrow()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error.isRetryableOrActionableReadFailure()) throw error
            return SyncReceiveResult(null, "sync batch could not be decoded")
        }
        runCatching { onEncryptedBatchReceived(entry) }
        return try {
            SyncReceiveResult(SyncBatchEncryption.decrypt(engine, secret, encrypted, spaceMaterial))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SyncReceiveResult(null, "sync batch could not be authenticated")
        }
    }
}

private fun Exception.isRetryableOrActionableReadFailure(): Boolean = when (this) {
    is SyncHttpException -> retryable || failureClass in setOf(
        SyncHttpFailureClass.AUTHORIZATION,
        SyncHttpFailureClass.CONFLICT,
        SyncHttpFailureClass.NETWORK,
        SyncHttpFailureClass.RATE_LIMITED,
        SyncHttpFailureClass.SERVER,
    )
    is java.io.IOException -> true
    else -> false
}
