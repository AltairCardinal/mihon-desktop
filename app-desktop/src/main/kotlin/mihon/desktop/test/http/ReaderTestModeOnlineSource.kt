package mihon.desktop.test.http

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import java.util.concurrent.atomic.AtomicReference

class ReaderTestModeOnlineSource internal constructor(
    private val controller: ReaderTestModeController,
) : CatalogueSource {
    override val id: Long = ReaderTestModeController.READER_TEST_SOURCE_ID
    override val name: String = ReaderTestModeController.READER_TEST_SOURCE_NAME
    override val lang: String = "en"
    override val supportsLatest: Boolean = false

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        MangasPage(emptyList(), false)

    override fun getFilterList(): FilterList = FilterList()

    override suspend fun getMangaDetails(manga: SManga): SManga = manga

    override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()

    override suspend fun getPageList(chapter: SChapter): List<Page> = controller.onlinePageUrls(chapter.url).mapIndexed { index, imageUrl ->
        Page(index = index, url = "${chapter.url}/$index", imageUrl = imageUrl)
    }

    override fun toString(): String = name
}

object ReaderTestModeSourceBridge {
    private val value = AtomicReference<ReaderTestModeOnlineSource?>()

    fun sources(): List<CatalogueSource> = listOfNotNull(value.get())

    fun install(source: ReaderTestModeOnlineSource) {
        value.set(source)
    }

    fun clear(expected: ReaderTestModeOnlineSource): Boolean = value.compareAndSet(expected, null)
}
