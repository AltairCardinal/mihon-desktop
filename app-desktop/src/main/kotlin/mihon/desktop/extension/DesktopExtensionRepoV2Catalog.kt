package mihon.desktop.extension

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCatalogEntry
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.toIdentity
import mihon.domain.extensionrepo.model.ExtensionRepo

/**
 * Decodes the repository v2 catalog used by Keiyoushi.
 *
 * Desktop selects the signed JVM artifact when one is published. APK remains a per-entry
 * compatibility fallback for repositories that have adopted v2 metadata but do not publish JARs.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object DesktopExtensionRepoV2Catalog {
    fun decode(compressed: ByteArray, repository: ExtensionRepo): List<ExtensionCatalogEntry> {
        val payload = GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        val index = ProtoBuf.decodeFromByteArray(Index.serializer(), payload)
        require(index.signingKey.sameFingerprint(repository.signingKeyFingerprint)) {
            "Repository v2 signing key does not match the trusted repository identity"
        }
        return index.extensionList.extensions.map { it.toCatalogEntry(repository) }
    }

    private fun Extension.toCatalogEntry(repository: ExtensionRepo): ExtensionCatalogEntry {
        val downloadUrl = resources.jarUrl.ifBlank { resources.apkUrl }
        require(downloadUrl.isNotBlank()) { "Extension $packageName publishes no installable artifact" }
        val artifact = ExtensionArtifact(
            name = name,
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            language = legacyLanguage(resources.apkUrl, sources),
            isNsfw = contentWarning > CONTENT_WARNING_MIXED,
            sources = sources.map {
                ExtensionSourceDescriptor(
                    id = it.id,
                    language = it.language,
                    name = it.name,
                    baseUrl = it.homeUrl,
                )
            },
            repository = repository.toIdentity(),
            downloadUrl = downloadUrl,
            iconUrl = resources.iconUrl,
            declaredSha256 = null,
        )
        return ExtensionCatalogEntry(artifact, artifact.compatibility())
    }

    private fun legacyLanguage(
        apkUrl: String,
        sources: List<Source>,
    ): String {
        var language = apkUrl.substringAfterLast('/')
            .substringAfter("tachiyomi-", missingDelimiterValue = "")
            .substringBefore('.', missingDelimiterValue = "")
            .ifBlank { sources.firstOrNull()?.language.orEmpty() }
        if (sources.size == 1) {
            val sourceLanguage = sources.single().language
            if (
                sourceLanguage != language &&
                sourceLanguage !in GENERIC_LANGUAGES &&
                language !in GENERIC_LANGUAGES
            ) {
                language = sourceLanguage
            }
        }
        return language
    }

    private fun String.sameFingerprint(other: String): Boolean =
        normalizedFingerprint() == other.normalizedFingerprint()

    private fun String.normalizedFingerprint(): String = replace(":", "").trim().lowercase()

    @Serializable
    private data class Index(
        @ProtoNumber(3)
        val signingKey: String = "",
        @ProtoNumber(101)
        val extensionList: ExtensionList = ExtensionList(),
    )

    @Serializable
    private data class ExtensionList(
        @ProtoNumber(1)
        val extensions: List<Extension> = emptyList(),
    )

    @Serializable
    private data class Extension(
        @ProtoNumber(1)
        val name: String = "",
        @ProtoNumber(2)
        val packageName: String = "",
        @ProtoNumber(3)
        val resources: Resources = Resources(),
        @ProtoNumber(4)
        val extensionLib: String = "",
        @ProtoNumber(5)
        val versionCode: Long = 0,
        @ProtoNumber(6)
        val versionName: String = "",
        @ProtoNumber(7)
        val contentWarning: Int = 0,
        @ProtoNumber(8)
        val sources: List<Source> = emptyList(),
    )

    @Serializable
    private data class Resources(
        @ProtoNumber(1)
        val apkUrl: String = "",
        @ProtoNumber(2)
        val iconUrl: String = "",
        @ProtoNumber(501)
        val jarUrl: String = "",
    )

    @Serializable
    private data class Source(
        @ProtoNumber(1)
        val id: Long = 0,
        @ProtoNumber(2)
        val name: String = "",
        @ProtoNumber(3)
        val language: String = "",
        @ProtoNumber(4)
        val homeUrl: String = "",
        @ProtoNumber(5)
        val mirrorUrls: List<String> = emptyList(),
        @ProtoNumber(7)
        val message: String? = null,
    )

    private const val CONTENT_WARNING_MIXED = 2
    private val GENERIC_LANGUAGES = setOf("all", "other")
}
