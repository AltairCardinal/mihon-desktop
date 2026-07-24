package mihon.desktop.source

import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.Query
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.model.Source as DomainSource

/** Minimal non-HTTP CatalogueSource for tests. */
class FakeSource(
    override val id: Long,
    override val lang: String,
    override val name: String,
) : CatalogueSource {
    override val supportsLatest = false
    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = MangasPage(emptyList(), false)
    override fun getFilterList(): FilterList = FilterList()
    override suspend fun getMangaDetails(manga: SManga): SManga = manga
    override suspend fun getChapterList(manga: SManga): List<eu.kanade.tachiyomi.source.model.SChapter> = emptyList()
    override suspend fun getPageList(chapter: eu.kanade.tachiyomi.source.model.SChapter): List<eu.kanade.tachiyomi.source.model.Page> = emptyList()
}

/** Minimal HttpSource stub. */
class FakeHttpSource(
    override val id: Long,
    override val lang: String,
    override val name: String,
) : HttpSource() {
    override val baseUrl = "https://example.com"
    override val supportsLatest = false
    override val client: OkHttpClient = OkHttpClient()
    override fun popularMangaRequest(page: Int): Request = Request.Builder().url(baseUrl).build()
    override fun popularMangaParse(response: Response): MangasPage = MangasPage(emptyList(), false)
    override fun latestUpdatesRequest(page: Int): Request = Request.Builder().url(baseUrl).build()
    override fun latestUpdatesParse(response: Response): MangasPage = MangasPage(emptyList(), false)
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = Request.Builder().url(baseUrl).build()
    override fun searchMangaParse(response: Response): MangasPage = MangasPage(emptyList(), false)
    override fun mangaDetailsParse(response: Response): SManga = SManga.create()
    override fun chapterListParse(response: Response): List<eu.kanade.tachiyomi.source.model.SChapter> = emptyList()
    override fun chapterPageParse(response: Response): eu.kanade.tachiyomi.source.model.SChapter = eu.kanade.tachiyomi.source.model.SChapter.create()
    override fun pageListParse(response: Response): List<eu.kanade.tachiyomi.source.model.Page> = emptyList()
    override fun imageUrlParse(response: Response): String = ""
}

class FakeDesktopSourceManager(
    private val sources: List<CatalogueSource>,
    override val catalogueSources: Flow<List<CatalogueSource>> = flowOf(sources),
) : SourceManager {
    override val isInitialized: StateFlow<Boolean> = MutableStateFlow(true)
    override fun getCatalogueSources(): List<CatalogueSource> = sources
    override fun getOnlineSources(): List<HttpSource> = sources.filterIsInstance<HttpSource>()
    override fun getStubSources(): List<StubSource> = emptyList()
    override fun get(sourceKey: Long) = sources.find { it.id == sourceKey }
    override fun getOrStub(sourceKey: Long): eu.kanade.tachiyomi.source.Source =
        sources.find { it.id == sourceKey } ?: StubSource(sourceKey, "", "")
}

/** Stub DatabaseHandler that returns empty flows (used for source-query-free tests). */
val FakeHandler: DatabaseHandler = FakeEmptyHandler

object FakeEmptyHandler : DatabaseHandler {
    override suspend fun <T> await(inTransaction: Boolean, block: suspend Database.() -> T): T =
        throw NotImplementedError("FakeEmptyHandler.await")
    override suspend fun <T : Any> awaitList(inTransaction: Boolean, block: suspend Database.() -> Query<T>): List<T> = emptyList()
    override suspend fun <T : Any> awaitOne(inTransaction: Boolean, block: suspend Database.() -> Query<T>): T =
        throw NotImplementedError()
    override suspend fun <T : Any> awaitOneExecutable(inTransaction: Boolean, block: suspend Database.() -> ExecutableQuery<T>): T =
        throw NotImplementedError()
    override suspend fun <T : Any> awaitOneOrNull(inTransaction: Boolean, block: suspend Database.() -> Query<T>): T? = null
    override suspend fun <T : Any> awaitOneOrNullExecutable(inTransaction: Boolean, block: suspend Database.() -> ExecutableQuery<T>): T? = null
    override fun <T : Any> subscribeToList(block: Database.() -> Query<T>): Flow<List<T>> = flowOf(emptyList())
    override fun <T : Any> subscribeToOne(block: Database.() -> Query<T>): Flow<T> = throw NotImplementedError()
    override fun <T : Any> subscribeToOneOrNull(block: Database.() -> Query<T>): Flow<T?> = flowOf(null)
}
