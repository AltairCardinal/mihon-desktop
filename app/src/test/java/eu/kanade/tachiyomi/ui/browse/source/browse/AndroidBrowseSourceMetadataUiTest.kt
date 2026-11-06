package eu.kanade.tachiyomi.ui.browse.source.browse

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import io.mockk.coEvery
import io.mockk.every
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
import org.robolectric.shadows.ShadowLog
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SOURCE_COMPATIBILITY_LOG_TAG
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w400dp-h1000dp-mdpi")
class AndroidBrowseSourceMetadataUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `actual browse title failure is visible before queries without warming HTTP`() {
        withBrowse("name") { fixture ->
            fixture.awaitIncompatible()
            assertEquals(0, fixture.queries.get())
            assertEquals(0, fixture.targets.get())
        }
    }

    @Test
    fun `actual browse latest capability failure is visible before queries`() {
        withBrowse("supportsLatest") { fixture ->
            fixture.awaitIncompatible()
            assertEquals(0, fixture.queries.get())
        }
    }

    @Test
    fun `actual browse assist failure stays visible and leaves no assist URL`() {
        withBrowse("baseUrl") { fixture ->
            fixture.awaitIncompatible()
            assertEquals(null, fixture.screen.onProvideAssistUrl())
            assertEquals(1, fixture.navigation!!.items.size)
        }
    }

    @Test
    fun `actual open WebView uses the prepared title and host ID after metadata becomes unavailable`() {
        withBrowse(null) { fixture ->
            val open = fixture.activity.stringResource(MR.strings.action_open_in_web_view)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(open).fetchSemanticsNodes().isNotEmpty() }
            every { fixture.source.name } throws NoSuchMethodError("late title")
            compose.onNodeWithText(open).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Destination").fetchSemanticsNodes().isNotEmpty() }
            val target = fixture.navigation!!.items.last() as WebViewScreen
            fun field(name: String) = WebViewScreen::class.java.getDeclaredField(name).apply {
                isAccessible = true
            }.get(target)
            assertEquals(9L, field("sourceId"))
            assertEquals("Prepared browse", field("initialTitle"))
            assertEquals("https://browse.invalid", fixture.screen.onProvideAssistUrl())
        }
    }

    @Test
    fun `actual browse login failure keeps the operation owner after installation changes`() {
        withBrowse("login") { fixture ->
            val login = fixture.activity.stringResource(MR.strings.login)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(login).fetchSemanticsNodes().isNotEmpty() }
            fixture.installed.value = emptyList()
            every { fixture.source.baseUrl } throws NoSuchMethodError("private late login")
            compose.onNodeWithText(login).performClick()
            fixture.awaitIncompatible()
            assertEquals(1, fixture.navigation!!.items.size)
            val records = ShadowLog.getLogsForTag(SOURCE_COMPATIBILITY_LOG_TAG)
            org.junit.Assert.assertTrue(
                records.any {
                    it.msg.contains("package=trusted.browse") && it.msg.contains("version=1.2.3")
                },
            )
            org.junit.Assert.assertTrue(records.none { it.msg.contains("private late login") })
        }
    }

    private inner class Fixture(val activity: ComponentActivity, val failure: String?) {
        val queries = AtomicInteger()
        val targets = AtomicInteger()
        val idReads = AtomicInteger()
        val languageReads = AtomicInteger()
        val clientReads = AtomicInteger()
        val source = mockk<HttpSource>()
        val installed = MutableStateFlow(
            listOf(
                mockk<Extension.Installed> {
                    every { sources } returns listOf(source)
                    every { pkgName } returns "trusted.browse"
                    every { name } returns "Trusted browse"
                    every { versionName } returns "1.2.3"
                    every { versionCode } returns 123L
                },
            ),
        )
        val screen = BrowseSourceScreen(9L, BrowseSourceScreenModel.Listing.Popular.query)
        var navigation: Navigator? = null

        init {
            every { source.id } answers {
                idReads.incrementAndGet()
                throw NoSuchMethodError("late ID")
            }
            every { source.name } answers {
                if (failure == "name") throw NoSuchMethodError("private title")
                "Prepared browse"
            }
            every { source.lang } answers {
                languageReads.incrementAndGet()
                throw AssertionError("unused language")
            }
            every { source.supportsLatest } answers {
                if (failure == "supportsLatest") throw NoSuchMethodError("private latest")
                false
            }
            every { source.client } answers {
                clientReads.incrementAndGet()
                throw AssertionError("HTTP warmed")
            }
            every { source.baseUrl } answers {
                targets.incrementAndGet()
                if (failure == "baseUrl") throw NoSuchMethodError("private assist")
                "https://browse.invalid"
            }
            every { source.getFilterList() } returns FilterList()
            coEvery { source.getPopularManga(1) } answers {
                queries.incrementAndGet()
                if (failure == "login") throw HttpException(401)
                MangasPage(emptyList(), false)
            }
        }

        fun awaitIncompatible() {
            val text = activity.stringResource(MR.strings.source_error_extension_incompatible)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private fun withBrowse(failure: String?, block: (Fixture) -> Unit) {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val fixture = Fixture(controller.get(), failure)
        try {
            ShadowLog.clear()
            val store = AndroidPreferenceStore(controller.get())
            Injekt.addSingleton(SourcePreferences(store))
            Injekt.addSingleton(BasePreferences(controller.get(), store))
            Injekt.addSingleton(LibraryPreferences(store))
            Injekt.addSingleton<SourceManager>(object : SourceManager by mockk(relaxed = true) {
                override val isInitialized = MutableStateFlow(true)
                override fun getOrStub(sourceKey: Long): Source = fixture.source
            })
            Injekt.addSingleton<ExtensionManager>(mockk { every { installedExtensionsFlow } returns fixture.installed })
            Injekt.addSingleton<eu.kanade.tachiyomi.data.cache.CoverCache>(mockk(relaxed = true))
            Injekt.addSingleton(SourceMangaSearchService())
            val repository = mockk<MangaRepository>()
            coEvery { repository.insertNetworkManga(any()) } answers { firstArg() }
            Injekt.addSingleton(NetworkToLocalManga(repository))
            Injekt.addSingleton<GetManga>(mockk { every { subscribe(any(), any()) } returns flowOf(null) })
            Injekt.addSingleton<tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga>(mockk())
            Injekt.addSingleton<tachiyomi.domain.category.interactor.GetCategories>(mockk())
            Injekt.addSingleton<tachiyomi.domain.category.interactor.SetMangaCategories>(mockk())
            Injekt.addSingleton<tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags>(mockk(relaxed = true))
            Injekt.addSingleton<eu.kanade.domain.manga.interactor.UpdateManga>(mockk())
            Injekt.addSingleton<eu.kanade.domain.track.interactor.AddTracks>(mockk(relaxed = true))
            Injekt.addSingleton<eu.kanade.domain.source.interactor.GetIncognitoState>(
                mockk {
                    every { await(9L) } returns true
                },
            )
            controller.get().setContent {
                MaterialTheme {
                    Navigator(fixture.screen) { navigator ->
                        fixture.navigation = navigator
                        if (navigator.items.last() is BrowseSourceScreen) CurrentScreen() else Text("Destination")
                    }
                }
            }
            block(fixture)
            assertEquals(0, fixture.idReads.get())
            assertEquals(0, fixture.languageReads.get())
            assertEquals(0, fixture.clientReads.get())
        } finally {
            controller.get().setContent {}
            controller.pause().stop().destroy()
            Injekt = previous
        }
    }
}
