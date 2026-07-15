package mihon.domain.extensionrepo.service

import kotlinx.serialization.Serializable
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCatalogEntry
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.toIdentity
import mihon.domain.extensionrepo.model.ExtensionRepo

@Serializable
data class ExtensionRepoMetaDto(
    val meta: ExtensionRepoDto,
)

@Serializable
data class ExtensionRepoDto(
    val name: String,
    val shortName: String?,
    val website: String,
    val signingKeyFingerprint: String,
)

fun ExtensionRepoMetaDto.toExtensionRepo(baseUrl: String): ExtensionRepo {
    return ExtensionRepo(
        baseUrl = baseUrl,
        name = meta.name,
        shortName = meta.shortName,
        website = meta.website,
        signingKeyFingerprint = meta.signingKeyFingerprint,
    )
}

@Serializable
data class ExtensionRepoIndexEntryDto(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<ExtensionRepoSourceDto>? = null,
    val sha256: String? = null,
)

@Serializable
data class ExtensionRepoSourceDto(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

fun ExtensionRepoIndexEntryDto.toCatalogEntry(repository: ExtensionRepo): ExtensionCatalogEntry {
    val baseUrl = repository.baseUrl.trimEnd('/')
    val artifact = ExtensionArtifact(
        name = name.substringAfter("Tachiyomi: "),
        packageName = pkg,
        versionName = version,
        versionCode = code,
        language = lang,
        isNsfw = nsfw == 1,
        sources = sources.orEmpty().map {
            ExtensionSourceDescriptor(
                id = it.id,
                language = it.lang,
                name = it.name,
                baseUrl = it.baseUrl,
            )
        },
        repository = repository.toIdentity(),
        downloadUrl = "$baseUrl/apk/$apk",
        iconUrl = "$baseUrl/icon/$pkg.png",
        declaredSha256 = sha256,
    )
    return ExtensionCatalogEntry(artifact, artifact.compatibility())
}
