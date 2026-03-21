package mihon.desktop.download

import tachiyomi.core.common.preference.PreferenceStore

/**
 * Download-related preferences — saved as CBZ, auto-download, delete after read.
 */
class DesktopDownloadPreferences(private val preferenceStore: PreferenceStore) {

    /** When true, finished chapter downloads are packaged as a .cbz archive. */
    val downloadAsCbz by lazy { preferenceStore.getBoolean("download_as_cbz", false) }

    /** When true, newly found chapters are automatically enqueued for download. */
    val autoDownloadNewChapters by lazy { preferenceStore.getBoolean("auto_download_new_chapters", false) }

    /** When true, downloaded chapter files are deleted after the chapter is marked as read. */
    val deleteAfterRead by lazy { preferenceStore.getBoolean("delete_after_read", false) }
}
