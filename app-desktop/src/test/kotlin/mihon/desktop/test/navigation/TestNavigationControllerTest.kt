package mihon.desktop.test.navigation

import mihon.desktop.ui.reader.DesktopReaderScreen
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TestNavigationControllerTest {

    @AfterEach
    fun tearDown() {
        TestNavigationController.reset()
    }

    @Test
    fun `clearing tab navigation does not drop pending screen navigation`() {
        TestNavigationController.navigateToMangaDetail(42L)

        TestNavigationController.clearPendingTabNavigation()

        assertNull(TestNavigationController.pendingTabNavigation.value)
        assertNotNull(TestNavigationController.pendingScreenRequest.value)
    }

    @Test
    fun `open reader publishes one real reader screen to the outer navigator`() {
        TestNavigationController.openReader(
            mangaId = 1L,
            chapterId = 10L,
            chapterTitle = "Chapter 10",
            mangaTitle = "Manga",
            chapterUrl = "https://example.com/chapter:10",
            sourceId = 99L,
        )

        val readerScreen = assertInstanceOf(
            DesktopReaderScreen::class.java,
            TestNavigationController.pendingScreenRequest.value?.screen,
        )
        assertEquals(listOf(readerScreen), TestNavigationController.pushedScreens.value)

        TestNavigationController.clearPendingScreenNavigation()

        assertNull(TestNavigationController.pendingScreenRequest.value)
        assertFalse(TestNavigationController.pendingPop.value)
    }

    @Test
    fun `acknowledging an older screen request does not erase a newer request`() {
        TestNavigationController.openReader(
            mangaId = 1L,
            chapterId = 10L,
            chapterTitle = "Chapter 10",
            mangaTitle = "Manga",
            chapterUrl = "/chapter/10",
            sourceId = 99L,
        )
        val firstRequest = requireNotNull(TestNavigationController.pendingScreenRequest.value)
        val first = firstRequest.screen as DesktopReaderScreen

        TestNavigationController.openReader(
            mangaId = 1L,
            chapterId = 10L,
            chapterTitle = "Chapter 10",
            mangaTitle = "Manga",
            chapterUrl = "/chapter/10",
            sourceId = 99L,
        )
        val secondRequest = requireNotNull(TestNavigationController.pendingScreenRequest.value)
        val second = secondRequest.screen as DesktopReaderScreen
        assertNotSame(first, second)

        TestNavigationController.acknowledgeScreenNavigation(firstRequest.id)

        assertEquals(secondRequest, TestNavigationController.pendingScreenRequest.value)
        assertEquals(listOf(first, second), TestNavigationController.pushedScreens.value)
    }

    @Test
    fun `concurrent reader publishers retain every pushed screen`() {
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val publishers = (1L..64L).map { chapterId ->
                executor.submit {
                    start.await()
                    TestNavigationController.openReader(
                        mangaId = 1L,
                        chapterId = chapterId,
                        chapterTitle = "Chapter $chapterId",
                        mangaTitle = "Manga",
                        chapterUrl = "/chapter/$chapterId",
                        sourceId = 99L,
                    )
                }
            }
            start.countDown()
            publishers.forEach { it.get(5, TimeUnit.SECONDS) }

            assertEquals((1L..64L).toSet(), TestNavigationController.pushedScreens.value
                .filterIsInstance<DesktopReaderScreen>()
                .map(DesktopReaderScreen::chapterId)
                .toSet())
            assertEquals(64, TestNavigationController.pushedScreens.value.size)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `authors tab is available to automation navigation`() {
        assertTrue(TestNavigationController.getAvailableScreens().contains("AuthorsTab"))
        assertNotNull(TestNavigationController.getTabOrNull("AuthorsTab"))
    }
}
