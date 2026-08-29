package mihon.desktop.download

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.source.FakeHttpSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences

class DesktopDownloadIdentityResolverTest {
    @Test
    fun `download item identity uses upstream source label canonical chapter metadata and filename preference`() = runTest {
        val chapter = Chapter.create().copy(
            id = 7L,
            url = "/canonical/chapter",
            name = "Canonical chapter",
            scanlator = "Canonical group",
        )
        val chapterRepository = mockk<ChapterRepository> {
            coEvery { getChapterById(7L) } returns chapter
        }
        val preferenceStore = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference(
                    key = "disallow_non_ascii_filenames",
                    data = true,
                    defaultValue = false,
                ),
            ),
        )
        val libraryPreferences = LibraryPreferences(preferenceStore)
        val resolver = DesktopDownloadIdentityResolver(
            sourceManager = FakeDesktopSourceManager(listOf(FakeHttpSource(42L, "zh", "Source 中文"))),
            chapterRepository = chapterRepository,
            libraryPreferences = libraryPreferences,
        )

        val identity = resolver.resolve(
            DownloadItem(
                sourceId = 42L,
                mangaTitle = "Manga 中文",
                chapterName = "Persisted stale chapter",
                chapterId = chapter.id,
                chapterUrl = "/persisted/stale-chapter",
            ),
        )

        assertEquals("Source 中文 (ZH)", identity.sourceDisplayName)
        assertEquals("Manga 中文", identity.mangaTitle)
        assertEquals("Canonical chapter", identity.chapterName)
        assertEquals("Canonical group", identity.scanlator)
        assertEquals("/canonical/chapter", identity.chapterUrl)
        assertTrue(identity.disallowNonAsciiFilenames)
    }

    @Test
    fun `missing domain chapter falls back to the complete persisted item identity`() = runTest {
        val chapterRepository = mockk<ChapterRepository> {
            coEvery { getChapterById(8L) } returns null
        }
        val resolver = DesktopDownloadIdentityResolver(
            sourceManager = FakeDesktopSourceManager(listOf(FakeHttpSource(42L, "en", "Source"))),
            chapterRepository = chapterRepository,
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
        )

        val identity = resolver.resolve(
            DownloadItem(
                sourceId = 42L,
                mangaTitle = "Persisted manga",
                chapterName = "Persisted chapter",
                chapterId = 8L,
                chapterUrl = "/persisted/chapter",
            ),
        )

        assertEquals("Persisted manga", identity.mangaTitle)
        assertEquals("Persisted chapter", identity.chapterName)
        assertEquals(null, identity.scanlator)
        assertEquals("/persisted/chapter", identity.chapterUrl)
    }
}
