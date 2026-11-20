package tachiyomi.domain.download.service

enum class MangaDownloadDeletion {
    INHERIT,
    ENABLED,
    DISABLED,
    ;

    fun resolveSlots(globalSlots: Int, enabledSlots: Int = 0): Int = when (this) {
        INHERIT -> globalSlots
        ENABLED -> enabledSlots.coerceIn(0, 4)
        DISABLED -> -1
    }
}

/** Local reader-cleanup settings; one write commits the mode and both dependent options. */
data class MangaDownloadDeletionSettings(
    val mode: MangaDownloadDeletion = MangaDownloadDeletion.INHERIT,
    val slots: Int = 0,
    val removeBookmarked: Boolean = false,
) {
    fun encode() = "${mode.name}|${slots.coerceIn(0, 4)}|$removeBookmarked"

    companion object {
        fun decode(value: String): MangaDownloadDeletionSettings {
            val parts = value.split('|')
            val mode = MangaDownloadDeletion.entries.firstOrNull { it.name == parts.firstOrNull() }
                ?: return MangaDownloadDeletionSettings()
            return MangaDownloadDeletionSettings(
                mode,
                parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 4) ?: 0,
                parts.getOrNull(2)?.toBooleanStrictOrNull() ?: false,
            )
        }
    }
}

/** This override is limited to reader completion; manual chapter deletion keeps its existing policy. */
fun DownloadPreferences.removeBookmarkedForReader(mangaId: Long): Boolean {
    val settings = mangaDeletionSettings(mangaId).get()
    return if (settings.mode == MangaDownloadDeletion.ENABLED) {
        settings.removeBookmarked
    } else {
        removeBookmarkedChapters().get()
    }
}
