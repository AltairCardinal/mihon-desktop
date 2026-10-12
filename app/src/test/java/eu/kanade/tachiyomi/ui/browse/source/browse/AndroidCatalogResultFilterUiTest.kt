package eu.kanade.tachiyomi.ui.browse.source.browse

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.presentation.browse.components.BrowseSourceResultMetadata
import eu.kanade.presentation.browse.components.CatalogResultFilterControl
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.CatalogResultFilter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourceResultMetadata
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
class AndroidCatalogResultFilterUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun `real browse screen keeps author query and scans only one original page per continue click`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, String>>()
        val selectedFilters = FilterList(eu.kanade.tachiyomi.source.model.Filter.Header("Native source condition"))
        val source = object : Source {
            override val id = 902L
            override val name = "Result filter fixture"
            override val lang = "ja"
            override fun getFilterList() = selectedFilters
            override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
                assertSame(selectedFilters, filters)
                calls += page to query
                return MangasPage(
                    List(if (page < 3) 25 else 6) { index ->
                        SManga.create().apply {
                            url = "/page/$page/$index"
                            title = when (page) {
                                1 -> "[English] Work $index"
                                2 -> "Unknown work $index"
                                else -> when (index) {
                                    0 -> "中文译本 [轟媽無修正版]"
                                    1 -> "[Chinese] [柠檬汉化组] 另一中文译本"
                                    2 -> "[Chinese] 中文无署名"
                                    4 -> "[简体中文] 简体版"
                                    5 -> "[繁體中文] 繁體版"
                                    else -> "[Japanese] 日文版"
                                }
                            }
                            initialized = true
                        }
                    },
                    hasNextPage = page < 3,
                )
            }
        }
        try {
            registerDependencies(activity.get(), source)
            activity.get().setContent {
                MaterialTheme {
                    Navigator(BrowseSourceScreen(source.id, "author:Alice")) { CurrentScreen() }
                }
            }
            // Pump the Activity's initial composition before polling an IO-side source call.
            // waitUntil advances the Compose clock but does not flush the paused Android main looper.
            compose.waitForIdle()
            compose.waitUntil(10_000) { calls.isNotEmpty() }
            compose.onNodeWithTag("catalog-result-filter-open").performClick()
            compose.onNodeWithTag("catalog-result-language-zh").performClick()
            compose.onNodeWithTag("catalog-result-filter-cancel").performClick()
            compose.onNodeWithText("[English] Work 0", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("catalog-result-filter-open").performClick()
            compose.onNodeWithTag("catalog-result-language-zh").performClick()
            compose.onNodeWithTag("catalog-result-filter-apply").performClick()
            compose.onNodeWithTag("catalog-result-no-match").assertExists()
            compose.runOnIdle { assertEquals(listOf(1 to "author:Alice"), calls.toList()) }

            compose.onNodeWithTag("catalog-result-continue").performClick()
            compose.waitUntil(10_000) { calls.size >= 2 }
            compose.onNodeWithTag("catalog-result-no-match").assertExists()
            compose.runOnIdle {
                assertEquals(listOf(1 to "author:Alice", 2 to "author:Alice"), calls.toList())
            }
            compose.onNodeWithTag("catalog-result-filter-open").performClick()
            compose.onNodeWithTag("catalog-result-unknown-language").performScrollTo().performClick()
            compose.onNodeWithTag("catalog-result-filter-apply").performClick()
            compose.onNodeWithText("Unknown work 0", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("catalog-result-filter-open").performClick()
            compose.onNodeWithTag("catalog-result-unknown-language").performScrollTo().performClick()
            compose.onNodeWithTag("catalog-result-filter-apply").performClick()
            compose.onNodeWithTag("catalog-result-no-match").assertExists()
            compose.onNodeWithTag("catalog-result-continue").performClick()
            compose.waitUntil(10_000) { calls.size >= 3 }
            compose.onNodeWithText("中文译本 [轟媽無修正版]", useUnmergedTree = true).assertExists()
            compose.onNodeWithText("[Chinese] [柠檬汉化组] 另一中文译本", useUnmergedTree = true).assertExists()
            compose.onNodeWithText("[Japanese] 日文版", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("catalog-result-metadata-/page/3/0", true)
                .assertTextContains("轟媽", substring = true)
            compose.onNodeWithTag("catalog-result-continue").assertDoesNotExist()
            compose.runOnIdle { assertEquals(3, calls.size) }

            compose.onNodeWithTag("catalog-result-filter-open").performClick()
            compose.onNodeWithText("轟媽").performScrollTo().performClick()
            compose.onNodeWithTag("catalog-result-filter-apply").performClick()
            compose.onNodeWithText("[Chinese] [柠檬汉化组] 另一中文译本", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("[Chinese] 中文无署名", useUnmergedTree = true).assertDoesNotExist()
            listOf(
                MR.strings.action_display_comfortable_grid,
                MR.strings.action_display_grid,
                MR.strings.action_display_list,
            ).forEach { mode ->
                compose.onNodeWithContentDescription(
                    activity.get().getString(MR.strings.action_display_mode.resourceId),
                )
                    .performClick()
                compose.onNodeWithText(activity.get().getString(mode.resourceId)).performClick()
                compose.onNodeWithTag("catalog-result-metadata-/page/3/0", true)
                    .assertTextContains("轟媽", substring = true)
            }

            compose.onNodeWithTag("catalog-result-filter-open").performClick()
            compose.onNodeWithTag("catalog-result-unknown-group").performScrollTo().performClick()
            compose.onNodeWithTag("catalog-result-filter-apply").performClick()
            compose.onNodeWithText("[Chinese] 中文无署名", useUnmergedTree = true).assertExists()

            listOf("zh-hans" to "[简体中文] 简体版", "zh-hant" to "[繁體中文] 繁體版").forEach { (tag, title) ->
                compose.onNodeWithTag("catalog-result-filter-open").performClick()
                compose.onNodeWithTag("catalog-result-filter-reset").performScrollTo().performClick()
                compose.onNodeWithTag("catalog-result-language-$tag").performScrollTo().performClick()
                compose.onNodeWithTag("catalog-result-filter-apply").performClick()
                compose.onNodeWithText(title, useUnmergedTree = true).assertExists()
                compose.onNodeWithText("中文译本 [轟媽無修正版]", useUnmergedTree = true).assertDoesNotExist()
            }

            compose.onNodeWithTag("catalog-result-filter-open").performClick()
            compose.onNodeWithTag("catalog-result-filter-reset").performScrollTo().performClick()
            compose.onNodeWithTag("catalog-result-filter-apply").performClick()
            compose.onNodeWithText("[English] Work 0", useUnmergedTree = true).assertExists()
            compose.runOnIdle { assertEquals(3, calls.size) }
        } finally {
            activity.pause().stop().destroy()
            Injekt = previous
        }
    }

    @Test
    fun `source without native filters still opens result language and group controls`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            activity.get().setContent {
                MaterialTheme {
                    BrowseSourceListingChips(
                        source = object : Source {
                            override val id = 901L
                            override val name = "No native filters"
                        },
                        listing = BrowseSourceScreenModel.Listing.Popular,
                        hasFilters = false,
                        onListingSelected = {},
                        onFilterClick = {},
                    )
                }
            }
            compose.onNodeWithTag("catalog-result-filter-open").performClick()
            compose.onNodeWithTag("catalog-result-language-zh").assertExists()
            compose.onNodeWithTag("catalog-result-language-zh-hans").assertExists()
            compose.onNodeWithTag("catalog-result-language-zh-hant").assertExists()
            compose.onNodeWithTag("catalog-result-unknown-language").assertExists()
            compose.onNodeWithTag("catalog-result-unknown-group").assertExists()
            compose.onNodeWithTag("catalog-result-filter-cancel").performClick()
            compose.onNodeWithTag("catalog-result-language-zh").assertDoesNotExist()
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `selected group stays removable without current matches and a translator label has no inferred suffix`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val manga = Manga.create().copy(url = "/credit", title = "A Journey [翻译：Saved Group]")
        val filter = mutableStateOf(CatalogResultFilter(translationGroupKeys = setOf("Saved Group")))
        val metadata = mutableStateOf(listOf(SourceResultMetadata.from(manga)))
        try {
            activity.get().setContent {
                MaterialTheme {
                    Column {
                        CatalogResultFilterControl(filter.value, metadata.value) { filter.value = it }
                        BrowseSourceResultMetadata(manga)
                    }
                }
            }
            compose.onNodeWithTag("catalog-result-metadata-/credit", true)
                .assertTextEquals(
                    activity.get().getString(MR.strings.catalog_result_unknown.resourceId) + " · Saved Group",
                )
            compose.runOnIdle { metadata.value = emptyList() }
            compose.onNodeWithTag("catalog-result-filter-open").performClick()
            compose.onNodeWithText("Saved Group").performScrollTo().performClick()
            compose.onNodeWithTag("catalog-result-filter-apply").performClick()
            compose.runOnIdle { assertEquals(emptySet<String>(), filter.value.translationGroupKeys) }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `real browse screen projects hide in library preference without dropping raw loaded items`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val source = object : Source {
            override val id = 903L
            override val name = "Hidden favorite fixture"
            override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = MangasPage(
                listOf("/favorite" to "Favorite work", "/visible" to "Visible work").map { (url, title) ->
                    SManga.create().apply {
                        this.url = url
                        this.title = title
                        initialized = true
                    }
                },
                hasNextPage = false,
            )
        }
        val favorite = Manga.create().copy(
            id = 123L,
            source = source.id,
            url = "/favorite",
            title = "Favorite work",
            favorite = true,
        )
        try {
            registerDependencies(activity.get(), source, hiddenFavorite = favorite)
            activity.get().setContent {
                MaterialTheme {
                    Navigator(BrowseSourceScreen(source.id, "fixture")) { CurrentScreen() }
                }
            }
            compose.waitForIdle()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Visible work", useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText("Favorite work", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("catalog-result-metadata-/favorite", true).assertDoesNotExist()
            compose.onNodeWithTag("catalog-result-scope").assertTextContains(
                activity.get().getString(MR.strings.catalog_result_scope.resourceId, 2, 1),
            )
        } finally {
            activity.pause().stop().destroy()
            Injekt = previous
        }
    }

    private fun registerDependencies(context: Context, source: Source, hiddenFavorite: Manga? = null) {
        val sharedPreferences = context.getSharedPreferences("catalog-result-ui-${source.id}", Context.MODE_PRIVATE)
        sharedPreferences.edit().clear().commit()
        val store = AndroidPreferenceStore(context, sharedPreferences)
        val preferences = SourcePreferences(store)
        preferences.sourceDisplayMode().set(LibraryDisplayMode.List)
        preferences.hideInLibraryItems().set(hiddenFavorite != null)
        assertEquals(hiddenFavorite != null, SourcePreferences(store).hideInLibraryItems().get())
        assertEquals(LibraryDisplayMode.List, SourcePreferences(store).sourceDisplayMode().get())
        val repository = mockk<MangaRepository>()
        coEvery { repository.insertNetworkManga(any()) } answers { firstArg() }
        Injekt.addSingleton<SourceManager>(
            mockk {
                every { getOrStub(source.id) } returns source
                every { isInitialized } returns MutableStateFlow(true)
            },
        )
        Injekt.addSingleton(preferences)
        Injekt.addSingleton(LibraryPreferences(store))
        Injekt.addSingleton<CoverCache>(mockk(relaxed = true))
        Injekt.addSingleton(SourceMangaSearchService())
        Injekt.addSingleton(NetworkToLocalManga(repository))
        Injekt.addSingleton<GetDuplicateLibraryManga>(mockk())
        Injekt.addSingleton<GetCategories>(mockk())
        Injekt.addSingleton<SetMangaCategories>(mockk())
        Injekt.addSingleton<SetMangaDefaultChapterFlags>(mockk(relaxed = true))
        Injekt.addSingleton<GetManga>(
            mockk {
                every { subscribe(any(), any()) } answers {
                    flowOf(hiddenFavorite?.takeIf { it.url == firstArg<String>() })
                }
            },
        )
        Injekt.addSingleton<UpdateManga>(mockk())
        Injekt.addSingleton<AddTracks>(mockk(relaxed = true))
        Injekt.addSingleton<GetIncognitoState>(
            mockk {
                every { await(source.id) } returns false
            },
        )
    }
}
