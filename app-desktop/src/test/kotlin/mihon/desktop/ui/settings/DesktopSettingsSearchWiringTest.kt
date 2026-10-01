package mihon.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.domain.ui.model.selectableAppThemes
import eu.kanade.presentation.theme.colorscheme.AppThemeColorScheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.platform.DesktopLocaleAdapter
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.ui.theme.DesktopTheme
import mihon.desktop.ui.theme.desktopColorScheme
import mihon.domain.settings.SearchableSettingsScreen
import mihon.domain.settings.SettingsLayoutDirection
import mihon.domain.settings.SettingsSearchPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
@org.junit.jupiter.api.parallel.Isolated
class DesktopSettingsSearchWiringTest {
    @Test
    fun `manual tracking preference search enters actual three state anchored control`() = runBlocking {
        withRestoredLocale {
            Locale.setDefault(Locale.US)
            withSearchScene(height = 500) { scene ->
                lateinit var navigator: Navigator
                scene.setContent {
                    dependencies {
                        Navigator(SettingsSearchScreen()) { nav ->
                            navigator = nav
                            CurrentScreen()
                        }
                    }
                }
                render(scene)
                val title = MR.strings.pref_auto_update_manga_on_mark_read.localized()
                setText(scene, title)
                render(scene)
                assertTrue(
                    nodes(scene, true).any {
                        title in flatten(it).flatMap(::text) &&
                            it.config.contains(SemanticsActions.OnClick)
                    },
                    "search exposes the production manual policy",
                )
                click(scene, title)
                render(scene)
                assertTrue(navigator.lastItem is mihon.desktop.ui.tracking.TrackingSettingsScreen)
                val highlighted = nodes(scene, true).single {
                    it.config.getOrElse(DesktopSettingsAnchorHighlighted) { false }
                }
                assertTrue(title in flatten(highlighted).flatMap(::text))
                click(scene, title)
                render(scene)
                click(scene, eu.kanade.domain.track.model.AutoTrackState.ASK.titleRes.localized())
                render(scene)
                assertEquals(
                    eu.kanade.domain.track.model.AutoTrackState.ASK,
                    currentPreferences.autoUpdateTrackOnMarkRead.get(),
                )
            }
        }
    }

    @Test
    fun `legacy column search uses shared controls and preserves both orientations`() = runBlocking {
        withRestoredLocale {
            Locale.setDefault(Locale.US)
            withSearchScene(height = 420) { scene ->
                lateinit var navigator: Navigator
                scene.setContent {
                    dependencies {
                        Navigator(SettingsSearchScreen()) { nav ->
                            navigator = nav
                            CurrentScreen()
                        }
                    }
                }
                render(scene)
                val title = MR.strings.desktop_appearance_library_grid.localized()
                setText(scene, title)
                render(scene)
                click(scene, title)
                render(scene)
                assertTrue(
                    navigator.lastItem is LibrarySettingsScreen,
                    "legacy column search must enter the actual shared library controls",
                )
                assertEquals(1, navigator.size)
                val highlighted = nodes(scene, true).single {
                    it.config.contains(DesktopSettingsAnchorHighlighted) && it.config[DesktopSettingsAnchorHighlighted]
                }
                assertTrue(title in text(highlighted))
                val sliders = nodes(scene, true).filter { it.config.contains(SemanticsActions.SetProgress) }
                assertEquals(2, sliders.size)
                requireNotNull(sliders[0].config[SemanticsActions.SetProgress].action).invoke(0f)
                requireNotNull(sliders[1].config[SemanticsActions.SetProgress].action).invoke(10f)
                assertEquals(0, currentLibraryPreferences.portraitColumns().get())
                assertEquals(10, currentLibraryPreferences.landscapeColumns().get())
                assertFalse(
                    currentPreferences.libraryGridColumns.isSet(),
                    "shared controls must not dual-write the legacy preference",
                )
            }
        }
    }

    @Test
    fun `More public entries execute existing category creation storage navigation and Help`() = runBlocking {
        val categories = mockk<tachiyomi.domain.category.interactor.GetCategories> {
            every { subscribe() } returns kotlinx.coroutines.flow.flowOf(emptyList())
        }
        io.mockk.coEvery { categories.await() } returns emptyList()
        val create = mockk<tachiyomi.domain.category.interactor.CreateCategoryWithName>()
        io.mockk.coEvery { create.await(any()) } returns
            tachiyomi.domain.category.interactor.CreateCategoryWithName.Result.Success
        val model = mihon.desktop.ui.library.LibraryScreenModel(getCategories = categories, createCategory = create)
        var helpUri: String? = null
        withSearchScene(MoreRootScreen(), height = 1400) { scene ->
            lateinit var navigator: Navigator
            scene.setContent {
                dependencies {
                    CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalUriHandler provides
                            object : androidx.compose.ui.platform.UriHandler {
                                override fun openUri(uri: String) {
                                    helpUri = uri
                                }
                            },
                    ) {
                        mihon.desktop.ui.library.ProvideLibraryScreenModelFactory({ model }) {
                            Navigator(MoreRootScreen()) { nav ->
                                navigator = nav
                                CurrentScreen()
                            }
                        }
                    }
                }
            }
            render(scene)
            click(scene, MR.strings.categories.localized())
            render(scene)
            assertTrue(navigator.lastItem is mihon.desktop.ui.library.CategoryManagementScreen)
            assertTrue(MR.strings.action_edit_categories.localized() in text(scene))
            val add = nodes(scene, true).single {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag] == "category-add"
            }
            requireNotNull(add.config[SemanticsActions.OnClick].action).invoke()
            render(scene)
            setText(scene, "Research")
            render(scene)
            val confirm = nodes(scene).last {
                it.config.contains(SemanticsActions.OnClick) &&
                    MR.strings.action_add.localized() in flatten(it).flatMap(::text)
            }
            requireNotNull(confirm.config[SemanticsActions.OnClick].action).invoke()
            render(scene)
            io.mockk.coVerify { create.await("Research") }
            clickDescription(scene, MR.strings.action_bar_up_description.localized())
            render(scene)
            click(scene, MR.strings.label_data_storage.localized())
            assertTrue(navigator.lastItem is BackupSettingsScreen)
            navigator.pop()
            render(scene)
            click(scene, MR.strings.label_help.localized())
            assertEquals(tachiyomi.core.common.Constants.URL_HELP, helpUri)
        }
    }

    @Test
    fun `settings directory preserves upstream public order before desktop General`() {
        assertEquals(
            listOf(
                AppearanceSettingsScreen::class, LibrarySettingsScreen::class, ReaderSettingsScreen::class,
                DownloadSettingsScreen::class, mihon.desktop.ui.tracking.TrackingSettingsScreen::class,
                ExtensionRepoScreen::class, BackupSettingsScreen::class, SecuritySettingsScreen::class,
                AdvancedSettingsScreen::class, AboutScreen::class, GeneralSettingsScreen::class,
            ),
            DesktopSettingsCatalog.directoryItems().map { it.route::class },
        )
    }

    @Test
    fun `HomeScreen root clicks and actual More and Library stacks are wired`() = runBlocking {
        mockkObject(mihon.desktop.updates.UpdatesScreenModelFactory)
        val updates = mockk<mihon.desktop.updates.UpdatesScreenModel>(relaxed = true) {
            every { state } returns MutableStateFlow(mihon.desktop.updates.UpdatesState())
        }
        every { mihon.desktop.updates.UpdatesScreenModelFactory.create() } returns updates
        val manga = mockk<tachiyomi.domain.manga.interactor.GetLibraryManga> {
            every { subscribe() } returns kotlinx.coroutines.flow.flowOf(emptyList())
        }
        val categories = mockk<tachiyomi.domain.category.interactor.GetCategories> {
            every { subscribe() } returns kotlinx.coroutines.flow.flowOf(emptyList())
        }
        lateinit var libraryStack: mihon.desktop.ui.library.LibraryScreenStack
        lateinit var libraryModel: mihon.desktop.ui.library.LibraryScreenModel
        val host = mihon.desktop.ui.library.VoyagerLibraryNavigationHost(onStackAttached = { libraryStack = it })
        try {
            withSearchScene(mihon.desktop.ui.home.HomeScreen(), width = 1400, height = 900) { scene ->
                scene.setContent {
                    dependencies {
                        mihon.desktop.ui.library.ProvideLibraryNavigationHost(host) {
                            mihon.desktop.ui.library.ProvideLibraryScreenModelFactory({
                                mihon.desktop.ui.library.LibraryScreenModel(
                                    getLibraryManga = manga,
                                    getCategories = categories,
                                    libraryPreferences = currentLibraryPreferences,
                                ).also { libraryModel = it }
                            }) { Navigator(mihon.desktop.ui.home.HomeScreen()) { CurrentScreen() } }
                        }
                    }
                }
                render(scene)
                fun tag(
                    name: String,
                ) = nodes(scene, true).firstOrNull {
                    it.config.contains(SemanticsProperties.TestTag) &&
                        it.config[SemanticsProperties.TestTag] == name
                }
                fun choose(tab: Tab) {
                    val button = requireNotNull(tag("desktop-root-${tab.key}"))
                    requireNotNull(button.config[SemanticsActions.OnClick].action).invoke()
                }
                choose(mihon.desktop.ui.browse.BrowseTab)
                render(scene)
                assertTrue(MR.strings.local_source.localized() in text(scene))
                choose(mihon.desktop.ui.updates.UpdatesTab)
                render(scene)
                io.mockk.coVerify { updates.loadUpdates(any()) }
                choose(mihon.desktop.ui.more.MoreTab)
                render(scene)
                assertTrue(MR.strings.label_downloaded_only.localized() in text(scene))
                click(scene, MR.strings.label_downloaded_only.localized())
                render(scene)
                click(scene, MR.strings.label_settings.localized())
                render(scene)
                assertEquals(null, tag("desktop-root-rail"), "production MoreTab must report its child stack")
                clickDescription(scene, MR.strings.action_bar_up_description.localized())
                render(scene)
                choose(mihon.desktop.ui.library.LibraryTab)
                render(scene)
                assertTrue(
                    libraryModel.state.value.filter.globalDownloadedOnly,
                    "More toggle must reach the actual Library consumer",
                )
                lateinit var child: Navigator
                libraryStack.push(object : Screen {
                    override val key = "library-child-fixture"

                    @androidx.compose.runtime.Composable
                    override fun Content() {
                        child = cafe.adriel.voyager.navigator.LocalNavigator.currentOrThrow
                        androidx.compose.material3.Text("Library child")
                    }
                })
                render(scene)
                assertEquals(null, tag("desktop-root-rail"), "production Library host must report its child stack")
                child.pop()
                render(scene)
                assertTrue(tag("desktop-root-rail") != null)
            }
        } finally {
            unmockkObject(mihon.desktop.updates.UpdatesScreenModelFactory)
            mihon.desktop.test.navigation.TestNavigationController.reset()
        }
    }
    private val creatorRows = MutableStateFlow(emptyList<tachiyomi.domain.creator.model.Creator>())
    private val preferenceNode = java.util.prefs.Preferences.userRoot().node(
        "mihon-tests/settings-${java.util.UUID.randomUUID()}",
    )

    @org.junit.jupiter.api.AfterEach
    fun removeTestPreferences() {
        preferenceNode.removeNode()
    }

    @Test
    fun `settings directory retains scroll anchor after narrowing and returning from child`() = runBlocking {
        withSearchScene(SettingsRootScreen(), width = 1400, height = 600) { scene ->
            render(scene)
            fun directory() = nodes(scene, true).single {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag] == "desktop-settings-directory"
            }
            val scroll = flatten(directory()).first { it.config.contains(SemanticsActions.ScrollToIndex) }
            requireNotNull(scroll.config[SemanticsActions.ScrollToIndex].action).invoke(3)
            render(scene)
            val anchor = MR.strings.pref_category_downloads.localized()
            fun anchorTop() = flatten(directory()).first {
                it.config.contains(SemanticsActions.OnClick) && flatten(it).any { child -> anchor in text(child) }
            }.boundsInRoot.top
            val before = anchorTop()
            val beforeScroll = directory().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue(beforeScroll > 0f, "fixture must scroll the actual directory")
            scene.resize(320, 600)
            render(scene)
            clickDescription(scene, MR.strings.action_bar_up_description.localized())
            render(scene)
            assertTrue(
                directory().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f,
                "directory scroll must survive hiding its composition",
            )
            assertEquals(
                beforeScroll,
                directory().config[SemanticsProperties.VerticalScrollAxisRange].value(),
                "directory must retain its scroll position",
            )
            assertEquals(before, anchorTop(), 1f, "returning to the directory must retain its visible anchor")
        }
    }

    @Test
    fun `narrow settings search returns to directory and wide appearance survives narrowing`() = runBlocking {
        withSearchScene(SettingsRootScreen(), width = 320, height = 900) { scene ->
            render(scene)
            clickDescription(scene, MR.strings.action_search_settings.localized())
            render(scene)
            clickDescription(scene, MR.strings.action_bar_up_description.localized())
            render(scene)
            assertTrue(
                nodes(scene, true).any {
                    it.config.contains(SemanticsProperties.TestTag) &&
                        it.config[SemanticsProperties.TestTag] == "desktop-settings-directory"
                },
            )
            assertFalse(nodes(scene, true).any { it.config.contains(SemanticsActions.SetProgress) })
        }
        withSearchScene(SettingsRootScreen(), width = 1400, height = 900) { scene ->
            render(scene)
            scene.resize(320, 900)
            render(scene)
            assertTrue(
                nodes(scene, true).any {
                    it.config.contains(SemanticsProperties.TestTag) &&
                        it.config[SemanticsProperties.TestTag] == "appearance-theme-cards"
                },
                "current Appearance must survive resizing",
            )
            assertFalse(
                nodes(scene, true).any {
                    it.config.contains(SemanticsProperties.TestTag) &&
                        it.config[SemanticsProperties.TestTag] == "desktop-settings-directory"
                },
            )
        }
        withSearchScene(SettingsRootScreen(), width = 1400, height = 900) { scene ->
            render(scene)
            clickDescription(scene, MR.strings.action_search_settings.localized())
            render(scene)
            val query = MR.strings.pref_app_theme.localized()
            setText(scene, query)
            render(scene)
            scene.resize(320, 900)
            render(scene)
            assertEquals(query, field(scene).config[SemanticsProperties.EditableText].text)
            scene.resize(1400, 900)
            render(scene)
            assertEquals(query, field(scene).config[SemanticsProperties.EditableText].text)
            click(scene, query)
            render(scene)
            assertTrue(
                nodes(scene, true).any {
                    it.config.contains(DesktopSettingsAnchorHighlighted) && it.config[DesktopSettingsAnchorHighlighted]
                },
                "host search must execute the real Appearance anchor",
            )
        }
    }

    @Test
    fun `Browse exposes four ordered sections and consumes the legacy Authors request`() = runBlocking {
        try {
            withSearchScene(mihon.desktop.ui.browse.BrowseSourceListScreen()) { scene ->
                lateinit var navigator: Navigator
                scene.setContent {
                    dependencies {
                        Navigator(mihon.desktop.ui.browse.BrowseSourceListScreen()) { nav ->
                            navigator =
                                nav
                            CurrentScreen()
                        }
                    }
                }
                render(scene)
                val labels =
                    listOf(
                        MR.strings.label_sources,
                        MR.strings.desktop_ui_authors,
                        MR.strings.label_extensions,
                        MR.strings.label_migration,
                    )
                labels.forEach { assertTrue(it.localized() in text(scene), "Browse missing ${it.localized()}") }
                mihon.desktop.test.navigation.TestNavigationController.navigateToTab("Authors")
                render(scene)
                assertTrue(MR.strings.desktop_ui_followed.localized() in text(scene))
                setText(scene, "Ada")
                render(scene)
                kotlinx.coroutines.withTimeout(5_000) {
                    while ("Ada Lovelace" !in text(scene)) {
                        kotlinx.coroutines.delay(10)
                        render(scene)
                    }
                }
                click(scene, "Ada Lovelace")
                assertTrue(navigator.lastItem is mihon.desktop.ui.authors.AuthorDetailScreen)
                navigator.pop()
                render(scene)
                assertEquals("Ada", field(scene).config[SemanticsProperties.EditableText].text)
                click(scene, MR.strings.label_sources.localized())
                render(scene)
                assertTrue(MR.strings.local_source.localized() in text(scene))
                click(scene, MR.strings.desktop_ui_authors.localized())
                render(scene)
                assertEquals("Ada", field(scene).config[SemanticsProperties.EditableText].text)
                assertTrue(creatorRows.subscriptionCount.value > 0, "actual Authors model must observe its port")
                navigator.replaceAll(object : Screen {
                    override val key = "closed-browse-fixture"

                    @androidx.compose.runtime.Composable
                    override fun Content() {
                        androidx.compose.material3.Text("Closed Browse")
                    }
                })
                render(scene)
                kotlinx.coroutines.withTimeout(2_000) {
                    while (creatorRows.subscriptionCount.value > 0) {
                        kotlinx.coroutines.delay(10)
                        render(scene)
                    }
                }
            }
        } finally {
            mihon.desktop.test.navigation.TestNavigationController.reset()
        }
    }

    @Test
    fun `production navigation host adapts five roots and hides them on More child screens`() = runBlocking {
        withSearchScene(MoreRootScreen(), width = 1400, height = 900) { scene ->
            lateinit var navigator: Navigator
            scene.setContent {
                dependencies {
                    mihon.desktop.ui.home.HomeNavigationHost(
                        current = mihon.desktop.ui.more.MoreTab,
                        onSelect = {},
                        showNavigation = true,
                        badgeCount = 0,
                    ) {
                        Navigator(MoreRootScreen()) { nav ->
                            navigator = nav
                            mihon.desktop.ui.home.ObserveHomeNavigationStack(nav)
                            CurrentScreen()
                        }
                    }
                }
            }
            render(scene)
            fun tags() = nodes(scene, true).mapNotNull {
                if (it.config.contains(SemanticsProperties.TestTag)) it.config[SemanticsProperties.TestTag] else null
            }
            assertTrue("desktop-root-rail" in tags())
            assertEquals(
                5,
                tags().count {
                    it.startsWith("desktop-root-") &&
                        it !in listOf("desktop-root-rail", "desktop-root-bar")
                },
            )
            click(scene, MR.strings.label_settings.localized())
            render(scene)
            assertFalse("desktop-root-rail" in tags())
            clickDescription(scene, MR.strings.action_bar_up_description.localized())
            render(scene)
            assertEquals(1, navigator.size)
            assertTrue("desktop-root-rail" in tags())
            scene.resize(320, 900)
            render(scene)
            assertTrue("desktop-root-bar" in tags())
            assertFalse("desktop-root-rail" in tags())
            val rootButtons = nodes(scene, true).filter {
                it.config.contains(SemanticsProperties.TestTag) &&
                    it.config[SemanticsProperties.TestTag].startsWith("desktop-root-") &&
                    it.config.contains(SemanticsActions.OnClick)
            }.sortedBy { it.boundsInRoot.left }
            assertEquals(
                80f,
                requireNotNull(
                    nodes(scene, true).firstOrNull {
                        it.config.contains(SemanticsProperties.TestTag) &&
                            it.config[SemanticsProperties.TestTag] == "desktop-root-bar"
                    },
                ).boundsInRoot.height,
            )
            assertEquals(
                0f,
                rootButtons[1].boundsInRoot.left - rootButtons[0].boundsInRoot.right,
                "upstream bar has no horizontal item spacer",
            )
        }
    }

    @Test
    fun `wide settings directory opens appearance alongside the directory with one back action`() = runBlocking {
        withSearchScene(SettingsRootScreen(), width = 1400, height = 900) { scene ->
            render(scene)
            assertTrue(
                nodes(scene, true).any {
                    it.config.contains(SemanticsProperties.TestTag) &&
                        it.config[SemanticsProperties.TestTag] == "appearance-theme-cards"
                },
                "default Appearance must be mounted",
            )
            val backs = nodes(scene, true).filter {
                it.config.contains(SemanticsActions.OnClick) &&
                    flatten(it).any { child ->
                        child.config.contains(SemanticsProperties.ContentDescription) &&
                            MR.strings.action_bar_up_description.localized() in
                            child.config[SemanticsProperties.ContentDescription]
                    }
            }
            assertEquals(1, backs.size, "only the directory owns the wide settings back action")
            click(scene, MR.strings.pref_category_reader.localized())
            render(scene)
            assertTrue(MR.strings.pref_category_library.localized() in text(scene), "directory stays mounted")
            assertTrue(MR.strings.pref_viewer_type.localized() in text(scene), "real Reader settings must be mounted")
        }
    }

    @Test
    fun `More groups expose downloaded only data storage and help`() = runBlocking {
        withSearchScene(MoreRootScreen(), width = 900, height = 1400) { scene ->
            render(scene)
            listOf(MR.strings.label_downloaded_only, MR.strings.label_data_storage, MR.strings.label_help).forEach {
                assertTrue(it.localized() in text(scene), "missing production More entry: ${it.localized()}")
            }
            assertFalse(MR.strings.label_donate.localized() in text(scene))
            click(scene, MR.strings.label_downloaded_only.localized())
            render(scene)
            assertTrue(currentLibraryPreferences.downloadedOnly().get())
        }
    }

    @Test
    fun `catalog preserves fixed-main prefix routes and shared top ten`() {
        val previous = Locale.getDefault()
        assertThrows(IllegalStateException::class.java) {
            withRestoredLocale {
                Locale.setDefault(Locale.JAPAN)
                error("expected")
            }
        }
        assertEquals(previous, Locale.getDefault())
        withRestoredLocale {
            Locale.setDefault(Locale.US)
            val screens = DesktopSettingsCatalog.screens()
            val fixedMain = "AppearanceSettingsScreen,LibrarySettingsScreen,ReaderSettingsScreen," +
                "DownloadSettingsScreen,TrackingSettingsScreen,BackupSettingsScreen," +
                "SecuritySettingsScreen,AdvancedSettingsScreen"
            assertEquals(fixedMain, screens.take(8).joinToString(",") { it.route::class.simpleName.orEmpty() })
            assertEquals(
                listOf("GeneralSettingsScreen", "ExtensionRepoScreen", "AboutScreen"),
                screens.drop(8).map {
                    it.route::class.simpleName
                },
            )
            assertTrue(screens.none { it.route is Tab })
            val originalRoutes = screens.take(8).map { it.route::class }.toSet()
            val results = DesktopSettingsCatalog.search("e")
            assertTrue(results.all { it.route::class in originalRoutes })
            assertTrue(
                DesktopSettingsCatalog.search(MR.strings.desktop_reader_prefetch_next_chapter.localized())
                    .any { it.route is ReaderSettingsScreen },
            )
        }
        assertEquals(previous, Locale.getDefault())
    }

    @Test
    fun `catalog exposes image prefetch by its user facing title`() = withRestoredLocale {
        Locale.setDefault(Locale.forLanguageTag("zh-CN"))

        val readerScreen = DesktopSettingsCatalog.screens().single { it.route is ReaderSettingsScreen }
        val readerEntries = readerScreen.preferences.map { it.title }

        assertTrue("图片预取" in readerEntries)
        assertFalse("预取下一章" in readerEntries)
        assertTrue(
            DesktopSettingsCatalog.search("图片预取")
                .any { it.route is ReaderSettingsScreen && it.anchorTitle == "图片预取" },
        )
    }

    @Test
    fun `catalog delegates search to shared policy`() {
        mockkObject(SettingsSearchPolicy)
        try {
            val expected = emptyList<mihon.domain.settings.SettingsSearchResult<Screen>>()
            every {
                SettingsSearchPolicy.search(
                    any<List<SearchableSettingsScreen<Screen>>>(),
                    "needle",
                    SettingsLayoutDirection.Ltr,
                )
            } returns expected
            assertSame(expected, DesktopSettingsCatalog.search("needle", SettingsLayoutDirection.Ltr))
            verify(exactly = 1) {
                SettingsSearchPolicy.search(
                    any<List<SearchableSettingsScreen<Screen>>>(),
                    "needle",
                    SettingsLayoutDirection.Ltr,
                )
            }
        } finally {
            unmockkObject(SettingsSearchPolicy)
        }
    }

    @Test
    fun `search has feedback focus submission keys and result navigation`() = runBlocking {
        withRestoredLocale {
            listOf(Locale.US, Locale.forLanguageTag("zh-CN")).forEach { locale ->
                Locale.setDefault(locale)
                withSearchScene { scene ->
                    render(scene)
                    assertTrue(text(scene).contains(MR.strings.desktop_settings_search_empty.localized(locale)))
                    assertTrue(field(scene).config[SemanticsProperties.Focused])
                    setText(scene, "no-such-setting-42")
                    render(scene)
                    assertTrue(text(scene).contains(MR.strings.no_results_found.localized(locale)))
                    requireNotNull(field(scene).config[SemanticsActions.OnImeAction].action).invoke()
                    render(scene)
                    assertFalse(field(scene).config[SemanticsProperties.Focused])
                }
            }
            Locale.setDefault(Locale.US)
            listOf(Key.Enter, Key.NumPadEnter).forEach { key ->
                withSearchScene { scene ->
                    render(scene)
                    scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyUp))
                    render(scene)
                    assertTrue(field(scene).config[SemanticsProperties.Focused])
                    scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyDown))
                    render(scene)
                    assertFalse(field(scene).config[SemanticsProperties.Focused])
                    scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyUp))
                }
            }
            withSearchScene { scene ->
                render(scene)
                scene.sendKeyEvent(composeKeyEvent(Key.Spacebar, KeyEventType.KeyDown))
                render(scene)
                assertTrue(field(scene).config[SemanticsProperties.Focused])
                assertEquals(AnnotatedString(""), field(scene).config[SemanticsProperties.EditableText])
            }
            withSearchScene(height = 260) { scene ->
                lateinit var navigator: Navigator
                scene.setContent {
                    dependencies {
                        Navigator(SettingsSearchScreen()) { nav ->
                            navigator = nav
                            CurrentScreen()
                        }
                    }
                }
                render(scene)
                val anchorTitle = MR.strings.desktop_appearance_library_grid.localized(Locale.US)
                setText(scene, anchorTitle)
                render(scene)
                val result = action(scene, anchorTitle)
                assertEquals(Role.Button, result.config[SemanticsProperties.Role])
                assertEquals(1, flatten(result).count { it.config.contains(SemanticsActions.OnClick) })
                click(scene, anchorTitle)
                render(scene)
                assertTrue(navigator.lastItem is LibrarySettingsScreen)
                assertEquals(1, navigator.items.size)
                val highlighted = nodes(scene, true).single {
                    it.config.contains(DesktopSettingsAnchorHighlighted) &&
                        it.config[DesktopSettingsAnchorHighlighted]
                }
                assertTrue(anchorTitle in text(highlighted))
                val scroll = nodes(scene, true).first {
                    it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                }
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                assertTrue(scroll.value() > 0f, "scroll=${scroll.value()} highlighted=${highlighted.boundsInRoot}")
                requireNotNull(
                    nodes(scene, true).filter { it.config.contains(SemanticsActions.SetProgress) }.first()
                        .config[SemanticsActions.SetProgress].action,
                ).invoke(6f)
                assertEquals(6, currentLibraryPreferences.portraitColumns().get())
                val landscape = nodes(scene, true).filter { it.config.contains(SemanticsActions.SetProgress) }.last()
                requireNotNull(landscape.config[SemanticsActions.SetProgress].action).invoke(0f)
                assertEquals(0, currentLibraryPreferences.landscapeColumns().get())
                assertFalse(currentPreferences.libraryGridColumns.isSet())
            }
            withSearchScene { scene ->
                lateinit var navigator: Navigator
                scene.setContent {
                    dependencies {
                        Navigator(SettingsSearchScreen()) { nav ->
                            navigator = nav
                            CurrentScreen()
                        }
                    }
                }
                render(scene)
                val anchorTitle = MR.strings.pref_incognito_mode.localized(Locale.US)
                setText(scene, anchorTitle)
                render(scene)
                click(scene, anchorTitle)
                render(scene)
                assertTrue(navigator.lastItem is GeneralSettingsScreen)
                assertTrue(
                    nodes(scene, true).any {
                        it.config.contains(DesktopSettingsAnchorHighlighted) &&
                            it.config[DesktopSettingsAnchorHighlighted] &&
                            flatten(it).any { child -> anchorTitle in text(child) }
                    },
                )
                currentPreferences.incognitoMode.set(false)
                click(scene, anchorTitle)
                assertTrue(currentPreferences.incognitoMode.get())
            }
        }
    }

    @Test
    fun `settings roles activate once on key down and never on key up`() = runBlocking {
        listOf("button", "radio", "switch").forEach { fixture ->
            listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar).forEach { key ->
                val scene = SearchScene(kotlinx.coroutines.currentCoroutineContext(), 300)
                var calls = 0
                try {
                    scene.setContent {
                        MaterialTheme {
                            Column {
                                when (fixture) {
                                    "button" -> SettingsEntry(
                                        Icons.Default.Settings,
                                        "Button",
                                        "Summary",
                                    ) { calls++ }
                                    "radio" -> RadioSettingsItem("Radio", false, { calls++ })
                                    else -> SwitchSettingsItem("Switch", "Summary", false, { calls++ })
                                }
                            }
                        }
                    }
                    render(scene)
                    scene.takeFocus()
                    val target = nodes(scene, true).single { it.config.contains(SemanticsActions.OnClick) }
                    assertTrue(requireNotNull(target.config[SemanticsActions.RequestFocus].action).invoke())
                    render(scene)
                    scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyUp))
                    render(scene)
                    assertEquals(0, calls, "$fixture $key KeyUp")
                    scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyDown))
                    render(scene)
                    assertEquals(1, calls, "$fixture $key KeyDown")
                    scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyUp))
                    render(scene)
                    assertEquals(1, calls, "$fixture $key second KeyUp")
                } finally {
                    scene.close()
                }
            }
        }
    }

    @Test
    fun `settings action focus order follows the rendered rows`() = runBlocking {
        val scene = SearchScene(kotlinx.coroutines.currentCoroutineContext(), 400)
        try {
            scene.setContent {
                MaterialTheme {
                    Column {
                        SettingsEntry(Icons.Default.Settings, "Button", "Summary") {}
                        RadioSettingsItem("Radio", false, {})
                        SwitchSettingsItem("Switch", "Summary", false, {})
                    }
                }
            }
            render(scene)
            fun actions() = nodes(scene, true).filter { it.config.contains(SemanticsActions.OnClick) }
            assertTrue(requireNotNull(actions()[0].config[SemanticsActions.RequestFocus].action).invoke())
            render(scene)
            assertTrue(actions()[0].config[SemanticsProperties.Focused])
            scene.sendKeyEvent(composeKeyEvent(Key.Tab, KeyEventType.KeyDown))
            render(scene)
            assertTrue(actions()[1].config[SemanticsProperties.Focused])
            scene.sendKeyEvent(composeKeyEvent(Key.Tab, KeyEventType.KeyDown))
            render(scene)
            assertTrue(actions()[2].config[SemanticsProperties.Focused])
        } finally {
            scene.close()
        }
    }

    @Test
    fun `reader global default selection writes the production preference`() = runBlocking {
        withRestoredLocale {
            Locale.setDefault(Locale.US)
            withSearchScene(ReaderSettingsScreen()) { scene ->
                currentReaderPreferences.readingMode = mihon.desktop.reader.ReadingMode.LTR
                render(scene)
                click(scene, MR.strings.automatic_background.localized(Locale.US))
                assertEquals(mihon.desktop.reader.ReadingMode.AUTO, currentReaderPreferences.readingMode)
            }
        }
    }

    @Test
    fun `reader search anchor scrolls highlights once and preserves mode writes`() = runBlocking {
        withRestoredLocale {
            Locale.setDefault(Locale.US)
            val anchorTitle = MR.strings.right_to_left_viewer.localized(Locale.US)
            withSearchScene(height = 180) { scene ->
                lateinit var navigator: Navigator
                scene.setContent {
                    dependencies {
                        Navigator(SettingsSearchScreen()) { nav ->
                            navigator = nav
                            CurrentScreen()
                        }
                    }
                }
                render(scene)
                currentReaderPreferences.readingMode = mihon.desktop.reader.ReadingMode.WEBTOON
                setText(scene, anchorTitle)
                render(scene)
                click(scene, anchorTitle)
                render(scene)
                assertTrue(navigator.lastItem is ReaderSettingsScreen)
                val highlighted = nodes(scene, true).single {
                    it.config.contains(DesktopSettingsAnchorHighlighted) &&
                        it.config[DesktopSettingsAnchorHighlighted]
                }
                assertTrue(flatten(highlighted).any { anchorTitle in text(it) })
                assertTrue(highlighted.boundsInRoot.height > 0f)
                val scroll = nodes(scene, true).first {
                    it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                }
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                assertTrue(scroll.value() > 0f)
                click(scene, anchorTitle)
                assertEquals(mihon.desktop.reader.ReadingMode.RTL, currentReaderPreferences.readingMode)
            }
            withSearchScene(ReaderSettingsScreen(), height = 180) { scene ->
                render(scene)
                assertFalse(nodes(scene, true).any { it.config.contains(DesktopSettingsAnchorHighlighted) })
                val scroll = nodes(scene, true).first {
                    it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                }
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                assertEquals(0f, scroll.value())
            }
        }
    }

    @Test
    fun `library search anchor scrolls highlights once and preserves update writes`() = runBlocking {
        withRestoredLocale {
            Locale.setDefault(Locale.US)
            val anchorTitle = MR.strings.pref_category_display.localized(Locale.US)
            withSearchScene(height = 260) { scene ->
                lateinit var navigator: Navigator
                scene.setContent {
                    dependencies {
                        Navigator(SettingsSearchScreen()) { nav ->
                            navigator = nav
                            CurrentScreen()
                        }
                    }
                }
                render(scene)
                currentPreferences.libraryUpdateInterval.set(mihon.desktop.settings.LibraryUpdateInterval.OFF)
                setText(scene, anchorTitle)
                render(scene)
                val results = DesktopSettingsCatalog.search(anchorTitle)
                val libraryIndex = results.indexOfFirst {
                    it.route is LibrarySettingsScreen && it.title == anchorTitle
                }
                assertTrue(libraryIndex >= 0, "the production catalog must retain the Library Display anchor")
                val resultList = nodes(scene).single { it.config.contains(SemanticsActions.ScrollToIndex) }
                requireNotNull(resultList.config[SemanticsActions.ScrollToIndex].action).invoke(libraryIndex)
                render(scene)
                val expectedResult = results[libraryIndex]
                val libraryResult = nodes(scene).single {
                    it.config.contains(SemanticsActions.OnClick) &&
                        expectedResult.title in text(it) && expectedResult.breadcrumb in text(it)
                }
                requireNotNull(libraryResult.config[SemanticsActions.OnClick].action).invoke()
                render(scene)
                assertTrue(navigator.lastItem is LibrarySettingsScreen)
                val highlighted = nodes(scene, true).single {
                    it.config.contains(DesktopSettingsAnchorHighlighted) &&
                        it.config[DesktopSettingsAnchorHighlighted]
                }
                assertTrue(anchorTitle in text(highlighted))
                assertTrue(highlighted.boundsInRoot.height > 0f)
                val scroll = nodes(scene, true).first {
                    it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                }
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                assertTrue(scroll.value() > 0f)
                click(scene, MR.strings.update_6hour.localized(Locale.US))
                assertEquals(
                    mihon.desktop.settings.LibraryUpdateInterval.EVERY_6H,
                    currentPreferences.libraryUpdateInterval.get(),
                )
            }
            withSearchScene(LibrarySettingsScreen(), height = 260) { scene ->
                render(scene)
                assertFalse(nodes(scene, true).any { it.config.contains(DesktopSettingsAnchorHighlighted) })
                val scroll = nodes(scene, true).first {
                    it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                }
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                assertEquals(0f, scroll.value())
            }
        }
    }

    @Test
    fun `More settings entry opens the settings directory`() = runBlocking {
        withSearchScene(MoreRootScreen(), height = 1400) { scene ->
            lateinit var navigator: Navigator
            scene.setContent {
                dependencies {
                    Navigator(MoreRootScreen()) { nav ->
                        navigator = nav
                        CurrentScreen()
                    }
                }
            }
            render(scene)
            click(scene, MR.strings.label_settings.localized(Locale.getDefault()))
            assertEquals("SettingsRootScreen", navigator.lastItem::class.simpleName)
            render(scene)
            listOf(
                MR.strings.pref_category_general,
                MR.strings.pref_category_appearance,
                MR.strings.pref_category_library,
                MR.strings.pref_category_reader,
                MR.strings.pref_category_downloads,
                MR.strings.pref_category_tracking,
                MR.strings.browse,
                MR.strings.label_data_storage,
                MR.strings.pref_category_security,
                MR.strings.pref_category_advanced,
                MR.strings.pref_category_about,
            ).forEach { resource ->
                assertTrue(resource.localized(Locale.getDefault()) in text(scene))
            }
            clickDescription(scene, MR.strings.action_search_settings.localized(Locale.getDefault()))
            render(scene)
            assertTrue(nodes(scene, true).any { it.config.contains(SemanticsActions.SetText) })
        }
    }

    @Test
    fun `search top bar stays within compact width and exposes back navigation`() = runBlocking {
        withSearchScene(MoreRootScreen(), width = 320) { scene ->
            lateinit var navigator: Navigator
            scene.setContent {
                dependencies {
                    Navigator(MoreRootScreen()) { nav ->
                        navigator = nav
                        CurrentScreen()
                    }
                }
            }
            render(scene)
            navigator.push(SettingsSearchScreen())
            render(scene)
            setText(scene, "appearance")
            render(scene)

            val rootBounds = nodes(scene).first().boundsInRoot
            val fieldBounds = field(scene).boundsInRoot
            assertTrue(fieldBounds.left >= rootBounds.left, "field=$fieldBounds root=$rootBounds")
            assertTrue(fieldBounds.right <= rootBounds.right, "field=$fieldBounds root=$rootBounds")
            assertTrue(
                nodes(scene, true).any {
                    it.config.contains(SemanticsProperties.ContentDescription) &&
                        MR.strings.action_bar_up_description.localized(Locale.getDefault()) in
                        it.config[SemanticsProperties.ContentDescription]
                },
            )
        }
    }

    @Test
    fun `desktop theme consumes shared static theme and amoled preferences`() = runBlocking {
        assertSame(
            AppThemeColorScheme.colorScheme(AppTheme.YINYANG, isDark = false, isAmoled = false),
            desktopColorScheme(AppTheme.YINYANG, ThemeMode.SYSTEM, systemIsDark = false, isAmoled = false),
        )
        assertSame(
            AppThemeColorScheme.colorScheme(AppTheme.YINYANG, isDark = true, isAmoled = false),
            desktopColorScheme(AppTheme.YINYANG, ThemeMode.SYSTEM, systemIsDark = true, isAmoled = false),
        )

        withSearchScene { scene ->
            lateinit var observed: ColorScheme
            scene.setContent {
                dependencies {
                    DesktopTheme { observed = MaterialTheme.colorScheme }
                }
            }
            render(scene)
            currentPreferences.themeMode.set(ThemeMode.DARK)
            currentPreferences.appTheme.set(AppTheme.YINYANG)
            currentPreferences.themeDarkAmoled.set(false)
            render(scene)
            assertEquals(
                AppThemeColorScheme.colorScheme(AppTheme.YINYANG, isDark = true, isAmoled = false),
                observed,
            )

            currentPreferences.themeDarkAmoled.set(true)
            render(scene)
            assertEquals(
                AppThemeColorScheme.colorScheme(AppTheme.YINYANG, isDark = true, isAmoled = true).toString(),
                observed.toString(),
            )

            currentPreferences.themeMode.set(ThemeMode.LIGHT)
            render(scene)
            assertSame(
                AppThemeColorScheme.colorScheme(AppTheme.YINYANG, isDark = false, isAmoled = true),
                observed,
            )
        }
    }

    @Test
    fun `appearance selects static theme and amoled while preserving grid`() = runBlocking {
        withRestoredLocale {
            Locale.setDefault(Locale.US)
            withSearchScene(AppearanceSettingsScreen(), height = 2_000) { scene ->
                render(scene)
                val themes = selectableAppThemes(dynamicColorAvailable = false)
                themes.forEachIndexed { index, theme ->
                    val strip = nodes(scene, true).single {
                        it.config.contains(SemanticsProperties.TestTag) &&
                            it.config[SemanticsProperties.TestTag] == "appearance-theme-cards"
                    }
                    requireNotNull(strip.config[SemanticsActions.ScrollToIndex].action).invoke(index)
                    render(scene)
                    assertTrue(requireNotNull(theme.titleRes).localized(Locale.US) in text(scene))
                }
                val rendered = text(scene)
                assertFalse(MR.strings.theme_monet.localized(Locale.US) in rendered)
                listOf(AppTheme.DARK_BLUE, AppTheme.HOT_PINK, AppTheme.BLUE).forEach { deprecated ->
                    assertFalse(deprecated.name in rendered)
                }

                val strip = nodes(scene, true).single {
                    it.config.contains(SemanticsProperties.TestTag) &&
                        it.config[SemanticsProperties.TestTag] == "appearance-theme-cards"
                }
                requireNotNull(
                    strip.config[SemanticsActions.ScrollToIndex].action,
                ).invoke(themes.indexOf(AppTheme.YINYANG))
                render(scene)
                val card = nodes(scene, true).single {
                    it.config.contains(SemanticsProperties.TestTag) &&
                        it.config[SemanticsProperties.TestTag] == "theme-card-YINYANG"
                }
                requireNotNull(
                    flatten(card).single {
                        it.config.contains(SemanticsActions.OnClick)
                    }.config[SemanticsActions.OnClick].action,
                ).invoke()
                assertEquals(AppTheme.YINYANG, currentPreferences.appTheme.get())
                click(scene, MR.strings.pref_dark_theme_pure_black.localized(Locale.US))
                assertTrue(currentPreferences.themeDarkAmoled.get())
                assertFalse(nodes(scene, true).any { it.config.contains(SemanticsActions.SetProgress) })
                assertFalse(
                    currentPreferences.libraryGridColumns.isSet(),
                    "appearance must leave the legacy grid authority unchanged",
                )
            }
        }
    }

    @Test
    fun `appearance theme search entries scroll to production anchors`() = runBlocking {
        withRestoredLocale {
            Locale.setDefault(Locale.US)
            listOf(MR.strings.pref_app_theme, MR.strings.pref_dark_theme_pure_black).forEach { resource ->
                withSearchScene(height = 260) { scene ->
                    lateinit var navigator: Navigator
                    scene.setContent {
                        dependencies {
                            Navigator(SettingsSearchScreen()) { nav ->
                                navigator = nav
                                CurrentScreen()
                            }
                        }
                    }
                    render(scene)
                    val title = resource.localized(Locale.US)
                    setText(scene, title)
                    render(scene)
                    click(scene, title)
                    render(scene)
                    assertTrue(navigator.lastItem is AppearanceSettingsScreen)
                    val highlighted = nodes(scene, true).single {
                        it.config.contains(DesktopSettingsAnchorHighlighted) &&
                            it.config[DesktopSettingsAnchorHighlighted]
                    }
                    if (resource == MR.strings.pref_app_theme) {
                        assertTrue(
                            flatten(highlighted).any {
                                it.config.contains(SemanticsProperties.TestTag) &&
                                    it.config[SemanticsProperties.TestTag] == "appearance-theme-cards"
                            },
                        )
                    } else {
                        assertTrue(flatten(highlighted).any { title in text(it) })
                    }
                    val scroll = nodes(scene, true).first {
                        it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                    }
                        .config[SemanticsProperties.VerticalScrollAxisRange]
                    assertTrue(scroll.value() > 0f)
                }
            }
        }
    }
    private suspend fun withSearchScene(
        screen: Screen = SettingsSearchScreen(),
        width: Int = 900,
        height: Int = 900,
        block: suspend (SearchScene) -> Unit,
    ) {
        val scene = SearchScene(kotlinx.coroutines.currentCoroutineContext(), height, width)
        if (screen is SettingsSearchScreen) {
            val showScreen = mutableStateOf(false)
            scene.setContent {
                dependencies {
                    if (showScreen.value) Navigator(screen) { CurrentScreen() } else BasicTextField("", {})
                }
            }
            render(scene)
            scene.takeFocus()
            showScreen.value = true
        } else {
            scene.setContent { dependencies { Navigator(screen) { CurrentScreen() } } }
        }
        try {
            block(scene)
        } finally {
            scene.close()
        }
    }

    @androidx.compose.runtime.Composable
    private fun dependencies(content: @androidx.compose.runtime.Composable () -> Unit) {
        val downloads = mockk<DesktopDownloadManager> { every { queue } returns MutableStateFlow(emptyList()) }
        currentPreferences = androidx.compose.runtime.remember {
            mihon.desktop.settings.DesktopAppPreferences(
                tachiyomi.core.common.preference.DesktopPreferenceStore(preferenceNode.node("app")),
            )
        }
        currentReaderPreferences = androidx.compose.runtime.remember {
            mihon.desktop.reader.ReaderPreferences(InMemoryPreferenceStore())
        }
        val localeAdapter = androidx.compose.runtime.remember(currentPreferences) {
            DesktopLocaleAdapter(currentPreferences.appLanguage)
        }
        val network = androidx.compose.runtime.remember(currentPreferences) {
            mockk<DesktopNetworkHelper> {
                every { routeObservations } returns MutableStateFlow(emptyList())
                every { activeGlobalMode } returns currentPreferences.globalNetworkMode.get()
                every { activeGlobalProxy } returns currentPreferences.proxyRuntimeConfig()
            }
        }
        val sources = object : tachiyomi.domain.source.service.SourceManager {
            override val isInitialized = MutableStateFlow(true)
            override val catalogueSources = kotlinx.coroutines.flow.flowOf(
                emptyList<eu.kanade.tachiyomi.source.CatalogueSource>(),
            )
            override fun get(sourceKey: Long) = null
            override fun getOrStub(sourceKey: Long) = error("no test sources")
            override fun getOnlineSources() = emptyList<eu.kanade.tachiyomi.source.online.HttpSource>()
            override fun getCatalogueSources() = emptyList<eu.kanade.tachiyomi.source.CatalogueSource>()
            override fun getStubSources() = emptyList<tachiyomi.domain.source.model.StubSource>()
        }
        val creators = mockk<tachiyomi.domain.creator.interactor.GetCreators> {
            every { subscribe() } returns creatorRows
            every { subscribeFollowed() } returns kotlinx.coroutines.flow.flowOf(emptyList())
        }
        val archive = mockk<tachiyomi.domain.creator.interactor.CreatorArchive> {
            every { observeUnread(any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())
            every { observeUnreadWorks(any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        }
        val ada = tachiyomi.domain.creator.model.Creator(42L, "Ada Lovelace", "ada lovelace", null, emptyList(), 0L, 0L)
        io.mockk.coEvery {
            archive.getCreatorCardProjectionPage(any(), any(), any(), any(), any(), any(), any())
        } returns
            tachiyomi.domain.creator.model.CreatorCardProjectionPage(
                0,
                50,
                false,
                listOf(tachiyomi.domain.creator.model.CreatorCardProjection(ada, true, 0)),
            )
        val indexer = mockk<tachiyomi.domain.creator.service.CreatorLibraryIndexer> {
            every { state } returns MutableStateFlow(tachiyomi.domain.creator.service.CreatorLibraryIndexState.Idle)
        }
        val notifications = mihon.desktop.domain.DesktopNotificationService()
        val challenges = mockk<mihon.desktop.network.DesktopChallengeUiPort> {
            every { challenges } returns kotlinx.coroutines.flow.MutableSharedFlow()
        }
        val externalActions = mihon.desktop.ui.ExternalActionNavigator(resolveTarget = {
            error("no external test input")
        })
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns currentPreferences
            every { readerPreferences } returns currentReaderPreferences
            every { this@mockk.localeAdapter } returns localeAdapter
            every { downloadManager } returns downloads
            every { downloadQueuePort } returns downloads
            every { networkHelper } returns network
            every { networkRoutingPort } returns network
            every { creatorDiscoveryScheduler } returns null
            every { libraryPreferences } returns currentLibraryPreferences
            every { sourceManager } returns sources
            every { getCreators } returns creators
            every { creatorArchive } returns archive
            every { creatorLibraryIndexer } returns indexer
            every { creatorDiscoveryPreferences } returns null
            every { notificationService } returns notifications
            every { challengeUiPort } returns challenges
            every { externalActionNavigator } returns externalActions
            every { syncPanel } returns null
        }
        CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies, content = content)
    }
    private suspend fun render(scene: SearchScene) = repeat(5) {
        scene.render()
        kotlinx.coroutines.yield()
    }
    private fun composeKeyEvent(key: Key, type: KeyEventType): ComposeKeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp")
            .invoke(null)
        val factory = events.declaredMethods.single {
            it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default")
        }
        val native = factory.invoke(null, key.keyCode, eventType, 0, false, false, false, false, null)
        return ComposeKeyEvent(native)
    }
    private fun action(scene: SearchScene, label: String) =
        nodes(scene).single { it.config.contains(SemanticsActions.OnClick) && label in flatten(it).flatMap(::text) }
    private fun field(scene: SearchScene) = nodes(scene, true).single { it.config.contains(SemanticsActions.SetText) }
    private fun setText(
        scene: SearchScene,
        value: String,
    ) = requireNotNull(field(scene).config[SemanticsActions.SetText].action).invoke(AnnotatedString(value))
    private fun click(scene: SearchScene, label: String) = requireNotNull(
        nodes(scene).first {
            it.config.contains(SemanticsActions.OnClick) &&
                flatten(it).any { node -> label in text(node) }
        }
            .config[SemanticsActions.OnClick].action,
    ).invoke()
    private fun clickDescription(scene: SearchScene, description: String) = requireNotNull(
        nodes(scene, true).first {
            it.config.contains(SemanticsActions.OnClick) &&
                flatten(it).any { child ->
                    child.config.contains(SemanticsProperties.ContentDescription) &&
                        description in child.config[SemanticsProperties.ContentDescription]
                }
        }.config[SemanticsActions.OnClick].action,
    ).invoke()
    private fun text(scene: SearchScene) = nodes(scene).flatMap(::text).joinToString()
    private fun text(
        node: SemanticsNode,
    ) = if (node.config.contains(SemanticsProperties.Text)) {
        node.config[SemanticsProperties.Text].map {
            it.text
        }
    } else {
        emptyList()
    }
    private fun nodes(
        scene: SearchScene,
        unmerged: Boolean = false,
    ) = scene.semanticsOwners.flatMap { flatten(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private inline fun <T> withRestoredLocale(block: () -> T): T {
        val previous = Locale.getDefault()
        return try {
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }
    private lateinit var currentPreferences: mihon.desktop.settings.DesktopAppPreferences
    private val currentLibraryPreferences =
        LibraryPreferences(tachiyomi.core.common.preference.DesktopPreferenceStore(preferenceNode.node("library")))
    private lateinit var currentReaderPreferences: mihon.desktop.reader.ReaderPreferences

    private class SearchScene(context: CoroutineContext, height: Int, width: Int = 900) : AutoCloseable {
        val semanticsOwners = linkedSetOf<SemanticsOwner>()
        private val canvas = Canvas(ImageBitmap(width, height))
        private val scene: ComposeScene = CanvasLayersComposeScene(
            size = IntSize(width, height),
            coroutineContext = context,
            platformContext = object : PlatformContext {
                override val windowInfo = object : WindowInfo {
                    override val isWindowFocused = true
                }
                override val inputModeManager = object : InputModeManager {
                    override val inputMode = InputMode.Keyboard
                    override fun requestInputMode(inputMode: InputMode) = true
                }
                override fun requestFocus() = true
                override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
                        semanticsOwners +=
                            semanticsOwner
                    }
                    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
                        semanticsOwners -=
                            semanticsOwner
                    }
                    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) = Unit
                    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) = Unit
                }
            },
            invalidate = {},
        )
        fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) = scene.setContent(content)
        fun render() = scene.render(canvas, System.nanoTime())
        fun resize(width: Int, height: Int) {
            scene.size = IntSize(width, height)
        }
        fun sendKeyEvent(event: ComposeKeyEvent) = scene.sendKeyEvent(event)
        fun takeFocus() = scene.focusManager.takeFocus(FocusDirection.Enter)
        override fun close() = scene.close()
    }
}
