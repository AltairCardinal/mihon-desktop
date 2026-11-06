package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.HttpSource
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
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.repository.MangaRepository
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
class AndroidGlobalSearchLoginCompatibilityUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `actual login click reports frozen owner incompatibility without opening WebView or reading ID`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val idReads = AtomicInteger()
        val targetReads = AtomicInteger()
        val source = mockk<HttpSource>()
        every { source.id } answers {
            idReads.incrementAndGet()
            throw NoSuchMethodError("late ID")
        }
        every { source.name } returns "Login fixture"
        every { source.lang } returns "en"
        every { source.getFilterList() } returns FilterList()
        every { source.baseUrl } answers {
            targetReads.incrementAndGet()
            throw NoSuchMethodError("private login target")
        }
        coEvery { source.getSearchManga(1, any(), any()) } throws HttpException(401)
        fun owner(version: String) = mockk<Extension.Installed> {
            every { pkgName } returns "trusted.login"
            every { name } returns "Trusted login"
            every { versionName } returns version
            every { versionCode } returns 123L
            every { sources } returns listOf(source)
        }
        val installed = MutableStateFlow(listOf(owner("1.2.3")))
        var navigation: Navigator? = null
        try {
            val store = AndroidPreferenceStore(activity.get())
            val preferences = SourcePreferences(store).apply {
                enabledLanguages().set(setOf("en"))
                pinnedSources().set(setOf("9"))
                globalSearchFilterState().set(false)
            }
            val repository = mockk<MangaRepository>()
            coEvery { repository.insertNetworkManga(any()) } answers { firstArg() }
            Injekt.addSingleton(preferences)
            Injekt.addSingleton(BasePreferences(activity.get(), store))
            Injekt.addSingleton(LibraryPreferences(store))
            Injekt.addSingleton<CoverCache>(mockk(relaxed = true))
            Injekt.addSingleton(SourceMangaSearchService())
            Injekt.addSingleton(NetworkToLocalManga(repository))
            Injekt.addSingleton<GetManga>(mockk { every { subscribe(any(), any()) } returns flowOf(null) })
            Injekt.addSingleton<ExtensionManager>(mockk { every { installedExtensionsFlow } returns installed })
            Injekt.addSingleton<SourceManager>(object : SourceManager by mockk(relaxed = true) {
                override val isInitialized = MutableStateFlow(true)
                override fun getQuerySources(): List<Source> = listOf(source)
                override fun getRegisteredSourceId(candidate: Source): Long? = if (candidate === source) 9L else null
            })
            activity.get().setContent {
                MaterialTheme {
                    Navigator(GlobalSearchScreen()) { navigator ->
                        navigation = navigator
                        if (navigator.items.last() is GlobalSearchScreen) CurrentScreen() else Text("Destination")
                    }
                }
            }
            compose.onNode(hasSetTextAction()).performTextInput("query")
            compose.onNode(hasSetTextAction()).performImeAction()
            val login = activity.get().stringResource(MR.strings.login)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(login).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, targetReads.get())
            installed.value = listOf(owner("9.9.9"))
            every { source.name } throws NoSuchMethodError("late name")
            every { source.lang } throws NoSuchMethodError("late language")
            compose.onNodeWithText(login).performClick()
            val incompatible = activity.get().stringResource(MR.strings.source_error_extension_incompatible)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(incompatible).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Trusted login (1.2.3)").assertExists()
            compose.onNodeWithText("private login target", substring = true).assertDoesNotExist()
            assertEquals(1, navigation!!.items.size)
            assertEquals(1, targetReads.get())
            assertEquals(0, idReads.get())
        } finally {
            activity.get().setContent {}
            activity.pause().stop().destroy()
            Injekt = previous
        }
    }
}
