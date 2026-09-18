package eu.kanade.tachiyomi.extension.model

import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.RepositoryIdentity

internal fun Extension.Available.toArtifact(url: String = downloadUrl) = ExtensionArtifact(
    name = name,
    packageName = pkgName,
    versionName = versionName,
    versionCode = versionCode,
    language = lang,
    isNsfw = isNsfw,
    sources = sources.map { ExtensionSourceDescriptor(it.id, it.lang, it.name, it.baseUrl) },
    repository = RepositoryIdentity(repoUrl, repoName, repoFingerprint),
    downloadUrl = url,
    iconUrl = iconUrl,
    declaredSha256 = declaredSha256,
    declaredLibVersion = libVersion,
)

internal fun ExtensionArtifact.toAvailable() = Extension.Available(
    name = name,
    pkgName = packageName,
    versionName = versionName,
    versionCode = versionCode,
    libVersion = libVersion,
    lang = language,
    isNsfw = isNsfw,
    sources = sources.map {
        Extension.Available.Source(
            id = it.id,
            lang = it.language,
            name = it.name,
            baseUrl = it.baseUrl,
        )
    },
    apkName = (apkUrl ?: downloadUrl).substringAfterLast('/'),
    iconUrl = iconUrl,
    repoUrl = repository.baseUrl,
    repoName = repository.name,
    repoFingerprint = repository.signingKeyFingerprint,
    declaredSha256 = declaredSha256,
    downloadUrl = apkUrl ?: downloadUrl,
)
