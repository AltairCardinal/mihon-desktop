package eu.kanade.tachiyomi.ui.reader

import tachiyomi.data.chapter.SourceChapterCatalogWriter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.reader.interactor.ReaderCatalogPreparation
import tachiyomi.domain.reader.model.ReaderChapterIdentity
import tachiyomi.domain.source.service.SourceManager

class AndroidReaderCatalogPreparation(
    private val mangas: MangaRepository,
    private val chapters: ChapterRepository,
    private val sources: SourceManager,
    private val writer: SourceChapterCatalogWriter,
) : ReaderCatalogPreparation {
    override suspend fun prepare(target: ReaderChapterIdentity): List<Chapter>? {
        val manga = mangas.getMangaById(target.mangaId)
        check(manga.source == target.sourceId && manga.url == target.mangaUrl) { "Reader manga identity conflict" }
        fun matches(chapter: Chapter?) = chapter?.mangaId == target.mangaId && chapter.url == target.chapterUrl
        check(matches(chapters.getChapterById(target.chapterId))) { "Reader chapter identity conflict" }
        if (!writer.needsRefresh(manga)) return chapters.getChapterByMangaId(manga.id)
        val source = sources.get(target.sourceId) ?: return null
        check(source.id == target.sourceId) { "Reader source identity conflict" }
        val update = tachiyomi.domain.source.service.SourceMangaUpdateService().awaitSharedCatalog(
            source,
            manga,
            chapters.getChapterByMangaId(manga.id),
            fetchDetails = false,
        )
        check(update.manga.url == target.mangaUrl) { "Reader remote manga identity conflict" }
        return writer.transaction {
            check(matches(chapters.getChapterById(target.chapterId))) { "Reader chapter identity conflict" }
            val result = writer.merge(manga, update.chapters)
            check(mangas.update(tachiyomi.domain.manga.model.MangaUpdate(manga.id, memo = update.manga.memo)))
            check(mangas.getMangaById(manga.id).memo == update.manga.memo) { "Reader manga memo write failed" }
            result.chapters
        }
    }
}
