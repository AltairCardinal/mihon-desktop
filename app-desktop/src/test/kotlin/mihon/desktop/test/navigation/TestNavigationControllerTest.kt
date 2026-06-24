package mihon.desktop.test.navigation

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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
        assertNotNull(TestNavigationController.pendingScreenNavigation.value)
    }

    @Test
    fun `clearing reader navigation consumes pending reader screen`() {
        TestNavigationController.openReader(
            mangaId = 1L,
            chapterId = 10L,
            chapterTitle = "Chapter 10",
            mangaTitle = "Manga",
            chapterUrl = "https://example.com/chapter:10",
            sourceId = 99L,
        )

        assertNotNull(TestNavigationController.pendingReaderScreen.value)

        TestNavigationController.clearPendingReaderScreen()

        assertNull(TestNavigationController.pendingReaderScreen.value)
        assertFalse(TestNavigationController.pendingPop.value)
    }
}
