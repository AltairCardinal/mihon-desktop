package mihon.desktop.extension

/**
 * An extension available for installation from a repository.
 */
data class DesktopAvailableExtension(
    val name: String,
    val pkgName: String,
    val versionName: String,
    val versionCode: Long,
    val lang: String,
    val isNsfw: Boolean,
    /** URL to download the JAR file. */
    val jarUrl: String,
    val iconUrl: String,
    val repoUrl: String,
)
