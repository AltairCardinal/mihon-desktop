package mihon.desktop.domain

import mihon.domain.task.TaskState
import tachiyomi.domain.manga.interactor.UpdateCustomCover
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

class DesktopCoverUpdater(
    coverStore: DesktopCustomCoverStore,
    mangaRepository: MangaRepository,
) {
    private val update = UpdateCustomCover(coverStore) { mangaId ->
        check(mangaRepository.update(MangaUpdate(id = mangaId, coverLastModified = System.currentTimeMillis())))
    }

    suspend operator fun invoke(mangaId: Long, bytes: ByteArray): TaskState<Unit> = update(mangaId, bytes)
}
