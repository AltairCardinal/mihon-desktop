package mihon.desktop.reader

import mihon.desktop.ui.library.toReaderChapterRefs
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.domain.reader.progress.resolveReaderChapterEntryPage
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.reader.model.ReadingSyncSnapshot

data class DesktopReaderOpenContext(
    val chapterTitle: String,
    val mangaTitle: String,
    val sourceId: Long,
    val chapterUrl: String,
    val chapterId: Long,
    val mangaId: Long,
    val mangaViewerFlags: Long,
    val initialPage: Int,
    val resumeSnapshot: ReadingSyncSnapshot? = null,
    val chapters: List<ReaderChapterRef> = emptyList(),
    val currentChapterIndex: Int = 0,
    val chapterNumber: Double = 0.0,
    val opening: tachiyomi.domain.reader.model.ReaderOpenContext? = null,
)

/** The target was already selected by the caller; this mapper never chooses another chapter. */
internal fun desktopReaderOpenContext(
    manga: Manga,
    chapters: List<Chapter>,
    target: Chapter,
    isDownloaded: (Chapter) -> Boolean,
): DesktopReaderOpenContext? {
    require(target.mangaId == manga.id) { "Reader chapter identity conflict" }
    if (target.url.externalChapterUrlOrNull() != null) return null
    val eligible = chapters.filter { it.mangaId == manga.id && it.url.externalChapterUrlOrNull() == null }.sortedWith(tachiyomi.domain.chapter.service.getChapterSort(manga, sortDescending = true))
    check(eligible.map { it.url }.distinct().size == eligible.size) { "Duplicate local chapter identity" }
    val chapter = eligible.find { it.id == target.id && it.url == target.url } ?: return null
    val refs = eligible.toReaderChapterRefs(target.id, manga, isDownloaded)
    val index = refs.indexOfFirst { it.id == target.id }
    if (index < 0) return null
    return DesktopReaderOpenContext(
        chapterTitle = chapter.name, mangaTitle = manga.title, sourceId = manga.source,
        chapterUrl = chapter.url, chapterId = chapter.id, mangaId = manga.id,
        mangaViewerFlags = manga.viewerFlags, initialPage = resolveReaderChapterEntryPage(chapter.read, chapter.lastPageRead),
        chapters = refs, currentChapterIndex = index, chapterNumber = chapter.chapterNumber,
    )
}

/** Production opening re-reads the already selected identity and its baseline atomically. */
internal suspend fun selectedDesktopReaderOpenContext(
    manga: Manga,
    chapters: List<Chapter>,
    target: Chapter,
    progress: tachiyomi.domain.reader.interactor.RecordReadingProgress?,
    isDownloaded: (Manga, Chapter) -> Boolean,
): DesktopReaderOpenContext? {
    if (progress == null) return desktopReaderOpenContext(manga, chapters, target) { isDownloaded(manga, it) }
    val identity = tachiyomi.domain.reader.model.ReaderChapterIdentity(manga.id, manga.source, manga.url, target.id, target.url)
    val opened = progress.openChapter(identity) ?: return null
    val currentChapters = chapters.map { if (it.id == opened.chapter.id) opened.chapter else it }
    val request = desktopReaderOpenContext(opened.manga, currentChapters, opened.chapter) { isDownloaded(opened.manga, it) } ?: return null
    return request.copy(
        initialPage = opened.pageIndex,
        resumeSnapshot = opened.snapshot,
        opening = opened,
    )
}

/** Both detail and history create the same real production reader. */
fun desktopReaderScreen(context: DesktopReaderOpenContext, onClosed: () -> Unit = {}): DesktopReaderScreen = DesktopReaderScreen(
    chapterTitle = context.chapterTitle, mangaTitle = context.mangaTitle, sourceId = context.sourceId,
    chapterUrl = context.chapterUrl, chapterId = context.chapterId, mangaId = context.mangaId,
    chapterNumber = context.chapterNumber, chapters = context.chapters, currentChapterIndex = context.currentChapterIndex,
    initialPage = context.initialPage, mangaViewerFlags = context.mangaViewerFlags, resumeSnapshot = context.resumeSnapshot,
    opening = context.opening,
    onProductionClosed = onClosed,
)
