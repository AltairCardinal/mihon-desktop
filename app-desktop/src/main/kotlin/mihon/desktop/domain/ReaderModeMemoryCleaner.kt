package mihon.desktop.domain

import tachiyomi.domain.manga.repository.MangaRepository

class ReaderModeMemoryCleaner(
    private val mangaRepository: MangaRepository,
) {
    suspend fun clearNonFavoriteManga(): Boolean {
        return mangaRepository.resetViewerFlagsForNonFavorites()
    }
}
