package eu.kanade.tachiyomi.extension.util

import android.os.Bundle
import mihon.domain.extension.model.extractExtensionLibVersion

internal data class ExtensionPackageMetadata(
    val name: String,
    val libVersion: Double?,
    val isNsfw: Boolean,
)

internal fun readExtensionPackageMetadata(
    metadata: Bundle?,
    legacyLabel: String,
    versionName: String?,
): ExtensionPackageMetadata {
    // A malformed explicit declaration must not inherit a legacy version-name fallback.
    @Suppress("DEPRECATION")
    val libVersion = if (metadata?.containsKey("tachiyomix.extensionLib") == true) {
        metadata.get("tachiyomix.extensionLib")?.toString()?.toDoubleOrNull()
    } else {
        versionName?.let(::extractExtensionLibVersion)
    }
    return ExtensionPackageMetadata(
        name = metadata?.getString("tachiyomix.name") ?: legacyLabel.substringAfter("Tachiyomi: "),
        libVersion = libVersion?.takeIf { it.isFinite() && it > 0.0 },
        isNsfw = (metadata?.getInt("tachiyomix.contentWarning") ?: 0) > 0 ||
            metadata?.getInt("tachiyomi.extension.nsfw") == 1,
    )
}
