package mihon.desktop.download

import mihon.domain.reader.content.DownloadChapterIdentity
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.updates.model.UpdatesWithRelations

/** Rebuilds the complete upstream download identity from existing domain authorities. */
class DesktopDownloadIdentityResolver(
    private val sourceManager: SourceManager,
    private val chapterRepository: ChapterRepository,
    private val libraryPreferences: LibraryPreferences,
) {
    suspend fun resolve(item: DownloadItem): DownloadChapterIdentity {
        val chapter = chapterRepository.getChapterById(item.chapterId)
        return resolve(
            sourceId = item.sourceId,
            mangaTitle = item.mangaTitle,
            chapterName = chapter?.name ?: item.chapterName,
            chapterUrl = chapter?.url ?: item.chapterUrl,
            scanlator = chapter?.scanlator,
        )
    }

    fun resolve(manga: Manga, chapter: Chapter): DownloadChapterIdentity = resolve(
        sourceId = manga.source,
        mangaTitle = manga.title,
        chapterName = chapter.name,
        chapterUrl = chapter.url,
        scanlator = chapter.scanlator,
    )

    fun resolve(manga: Manga): DownloadChapterIdentity = resolve(
        sourceId = manga.source,
        mangaTitle = manga.title,
        chapterName = "",
        chapterUrl = "",
        scanlator = null,
    )

    fun resolve(update: UpdatesWithRelations): DownloadChapterIdentity = resolve(
        sourceId = update.sourceId,
        mangaTitle = update.mangaTitle,
        chapterName = update.chapterName,
        chapterUrl = update.chapterUrl,
        scanlator = update.scanlator,
    )

    private fun resolve(
        sourceId: Long,
        mangaTitle: String,
        chapterName: String,
        chapterUrl: String,
        scanlator: String?,
    ) = DownloadChapterIdentity(
        sourceDisplayName = sourceManager.get(sourceId)?.toString() ?: sourceId.toString(),
        mangaTitle = mangaTitle,
        chapterName = chapterName,
        scanlator = scanlator,
        chapterUrl = chapterUrl,
        disallowNonAsciiFilenames = libraryPreferences.disallowNonAsciiFilenames().get(),
    )
}
