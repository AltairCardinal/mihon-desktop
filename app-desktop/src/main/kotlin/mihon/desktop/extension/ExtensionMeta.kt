package mihon.desktop.extension

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Indicates how an extension was originally obtained and installed. */
@Serializable
enum class ExtensionOrigin {
    /** Pre-compiled JVM JAR downloaded from an extensions-desktop repository. */
    COMPILED_JAR,
    /** Converted from an Android APK using dex2jar at install time. */
    CONVERTED_APK,
}

/** Metadata saved alongside an installed extension JAR for version tracking. */
@Serializable
data class ExtensionMeta(
    val pkgName: String,
    val versionCode: Long,
    val versionName: String,
    val iconUrl: String = "",
    val repoUrl: String = "",
    val repoName: String = "",
    val repoFingerprint: String = "",
    val installedAt: Long = 0L,
    val artifactSha256: String = "",
    val source: ExtensionOrigin = ExtensionOrigin.COMPILED_JAR,
    /**
     * Fully-qualified Source class name extracted from AndroidManifest.xml
     * (`tachiyomi.extension.class` meta-data).
     *
     * Present for [ExtensionOrigin.CONVERTED_APK] extensions; null for
     * JVM-compiled JARs (which use ServiceLoader instead).
     */
    val extensionClass: String? = null,
)

private val metaJson = Json { ignoreUnknownKeys = true }

/** Reads the meta file for the given JAR, returning null if it doesn't exist or is malformed. */
internal fun readExtensionMeta(jarFile: File): ExtensionMeta? {
    val metaFile = metaFileFor(jarFile)
    if (!metaFile.exists()) return null
    return try {
        metaJson.decodeFromString<ExtensionMeta>(metaFile.readText())
    } catch (_: Exception) {
        null
    }
}

/** Saves an [ExtensionMeta] sidecar next to the given JAR file. */
internal fun writeExtensionMeta(jarFile: File, meta: ExtensionMeta) {
    metaFileFor(jarFile).writeText(metaJson.encodeToString(ExtensionMeta.serializer(), meta))
}

/** Deletes the meta sidecar for the given JAR file (if it exists). */
internal fun deleteExtensionMeta(jarFile: File) {
    metaFileFor(jarFile).delete()
}

private fun metaFileFor(jarFile: File): File =
    File(jarFile.parent, "${jarFile.nameWithoutExtension}.meta.json")

internal fun repositoryIdentityConflicts(existing: String, incoming: String): Boolean =
    existing.isNotBlank() && incoming.isNotBlank() && !existing.equals(incoming, ignoreCase = true)
