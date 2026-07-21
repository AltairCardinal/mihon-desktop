package tachiyomi.data.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tachiyomi.domain.release.model.ReleaseAsset
import tachiyomi.domain.release.model.ReleaseChecksum
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.ReleasePackageType
import tachiyomi.domain.release.model.ReleaseTarget
import tachiyomi.domain.release.model.ReleaseVariant

/**
 * Contains information about the latest release from GitHub.
 */
@Serializable
data class GithubRelease(
    @SerialName("tag_name")
    val version: String,
    @SerialName("body")
    val info: String,
    @SerialName("html_url")
    val releaseLink: String,
    @SerialName("assets")
    val assets: List<GitHubAsset>,
)

/**
 * Asset class containing asset name and download url.
 */
@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url")
    val downloadLink: String,
    val digest: String? = null,
)

internal data class ParsedAsset(
    val downloadLink: String,
    val metadata: ReleaseAsset,
)

internal fun GitHubAsset.parse(version: String): ParsedAsset? {
    val escapedVersion = Regex.escape(version)
    val target = when {
        Regex("^mihon-$escapedVersion-foss\\.apk$").matches(name) ->
            ReleaseTarget(ReleaseOs.ANDROID, null, ReleasePackageType.APK, ReleaseVariant.FOSS)
        Regex("^mihon-$escapedVersion\\.apk$").matches(name) ->
            ReleaseTarget(ReleaseOs.ANDROID, null, ReleasePackageType.APK, ReleaseVariant.STANDARD)
        Regex("^mihon-desktop-windows-x86_64-$escapedVersion\\.msi$").matches(name) ->
            ReleaseTarget(ReleaseOs.WINDOWS, "x86_64", ReleasePackageType.MSI, ReleaseVariant.STANDARD)
        Regex("^mihon-desktop-macos-x86_64-$escapedVersion\\.dmg$").matches(name) ->
            ReleaseTarget(ReleaseOs.MACOS, "x86_64", ReleasePackageType.DMG, ReleaseVariant.STANDARD)
        Regex("^mihon-desktop-macos-arm64-$escapedVersion\\.dmg$").matches(name) ->
            ReleaseTarget(ReleaseOs.MACOS, "arm64", ReleasePackageType.DMG, ReleaseVariant.STANDARD)
        else -> {
            val match = Regex("^mihon-(arm64-v8a|armeabi-v7a|x86_64|x86)-$escapedVersion\\.apk$")
                .matchEntire(name) ?: return null
            ReleaseTarget(
                ReleaseOs.ANDROID,
                match.groupValues[1],
                ReleasePackageType.APK,
                ReleaseVariant.STANDARD,
            )
        }
    }
    val checksum = digest
        ?.split(":", limit = 2)
        ?.takeIf { it.size == 2 && it[0] == "sha256" && it[1].matches(Regex("[0-9a-fA-F]{64}")) }
        ?.let { ReleaseChecksum(it[0], it[1].lowercase()) }
    return ParsedAsset(downloadLink, ReleaseAsset(name, target, checksum))
}
