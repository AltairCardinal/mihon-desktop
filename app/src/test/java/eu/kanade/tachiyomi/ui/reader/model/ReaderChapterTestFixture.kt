package eu.kanade.tachiyomi.ui.reader.model

import org.junit.jupiter.api.Assertions.assertTrue

internal fun ReaderChapter.openPageListForTest(): Long = checkNotNull(beginPageListLoadIfNeeded())

internal fun ReaderChapter.completePageListForTest(
    generation: Long,
    pages: List<ReaderPage>,
) {
    assertTrue(completePageListLoad(generation, pages))
}

internal fun ReaderChapter.publishLoadedPageListForTest(pages: List<ReaderPage>) {
    completePageListForTest(openPageListForTest(), pages)
}

internal fun ReaderChapter.failPageListForTest(
    generation: Long,
    error: Throwable,
) {
    assertTrue(failPageListLoad(generation, error))
}
