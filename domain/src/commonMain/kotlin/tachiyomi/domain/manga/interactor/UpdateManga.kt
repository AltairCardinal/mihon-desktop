package tachiyomi.domain.manga.interactor

import tachiyomi.core.common.feedback.DiagnosticCategory
import tachiyomi.core.common.feedback.DiagnosticOperation
import tachiyomi.core.common.feedback.diagnosticOperation
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

class UpdateManga(
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(
        update: MangaUpdate,
    ): Boolean = diagnosticOperation(DiagnosticCategory.LIBRARY_PROGRESS, DiagnosticOperation.LIBRARY) {
        mangaRepository.update(update)
    }

    suspend fun awaitAll(updates: List<MangaUpdate>): Boolean = diagnosticOperation(
        DiagnosticCategory.LIBRARY_PROGRESS,
        DiagnosticOperation.LIBRARY,
    ) {
        mangaRepository.updateAll(updates)
    }
}
