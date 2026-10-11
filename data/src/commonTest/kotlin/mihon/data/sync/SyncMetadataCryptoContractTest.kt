package mihon.data.sync

import mihon.data.sync.crypto.SyncAeadEngineFactory
import mihon.data.sync.crypto.SyncSpaceCrypto
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncMetadataPolicy
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.crypto.SyncBatchEncryption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SyncMetadataCryptoContractTest {
    @Test
    fun `mixed event schemas preserve existing password and plain space binding`() {
        val key = SyncObjectKey(SyncObjectType.MANGA, sourceId = "42", originalUrl = "/manga")
        val legacy = SyncEventEnvelope(
            1, "space", 1, "actor", 1, 1, SyncCategory.FAVORITE,
            listOf(
                SyncEffect("favorite", key, SyncField.FAVORITE, SyncEffectKind.ADD),
            ),
            SyncOrigin.USER, batchId = "batch",
        )
        val metadata = legacy.copy(
            protocolVersion = 2,
            seq = 2,
            effects = listOf(
                SyncEffect(
                    "categories",
                    key,
                    SyncField.MANGA_CATEGORIES,
                    SyncEffectKind.SET_CATEGORIES,
                    payload = SyncMetadataPolicy.categoryPayload(listOf("科幻")),
                ),
            ),
        )
        val batch = SyncBatch(1, "space", 1, "batch", listOf(legacy, metadata))
        val engine = SyncAeadEngineFactory.create()
        listOf("", "test-password").forEach { password ->
            val material = SyncSpaceCrypto.create("space", 1, password)
            val encrypted = SyncBatchEncryption.encrypt(
                engine,
                material.secret,
                batch,
                ".mihon-sync/batches/actor/1/batch.json",
                material,
            )
            assertEquals(1, encrypted.protocolVersion)
            assertEquals(1, material.descriptor.eventProtocolVersion)
            assertEquals(batch, SyncBatchEncryption.decrypt(engine, material.secret, encrypted, material))
        }
    }
}
