package mihon.domain.extensionrepo.service

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCatalogEntry
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.model.toIdentity
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.model.normalizedSigningKeyFingerprint
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import okio.GzipSource
import okio.IOException
import okio.buffer

/** A shared result for both platform catalog adapters. */
data class ExtensionStoreCatalog(
    val store: ExtensionStore,
    val entries: List<ExtensionCatalogEntry>,
    val nextUrl: String? = null,
)

/**
 * Decodes the wire formats used by old repositories and the v2 store.
 *
 * Network clients remain platform-owned so HTTP status, retry headers and
 * cancellation stay on their production adapters. This class only consumes
 * the raw successful response bytes and therefore is shared by Android and
 * Desktop without duplicating protocol decisions.
 */
@OptIn(ExperimentalSerializationApi::class)
object ExtensionStoreCatalogDecoder {
    private const val MAX_COMPRESSED_CATALOG_BYTES = 16L * 1024 * 1024
    private const val MAX_DECOMPRESSED_CATALOG_BYTES = 64L * 1024 * 1024

    /** Follow store metadata and its list with a single protocol decision on both platforms. */
    suspend fun load(
        repository: ExtensionRepo,
        json: Json,
        fetch: suspend (String) -> ByteArray,
        parse: (() -> ExtensionStoreCatalog) -> ExtensionStoreCatalog = { it() },
    ): ExtensionStoreCatalog {
        var url = repository.indexUrl ?: "${repository.baseUrl.trimEnd('/')}/repo.json"
        val visited = mutableSetOf<String>()
        var remoteStore: ExtensionStore? = null
        repeat(4) {
            requireUnvisitedCatalogUrl(visited, url)
            visited += url
            val bytes = fetch(url)
            val store = remoteStore
            val result = parse {
                if (store != null) {
                    decodeExtensionList(bytes, url, repository, json).copy(store = store)
                } else {
                    decode(bytes, url, repository, json)
                }
            }
            val next = result.nextUrl ?: return result
            remoteStore = result.store.takeIf { !it.isLegacy && it.extensionListUrl != null }
            url = next
        }
        throw IllegalArgumentException("Extension repository followed too many catalog URLs")
    }

    fun decode(
        bytes: ByteArray,
        indexUrl: String,
        repository: ExtensionRepo,
        json: Json = Json { ignoreUnknownKeys = true },
        protoBuf: ProtoBuf = ProtoBuf,
        expectedFingerprint: String? = repository.signingKeyFingerprint,
    ): ExtensionStoreCatalog {
        requireSupportedCatalogUrl(indexUrl)
        val payload = bytes.decompressIfGzipped()
        val first = payload.firstOrNull { !it.toInt().toChar().isWhitespace() }
        val catalog = when (first.takeIf { payload.isJson(json) }) {
            '['.code.toByte() -> decodeLegacyEntries(payload, indexUrl, repository, json, strictUrl = true)
            '{'.code.toByte() -> decodeJson(payload, indexUrl, repository, json)
            else -> decodeProto(payload, indexUrl, repository, protoBuf)
        }
        expectedFingerprint?.let {
            require(
                catalog.store.signingKey.normalizedSigningKeyFingerprint() == it.normalizedSigningKeyFingerprint(),
            ) {
                "Catalog signing key does not match the trusted repository identity"
            }
        }
        catalog.nextUrl?.let(::requireSupportedCatalogUrl)
        return catalog
    }

    fun decodeExtensionList(
        bytes: ByteArray,
        indexUrl: String,
        repository: ExtensionRepo,
        json: Json = Json { ignoreUnknownKeys = true },
        protoBuf: ProtoBuf = ProtoBuf,
    ): ExtensionStoreCatalog {
        requireSupportedCatalogUrl(indexUrl)
        val payload = bytes.decompressIfGzipped()
        val first = payload.firstOrNull { !it.toInt().toChar().isWhitespace() }
        val entries = if (first == '{'.code.toByte() && payload.isJson(json)) {
            json.decodeFromString<NetworkExtensionStore.ExtensionList>(payload.decodeToString())
                .extensions
                .map { it.toCatalogEntry(repository) }
        } else {
            protoBuf.decodeFromByteArray(
                NetworkExtensionStore.ExtensionList.serializer(),
                payload,
            ).extensions.map { it.toCatalogEntry(repository) }
        }
        return ExtensionStoreCatalog(
            store = ExtensionStore(
                indexUrl = indexUrl,
                name = repository.name,
                badgeLabel = repository.shortName ?: repository.name,
                signingKey = repository.signingKeyFingerprint,
                contact = ExtensionStore.Contact(repository.website, repository.contactDiscord),
                isLegacy = false,
                extensionListUrl = indexUrl,
            ),
            entries = entries,
        )
    }

    fun decodeLegacyList(
        bytes: ByteArray,
        indexUrl: String,
        repository: ExtensionRepo,
        json: Json = Json { ignoreUnknownKeys = true },
    ): ExtensionStoreCatalog = decodeLegacyEntries(
        bytes.decompressIfGzipped().also { requireSupportedCatalogUrl(indexUrl) },
        indexUrl,
        repository,
        json,
        strictUrl = false,
    )

    private fun decodeJson(
        payload: ByteArray,
        indexUrl: String,
        repository: ExtensionRepo,
        json: Json,
    ): ExtensionStoreCatalog {
        val text = payload.decodeToString()
        val objectValue = json.parseToJsonElement(text).jsonObject
        val v2Fields = setOf("signingKey", "badgeLabel", "extensionList", "extensionListUrl")
        return if ("meta" in objectValue && objectValue.keys.none { it in v2Fields }) {
            val legacy = json.decodeFromString<ExtensionRepoMetaDto>(text)
            val store = legacy.toStore(indexUrl)
            ExtensionStoreCatalog(
                store = store,
                entries = emptyList(),
                nextUrl = legacy.indexV2Url ?: legacyBaseUrl(indexUrl) + "/index.min.json",
            )
        } else {
            val networkStore = json.decodeFromString<NetworkExtensionStore>(text)
            networkStore.toCatalog(indexUrl, repository, json)
        }
    }

    private fun decodeProto(
        payload: ByteArray,
        indexUrl: String,
        repository: ExtensionRepo,
        protoBuf: ProtoBuf,
    ): ExtensionStoreCatalog = protoBuf.decodeFromByteArray(
        NetworkExtensionStore.serializer(),
        payload,
    ).toCatalog(indexUrl, repository, null)

    private fun decodeLegacyEntries(
        payload: ByteArray,
        indexUrl: String,
        repository: ExtensionRepo,
        json: Json,
        strictUrl: Boolean,
    ): ExtensionStoreCatalog {
        require(!strictUrl || indexUrl.endsWith("/index.min.json")) {
            "A legacy catalog must be addressed as index.min.json"
        }
        val store = ExtensionStore(
            indexUrl = indexUrl,
            name = repository.name,
            badgeLabel = repository.shortName ?: repository.name,
            signingKey = repository.signingKeyFingerprint,
            contact = ExtensionStore.Contact(repository.website, null),
            isLegacy = true,
            extensionListUrl = null,
        )
        val entries = json.decodeFromString<List<ExtensionRepoIndexEntryDto>>(payload.decodeToString())
            .map { it.toCatalogEntry(repository) }
        return ExtensionStoreCatalog(store, entries)
    }

    private fun NetworkExtensionStore.toCatalog(
        indexUrl: String,
        repository: ExtensionRepo,
        json: Json?,
    ): ExtensionStoreCatalog {
        require(name.isNotBlank()) { "Store name is missing" }
        require(badgeLabel.isNotBlank()) { "Store badgeLabel is missing" }
        require(signingKey.isNotBlank()) { "Store signingKey is missing" }
        require(contact.website.isNotBlank()) { "Store contact website is missing" }
        require(extensionList != null || !extensionListUrl.isNullOrBlank()) {
            "Store contains neither an inline nor a remote extension list"
        }
        val store = ExtensionStore(
            indexUrl = indexUrl,
            name = name,
            badgeLabel = badgeLabel,
            signingKey = signingKey,
            contact = ExtensionStore.Contact(contact.website, contact.discord),
            isLegacy = false,
            extensionListUrl = extensionListUrl,
        )
        val entries = if (extensionListUrl.isNullOrBlank()) {
            extensionList?.extensions.orEmpty().map { it.toCatalogEntry(repository) }
        } else {
            emptyList()
        }
        return ExtensionStoreCatalog(
            store = store,
            entries = entries,
            nextUrl = extensionListUrl?.takeIf { entries.isEmpty() }?.also(::requireSupportedCatalogUrl),
        )
    }

    private fun NetworkExtensionStore.Extension.toCatalogEntry(
        repository: ExtensionRepo,
    ): ExtensionCatalogEntry {
        val libVersion = extensionLib.toDoubleOrNull()
        require(libVersion != null && libVersion.isFinite()) { "Extension $packageName has no valid extensionLib" }
        val downloadUrl = resources.jarUrl.ifBlank { resources.apkUrl }
        require(downloadUrl.isNotBlank()) { "Extension $packageName publishes no artifact" }
        val languageSet = sources.map { it.language }.toSet()
        val artifact = ExtensionArtifact(
            name = name,
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            language = if (languageSet.size == 1) languageSet.single() else "all",
            isNsfw = contentWarning == NetworkExtensionStore.ContentWarning.MIXED ||
                contentWarning == NetworkExtensionStore.ContentWarning.NSFW,
            sources = sources.map {
                ExtensionSourceDescriptor(it.id, it.language, it.name, it.homeUrl)
            },
            repository = repository.toIdentity(),
            downloadUrl = downloadUrl,
            iconUrl = resources.iconUrl,
            declaredSha256 = null,
            declaredLibVersion = libVersion,
            apkUrl = resources.apkUrl,
            jarUrl = resources.jarUrl.takeIf(String::isNotBlank),
        )
        return ExtensionCatalogEntry(artifact, artifact.compatibility())
    }

    private fun ExtensionRepoMetaDto.toStore(indexUrl: String): ExtensionStore = ExtensionStore(
        indexUrl = indexUrl,
        name = meta.name,
        badgeLabel = meta.shortName,
        signingKey = meta.signingKeyFingerprint,
        contact = ExtensionStore.Contact(meta.website, null),
        isLegacy = true,
        extensionListUrl = null,
    )

    private fun legacyBaseUrl(indexUrl: String): String = indexUrl
        .removeSuffix("/repo.json")
        .removeSuffix("/index.min.json")
        .trimEnd('/')

    private fun ByteArray.decompressIfGzipped(): ByteArray = if (
        size >= 2 && this[0] == 0x1f.toByte() && this[1] == 0x8b.toByte()
    ) {
        require(size.toLong() <= MAX_COMPRESSED_CATALOG_BYTES) {
            "Compressed catalog exceeds the maximum size"
        }
        try {
            GzipSource(Buffer().write(this)).buffer().use { source ->
                val output = Buffer()
                while (!source.exhausted()) {
                    source.read(output, 8192)
                    require(output.size <= MAX_DECOMPRESSED_CATALOG_BYTES) {
                        "Decompressed catalog exceeds the maximum size"
                    }
                }
                output.readByteArray()
            }
        } catch (error: IOException) {
            throw IllegalArgumentException("Catalog gzip payload is damaged", error)
        }
    } else {
        require(size.toLong() <= MAX_DECOMPRESSED_CATALOG_BYTES) {
            "Catalog exceeds the maximum size"
        }
        this
    }

    fun requireSupportedCatalogUrl(url: String) {
        val parsed = url.toHttpUrlOrNull()
        require(parsed != null && parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "Catalog URL must be an HTTP(S) URL without userinfo"
        }
        val loopback = parsed.host in setOf("localhost", "127.0.0.1", "::1")
        require(parsed.scheme == "https" || (parsed.scheme == "http" && loopback)) {
            "Catalog URL must use HTTPS or loopback HTTP"
        }
    }

    /** Validates and records a catalog hop so a malformed store cannot loop forever. */
    fun requireUnvisitedCatalogUrl(visitedUrls: Set<String>, nextUrl: String): String {
        requireSupportedCatalogUrl(nextUrl)
        require(nextUrl !in visitedUrls) { "Catalog URL cycle detected" }
        return nextUrl
    }

    private fun ByteArray.isJson(json: Json): Boolean {
        val first = firstOrNull { !it.toInt().toChar().isWhitespace() }
        if (first != '{'.code.toByte() && first != '['.code.toByte()) return false
        // A protobuf string tag is 0x0a, also a JSON whitespace byte. Validate JSON syntax
        // before choosing its decoder so protobuf length bytes cannot impersonate delimiters.
        return try {
            json.parseToJsonElement(decodeToString())
            true
        } catch (_: SerializationException) {
            false
        }
    }

    @Serializable
    private data class NetworkExtensionStore(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val badgeLabel: String,
        @ProtoNumber(3) val signingKey: String,
        @ProtoNumber(4) val contact: Contact,
        @ProtoNumber(101) val extensionList: ExtensionList? = null,
        @ProtoNumber(102) val extensionListUrl: String? = null,
    ) {
        @Serializable
        data class Contact(
            @ProtoNumber(1) val website: String,
            @ProtoNumber(2) val discord: String? = null,
        )

        @Serializable
        data class ExtensionList(@ProtoNumber(1) val extensions: List<Extension>)

        @Serializable
        data class Extension(
            @ProtoNumber(1) val name: String,
            @ProtoNumber(2) val packageName: String,
            @ProtoNumber(3) val resources: Resources,
            @ProtoNumber(4) val extensionLib: String,
            @ProtoNumber(5) val versionCode: Long,
            @ProtoNumber(6) val versionName: String,
            @ProtoNumber(7) val contentWarning: ContentWarning,
            @ProtoNumber(8) val sources: List<Source>,
        )

        @Serializable
        data class Resources(
            @ProtoNumber(1) val apkUrl: String,
            @ProtoNumber(2) val iconUrl: String,
            @ProtoNumber(501) val jarUrl: String = "",
        )

        @Serializable
        data class Source(
            @ProtoNumber(1) val id: Long,
            @ProtoNumber(2) val name: String,
            @ProtoNumber(3) val language: String,
            @ProtoNumber(4) val homeUrl: String = "",
            @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
            @ProtoNumber(7) val message: String? = null,
        )

        @Serializable
        enum class ContentWarning {
            @ProtoNumber(0)
            @JsonNames("CONTENT_WARNING_UNSPECIFIED")
            UNSPECIFIED,

            @ProtoNumber(1)
            @JsonNames("CONTENT_WARNING_SAFE")
            SAFE,

            @ProtoNumber(2)
            @JsonNames("CONTENT_WARNING_MIXED")
            MIXED,

            @ProtoNumber(3)
            @JsonNames("CONTENT_WARNING_NSFW")
            NSFW,
        }
    }
}
