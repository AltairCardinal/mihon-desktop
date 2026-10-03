package mihon.desktop.reader

import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.extension.SourceCallResult
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.reader.interactor.ReaderCatalogPreparation
import tachiyomi.domain.reader.model.ReaderChapterIdentity
import tachiyomi.domain.source.service.SourceManager

internal class DesktopReaderCatalogPreparation(
    private val mangas: MangaRepository,
    private val sources: SourceManager,
    private val catalog: SaveSourceMangaForDetails,
) : ReaderCatalogPreparation {
    override suspend fun prepare(target: ReaderChapterIdentity): List<tachiyomi.domain.chapter.model.Chapter>? {
        val manga = mangas.getMangaById(target.mangaId)
        check(manga.source == target.sourceId && manga.url == target.mangaUrl) { "Reader manga identity conflict" }
        val prepared = catalog.awaitPrepared(sources.get(target.sourceId), manga)
        val result = (prepared as? SourceCallResult.Success)?.value ?: return null
        check(result.manga.id == target.mangaId && result.manga.source == target.sourceId && result.manga.url == target.mangaUrl)
        check(result.chapters.any { it.id == target.chapterId && it.url == target.chapterUrl }) { "Reader chapter identity conflict" }
        return result.chapters
    }
}
