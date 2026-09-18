package mihon.data.sync

import mihon.data.sync.crypto.SyncAeadEngineFactory
import mihon.data.sync.crypto.SyncSpaceCrypto
import mihon.domain.sync.crypto.SyncCryptoBinding
import mihon.domain.sync.crypto.SyncSpaceDescriptorCodec
import mihon.domain.sync.crypto.SyncSpacePayloadCodec
import mihon.domain.sync.crypto.SyncSpaceProtection
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncSpaceContractTest {
    @Test
    fun `both modes preserve the maximum plaintext byte limit after envelope encoding`() {
        val engine = SyncAeadEngineFactory.create()
        val binding = SyncCryptoBinding(1, "space", 1, "batch", ".mihon-sync/batches/actor/1/batch.json")
        // Quotes exercise the additional JSON-string escaping in the unencrypted envelope.
        val plaintext = ByteArray(512 * 1024) { '"'.code.toByte() }
        for (password in listOf("", "password")) {
            val material = SyncSpaceCrypto.create("space", 1, password)
            val encoded = SyncSpacePayloadCodec.encode(engine, material, binding, plaintext)
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                plaintext,
                SyncSpacePayloadCodec.decode(engine, material, binding, encoded),
            )
        }
    }

    @Test
    fun `valid v2 plain descriptor is recognized before any key exists`() {
        val bytes = """
            {"application":"mihon-sync","spaceFormatVersion":2,"eventProtocolVersion":1,
             "spaceId":"space","generation":1,"protection":{"mode":"none"}}
        """.trimIndent().encodeToByteArray()
        val decoded = SyncSpaceDescriptorCodec.decode(bytes)
        assertTrue(decoded.isSuccess, "a valid v2 none descriptor must be supported")
        assertEquals("none", decoded.getOrThrow().mode)
    }

    @Test
    fun `empty password creates a space without any hidden data key`() {
        val created = SyncSpaceCrypto.create("space", 1, "")
        assertEquals("none", created.descriptor.mode)
        assertNull(created.secret)
    }

    @Test
    fun `password derivation matches independently generated UTF8 vector on both platforms`() {
        // Python hashlib.pbkdf2_hmac, SHA256, 600000 rounds, 32-byte result.
        val derived = SyncSpaceCrypto.derive(" 密碼e\u0301😀 ".encodeToByteArray(), ByteArray(16) { it.toByte() })
        assertEquals("c6a5b3262dd640d86afa099132967aa2b1e6faff94f081bd35f9b56a246aa294", derived.toByteString().hex())
    }

    @Test
    fun `password wrapping round trips without trimming normalizing or accepting wrong password`() {
        val password = " 密碼e\u0301😀 "
        val material = SyncSpaceCrypto.create("space", 1, password)
        val encoded = SyncSpaceDescriptorCodec.encode(material.descriptor)
        assertFalse(encoded.decodeToString().contains(password))
        val descriptor = SyncSpaceDescriptorCodec.decode(encoded).getOrThrow()
        assertEquals(material.secret, SyncSpaceCrypto.unlock(descriptor, password).getOrThrow().secret)
        for (wrong in listOf("", "wrong", password.trim(), " 密碼é😀 ")) {
            val failure = SyncSpaceCrypto.unlock(descriptor, wrong)
            assertTrue(failure.isFailure)
            assertFalse(failure.exceptionOrNull().toString().contains(password))
        }
        val protection = descriptor.protection as SyncSpaceProtection.Password
        val changed = protection.wrappedKeyHex.toCharArray().also {
            it[0] = if (it[0] == '0') '1' else '0'
        }.concatToString()
        val tampered = descriptor.copy(protection = protection.copy(wrappedKeyHex = changed))
        assertTrue(SyncSpaceCrypto.unlock(tampered, password).isFailure)
        assertTrue(SyncSpaceCrypto.unlock(descriptor.copy(spaceId = "different"), password).isFailure)
    }

    @Test
    fun `password length and Unicode errors never create an unencrypted space`() {
        assertEquals("password", SyncSpaceCrypto.create("space", 1, " ").descriptor.mode)
        assertEquals("password", SyncSpaceCrypto.create("space", 1, "a".repeat(1024)).descriptor.mode)
        for (invalid in listOf("a".repeat(1025), "界".repeat(342), "\uD800")) {
            assertThrows(IllegalArgumentException::class.java) { SyncSpaceCrypto.create("space", 1, invalid) }
        }
    }

    @Test
    fun `metadata rejects unknown versions downgrade malformed fields and duplicate keys`() {
        val plain = SyncSpaceDescriptorCodec.encode(SyncSpaceCrypto.create("space", 1, "").descriptor).decodeToString()
        val invalid = listOf(
            plain.replace("\"spaceFormatVersion\":2", "\"spaceFormatVersion\":3"),
            plain.replace("\"mode\":\"none\"", "\"mode\":\"future\""),
            plain.replace("\"mode\":\"none\"", "\"mode\":\"password\""),
            plain.replace("\"mode\":\"none\"", "\"mode\":\"password\",\"mode\":\"none\""),
            plain.replace("\"generation\":1", "\"generation\":\"1\""),
            plain.replace("\"generation\":1", "\"generation\":-1"),
            plain.replace("\"mode\":\"none\"", "\"mode\":\"none\",\"kdf\":\"other\""),
            plain.replace("\"spaceId\":\"space\"", "\"spaceId\":\"space\",\"spaceId\":\"other\""),
            "x".repeat(16385),
        )
        invalid.forEach { assertTrue(SyncSpaceDescriptorCodec.decode(it.encodeToByteArray()).isFailure) }
        assertTrue(SyncSpaceDescriptorCodec.decode(byteArrayOf(-1)).isFailure)
        val protected = SyncSpaceCrypto.create("space", 1, "password").descriptor
        val protection = protected.protection as SyncSpaceProtection.Password
        val oversized = protected.copy(protection = protection.copy(iterations = Int.MAX_VALUE))
        assertTrue(SyncSpaceCrypto.unlock(oversized, "password").isFailure)
    }
}
