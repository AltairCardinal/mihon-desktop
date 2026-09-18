package mihon.domain.sync.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

// JSON-string escaping can double the maximum batch bytes; the envelope needs a separate bounded allowance.
const val SYNC_MAX_SPACE_PAYLOAD_BYTES = SYNC_MAX_CIPHERTEXT_BYTES + 16 * 1024

sealed interface SyncSpaceProtection {
    data object None : SyncSpaceProtection

    data class Password(
        val kdf: String,
        val iterations: Int,
        val saltHex: String,
        val aead: String,
        val wrappedKeyHex: String,
    ) : SyncSpaceProtection
}

data class SyncSpaceDescriptor(
    val spaceId: String,
    val generation: Long,
    val protection: SyncSpaceProtection,
    val spaceFormatVersion: Int = 2,
    val eventProtocolVersion: Int = 1,
) {
    val mode: String get() = if (protection is SyncSpaceProtection.None) "none" else "password"
}

class SyncSpaceMaterial(val descriptor: SyncSpaceDescriptor, val secret: SyncSecret?) {
    init {
        SyncSpaceDescriptorCodec.validate(descriptor)
        require((descriptor.protection is SyncSpaceProtection.None) == (secret == null)) {
            "space material does not match its protection mode"
        }
    }

    override fun toString(): String = "SyncSpaceMaterial(<redacted>)"
}

object SyncSpaceDescriptorCodec {
    const val PATH = ".mihon-sync/space.json"

    fun encode(descriptor: SyncSpaceDescriptor): ByteArray {
        validate(descriptor)
        return buildJsonObject {
            put("application", "mihon-sync")
            put("spaceFormatVersion", descriptor.spaceFormatVersion)
            put("eventProtocolVersion", descriptor.eventProtocolVersion)
            put("spaceId", descriptor.spaceId)
            put("generation", descriptor.generation)
            put(
                "protection",
                buildJsonObject {
                    put("mode", descriptor.mode)
                    (descriptor.protection as? SyncSpaceProtection.Password)?.let {
                        put("kdf", it.kdf)
                        put("iterations", it.iterations)
                        put("saltHex", it.saltHex)
                        put("aead", it.aead)
                        put("wrappedKeyHex", it.wrappedKeyHex)
                    }
                },
            )
        }.toString().encodeToByteArray()
    }

    fun decode(bytes: ByteArray): Result<SyncSpaceDescriptor> = try {
        val root = strictSyncObject(bytes, 16 * 1024)
        require(
            root.keys == setOf(
                "application",
                "spaceFormatVersion",
                "eventProtocolVersion",
                "spaceId",
                "generation",
                "protection",
            ),
        )
        require(root.string("application") == "mihon-sync")
        val protection = root.getValue("protection").jsonObject
        val mode = protection.string("mode")
        val parsed = when (mode) {
            "none" -> {
                require(protection.keys == setOf("mode"))
                SyncSpaceProtection.None
            }
            "password" -> {
                require(protection.keys == setOf("mode", "kdf", "iterations", "saltHex", "aead", "wrappedKeyHex"))
                require(!protection.getValue("iterations").jsonPrimitive.isString)
                SyncSpaceProtection.Password(
                    protection.string("kdf"),
                    protection.getValue("iterations").jsonPrimitive.int,
                    protection.string("saltHex"),
                    protection.string("aead"),
                    protection.string("wrappedKeyHex"),
                )
            }
            else -> error("unsupported protection mode")
        }
        listOf("generation", "spaceFormatVersion", "eventProtocolVersion").forEach {
            require(!root.getValue(it).jsonPrimitive.isString)
        }
        Result.success(
            SyncSpaceDescriptor(
                root.string("spaceId"),
                root.getValue("generation").jsonPrimitive.long,
                parsed,
                root.getValue("spaceFormatVersion").jsonPrimitive.int,
                root.getValue("eventProtocolVersion").jsonPrimitive.int,
            ).also(::validate),
        )
    } catch (_: Exception) {
        Result.failure(SyncCryptoException("space descriptor is malformed or unsupported"))
    }

    fun validate(descriptor: SyncSpaceDescriptor) {
        require(descriptor.spaceFormatVersion == 2 && descriptor.eventProtocolVersion == 1) {
            "unsupported sync space format"
        }
        require(descriptor.spaceId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && descriptor.generation >= 0) {
            "invalid sync space identity"
        }
        (descriptor.protection as? SyncSpaceProtection.Password)?.let {
            require(it.kdf == "PBKDF2-HMAC-SHA256" && it.iterations == 600_000 && it.aead == SYNC_AEAD_ALGORITHM) {
                "unsupported space protection parameters"
            }
            require(it.saltHex.matches(Regex("[0-9a-f]{32}")) && it.wrappedKeyHex.matches(Regex("[0-9a-f]{120}"))) {
                "invalid space protection material"
            }
        }
    }
}

/** All v2 authentication context uses byte lengths, independent of platform string representation. */
fun SyncSpaceDescriptor.authenticationData(domain: String, vararg fields: String): ByteArray =
    (
        listOf(
            domain,
            spaceFormatVersion.toString(),
            eventProtocolVersion.toString(),
            mode,
            spaceId,
            generation.toString(),
        ) + fields
        )
        .joinToString("\u0000") { "${it.encodeToByteArray().size}:$it" }.encodeToByteArray()

/** A v2 envelope is deliberately distinct from AEAD ciphertext, including in unencrypted spaces. */
class SyncSpacePayload(input: ByteArray) : SyncPayload {
    private val value = input.copyOf()
    override val bytes: ByteArray get() = value.copyOf()

    init {
        require(value.size in 1..SYNC_MAX_SPACE_PAYLOAD_BYTES) { "space payload exceeds limit" }
    }

    override fun equals(other: Any?): Boolean = other is SyncSpacePayload && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = "SyncSpacePayload(${value.size} bytes)"
}

object SyncSpacePayloadCodec {
    fun encode(
        engine: SyncAeadEngine,
        material: SyncSpaceMaterial,
        binding: SyncCryptoBinding,
        plaintext: ByteArray,
    ): SyncSpacePayload {
        requireBinding(material.descriptor, binding)
        require(plaintext.size <= 512 * 1024) { "space plaintext exceeds limit" }
        val descriptor = material.descriptor
        val payload = buildJsonObject {
            put("spaceFormatVersion", 2)
            put("mode", descriptor.mode)
            if (material.secret == null) {
                put("plaintext", plaintext.decodeToString(throwOnInvalidSequence = true))
            } else {
                val aad = descriptor.authenticationData("mihon-sync-payload-v2", binding.batchId, binding.path)
                put("ciphertextBase64", engine.encrypt(material.secret, plaintext, aad).bytes.toByteString().base64())
            }
        }
        return SyncSpacePayload(payload.toString().encodeToByteArray())
    }

    fun decode(
        engine: SyncAeadEngine,
        material: SyncSpaceMaterial,
        binding: SyncCryptoBinding,
        payload: SyncPayload,
    ): ByteArray {
        try {
            require(payload is SyncSpacePayload)
            requireBinding(material.descriptor, binding)
            val root = strictSyncObject(payload.bytes, SYNC_MAX_SPACE_PAYLOAD_BYTES)
            val version = root.getValue("spaceFormatVersion").jsonPrimitive
            require(!version.isString && version.int == 2)
            require(root.string("mode") == material.descriptor.mode)
            val plaintext = if (material.secret == null) {
                require(root.keys == setOf("spaceFormatVersion", "mode", "plaintext"))
                root.string("plaintext").encodeToByteArray()
            } else {
                require(root.keys == setOf("spaceFormatVersion", "mode", "ciphertextBase64"))
                val encoded = root.string("ciphertextBase64")
                val ciphertext = requireNotNull(encoded.decodeBase64())
                require(ciphertext.base64() == encoded)
                engine.decrypt(
                    material.secret,
                    SyncAeadCiphertext(ciphertext.toByteArray()),
                    material.descriptor.authenticationData("mihon-sync-payload-v2", binding.batchId, binding.path),
                )
            }
            require(plaintext.size <= 512 * 1024)
            return plaintext
        } catch (_: Exception) {
            throw SyncCryptoException("space payload verification failed")
        }
    }

    private fun requireBinding(descriptor: SyncSpaceDescriptor, binding: SyncCryptoBinding) {
        require(
            binding.protocolVersion == descriptor.eventProtocolVersion && binding.spaceId == descriptor.spaceId &&
                binding.generation == descriptor.generation,
        ) {
            "space payload binding mismatch"
        }
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let {
    require(it.isString)
    it.content
}

/** Json parsing validates grammar; this bounded second walk rejects duplicate object keys at every depth. */
private fun strictSyncObject(bytes: ByteArray, limit: Int): JsonObject {
    require(bytes.size <= limit)
    val text = bytes.decodeToString(throwOnInvalidSequence = true)
    var position = 0
    fun whitespace() {
        while (position < text.length && text[position].isWhitespace()) position++
    }
    fun string(): String {
        val start = position++
        while (text[position] != '"') {
            if (text[position] == '\\') position++
            position++
        }
        position++
        return Json.decodeFromString<String>(text.substring(start, position))
    }
    fun value(depth: Int) {
        require(depth <= 8)
        whitespace()
        when (text[position]) {
            '{' -> {
                position++
                whitespace()
                val keys = mutableSetOf<String>()
                if (text[position] != '}') {
                    while (true) {
                        whitespace()
                        require(keys.add(string()))
                        whitespace()
                        position++
                        value(depth + 1)
                        whitespace()
                        if (text[position] != ',') break
                        position++
                    }
                }
                position++
            }
            '[' -> {
                position++
                whitespace()
                if (text[position] != ']') {
                    while (true) {
                        value(depth + 1)
                        whitespace()
                        if (text[position] != ',') break
                        position++
                    }
                }
                position++
            }
            '"' -> string()
            else -> while (position < text.length && text[position] !in ",]} \t\r\n") position++
        }
    }
    value(0)
    return Json.parseToJsonElement(text).jsonObject
}
