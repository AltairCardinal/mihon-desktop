package tachiyomi.domain.manga.model

enum class MangaDetailAction {
    LIBRARY,
    UPDATE_INTERVAL,
    TRACKING,
    WEB,
    DELETE_AFTER_READ,
    OPEN_BROWSER,
    COPY_LINK,
    SHARE,
    ;

    companion object {
        const val MAX_PRIMARY = 4
        val defaults = listOf(LIBRARY, UPDATE_INTERVAL, TRACKING, WEB)

        fun normalize(actions: List<MangaDetailAction>): List<MangaDetailAction> {
            val unique = actions.distinct()
            if (LIBRARY !in unique) return (listOf(LIBRARY) + unique).take(MAX_PRIMARY)
            val limited = unique.take(MAX_PRIMARY)
            return if (LIBRARY in limited) limited else limited.take(MAX_PRIMARY - 1) + LIBRARY
        }
    }
}

data class MangaDetailLayout(
    val primary: List<MangaDetailAction> = MangaDetailAction.defaults,
    val order: List<MangaDetailAction> = MangaDetailAction.entries,
)
