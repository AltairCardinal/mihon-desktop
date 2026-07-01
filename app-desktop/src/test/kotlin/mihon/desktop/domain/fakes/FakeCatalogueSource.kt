package mihon.desktop.domain.fakes

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga

class FakeCatalogueSource(
    private val details: SManga,
    private val chapters: List<SChapter> = emptyList(),
) : CatalogueSource {

    override val id: Long = 42L
    override val name: String = "Fake Source"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true

    override suspend fun getMangaDetails(manga: SManga): SManga = details

    override suspend fun getChapterList(manga: SManga): List<SChapter> = chapters

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        MangasPage(emptyList(), false)

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)

    override fun getFilterList(): FilterList = FilterList()
}
