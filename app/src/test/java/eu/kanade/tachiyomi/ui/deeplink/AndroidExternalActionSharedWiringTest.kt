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
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.ResolvableSource
import eu.kanade.tachiyomi.source.online.UriType
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
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager

class AndroidExternalActionSharedWiringTest {
    @AfterEach
    fun tearDown() {
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
        val model = DeepLinkScreenModel(
            query = (act(Intent.ACTION_SEND, text = url) as ExternalAction.Search).query,
            sourceManager = sourceManager,
            networkToLocalManga = mockk<NetworkToLocalManga>(relaxed = true),
            getChapterByUrlAndMangaId = mockk<GetChapterByUrlAndMangaId>(relaxed = true),
            syncChaptersWithSource = mockk<SyncChaptersWithSource>(relaxed = true),
        )
        val state = withContext(Dispatchers.IO) {
            withTimeout(5_000) { model.state.first { it !is DeepLinkScreenModel.State.Loading } }
        }
        assertSame(DeepLinkScreenModel.State.NoResults, state)
        verify(exactly = 1) { (unknown as ResolvableSource).getUriType(url) }
        verify(atLeast = 1) { (selected as ResolvableSource).getUriType(url) }
        verify(exactly = 0) { (later as ResolvableSource).getUriType(any()) }
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
        override val isInitialized = MutableStateFlow(true)
        override val catalogueSources = flowOf(sources)
        override fun get(sourceKey: Long): Source? = null
        override fun getOrStub(sourceKey: Long): Source = error("Not used")
        override fun getOnlineSources(): List<HttpSource> = emptyList()
        override fun getCatalogueSources(): List<CatalogueSource> = sources
        override fun getStubSources(): List<StubSource> = emptyList()
    }
}
