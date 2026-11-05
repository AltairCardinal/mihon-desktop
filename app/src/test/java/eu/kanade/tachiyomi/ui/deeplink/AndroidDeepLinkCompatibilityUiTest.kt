package eu.kanade.tachiyomi.ui.deeplink

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.ResolvableSource
import eu.kanade.tachiyomi.source.online.UriType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.interactor.GetChapterByUrlAndMangaId
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w400dp-h900dp-mdpi")
class AndroidDeepLinkCompatibilityUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `actual deep link source hooks show compatibility instead of navigation or successful chapter sync`() {
        val previous = Injekt
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val observations = mutableListOf<String>()
            for (site in listOf("uri", "manga", "id", "chapter", "chapters")) {
                Injekt = InjektScope(DefaultRegistrar())
                val source = FailingResolvableSource(site)
                val network = mockk<NetworkToLocalManga>()
                coEvery { network.invoke(any<Manga>()) } returns Manga.create().copy(id = 1L, source = 95L)
                val localChapter = mockk<GetChapterByUrlAndMangaId>()
                coEvery { localChapter.await(any(), any()) } returns null
                val sync = mockk<SyncChaptersWithSource>(relaxed = true)
                Injekt.addSingleton<SourceManager>(FixtureManager(source))
                Injekt.addSingleton(network)
                Injekt.addSingleton(localChapter)
                Injekt.addSingleton(sync)
                activity.get().setContent {
                    MaterialTheme { Navigator(DeepLinkScreen("https://fixture.invalid/$site")) { CurrentScreen() } }
                }
                val expected = activity.get().stringResource(MR.strings.source_error_extension_incompatible)
                val visible = try {
                    compose.waitUntil(5_000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
                    true
                } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
                    false
                }
                observations += "$site:$visible:${source.entered}"
                compose.onNodeWithText("private future ABI", substring = true).assertDoesNotExist()
                coVerify(exactly = 0) { sync.await(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
                activity.get().setContent { }
                compose.waitForIdle()
            }
            assertEquals(
                listOf(
                    "uri:true:uri",
                    "manga:true:manga",
                    "id:true:id",
                    "chapter:true:chapter",
                    "chapters:true:chapters",
                ),
                observations,
            )
        } finally {
            activity.get().setContent { }
            activity.pause().stop().destroy()
            Injekt = previous
        }
    }

    private class FailingResolvableSource(private val site: String) : CatalogueSource, ResolvableSource {
        @Volatile var entered: String? = null
        private fun fail(at: String) {
            if (site == at) {
                entered = at
                throw NoSuchMethodError("private future ABI $at")
            }
        }
        override val id: Long get() {
            fail("id")
            return 95L
        }
        override val name = "Resolvable fixture"
        override val supportsLatest = false
        override fun getFilterList() = eu.kanade.tachiyomi.source.model.FilterList()
        override val lang = "en"
        override fun getUriType(uri: String): UriType {
            fail("uri")
            return UriType.Chapter
        }
        override suspend fun getManga(uri: String): SManga {
            fail("manga")
            return SManga.create().apply {
                url = "/manga"
                title = "Manga"
            }
        }
        override suspend fun getChapter(uri: String): SChapter {
            fail("chapter")
            return SChapter.create().apply {
                url = "/chapter"
                name = "Chapter"
            }
        }
        override suspend fun getChapterList(manga: SManga): List<SChapter> {
            fail("chapters")
            return emptyList()
        }
    }

    private class FixtureManager(private val source: CatalogueSource) : SourceManager {
        override val isInitialized = MutableStateFlow(true)
        override val catalogueSources = flowOf(listOf(source))
        override fun get(sourceKey: Long): Source = source
        override fun getOrStub(sourceKey: Long): Source = source
        override fun getOnlineSources() = emptyList<HttpSource>()
        override fun getCatalogueSources() = listOf(source)
        override fun getStubSources() = emptyList<tachiyomi.domain.source.model.StubSource>()
    }
}
