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
