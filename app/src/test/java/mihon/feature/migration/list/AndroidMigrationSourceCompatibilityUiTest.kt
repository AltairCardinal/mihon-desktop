package mihon.feature.migration.list

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w500dp-h1000dp-mdpi")
class AndroidMigrationSourceCompatibilityUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `real migration Screen keeps incompatible candidates visible when unmatched rows are hidden`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val store = AndroidPreferenceStore(activity.get())
            val preferences = SourcePreferences(store).apply {
                migrationSources().set(listOf(11L))
                migrationHideUnmatched().set(true)
                enabledLanguages().set(setOf("en"))
            }
            val candidate = mockk<CatalogueSource> {
                every { id } returns 11L
                every { name } returns "Candidate"
                every { lang } returns "en"
                every { getFilterList() } returns FilterList()
                coEvery { getSearchManga(any(), any(), any()) } throws
                    IOException("private", NoSuchMethodError("future candidate"))
            }
            val originalSource = mockk<Source> {
                every { name } returns "Original source"
                every { lang } returns "en"
            }
            val original = Manga.create().copy(id = 1L, source = 1L, url = "/original", title = "Original work")
            Injekt.addSingleton(preferences)
            Injekt.addSingleton<SourceManager>(
                mockk {
                    every { get(11L) } returns candidate
                    every { getOrStub(1L) } returns originalSource
                    every { getRegisteredSourceId(candidate) } returns 11L
                },
            )
            Injekt.addSingleton<ExtensionManager>(
                mockk {
                    every { installedExtensionsFlow } returns MutableStateFlow(emptyList())
                },
            )
            Injekt.addSingleton<tachiyomi.domain.manga.interactor.GetManga>(
                mockk {
                    coEvery { await(1L) } returns original
                },
            )
            val repository = mockk<MangaRepository>()
            Injekt.addSingleton(NetworkToLocalManga(repository))
            Injekt.addSingleton<eu.kanade.domain.manga.interactor.UpdateManga>(mockk(relaxed = true))
            Injekt.addSingleton<eu.kanade.domain.chapter.interactor.SyncChaptersWithSource>(mockk(relaxed = true))
            Injekt.addSingleton<tachiyomi.domain.chapter.interactor.GetChaptersByMangaId>(
                mockk {
                    coEvery { await(any()) } returns emptyList()
                },
            )
            Injekt.addSingleton<mihon.domain.migration.usecases.MigrateMangaUseCase>(mockk(relaxed = true))
            val landing = object : Screen {
                override val key = "migration-compatibility-landing"

                @androidx.compose.runtime.Composable
                override fun Content() {
                    Text("Landing")
                }
            }
            activity.get().setContent {
                MaterialTheme { Navigator(listOf(landing, MigrationListScreen(listOf(1L), null))) { CurrentScreen() } }
            }
            val incompatible = activity.get().stringResource(MR.strings.source_error_extension_incompatible)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(incompatible).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText("Landing").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(incompatible).assertExists()
            compose.onNodeWithText("Original work").assertExists()
            coVerify(exactly = 0) { repository.insertNetworkManga(any()) }
        } finally {
            activity.get().setContent {}
            activity.pause().stop().destroy()
            Injekt = previous
        }
    }
}
