package mihon.desktop.reader

import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderChapterSession
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.session.ReaderPageSession
import mihon.domain.reader.session.ReaderSessionSnapshot

internal fun readerChapterSession(
    chapterId: Long = 1L,
    generation: Long = 1L,
    pageCount: Int,
    pageLoadState: (Int) -> ReaderPageLoadState = { ReaderPageLoadState.Ready },
): ReaderChapterSession {
    val id = ReaderChapterId(chapterId)
    return ReaderChapterSession(
        id = id,
        generation = generation,
        loadState = ReaderChapterLoadState.Loaded,
        pages = List(pageCount) { index ->
            val state = pageLoadState(index)
            ReaderPageSession(
                id = ReaderPageId(id, index),
                url = "/page/$index",
                imageUrl = "https://example.test/page/$index",
                encodedPageRef = EncodedPageRef("test:$chapterId:$index")
                    .takeIf { state == ReaderPageLoadState.Ready },
                loadState = state,
            )
        },
    )
}

internal fun desktopReaderSessionState(
    chapterId: Long = 1L,
    generation: Long = 1L,
    pageCount: Int,
    initialPage: Int = 0,
    pageLoadState: (Int) -> ReaderPageLoadState = { ReaderPageLoadState.Ready },
): DesktopReaderSessionState {
    val chapter = readerChapterSession(chapterId, generation, pageCount, pageLoadState)
    return DesktopReaderSessionState(
        context = DesktopReaderChapterContext(
            chapterId = chapterId,
            sourceId = 42L,
            chapterUrl = "/chapter/$chapterId",
            mangaTitle = "Manga",
            chapterTitle = "Chapter $chapterId",
            chapterNumber = chapterId.toDouble(),
            chapterIndex = 0,
            initialPage = initialPage,
            wasRead = false,
        ),
        snapshot = ReaderSessionSnapshot(generation, chapter),
    )
}
