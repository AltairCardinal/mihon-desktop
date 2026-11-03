package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import mihon.domain.reader.ReaderChapterState
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderPageDescriptor
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.session.ReaderSessionSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ReaderChapterProjectionTest {

    @Test
    fun `same state skips projection while real state and encoded identity changes remain published`() {
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
        val generation = checkNotNull(chapter.beginPageListLoadIfNeeded())
        val page = ReaderPage(0).apply {
            this.chapter = chapter
            status = Page.State.Ready
            encodedPageRef = EncodedPageRef("original")
        }
        assertTrue(chapter.completePageListLoad(generation, listOf(page)))
        var projections = 0
        mockkStatic("eu.kanade.tachiyomi.ui.reader.model.ReaderChapterKt")
        try {
            every { any<ReaderSessionSnapshot>().toLegacyState() } answers {
                projections++
                callOriginal()
            }

            val initial = chapter.sharedSessionStateFlow.value
            page.status = Page.State.Ready
            assertEquals(0, projections)
            assertSame(initial, chapter.sharedSessionStateFlow.value)
            page.status = Page.State.LoadPage
            assertEquals(1, projections)
            assertEquals(
                ReaderPageLoadState.ResolvingImage,
                chapter.sharedSessionStateFlow.value.activeChapter.pages.single().loadState,
            )
            page.encodedPageRef = EncodedPageRef("replacement")
            page.status = Page.State.Ready
            assertEquals(2, projections)
            val ready = chapter.sharedSessionStateFlow.value.activeChapter.pages.single()
            assertEquals(ReaderPageLoadState.Ready, ready.loadState)
            assertEquals(EncodedPageRef("replacement"), ready.encodedPageRef)
            page.status = Page.State.Ready
            assertEquals(2, projections)
        } finally {
            unmockkStatic("eu.kanade.tachiyomi.ui.reader.model.ReaderChapterKt")
        }
    }

    @Test
    fun `binding still publishes changes made after descriptor capture`() {
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
        val generation = checkNotNull(chapter.beginPageListLoadIfNeeded())
        val page = ReaderPage(0).apply {
            this.chapter = chapter
            status = Page.State.Ready
            encodedPageRef = EncodedPageRef("original")
        }
        val descriptor = ReaderPageDescriptor(
            sourcePageIndex = page.index,
            encodedPageRef = page.encodedPageRef,
            initialLoadState = page.toSharedLoadState(),
        )
        page.status = Page.State.LoadPage
        page.encodedPageRef = EncodedPageRef("changed-before-bind")

        assertTrue(chapter.completePageListLoad(generation, listOf(page), listOf(descriptor)))

        val bound = chapter.sharedSessionStateFlow.value.activeChapter.pages.single()
        assertEquals(ReaderPageLoadState.ResolvingImage, bound.loadState)
        assertEquals(EncodedPageRef("changed-before-bind"), bound.encodedPageRef)
        page.status = Page.State.Ready
        assertEquals(
            ReaderPageLoadState.Ready,
            chapter.sharedSessionStateFlow.value.activeChapter.pages.single().loadState,
        )
    }

    @Test
    fun `ready page binding projects the complete chapter only once`() {
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, mangaId = 1))
        val generation = checkNotNull(chapter.beginPageListLoadIfNeeded())
        val pages = List(187) { index ->
            ReaderPage(index, url = "/$index").apply {
                this.chapter = chapter
                status = Page.State.Ready
                encodedPageRef = EncodedPageRef("page:$index")
            }
        }
        val descriptors = pages.map {
            ReaderPageDescriptor(it.index, it.url, it.imageUrl, it.encodedPageRef, it.toSharedLoadState())
        }
        var projections = 0
        mockkStatic("eu.kanade.tachiyomi.ui.reader.model.ReaderChapterKt")
        try {
            every { any<ReaderSessionSnapshot>().toLegacyState() } answers {
                projections++
                callOriginal()
            }

            assertTrue(chapter.completePageListLoad(generation, pages, descriptors))

            assertEquals(187, chapter.sharedSessionStateFlow.value.activeChapter.pages.size)
            assertEquals(187, (chapter.sharedStateFlow.value as ReaderChapterState.Loaded).pages.size)
            assertEquals(
                descriptors.map { it.encodedPageRef },
                chapter.sharedSessionStateFlow.value.activeChapter.pages.map { it.encodedPageRef },
            )
            assertEquals(
                pages.map { it.url },
                (chapter.sharedStateFlow.value as ReaderChapterState.Loaded).pages.map { it.url },
            )
            assertEquals(1, projections)
        } finally {
            unmockkStatic("eu.kanade.tachiyomi.ui.reader.model.ReaderChapterKt")
        }
    }
}
