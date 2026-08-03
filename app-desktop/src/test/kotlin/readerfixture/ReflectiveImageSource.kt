package readerfixture

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Packaged into a test JAR so ExtensionClassLoader executes the reader reflection bridge. */
class ReflectiveImageSource : CatalogueSource {
    private val httpClient = OkHttpClient()

    override val id = 9_901L
    override val name = "child-loader-reader-fixture"
    override val lang = "en"
    override val supportsLatest = false

    @Suppress("unused")
    fun getClient(): OkHttpClient = httpClient

    @Suppress("unused")
    suspend fun getImage(page: Page): Response = httpClient.newCall(
        Request.Builder()
            .url(requireNotNull(page.imageUrl))
            .header("X-Reflection-Bridge", "child-loader")
            .header("X-Page-Token", page.url)
            .post("child-loader-body".toRequestBody())
            .build(),
    ).execute()

    override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
    override suspend fun getMangaDetails(manga: SManga): SManga = manga
    override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        MangasPage(emptyList(), false)
    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
    override fun getFilterList(): FilterList = FilterList()
}
