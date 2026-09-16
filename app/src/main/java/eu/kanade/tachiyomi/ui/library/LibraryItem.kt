package eu.kanade.tachiyomi.ui.library

import eu.kanade.tachiyomi.source.getNameForMangaInfo
import tachiyomi.domain.library.matchesLibraryQuery
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class LibraryItem(
    val libraryManga: LibraryManga,
    val downloadCount: Long = -1,
    val unreadCount: Long = -1,
    val isLocal: Boolean = false,
    val sourceLanguage: String = "",
    val hasSynchronizedResume: Boolean = false,
    private val sourceManager: SourceManager = Injekt.get(),
) {
    val id: Long = libraryManga.id

    /**
     * Checks if a query matches the manga
     *
     * @param constraint the query to check.
     * @return true if the manga matches the query, false otherwise.
     */
    fun matches(constraint: String): Boolean {
        val source = sourceManager.getOrStub(libraryManga.manga.source)
        return matchesLibraryQuery(
            libraryManga = libraryManga,
            query = constraint,
            sourceName = source.getNameForMangaInfo(),
            localSourceId = LocalSource.ID,
        )
    }
}
