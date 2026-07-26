package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

class UpdateManga(
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(update: MangaUpdate): Boolean = mangaRepository.update(update)

    suspend fun awaitAll(updates: List<MangaUpdate>): Boolean = mangaRepository.updateAll(updates)
}
