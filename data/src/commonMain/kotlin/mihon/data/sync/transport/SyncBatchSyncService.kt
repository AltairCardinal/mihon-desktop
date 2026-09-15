package mihon.data.sync.transport

import kotlinx.coroutines.CancellationException
import mihon.data.sync.crypto.SyncAeadEngineFactory
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.crypto.SyncBatchEncryption
import mihon.domain.sync.crypto.SyncSecret
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
    private val secret: SyncSecret,
    private val engine: mihon.domain.sync.crypto.SyncAeadEngine = SyncAeadEngineFactory.create(),
) {
    /** Freeze the complete upload artifact before any network side effect. */
    fun prepare(snapshot: SyncSnapshot, batch: SyncBatch, path: String): SyncPreparedUpload =
        transport.prepare(snapshot, SyncBatchEncryption.encrypt(engine, secret, batch, path))

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
    ): SyncUploadResult = SyncUploadResult(
        prepared,
        transport.publish(repository, snapshot, prepared),
    )

    suspend fun receive(
        snapshot: SyncSnapshot,
        entry: SyncBatchIndexEntry,
    ): SyncReceiveResult {
        val encrypted = transport.readBatch(snapshot, entry).getOrElse {
            return SyncReceiveResult(null, "sync batch could not be read")
        }
        return try {
            SyncReceiveResult(SyncBatchEncryption.decrypt(engine, secret, encrypted))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SyncReceiveResult(null, "sync batch could not be authenticated")
        }
    }
}
