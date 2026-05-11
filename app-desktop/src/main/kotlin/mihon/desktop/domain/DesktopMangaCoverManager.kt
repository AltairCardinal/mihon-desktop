package mihon.desktop.domain

import java.io.File

/**
 * Manages custom manga cover images stored on disk.
 *
 * Custom covers are stored as `{coversDir}/{mangaId}` (no extension —
 * we treat the bytes as opaque image data, Coil will detect the format).
 *
 * @param coversDir Directory where custom covers are persisted.
 *                  Defaults to `~/.mihon/covers/`.
 */
class DesktopMangaCoverManager(
    private val coversDir: File = File(System.getProperty("user.home"), ".mihon/covers"),
) {

    /** Returns the [File] path where a custom cover for [mangaId] would be stored. */
    fun getCustomCoverFile(mangaId: Long): File = File(coversDir, "$mangaId")

    /** Returns true if a custom cover file exists for [mangaId]. */
    fun customCoverExists(mangaId: Long): Boolean = getCustomCoverFile(mangaId).exists()

    /**
     * Copies [source] as the custom cover for [mangaId].
     * Creates [coversDir] if it does not yet exist.
     */
    fun setCustomCover(mangaId: Long, source: File) {
        coversDir.mkdirs()
        source.copyTo(getCustomCoverFile(mangaId), overwrite = true)
    }

    /** Deletes the custom cover for [mangaId], if present. */
    fun deleteCustomCover(mangaId: Long) {
        getCustomCoverFile(mangaId).delete()
    }

    /**
     * Returns the model to pass to Coil `AsyncImage`:
     * - the custom cover file path when a custom cover exists
     * - [fallbackUrl] otherwise (may be null)
     */
    fun resolveModel(mangaId: Long, fallbackUrl: String?): String? {
        val custom = getCustomCoverFile(mangaId)
        return if (custom.exists()) custom.absolutePath else fallbackUrl
    }
}
