package eu.kanade.tachiyomi.ui.deeplink

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.presentation.more.settings.screen.browse.ExtensionReposScreen
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupScreen
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.ResolvableSource
import eu.kanade.tachiyomi.source.online.UriType
import eu.kanade.tachiyomi.test.ScreenModelTestHost
import eu.kanade.tachiyomi.ui.main.navigateExternalAction
import eu.kanade.tachiyomi.ui.main.toExternalAction
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.domain.platform.ExternalAction
import mihon.domain.platform.ExternalActionParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChapterByUrlAndMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager

class AndroidExternalActionSharedWiringTest {
    private val modelHost = ScreenModelTestHost()

    @AfterEach
    fun tearDown() {
        modelHost.close()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `Android search and send inputs preserve fixed main values through shared parser`() {
        mockkObject(ExternalActionParser)
        every { ExternalActionParser.resolve(any()) } answers { callOriginal() }
        assertEquals(ExternalAction.Search("fallback"), act(Intent.ACTION_SEARCH, text = "fallback"))
        assertEquals(ExternalAction.NoOp, act(Intent.ACTION_SEARCH, query = "", text = "fallback"))
        assertEquals(ExternalAction.Search(" \t"), act(Intent.ACTION_SEARCH, query = " \t"))
        val send = act(Intent.ACTION_SEND, query = "send query", text = "shared text")
        assertEquals(ExternalAction.Search("send query"), send)
        assertEquals(ExternalAction.Search("shared text"), act(Intent.ACTION_SEND, text = "shared text"))
        assertEquals(ExternalAction.NoOp, act(Intent.ACTION_SEND, query = "", text = "shared text"))
        val googleAction = "com.google.android.gms.actions.SEARCH_ACTION"
        assertEquals(ExternalAction.Search("google"), act(googleAction, query = "google"))
        verify(exactly = 7) { ExternalActionParser.resolve(any()) }
    }

    @Test
    fun `external intents navigate accepted screens and ignore rejected or empty actions`() {
        mockkObject(ExternalActionParser)
        every { ExternalActionParser.resolve(any()) } answers { callOriginal() }
        val backup = "tachiyomi://add-repo?url=https%3A%2F%2Fexample.org%2Frepo.tachibk"
        val repository = "tachiyomi://add-repo?url=https%3A%2F%2Fexample.org%2Findex.min.json"
        val navigator = mockk<Navigator>(relaxed = true)
        val pushed = mutableListOf<Screen>()
        every { navigator.push(capture(pushed)) } answers { Unit }
        listOf(
            intent(Intent.ACTION_SEARCH, query = "query"),
            intent(Intent.ACTION_VIEW, data = backup),
            intent(Intent.ACTION_VIEW, data = repository),
            intent(Intent.ACTION_VIEW, data = "tachiyomi://add-repo?url=%"),
            intent(Intent.ACTION_VIEW, data = "https://example.org/manga/1"),
            intent(Intent.ACTION_SEARCH, query = ""),
        ).forEach { it.navigateExternalAction(navigator) }
        assertEquals("query", (pushed[0] as DeepLinkScreen).query)
        assertEquals(backup, (pushed[1] as RestoreBackupScreen).field("uri"))
        assertEquals("https://example.org/index.min.json", (pushed[2] as ExtensionReposScreen).field("url"))
        verify(exactly = 6) { ExternalActionParser.resolve(any()) }
        verify(exactly = 3) { navigator.popUntilRoot() }
    }

    @Test
    fun `source URL search uses first resolvable source and falls back to NoResults`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val url = "https://example.org/manga/1"
        val unknown = resolvableSource(UriType.Unknown)
        val selected = resolvableSource(UriType.Manga)
        val later = resolvableSource(UriType.Manga)
        coEvery { (selected as ResolvableSource).getManga(url) } returns null
        val sourceManager = TestSourceManager(listOf(unknown, selected, later))
        val model = modelHost.create {
            DeepLinkScreenModel(
                query = (act(Intent.ACTION_SEND, text = url) as ExternalAction.Search).query,
                sourceManager = sourceManager,
                networkToLocalManga = mockk<NetworkToLocalManga>(relaxed = true),
                getChapterByUrlAndMangaId = mockk<GetChapterByUrlAndMangaId>(relaxed = true),
                syncChaptersWithSource = mockk<SyncChaptersWithSource>(relaxed = true),
            )
        }
        val state = withContext(Dispatchers.IO) {
            withTimeout(5_000) { model.state.first { it !is DeepLinkScreenModel.State.Loading } }
        }
        assertSame(DeepLinkScreenModel.State.NoResults, state)
        verify(exactly = 1) { (unknown as ResolvableSource).getUriType(url) }
        verify(atLeast = 1) { (selected as ResolvableSource).getUriType(url) }
        verify(exactly = 0) { (later as ResolvableSource).getUriType(any()) }
    }

    @Test
    fun `resolvable source URI hook preserves typed ABI failure instead of escaping source selection`() {
        val missing = NoSuchMethodError("future uri hook")
        val source = resolvableSource(UriType.Manga)
        every { (source as ResolvableSource).getUriType(any()) } throws missing
        val failure = runCatching { TestSourceManager(listOf(source)).firstResolvableSource("private-query") }
            .exceptionOrNull()
        assertEquals("SourceCompatibilityException", failure?.javaClass?.simpleName)
        assertSame(missing, failure?.cause)
    }

    @Test
    fun `deep link chapter resolution keeps healthy source and existing chapter synchronization wiring`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = resolvableSource(UriType.Chapter)
        every { source.id } returns 42L
        val remote = SManga.create().apply {
            url = "/manga"
            title = "Manga"
        }
        val remoteChapter = SChapter.create().apply {
            url = "/chapter"
            name = "Chapter"
        }
        coEvery { (source as ResolvableSource).getManga(any()) } returns remote
        coEvery { (source as ResolvableSource).getChapter(any()) } returns remoteChapter
        coEvery { source.getChapterList(any()) } returns listOf(remoteChapter)
        val manga = Manga.create().copy(id = 1L, source = 42L)
        val network = mockk<NetworkToLocalManga>()
        coEvery { network.invoke(any<Manga>()) } returns manga
        val lookup = mockk<GetChapterByUrlAndMangaId>()
        coEvery { lookup.await(any(), any()) } returns null
        val sync = mockk<SyncChaptersWithSource>()
        coEvery { sync.await(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns listOf(
            Chapter.create().copy(id = 77L, mangaId = 1L, url = "/chapter"),
        )
        val model = modelHost.create {
            DeepLinkScreenModel(
                "https://fixture.invalid/chapter",
                TestSourceManager(listOf(source)),
                network,
                lookup,
                sync,
            )
        }
        val state = withContext(Dispatchers.IO) {
            withTimeout(5_000) { model.state.first { it !is DeepLinkScreenModel.State.Loading } }
        }
        assertEquals(DeepLinkScreenModel.State.Result(manga, 77L), state)
        io.mockk.coVerify(exactly = 1) { sync.await(any(), manga, source, false, any(), any(), any(), any(), any()) }
    }

    private fun act(action: String, query: String? = null, text: String? = null) =
        intent(action, query, text).toExternalAction()
    private fun intent(action: String, query: String? = null, text: String? = null, data: String? = null): Intent {
        val uri = data?.let { value -> mockk<Uri>().also { every { it.toString() } returns value } }
        return mockk {
            every { this@mockk.action } returns action
            every { getStringExtra(SearchManager.QUERY) } returns query
            every { getStringExtra(Intent.EXTRA_TEXT) } returns text
            every { this@mockk.data } returns uri
        }
    }
    private fun resolvableSource(type: UriType): CatalogueSource =
        mockk(moreInterfaces = arrayOf(ResolvableSource::class), relaxed = true) {
            every { (this@mockk as ResolvableSource).getUriType(any()) } returns type
        }
    private fun Any.field(name: String) = javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(this)
    private class TestSourceManager(private val sources: List<CatalogueSource>) : SourceManager {
        private val registrations = sources.map { it to it.id }
        override fun getRegisteredSourceId(source: Source): Long? =
            registrations.firstOrNull { it.first === source }?.second
        override val isInitialized = MutableStateFlow(true)
        override val catalogueSources = flowOf(sources)
        override fun get(sourceKey: Long): Source? = null
        override fun getOrStub(sourceKey: Long): Source = error("Not used")
        override fun getOnlineSources(): List<HttpSource> = emptyList()
        override fun getCatalogueSources(): List<CatalogueSource> = sources
        override fun getStubSources(): List<StubSource> = emptyList()
    }
}
