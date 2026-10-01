package mihon.desktop.domain

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import mihon.desktop.extension.SourceCallResult
import mihon.desktop.extension.safeSourceCall
import mihon.domain.error.AppError
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.SourceDateExtensionIdentity
import tachiyomi.domain.creator.model.SourceDateField
import tachiyomi.domain.creator.model.SourceDateObservation
import tachiyomi.domain.creator.model.SourceDatePrecision
import tachiyomi.domain.creator.model.SourceDateQualityIdentity
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.service.CreatorSourceWorkKey
import tachiyomi.domain.manga.model.Manga

/**
 * Checks a single library manga against its source for new chapters,
 * and inserts any new ones into the database.
 */
class LibraryUpdateChecker(
    private val chapterRepository: ChapterRepository,
    private val mangaRepository: tachiyomi.domain.manga.repository.MangaRepository,
    private val creatorArchiveRepository: CreatorArchiveRepository? = null,
    private val sourceDateExtensionIdentityProvider: (Long) -> SourceDateExtensionIdentity = {
        SourceDateExtensionIdentity("unknown.extension", "unknown")
    },
    private val catalogWriter: SourceChapterCatalogWriter = SourceChapterCatalogWriter(
        chapterRepository,
        creatorArchiveRepository,
        extensionIdentity = sourceDateExtensionIdentityProvider,
    ),
) {

    /**
     * Fetches the chapter list from [source] and inserts chapters whose URL
     * is not yet stored in the DB for [manga].
     *
     * @return an [UpdateResult] with the count of newly added chapters.
     */
    suspend fun checkForUpdates(manga: Manga, source: Source): UpdateResult {
        val knownChapters = chapterRepository.getChapterByMangaId(manga.id)
        val remoteUpdate = when (
            val r = safeSourceCall {
                tachiyomi.domain.source.service.SourceMangaUpdateService().await(source, manga, knownChapters, false, true)
                    .also { catalogWriter.validate(it.chapters) }
            }
        ) {
            is SourceCallResult.Success -> r.value
            is SourceCallResult.Timeout -> return UpdateResult(newChapterCount = 0, sourceError = r.error)
            is SourceCallResult.Error -> return UpdateResult(newChapterCount = 0, sourceError = r.error)
        }
        val inserted = catalogWriter.transaction {
            val current = mangaRepository.getMangaById(manga.id)
            check(current.source == manga.source && current.url == manga.url) { "Source manga identity conflict" }
            catalogWriter.validateWorkIdentity(current)
            check(mangaRepository.update(tachiyomi.domain.manga.model.MangaUpdate(manga.id, memo = remoteUpdate.manga.memo)))
            catalogWriter.merge(current, remoteUpdate.chapters).added
        }

        return UpdateResult(newChapterCount = inserted.size, newChapters = inserted)
    }

    data class UpdateResult(
        val newChapterCount: Int,
        val newChapters: List<tachiyomi.domain.chapter.model.Chapter> = emptyList(),
        val error: String? = null,
        val sourceError: AppError? = null,
    )
}
