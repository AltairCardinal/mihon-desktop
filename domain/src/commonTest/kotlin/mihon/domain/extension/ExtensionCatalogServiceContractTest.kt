package mihon.domain.extension

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.service.ExtensionStoreCatalogDecoder
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalSerializationApi::class)
class ExtensionCatalogServiceContractTest {

    @Test
    fun `protobuf name lengths cannot be mistaken for JSON delimiters`() {
        val repository =
            ExtensionRepo("https://repo.example", "Store", null, "https://repo.example", "trusted-fingerprint")
        for (length in listOf(91, 123)) {
            val store =
                WireStore(
                    "A".repeat(length),
                    "Badge",
                    "trusted-fingerprint",
                    WireContact("https://repo.example"),
                    WireList(),
                )
            val bytes = ProtoBuf.encodeToByteArray(WireStore.serializer(), store)
            val decoded = ExtensionStoreCatalogDecoder.decode(bytes, "https://repo.example/index.pb", repository)
            assertEquals(store.name, decoded.store.name)
            assertTrue(decoded.entries.isEmpty())
        }
    }

    @Test
    fun `unknown top level meta does not change the v2 wire format`() {
        val repository =
            ExtensionRepo("https://repo.example", "Store", null, "https://repo.example", "trusted-fingerprint")
        for (unknown in listOf("{}", "\"optional\"")) {
            val payload = V2_STORE_JSON.replace("\"name\": \"Store\"", "\"meta\": $unknown, \"name\": \"Store\"")
            val decoded = ExtensionStoreCatalogDecoder.decode(
                payload.encodeToByteArray(),
                "https://repo.example/index.json",
                repository,
            )
            assertEquals("eu.kanade.example", decoded.entries.single().artifact.packageName)
        }
    }

    @Serializable
    private data class WireStore(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val badgeLabel: String,
        @ProtoNumber(3) val signingKey: String,
        @ProtoNumber(4) val contact: WireContact,
        @ProtoNumber(101) val extensionList: WireList,
    )

    @Serializable
    private data class WireContact(@ProtoNumber(1) val website: String)

    @Serializable
    private data class WireList(@ProtoNumber(1) val extensions: List<String> = emptyList())

    @Test
    fun `damaged gzip is malformed catalog data rather than a network failure`() {
        val repository =
            ExtensionRepo("https://repo.example", "Store", null, "https://repo.example", "trusted-fingerprint")
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionStoreCatalogDecoder.decode(
                byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00),
                "https://repo.example/index.pb",
                repository,
            )
        }
    }

    @Test
    fun `frozen real Keiyoushi gzip protobuf store decodes through the shared production decoder`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/keiyoushi-index.pb")).use { it.readBytes() }
        val repository = ExtensionRepo(
            "https://github.com/keiyoushi/extensions/raw/repo",
            "Keiyoushi",
            null,
            "https://keiyoushi.github.io",
            "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
        )
        val decoded = ExtensionStoreCatalogDecoder.decode(bytes, "${repository.baseUrl}/index.pb", repository)
        assertEquals("Keiyoushi", decoded.store.name)
        assertTrue(decoded.entries.isNotEmpty())
        assertTrue(decoded.entries.any { it.artifact.libVersion == 1.6 })
        assertTrue(decoded.entries.all { it.artifact.apkUrl?.startsWith("https://") == true })
    }

    @Test
    fun `non finite library versions are malformed instead of installable`() {
        val repository =
            ExtensionRepo("https://repo.example", "Store", null, "https://repo.example", "trusted-fingerprint")
        for (version in listOf("NaN", "Infinity", "-Infinity")) {
            assertThrows(IllegalArgumentException::class.java) {
                ExtensionStoreCatalogDecoder.decode(
                    V2_STORE_JSON.replace(
                        "\"extensionLib\": \"1.6\"",
                        "\"extensionLib\": \"$version\"",
                    ).encodeToByteArray(),
                    "https://repo.example/store.json",
                    repository,
                )
            }
        }
    }

    @Test
    fun `v2 store preserves explicit metadata and catalog semantics`() {
        val repository = ExtensionRepo(
            baseUrl = "https://repo.example",
            name = "legacy name",
            shortName = "legacy",
            website = "https://repo.example/about",
            signingKeyFingerprint = "trusted-fingerprint",
        )

        val decoded = ExtensionStoreCatalogDecoder.decode(
            bytes = V2_STORE_JSON.encodeToByteArray(),
            indexUrl = "https://repo.example/store.json",
            repository = repository,
            json = Json { ignoreUnknownKeys = true },
        )

        assertEquals("Store badge", decoded.store.badgeLabel)
        assertEquals("discord#store", decoded.store.contact.discord)
        assertEquals("https://repo.example/about", decoded.store.contact.website)
        assertEquals("https://repo.example/jar/example.jar", decoded.entries.single().artifact.downloadUrl)
        assertEquals("https://repo.example/apk/example.apk", decoded.entries.single().artifact.apkUrl)
        assertEquals("https://repo.example/jar/example.jar", decoded.entries.single().artifact.jarUrl)
        assertEquals(1.6, decoded.entries.single().artifact.libVersion)
        assertTrue(decoded.entries.single().artifact.isNsfw)
        assertEquals("all", decoded.entries.single().artifact.language)
        assertEquals(ExtensionCompatibility.Compatible, decoded.entries.single().compatibility)
    }

    @Test
    fun `remote extension list wins over inline list and metadata values do not change format detection`() {
        val repository = ExtensionRepo(
            baseUrl = "https://repo.example",
            name = "legacy name",
            shortName = "legacy",
            website = "https://repo.example/about",
            signingKeyFingerprint = "trusted-fingerprint",
        )
        val remoteUrl = "https://repo.example/remote-list.pb"
        val response = V2_STORE_JSON
            .replace("\"discord#store\"", "\"meta\"")
            .replace(
                "\"extensionList\": {",
                "\"extensionListUrl\": \"$remoteUrl\",\n              \"extensionList\": {",
            )

        val decoded = ExtensionStoreCatalogDecoder.decode(
            bytes = response.encodeToByteArray(),
            indexUrl = "https://repo.example/store.json",
            repository = repository,
        )

        assertEquals(remoteUrl, decoded.nextUrl)
        assertFalse(decoded.entries.isNotEmpty())
        assertEquals("meta", decoded.store.contact.discord)
    }

    @Test
    fun `v2 catalog cannot change the trusted repository identity`() {
        val mismatched = V2_STORE_JSON.replace("trusted-fingerprint", "different-fingerprint")

        assertThrows(IllegalArgumentException::class.java) {
            ExtensionStoreCatalogDecoder.decode(
                mismatched.encodeToByteArray(),
                "https://repo.example/repo.json",
                ExtensionRepo(
                    baseUrl = "https://repo.example",
                    name = "legacy name",
                    shortName = "legacy",
                    website = "https://repo.example/about",
                    signingKeyFingerprint = "trusted-fingerprint",
                ),
            )
        }
    }

    @Test
    fun `catalog URL rejects userinfo and non-loopback HTTP`() {
        val repository = ExtensionRepo(
            baseUrl = "https://repo.example",
            name = "legacy name",
            shortName = "legacy",
            website = "https://repo.example/about",
            signingKeyFingerprint = "trusted-fingerprint",
        )
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionStoreCatalogDecoder.decode(
                V2_STORE_JSON.encodeToByteArray(),
                "https://user:password@repo.example/repo.json",
                repository,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionStoreCatalogDecoder.decode(
                V2_STORE_JSON.encodeToByteArray(),
                "http://repo.example/repo.json",
                repository,
            )
        }
    }

    @Test
    fun `v2 store rejects missing required identity fields instead of falling back to legacy`() {
        val repository = ExtensionRepo(
            baseUrl = "https://repo.example",
            name = "legacy name",
            shortName = "legacy",
            website = "https://repo.example/about",
            signingKeyFingerprint = "trusted-fingerprint",
        )

        assertThrows(IllegalArgumentException::class.java) {
            ExtensionStoreCatalogDecoder.decode(
                V2_STORE_JSON.replace("\"badgeLabel\": \"Store badge\",\n", "").encodeToByteArray(),
                "https://repo.example/store.json",
                repository,
            )
        }
    }

    @Test
    fun `catalog hop rejects a repeated URL`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionStoreCatalogDecoder.requireUnvisitedCatalogUrl(
                setOf("https://repo.example/store.json"),
                "https://repo.example/store.json",
            )
        }
    }

    @Test
    fun `gzip catalog is bounded decoded through the shared resolver`() {
        val repository = ExtensionRepo(
            baseUrl = "https://repo.example",
            name = "legacy name",
            shortName = "legacy",
            website = "https://repo.example/about",
            signingKeyFingerprint = "trusted-fingerprint",
        )
        val compressed = Buffer().also { target ->
            GzipSink(target).buffer().use { it.write(V2_STORE_JSON.encodeToByteArray()) }
        }.readByteArray()

        val decoded = ExtensionStoreCatalogDecoder.decode(
            compressed,
            "https://repo.example/store.json",
            repository,
        )

        assertEquals("eu.kanade.example", decoded.entries.single().artifact.packageName)
    }

    private companion object {
        val V2_STORE_JSON = """
            {
              "name": "Store",
              "badgeLabel": "Store badge",
              "signingKey": "trusted-fingerprint",
              "contact": {"website": "https://repo.example/about", "discord": "discord#store"},
              "extensionList": {
                "extensions": [{
                  "name": "Example",
                  "packageName": "eu.kanade.example",
                  "resources": {
                    "apkUrl": "https://repo.example/apk/example.apk",
                    "iconUrl": "https://repo.example/icon/example.png",
                    "jarUrl": "https://repo.example/jar/example.jar"
                  },
                  "extensionLib": "1.6",
                  "versionCode": 160,
                  "versionName": "1.4.99",
                  "contentWarning": "CONTENT_WARNING_MIXED",
                  "sources": [
                    {"id": 1, "name": "EN", "language": "en", "homeUrl": "https://en.example"},
                    {"id": 2, "name": "ZH", "language": "zh", "homeUrl": "https://zh.example"}
                  ]
                }]
              }
            }
        """.trimIndent()
    }
}
