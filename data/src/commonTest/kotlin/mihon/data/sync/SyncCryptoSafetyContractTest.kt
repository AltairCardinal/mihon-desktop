package mihon.data.sync

import mihon.data.sync.crypto.SyncAeadEngineFactory
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncBatchCodec
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.crypto.SYNC_AEAD_ALGORITHM
import mihon.domain.sync.crypto.SyncAeadCiphertext
import mihon.domain.sync.crypto.SyncBatchEncryption
import mihon.domain.sync.crypto.SyncCryptoBinding
import mihon.domain.sync.crypto.SyncEncryptedBatch
import mihon.domain.sync.crypto.SyncRecoveryCodec
import mihon.domain.sync.crypto.SyncRecoveryData
import mihon.domain.sync.crypto.SyncSecret
import okio.ByteString.Companion.decodeHex
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncCryptoSafetyContractTest {
    private val engine = SyncAeadEngineFactory.create()
    private val secret = SyncSecret.fromBytes(ByteArray(32))
    private val path = ".mihon-sync/batches/actor/1/batch.json"

    @Test
    fun `Android and JVM decode the same fixed AES256 GCM wire vector`() {
        // Independent AES-GCM vector: zero key/nonce, 16 zero plaintext bytes, empty AAD.
        // The wire representation is nonce || ciphertext || tag, without a Tink key prefix.
        val frozen = (
            "000000000000000000000000" +
                "cea7403d4d606b6e074ec5d3baf39d18" +
                "d0d1c8a799996bf0265b98b5d48ab919"
            ).decodeHex().toByteArray()
        assertArrayEquals(ByteArray(16), engine.decrypt(secret, SyncAeadCiphertext(frozen), byteArrayOf()))
        assertThrows(IllegalArgumentException::class.java) {
            engine.decrypt(secret, SyncAeadCiphertext(frozen), byteArrayOf(1))
        }
    }

    @Test
    fun `batch encryption rejects a sequence gap before producing upload bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            SyncBatchEncryption.encrypt(engine, secret, batch(1, 3), path)
        }
    }

    @Test
    fun `authenticated but incomplete batch cannot become valid input`() {
        val input = batch(1, 3)
        val plaintext = SyncBatchCodec.rawEncode(input).encodeToByteArray()
        val binding = SyncCryptoBinding(1, "space", 1, "batch", path)
        val encrypted = SyncEncryptedBatch(
            1, "space", 1, "batch", path, engine.sha256(plaintext),
            engine.encrypt(secret, plaintext, binding.canonicalAad()), "actor", 1, 1, 3,
        )
        assertThrows(IllegalArgumentException::class.java) { SyncBatchEncryption.decrypt(engine, secret, encrypted) }
    }

    @Test
    fun `authenticated events must belong to their containing batch`() {
        for (eventBatchId in listOf("other", null)) {
            val input = batch(1).let { it.copy(events = it.events.map { event -> event.copy(batchId = eventBatchId) }) }
            val plaintext = SyncBatchCodec.rawEncode(input).encodeToByteArray()
            val binding = SyncCryptoBinding(1, "space", 1, "batch", path)
            val encrypted = SyncEncryptedBatch(
                1, "space", 1, "batch", path, engine.sha256(plaintext),
                engine.encrypt(secret, plaintext, binding.canonicalAad()), "actor", 1, 1, 1,
            )
            assertThrows(IllegalArgumentException::class.java) {
                SyncBatchEncryption.decrypt(engine, secret, encrypted)
            }
        }
    }

    @Test
    fun `batch storage identifiers cannot introduce additional path segments`() {
        val unsafe = batch(1).let { it.copy(events = it.events.map { event -> event.copy(actorId = "../actor") }) }
        assertThrows(IllegalArgumentException::class.java) {
            SyncBatchEncryption.encrypt(engine, secret, unsafe, ".mihon-sync/batches/../actor/1/batch.json")
        }
    }

    @Test
    fun `contiguous batches can start after the first event and tolerate input order`() {
        val input = batch(4, 2, 3)
        val encrypted = SyncBatchEncryption.encrypt(engine, secret, input, path)
        assertEquals(2L, encrypted.firstSeq)
        assertEquals(4L, encrypted.lastSeq)
        assertEquals(input, SyncBatchEncryption.decrypt(engine, secret, encrypted))
    }

    @Test
    fun `every batch binding field authenticates and tampering fails closed`() {
        val input = batch(1)
        val encrypted = SyncBatchEncryption.encrypt(engine, secret, input, path)
        val altered = listOf(
            encrypted.copy(protocolVersion = 2),
            encrypted.copy(spaceId = "other"),
            encrypted.copy(generation = 2),
            encrypted.copy(batchId = "other"),
            encrypted.copy(path = ".mihon-sync/batches/actor/1/other.json"),
            encrypted.copy(
                ciphertext = SyncAeadCiphertext(
                    encrypted.ciphertext.bytes.also {
                        it[it.lastIndex] =
                            (it.last() + 1).toByte()
                    },
                ),
            ),
        )
        altered.forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                SyncBatchEncryption.decrypt(engine, secret, candidate)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncBatchEncryption.decrypt(engine, SyncSecret.fromBytes(ByteArray(32) { 1 }), encrypted)
        }
    }

    @Test
    fun `malformed recovery input cannot escape in failure diagnostics`() {
        val marker = "private-material-must-not-appear-in-diagnostics"
        val result = SyncRecoveryCodec.decode("{\"unexpected\":\"$marker\"}")
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull().toString().contains(marker))
        assertFalse(result.exceptionOrNull()?.cause.toString().contains(marker))
    }

    @Test
    fun `recovery round trip checks key format scope and version`() {
        val bundle = SyncRecoveryCodec.generate("space", 1, "synthetic-key-id-1", { secret.bytes }, 1)
        val encoded = SyncRecoveryCodec.encode(bundle.data)
        val restored = SyncRecoveryCodec.decode(encoded).getOrThrow()
        assertEquals(secret, SyncRecoveryCodec.importSecret(restored, "space", 1).getOrThrow())
        assertTrue(SyncRecoveryCodec.importSecret(restored, "other", 1).isFailure)
        assertTrue(SyncRecoveryCodec.importSecret(restored, "space", 2).isFailure)
        assertTrue(SyncRecoveryCodec.importSecret(restored.copy(rawKeyset = "z".repeat(64)), "space", 1).isFailure)
        assertTrue(SyncRecoveryCodec.decode(encoded.replace("AES-256-GCM", "unknown")).isFailure)
        assertTrue(SyncRecoveryCodec.decode(" ".repeat(16 * 1024 + 1) + encoded).isFailure)
    }

    @Test
    fun `batch size scope and actor boundaries reject the whole upload`() {
        val valid = batch(1)
        val invalid = listOf(
            batch(*(1L..257L).toList().toLongArray()),
            batch(1, 1),
            valid.copy(events = emptyList()),
            valid.copy(events = valid.events.map { it.copy(spaceId = "other") }),
            batch(1, 2).let { it.copy(events = listOf(it.events.first(), it.events.last().copy(actorId = "other"))) },
        )
        invalid.forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                SyncBatchEncryption.encrypt(engine, secret, candidate, path)
            }
        }
    }

    @Test
    fun `wire metadata and mixed actor epochs cannot be accepted after authentication`() {
        val encrypted = SyncBatchEncryption.encrypt(engine, secret, batch(1), path)
        listOf(
            encrypted.copy(actorId = "other"),
            encrypted.copy(epoch = 2),
            encrypted.copy(firstSeq = 0),
            encrypted.copy(lastSeq = 2),
            encrypted.copy(plaintextDigest = ByteArray(32)),
        ).forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                SyncBatchEncryption.decrypt(engine, secret, candidate)
            }
        }
        for (otherActor in listOf(false, true)) {
            val input = batch(1, 2).let {
                val changed = if (otherActor) {
                    it.events.last().copy(
                        actorId = "other",
                    )
                } else {
                    it.events.last().copy(epoch = 2)
                }
                it.copy(events = listOf(it.events.first(), changed))
            }
            assertThrows(IllegalArgumentException::class.java) {
                SyncBatchEncryption.encrypt(engine, secret, input, path)
            }
            val bytes = SyncBatchCodec.rawEncode(input).encodeToByteArray()
            val candidate = encrypted.copy(
                plaintextDigest = engine.sha256(bytes),
                lastSeq = 2,
                ciphertext = engine.encrypt(secret, bytes, encrypted.binding().canonicalAad()),
            )
            assertThrows(IllegalArgumentException::class.java) {
                SyncBatchEncryption.decrypt(engine, secret, candidate)
            }
        }
    }

    @Test
    fun `plaintext ciphertext and digest have independent byte boundaries`() {
        val encrypted = SyncBatchEncryption.encrypt(engine, secret, batch(1), path)
        val tooLarge = ByteArray(512 * 1024 + 1)
        val candidate = encrypted.copy(
            plaintextDigest = engine.sha256(tooLarge),
            ciphertext = engine.encrypt(secret, tooLarge, encrypted.binding().canonicalAad()),
        )
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                SyncBatchEncryption.decrypt(engine, secret, candidate)
            }
        assertTrue(failure.message.orEmpty().contains("plaintext batch exceeds"))
        assertThrows(IllegalArgumentException::class.java) { SyncAeadCiphertext(ByteArray(1024 * 1024 + 1)) }
        assertThrows(IllegalArgumentException::class.java) { SyncAeadCiphertext(ByteArray(27)) }

        val largeBatch = batch(*(1L..256L).toList().toLongArray()).let { input ->
            input.copy(
                events = input.events.map { event ->
                    event.copy(
                        effects = event.effects.map {
                            it.copy(
                                objectKey = it.objectKey.copy(
                                    originalUrl =
                                    "/" + "x".repeat(2047),
                                ),
                            )
                        },
                    )
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncBatchEncryption.encrypt(engine, secret, largeBatch, path)
        }
        val suppliedDigest = encrypted.plaintextDigest
        val frozen = encrypted.copy(plaintextDigest = suppliedDigest)
        suppliedDigest.fill(0)
        frozen.plaintextDigest.fill(0)
        assertArrayEquals(encrypted.plaintextDigest, frozen.plaintextDigest)
        assertEquals(batch(1), SyncBatchEncryption.decrypt(engine, secret, frozen))
    }

    private fun batch(vararg seqs: Long): SyncBatch = SyncBatch(
        1,
        "space",
        1,
        "batch",
        seqs.map { seq ->
            SyncEventEnvelope(
                1, "space", 1, "actor", 1, seq, SyncCategory.FAVORITE,
                listOf(
                    SyncEffect(
                        "favorite",
                        SyncObjectKey(SyncObjectType.MANGA, "1", originalUrl = "/manga"),
                        SyncField.FAVORITE,
                        SyncEffectKind.ADD,
                    ),
                ),
                SyncOrigin.USER, batchId = "batch",
            )
        },
    )

    @Test
    fun `frozen ciphertext does not retain the callers mutable byte array`() {
        val input = ByteArray(64) { it.toByte() }
        val original = input.copyOf()
        val frozen = SyncAeadCiphertext(input)
        input.fill(0)
        assertArrayEquals(original, frozen.bytes)
    }

    @Test
    fun `reading a frozen ciphertext cannot modify a later upload retry`() {
        val original = ByteArray(64) { it.toByte() }
        val frozen = SyncAeadCiphertext(original.copyOf())
        val callerCopy = frozen.bytes
        callerCopy.fill(0)
        assertArrayEquals(original, frozen.bytes)
    }

    @Test
    fun `recovery data default diagnostics do not reveal key material`() {
        val material = "key-material-only-for-synthetic-test"
        val recovery = SyncRecoveryData(
            formatVersion = 1,
            algorithm = SYNC_AEAD_ALGORITHM,
            keyId = "synthetic-key-identifier",
            wrappedKey = material,
            createdAtMillis = 1,
        )
        assertFalse(recovery.toString().contains(material))
    }
}
