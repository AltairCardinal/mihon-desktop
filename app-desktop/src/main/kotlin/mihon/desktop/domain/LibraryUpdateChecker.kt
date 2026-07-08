package mihon.desktop.domain

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import mihon.desktop.extension.SourceCallResult
import mihon.desktop.extension.safeSourceCall
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga

/**
 * Checks a single library manga against its source for new chapters,
 * and inserts any new ones into the database.
 */
class LibraryUpdateChecker(
    private val chapterRepository: ChapterRepository,
) {

    /**
     * Fetches the chapter list from [source] and inserts chapters whose URL
     * is not yet stored in the DB for [manga].
     *
     * @return an [UpdateResult] with the count of newly added chapters.
     */
    suspend fun checkForUpdates(manga: Manga, source: CatalogueSource): UpdateResult {
        val sManga = SManga.create().apply {
            url = manga.url
            title = manga.title
        }

        val remoteChapters = when (val r = safeSourceCall { source.getChapterList(sManga) }) {
            is SourceCallResult.Success -> r.value
            is SourceCallResult.Timeout -> return UpdateResult(newChapterCount = 0, error = "Timed out")
            is SourceCallResult.Error -> return UpdateResult(newChapterCount = 0, error = r.message)
        }

        val knownChaptersByUrl = chapterRepository.getChapterByMangaId(manga.id)
            .associateBy { it.url }
        val toUpdate = mutableListOf<ChapterUpdate>()

        val toAdd = remoteChapters.mapIndexedNotNull { index, sc ->
            val chapterNumber = sc.recognizedChapterNumber(manga)
            val knownChapter = knownChaptersByUrl[sc.url]
            if (knownChapter != null) {
                if (knownChapter.chapterNumber != chapterNumber) {
                    toUpdate += ChapterUpdate(id = knownChapter.id, chapterNumber = chapterNumber)
                }
                return@mapIndexedNotNull null
            }
            Chapter.create().copy(
                mangaId = manga.id,
                url = sc.url,
                name = sc.name,
                dateUpload = sc.date_upload,
                chapterNumber = chapterNumber,
                scanlator = sc.scanlator?.ifBlank { null }?.trim(),
                sourceOrder = index.toLong(),
                dateFetch = System.currentTimeMillis(),
            )
        }

        if (toUpdate.isNotEmpty()) {
            chapterRepository.updateAll(toUpdate)
        }
        val inserted = if (toAdd.isNotEmpty()) {
            chapterRepository.addAll(toAdd)
        } else {
            emptyList()
        }

        return UpdateResult(newChapterCount = inserted.size, newChapters = inserted)
    }

    data class UpdateResult(
        val newChapterCount: Int,
        val newChapters: List<tachiyomi.domain.chapter.model.Chapter> = emptyList(),
        val error: String? = null,
    )
}
