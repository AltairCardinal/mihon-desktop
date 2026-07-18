package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GlobalSearchSourcePolicyTest {

    private val englishPinned = FakeCatalogueSource(1, "English pinned", "en")
    private val englishUnpinned = FakeCatalogueSource(2, "English unpinned", "en")
    private val hidden = FakeCatalogueSource(3, "Hidden", "en")
    private val disabledLanguage = FakeCatalogueSource(4, "Japanese", "ja")
    private val sources = listOf(englishPinned, englishUnpinned, hidden, disabledLanguage)

    @Test
    fun `default global search includes only pinned sources allowed by language and hidden preferences`() {
        val selected = GlobalSearchSourcePolicy.select(
            sources = sources,
            enabledLanguages = setOf("en"),
            hiddenSourceIds = setOf(hidden.id.toString()),
            pinnedSourceIds = setOf(englishPinned.id.toString(), hidden.id.toString()),
        )

        assertEquals(listOf(englishPinned), selected)
    }

    @Test
    fun `all filter includes every allowed source while preserving candidate order`() {
        val selected = GlobalSearchSourcePolicy.select(
            sources = sources,
            enabledLanguages = setOf("en"),
            hiddenSourceIds = setOf(hidden.id.toString()),
            pinnedSourceIds = setOf(englishPinned.id.toString()),
            filter = GlobalSearchSourceFilter.All,
        )

        assertEquals(listOf(englishPinned, englishUnpinned), selected)
    }

    private class FakeCatalogueSource(
        override val id: Long,
        override val name: String,
        override val lang: String,
    ) : CatalogueSource {
        override val supportsLatest = false
        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = MangasPage(emptyList(), false)
        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }
}
