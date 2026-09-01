package mihon.desktop.download

import mihon.desktop.platform.DesktopDownloadDirectoryPolicy
import mihon.desktop.platform.DesktopDownloadDirectoryProbeResult
import mihon.desktop.platform.DesktopDownloadDirectorySelection
import mihon.desktop.platform.DesktopDownloadDirectoryState
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.io.File

/**
 * Download-related preferences — saved as CBZ, auto-download, delete after read.
 */
class DesktopDownloadPreferences(private val preferenceStore: PreferenceStore) {

    fun downloadDirectory(
        defaultDirectory: File,
        policy: DesktopDownloadDirectoryPolicy = DesktopDownloadDirectoryPolicy(),
    ): DesktopDownloadDirectoryPreference {
        val normalizedDefault = defaultDirectory.toPath().toAbsolutePath().normalize().toFile()
        return DesktopDownloadDirectoryPreference(
            preference = preferenceStore.getString(
                Preference.appStateKey(DOWNLOAD_DIRECTORY_KEY),
                normalizedDefault.path,
            ),
            defaultDirectory = normalizedDefault,
            policy = policy,
        )
    }

    /** When true, finished chapter downloads are packaged as a .cbz archive. */
    val downloadAsCbz by lazy { preferenceStore.getBoolean("download_as_cbz", false) }

    /** When true, newly found chapters are automatically enqueued for download. */
    val autoDownloadNewChapters by lazy { preferenceStore.getBoolean("download_new", false) }

    /** When true, downloaded chapter files are deleted after the chapter is marked as read. */
    val deleteAfterRead by lazy { preferenceStore.getBoolean("delete_after_read", false) }

    /** Maximum number of chapters to download in parallel (1–5). */
    val parallelDownloadLimit by lazy { preferenceStore.getInt("parallel_download_limit", 1) }

    private companion object {
        const val DOWNLOAD_DIRECTORY_KEY = "download_directory"
    }
}

class DesktopDownloadDirectoryPreference internal constructor(
    private val preference: Preference<String>,
    private val defaultDirectory: File,
    private val policy: DesktopDownloadDirectoryPolicy,
) {
    fun key(): String = preference.key()

    fun isSet(): Boolean = preference.isSet()

    fun state(): DesktopDownloadDirectoryState {
        val configuredPath = preference.get().takeIf { preference.isSet() }
        return policy.resolveStartup(defaultDirectory, configuredPath)
    }

    fun save(rawPath: String): DesktopDownloadDirectorySelection {
        val result = policy.validateSelection(rawPath)
        if (result is DesktopDownloadDirectorySelection.ValidCustom) {
            preference.set(result.directory.path)
        }
        return result
    }

    fun inspect(directory: File): DesktopDownloadDirectoryProbeResult = policy.inspect(directory.path)

    fun restoreDefault(): DesktopDownloadDirectorySelection.UseDefault {
        preference.delete()
        return policy.useDefault(defaultDirectory)
    }
}

/**
 * Keeps the download graph on its startup directory while exposing a newly saved directory as
 * pending until the next application start.
 */
class DesktopDownloadDirectoryController internal constructor(
    private val preference: DesktopDownloadDirectoryPreference,
    val startupState: DesktopDownloadDirectoryState,
) {
    fun selectDirectory(directory: File): DesktopDownloadDirectorySelection = preference.save(directory.path)

    fun restoreDefault(): DesktopDownloadDirectorySelection.UseDefault = preference.restoreDefault()

    fun inspectDirectory(directory: File): DesktopDownloadDirectoryProbeResult = preference.inspect(directory)

    fun currentState(): DesktopDownloadDirectoryState {
        val resolved = preference.state()
        if (resolved.hasSameSelectionAs(startupState)) return startupState

        return resolved.copy(
            activeDirectory = startupState.activeDirectory,
            pendingDirectory = resolved.activeDirectory,
            restartRequired = resolved.activeDirectory != startupState.activeDirectory,
        )
    }

    private fun DesktopDownloadDirectoryState.hasSameSelectionAs(
        other: DesktopDownloadDirectoryState,
    ): Boolean =
        defaultDirectory == other.defaultDirectory &&
            configuredDirectory == other.configuredDirectory &&
            activeDirectory == other.activeDirectory &&
            pendingDirectory == other.pendingDirectory &&
            availability == other.availability &&
            cause?.javaClass == other.cause?.javaClass &&
            cause?.message == other.cause?.message
}
