package mihon.desktop.domain

import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeCategoryRepository
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import java.util.UUID
import java.util.prefs.Preferences

class FilterChaptersForDownloadIntegrationTest {
    private val chapterRepository = FakeChapterRepository()
    private val categoryRepository = FakeCategoryRepository()
    private val preferences = DownloadPreferences(
        DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
    )
    private val filter = FilterChaptersForDownload(
        GetChaptersByMangaId(chapterRepository),
        preferences,
        GetCategories(categoryRepository),
    )
    private val favorite = Manga.create().copy(id = 10, favorite = true)
    private val candidates = listOf(chapter(3, 3.0), chapter(2, 2.0), chapter(1, 1.0))

    @Test
    fun `关闭自动下载或漫画未收藏时不返回章节`() = runTest {
        assertEquals(emptyList<Chapter>(), filter.await(favorite, candidates))
        preferences.downloadNewChapters().set(true)
        assertEquals(emptyList<Chapter>(), filter.await(favorite.copy(favorite = false), candidates))
    }

    @Test
    fun `开启后保留候选数量与上游排序且正确处理空列表`() = runTest {
        preferences.downloadNewChapters().set(true)
        assertEquals(candidates, filter.await(favorite, candidates))
        assertEquals(emptyList<Chapter>(), filter.await(favorite, emptyList()))
    }

    @Test
    fun `包含分类允许匹配漫画并拒绝不匹配漫画`() = runTest {
        preferences.downloadNewChapters().set(true)
        categoryRepository.insert(Category(1, "included", 0, 0))
        categoryRepository.insert(Category(2, "other", 1, 0))
        preferences.downloadNewChapterCategories().set(setOf("1"))
        categoryRepository.setMangaCategories(favorite.id, setOf(1))
        assertEquals(candidates, filter.await(favorite, candidates))
        categoryRepository.setMangaCategories(favorite.id, setOf(2))
        assertEquals(emptyList<Chapter>(), filter.await(favorite, candidates))
    }

    @Test
    fun `排除分类优先于包含分类`() = runTest {
        preferences.downloadNewChapters().set(true)
        categoryRepository.insert(Category(1, "included", 0, 0))
        categoryRepository.insert(Category(2, "excluded", 1, 0))
        categoryRepository.setMangaCategories(favorite.id, setOf(1, 2))
        preferences.downloadNewChapterCategories().set(setOf("1"))
        preferences.downloadNewChapterCategoriesExclude().set(setOf("2"))
        assertEquals(emptyList<Chapter>(), filter.await(favorite, candidates))
    }

    @Test
    fun `仅未读排除已读章节号并保留未知与未读章节顺序`() = runTest {
        preferences.downloadNewChapters().set(true)
        preferences.downloadNewUnreadChaptersOnly().set(true)
        chapterRepository.seed(chapter(20, 2.0).copy(read = true))
        val unknown = chapter(4, -1.0)
        assertEquals(listOf(candidates[0], candidates[2], unknown), filter.await(favorite, candidates + unknown))
    }

    private fun chapter(id: Long, number: Double) = Chapter.create().copy(
        id = id,
        mangaId = favorite.id,
        name = "Chapter $number",
        url = "/$id",
        chapterNumber = number,
    )
}
