package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h1000dp-mdpi")
class AndroidGlobalSearchCompatibilityUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `actual search shows the frozen owner and new query cancels only the old pending source`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val brokenCalls = AtomicInteger()
        val healthyCalls = AtomicInteger()
        val brokenStarted = CompletableDeferred<Unit>()
        val releaseBroken = CompletableDeferred<Unit>()
        val pendingStarted = CompletableDeferred<Unit>()
        val pendingCancelled = CompletableDeferred<Unit>()
        val broken = object : FixtureSource(9L, "Broken source") {
            override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
                brokenCalls.incrementAndGet()
                if (query == "first") {
                    brokenStarted.complete(Unit)
                    releaseBroken.await()
                }
                throw NoSuchMethodError("private extension ABI details")
            }
        }
        val healthy = object : FixtureSource(10L, "Healthy source") {
            override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
                healthyCalls.incrementAndGet()
                return MangasPage(
                    listOf(
                        SManga.create().apply {
                            url = "/$query"
                            title = "Healthy $query"
                            initialized = true
                        },
                    ),
                    false,
                )
            }
        }
        val pending = object : FixtureSource(11L, "Pending source") {
            override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
                if (query == "first") {
                    pendingStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        pendingCancelled.complete(Unit)
                    }
                }
                return MangasPage(emptyList(), false)
            }
        }
        val sources = listOf(broken, healthy, pending)
        fun installed(version: String) = mockk<Extension.Installed> {
            every { pkgName } returns "trusted.package"
            every { name } returns "Trusted extension"
            every { versionName } returns version
            every { versionCode } returns 123L
            every { this@mockk.sources } returns sources
        }
        val owners = MutableStateFlow(listOf(installed("1.2.3")))
        try {
            val store = AndroidPreferenceStore(activity.get())
            val preferences = SourcePreferences(store).apply {
                enabledLanguages().set(setOf("en"))
                pinnedSources().set(setOf("9", "10", "11"))
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
            Injekt.addSingleton<ExtensionManager>(mockk { every { installedExtensionsFlow } returns owners })
            Injekt.addSingleton<SourceManager>(object : SourceManager by mockk(relaxed = true) {
                override val isInitialized = MutableStateFlow(true)
                override fun getQuerySources(): List<Source> = sources
            })
            activity.get().setContent { MaterialTheme { Navigator(GlobalSearchScreen()) { CurrentScreen() } } }
            compose.onNode(hasSetTextAction()).performTextInput("first")
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(10_000) {
                brokenStarted.isCompleted && pendingStarted.isCompleted &&
                    compose.onAllNodesWithText("Healthy first").fetchSemanticsNodes().isNotEmpty()
            }
            owners.value = listOf(installed("9.9.9"))
            releaseBroken.complete(Unit)
            val message = activity.get().stringResource(MR.strings.source_error_extension_incompatible)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.onNodeWithText("Trusted extension (1.2.3)").assertIsDisplayed()
            compose.onNodeWithText("private extension ABI details", substring = true).assertDoesNotExist()
            assertEquals(1, brokenCalls.get())
            assertEquals(1, healthyCalls.get())
            assertFalse(pendingCancelled.isCompleted)

            compose.onNode(hasSetTextAction()).performTextReplacement("second")
            compose.onNode(hasSetTextAction()).performImeAction()
            try {
                compose.waitUntil(10_000) {
                    pendingCancelled.isCompleted &&
                        compose.onAllNodesWithText("Healthy second").fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodesWithText("Trusted extension (9.9.9)").fetchSemanticsNodes().isNotEmpty()
                }
            } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError(
                    "broken=${brokenCalls.get()}, healthy=${healthyCalls.get()}, " +
                        "cancelled=${pendingCancelled.isCompleted}\n" +
                        compose.onRoot(useUnmergedTree = true).printToString().take(6_000),
                    failure,
                )
            }
            compose.onNodeWithText("Healthy first").assertDoesNotExist()
            compose.onNodeWithText("Trusted extension (1.2.3)").assertDoesNotExist()
            assertEquals(2, brokenCalls.get())
            assertEquals(2, healthyCalls.get())
            val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
            compose.waitUntil(10_000) { compose.onAllNodes(progress).fetchSemanticsNodes().isEmpty() }
            compose.onAllNodes(progress).assertCountEquals(0)

            owners.value = emptyList()
            compose.onNode(hasSetTextAction()).performTextReplacement("third")
            compose.onNode(hasSetTextAction()).performImeAction()
            val unknown = activity.get().stringResource(MR.strings.unknown)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("$unknown ($unknown)").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("Healthy third").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodes(progress).fetchSemanticsNodes().isEmpty()
            }
            assertEquals(3, brokenCalls.get())
            assertEquals(3, healthyCalls.get())
        } finally {
            releaseBroken.complete(Unit)
            activity.get().setContent {}
            activity.pause().stop().destroy()
            Injekt = previous
        }
    }

    private abstract class FixtureSource(override val id: Long, override val name: String) : Source {
        override val lang = "en"
        override fun getFilterList() = FilterList()
    }
}
