package mihon.desktop.ui.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import io.mockk.mockk
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.core.screen.Screen
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.BuildInfo
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.fakes.FakeCategoryRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeHistoryRepository
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.source.FakeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.SetChapterReadStatus
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.interactor.LibraryFilter
import tachiyomi.domain.library.model.LibraryDisplayMode as SharedLibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackerSessionProvider
import tachiyomi.i18n.MR
import java.nio.file.Path
import mihon.desktop.domain.DesktopNotification
import mihon.desktop.domain.DesktopNotificationService

class LibraryPageCompositionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `accepted root success survives leaving without replaying stale feedback`() = runTest {
        val manga = sampleManga(90L, "Lifecycle manga", 7L)
        val mangaRepository = FakeMangaRepository().apply {
            libraryManga = listOf(sampleLibraryManga(manga).copy(totalChapters = 1L))
        }
        val backing = FakeChapterRepository().apply {
            seed(Chapter.create().copy(id = 901L, mangaId = manga.id, read = false))
        }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val chapters = object : ChapterRepository by backing {
            override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
                entered.complete(Unit)
                release.await()
                backing.updateAll(chapterUpdates)
                finished.complete(Unit)
            }
        }
        val getChapters = GetChaptersByMangaId(chapters)
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(mangaRepository),
            getCategories = GetCategories(FakeCategoryRepository()),
            getChaptersByMangaId = getChapters,
            setChapterReadStatus = SetChapterReadStatus(getChapters, UpdateChapter(chapters)),
        )
        model.setOperationFeedback("stale feedback")
        val notifications = DesktopNotificationService()
        val received = mutableListOf<DesktopNotification>()
        val notificationJob = backgroundScope.launch { notifications.notifications.collect(received::add) }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            io.mockk.every { notificationService } returns notifications
        }
        val scene = ImageComposeScene(1_200, 900, coroutineContext = coroutineContext) {}
        scene.setContent {
            CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                ProvideLibraryScreenModelFactory(factory = { model }) {
                    Navigator(LibraryRootScreen()) { CurrentScreen() }
                }
            }
        }
        try {
            render(scene)
            val item = nodes(scene).first {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) &&
                    semanticLabels(it).contains(manga.title)
            }
            assertTrue(requireNotNull(item.config[androidx.compose.ui.semantics.SemanticsActions.OnLongClick].action).invoke())
            render(scene)
            click(scene, MR.strings.desktop_ui_mark_read.localized())
            entered.await()

            scene.close()
            release.complete(Unit)
            assertTrue(withTimeoutOrNull(1_000) { finished.await(); true } == true)
            repeat(3) { yield() }

            assertTrue(backing.getChapterById(901L)?.read == true)
            assertTrue(received.isEmpty())
        } finally {
            release.complete(Unit)
            notificationJob.cancel()
            runCatching { scene.close() }
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `root starts with search closed and does not expose global search for an empty query`() = runTest {
        val model = rootModel(listOf(sampleLibraryManga(sampleManga(1L, "Visible", 1L))))
        val scene = rootScene(model)
        try {
            render(scene)

            assertTrue(nodes(scene).none { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText) })
            assertTrue(!semanticLabels(scene).contains(MR.strings.action_global_search.localized()))
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `local manga context menu hides the unsupported single download action`() = runTest {
        val manga = sampleManga(91L, "Local context", 0L)
        val model = rootModel(listOf(sampleLibraryManga(manga)))
        val scene = rootScene(model)
        try {
            render(scene)
            val item = nodes(scene).first {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) &&
                    semanticLabels(it).contains(manga.title)
            }
            secondaryPress(scene, item)
            render(scene)

            assertTrue(!semanticLabels(scene).contains(MR.strings.desktop_ui_download_next_unread.localized()))
            assertTrue(semanticLabels(scene).contains(MR.strings.remove_from_library.localized()))
            secondaryRelease(scene, item)
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `remote manga context actions use the production mark and download model ports`() = runTest {
        val manga = sampleManga(92L, "Remote context", 7L)
        val mangaRepository = FakeMangaRepository().apply {
            seed(manga)
            libraryManga = listOf(sampleLibraryManga(manga).copy(totalChapters = 1L))
        }
        val chapters = FakeChapterRepository().apply {
            seed(Chapter.create().copy(id = 921L, mangaId = manga.id, name = "Chapter", url = "/chapter", read = false))
        }
        val getChapters = GetChaptersByMangaId(chapters)
        val enqueued = mutableListOf<mihon.desktop.download.DownloadItem>()
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(mangaRepository),
            getCategories = GetCategories(FakeCategoryRepository()),
            getChaptersByMangaId = getChapters,
            getNextChapters = tachiyomi.domain.history.interactor.GetNextChapters(
                getChapters,
                GetManga(mangaRepository),
                FakeHistoryRepository(),
            ),
            setChapterReadStatus = SetChapterReadStatus(getChapters, UpdateChapter(chapters)),
            enqueueDownload = enqueued::add,
        )
        val scene = rootScene(model)
        try {
            render(scene)
            var item = nodes(scene).first {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) &&
                    semanticLabels(it).contains(manga.title)
            }
            secondaryPress(scene, item)
            render(scene)
            click(scene, MR.strings.desktop_ui_download_next_unread.localized())
            secondaryRelease(scene, item)
            withContext(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) { while (enqueued.isEmpty()) delay(10) }
            }
            assertEquals(listOf(manga.id), enqueued.map { it.mangaId })

            render(scene)
            item = nodes(scene).first {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) &&
                    semanticLabels(it).contains(manga.title)
            }
            secondaryPress(scene, item)
            render(scene)
            click(scene, MR.strings.desktop_ui_mark_all_read.localized())
            withContext(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) { while (chapters.getChapterById(921L)?.read != true) delay(10) }
            }
            assertTrue(chapters.getChapterById(921L)?.read == true)
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `root search click keeps empty search expanded and forwards the nonempty query globally`() = runTest {
        val model = rootModel(listOf(sampleLibraryManga(sampleManga(4L, "Visible", 1L))))
        var destination: Screen? = null
        val scene = rootScene(model, onDestination = { destination = it })
        try {
            render(scene)
            click(scene, MR.strings.action_search.localized())
            render(scene)
            val field = nodes(scene).single { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText) }
            assertTrue(field.config[androidx.compose.ui.semantics.SemanticsProperties.Focused])
            assertTrue(requireNotNull(field.config[androidx.compose.ui.semantics.SemanticsActions.SetText].action).invoke(AnnotatedString("Visible")))
            render(scene)
            click(scene, MR.strings.action_reset.localized())
            render(scene)
            assertTrue(nodes(scene).any { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText) })
            assertTrue(!semanticLabels(scene).contains(MR.strings.action_global_search.localized()))
            val clearedField = nodes(scene).single { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText) }
            assertTrue(requireNotNull(clearedField.config[androidx.compose.ui.semantics.SemanticsActions.SetText].action).invoke(AnnotatedString("Visible")))
            render(scene)
            click(scene, MR.strings.action_global_search.localized())
            render(scene)

            val global = destination as mihon.desktop.ui.browse.GlobalSearchScreen
            assertEquals("Visible", global.initialQuery)
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `whitespace search keeps category tabs and forwards the exact global query`() = runTest {
        val categories = FakeCategoryRepository().apply {
            insert(Category(1L, "A", 0L, 0L))
            insert(Category(2L, "B", 1L, 0L))
        }
        val model = rootModel(emptyList(), categories).apply { setSearchQuery(" ") }
        var destination: Screen? = null
        val scene = rootScene(model, onDestination = { destination = it })
        try {
            render(scene)

            val labels = semanticLabels(scene)
            assertTrue(labels.contains("A (0)"))
            assertTrue(labels.contains("B (0)"))
            click(scene, MR.strings.action_global_search.localized())
            render(scene)
            assertEquals(" ", (destination as mihon.desktop.ui.browse.GlobalSearchScreen).initialQuery)
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `escape clears selection before it closes an expanded search`() = runTest {
        val title = "Escape target"
        val model = rootModel(listOf(sampleLibraryManga(sampleManga(5L, title, 1L))))
        val scene = rootScene(model)
        try {
            render(scene)
            click(scene, MR.strings.action_search.localized())
            render(scene)
            val field = nodes(scene).single { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText) }
            assertTrue(requireNotNull(field.config[androidx.compose.ui.semantics.SemanticsActions.SetText].action).invoke(AnnotatedString("Escape")))
            val item = nodes(scene).first {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) && semanticLabels(it).contains(title)
            }
            assertTrue(requireNotNull(item.config[androidx.compose.ui.semantics.SemanticsActions.OnLongClick].action).invoke())
            render(scene)

            scene.sendKeyEvent(composeKeyEvent(Key.Escape, KeyEventType.KeyDown))
            render(scene)
            assertTrue(nodes(scene).any { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText) })
            assertTrue(semanticLabels(scene).contains(MR.strings.action_sort.localized()))

            scene.sendKeyEvent(composeKeyEvent(Key.Escape, KeyEventType.KeyDown))
            render(scene)
            assertTrue(nodes(scene).none { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText) })
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `root selection hides ordinary toolbar actions`() = runTest {
        val title = "Selectable"
        val model = rootModel(listOf(sampleLibraryManga(sampleManga(2L, title, 1L))))
        val scene = rootScene(model)
        try {
            render(scene)
            val item = nodes(scene).first {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) &&
                    semanticLabels(it).contains(title)
            }
            assertTrue(requireNotNull(item.config[androidx.compose.ui.semantics.SemanticsActions.OnLongClick].action).invoke())
            render(scene)

            assertTrue(semanticLabels(scene).contains(MR.strings.desktop_ui_selected_count.localized(Locale.getDefault(), 1)))
            assertTrue(!semanticLabels(scene).contains(MR.strings.action_sort.localized()))
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `selection controls live only in the top bar while batch actions remain at the bottom`() = runTest {
        val title = "One selection surface"
        val model = rootModel(listOf(sampleLibraryManga(sampleManga(3L, title, 1L))))
        val scene = rootScene(model)
        try {
            render(scene)
            val item = nodes(scene).first {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) && semanticLabels(it).contains(title)
            }
            assertTrue(requireNotNull(item.config[androidx.compose.ui.semantics.SemanticsActions.OnLongClick].action).invoke())
            render(scene)

            val labels = semanticLabels(scene)
            assertEquals(1, labels.count { it == MR.strings.desktop_ui_selected_count.localized(Locale.getDefault(), 1) })
            assertEquals(1, labels.count { it == MR.strings.action_select_all.localized() })
            assertEquals(1, labels.count { it == MR.strings.desktop_ui_invert_selection.localized() })
            assertTrue(labels.contains(MR.strings.action_download.localized()))
            assertTrue(labels.contains(MR.strings.action_remove.localized()))
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `empty library opens the upstream getting started guide`() = runTest {
        var openedUri: String? = null
        val scene = rootScene(
            model = rootModel(emptyList()),
            uriHandler = object : UriHandler {
                override fun openUri(uri: String) {
                    openedUri = uri
                }
            },
        )
        try {
            render(scene)

            val labels = semanticLabels(scene)
            assertTrue(labels.contains(MR.strings.getting_started_guide.localized()))
            assertTrue(!labels.contains(MR.strings.action_global_search.localized()))
            click(scene, MR.strings.getting_started_guide.localized())
            assertEquals("https://mihon.app/docs/guides/getting-started", openedUri)
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `empty library with real default preferences still shows getting started`() = runTest {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val preferences = LibraryPreferences(DesktopPreferenceStore(preferencesNode))
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(FakeMangaRepository()),
            getCategories = GetCategories(FakeCategoryRepository()),
            libraryPreferences = preferences,
        )
        val scene = rootScene(model)
        try {
            render(scene)

            assertTrue(semanticLabels(scene).contains(MR.strings.getting_started_guide.localized()))
        } finally {
            scene.close()
            preferencesNode.removeNode()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `global downloaded only does not become a local active filter or overwrite its value`() = runTest {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val preferences = LibraryPreferences(DesktopPreferenceStore(preferencesNode)).apply {
            downloadedOnly().set(true)
        }
        val model = preferenceRootModel(emptyList(), FakeCategoryRepository(), preferences)
        val scene = rootScene(model)
        try {
            render(scene)
            assertEquals(TriState.DISABLED, model.state.value.filter.downloaded)
            assertTrue(semanticLabels(scene).contains(MR.strings.getting_started_guide.localized()))

            preferences.filterDownloaded().set(TriState.ENABLED_NOT)
            render(scene)
            assertEquals(TriState.ENABLED_NOT, model.state.value.filter.downloaded)
            preferences.downloadedOnly().set(false)
            render(scene)
            assertEquals(TriState.ENABLED_NOT, model.state.value.filter.downloaded)
        } finally {
            scene.close()
            preferencesNode.removeNode()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `root distinguishes search filter and empty category feedback`() = runTest {
        val item = sampleLibraryManga(sampleManga(31L, "Present", 1L)).copy(categories = listOf(2L))
        val searchModel = rootModel(listOf(item)).apply { setSearchQuery("Missing") }
        val searchScene = rootScene(searchModel)
        try {
            render(searchScene)
            assertTrue(semanticLabels(searchScene).contains(MR.strings.no_results_found.localized()))
        } finally {
            searchScene.close()
        }

        val filterModel = rootModel(listOf(item)).apply {
            setFilter(state.value.filter.copy(unread = TriState.ENABLED_IS))
        }
        val filterScene = rootScene(filterModel)
        try {
            render(filterScene)
            assertTrue(semanticLabels(filterScene).contains(MR.strings.error_no_match.localized()))
        } finally {
            filterScene.close()
        }

        val categories = FakeCategoryRepository().apply {
            insert(Category(1L, "Empty", 0L, 0L))
            insert(Category(2L, "Populated", 1L, 0L))
        }
        val categoryScene = rootScene(rootModel(listOf(item), categories))
        try {
            render(categoryScene)
            assertTrue(semanticLabels(categoryScene).contains(MR.strings.information_no_manga_category.localized()))
        } finally {
            categoryScene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `search category counts use each category filtered result`() = runTest {
        val categories = FakeCategoryRepository().apply {
            insert(Category(1L, "A", 0L, 0L))
            insert(Category(2L, "B", 1L, 0L))
        }
        val items = listOf(
            sampleLibraryManga(sampleManga(11L, "Match", 1L)).copy(categories = listOf(1L)),
            sampleLibraryManga(sampleManga(12L, "Other", 1L)).copy(categories = listOf(1L)),
            sampleLibraryManga(sampleManga(13L, "Third", 1L)).copy(categories = listOf(2L)),
        )
        val model = rootModel(items, categories).apply { setSearchQuery("Match") }
        val scene = rootScene(model)
        try {
            render(scene)

            val labels = semanticLabels(scene)
            assertTrue(labels.contains("A (1)"))
            assertTrue(labels.contains("B (0)"))
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `closed search toolbar title follows category tab and count preferences`() = runTest {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val preferences = LibraryPreferences(DesktopPreferenceStore(preferencesNode)).apply {
            categoryTabs().set(false)
            categoryNumberOfItems().set(true)
        }
        val categories = FakeCategoryRepository().apply {
            insert(Category(1L, "A", 0L, 0L))
            insert(Category(2L, "B", 1L, 0L))
        }
        val items = listOf(
            sampleLibraryManga(sampleManga(51L, "First", 1L)).copy(categories = listOf(1L)),
            sampleLibraryManga(sampleManga(52L, "Second", 1L)).copy(categories = listOf(2L)),
        )
        val model = preferenceRootModel(items, categories, preferences)
        val scene = rootScene(model)
        try {
            render(scene)
            assertTrue(semanticLabels(scene).contains("A (1)"))

            preferences.categoryTabs().set(true)
            render(scene)
            assertTrue(semanticLabels(scene).contains("${MR.strings.label_library.localized()} (2)"))
        } finally {
            scene.close()
            preferencesNode.removeNode()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `real global preferences gate local filter menu and tracker sort`() = runTest {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val preferences = LibraryPreferences(DesktopPreferenceStore(preferencesNode)).apply {
            downloadedOnly().set(true)
            autoUpdateMangaRestrictions().set(emptySet())
        }
        val model = preferenceRootModel(
            listOf(sampleLibraryManga(sampleManga(61L, "Gated", 1L))),
            FakeCategoryRepository(),
            preferences,
        )
        val scene = rootScene(model)
        try {
            render(scene)
            click(scene, MR.strings.action_filter.localized())
            render(scene)

            val labels = semanticLabels(scene)
            val downloaded = MR.strings.desktop_ui_filter_value.localized(
                Locale.getDefault(),
                MR.strings.label_downloaded.localized(),
                MR.strings.desktop_ui_filter_include.localized(),
            )
            val downloadedNode = nodes(scene).first { semanticLabels(it).contains(downloaded) }
            assertTrue(downloadedNode.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled))
            assertTrue(labels.none { it.startsWith(MR.strings.desktop_ui_global_downloaded_only.localized(Locale.getDefault(), "")) })
            assertTrue(labels.none { it.contains(MR.strings.desktop_ui_custom_interval.localized()) })

            click(scene, MR.strings.action_sort.localized())
            render(scene)
            assertTrue(!semanticLabels(scene).any { it.startsWith(MR.strings.action_sort_tracker_score.localized()) })
        } finally {
            scene.close()
            preferencesNode.removeNode()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `explicit non release build exposes custom interval when restriction is enabled`() = runTest {
        assumeTrue(BuildInfo.IS_NON_RELEASE_BUILD)
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val preferences = LibraryPreferences(DesktopPreferenceStore(preferencesNode)).apply {
            autoUpdateMangaRestrictions().set(setOf(LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD))
        }
        val scene = rootScene(preferenceRootModel(emptyList(), FakeCategoryRepository(), preferences))
        try {
            render(scene)
            click(scene, MR.strings.action_filter.localized())
            render(scene)
            assertTrue(semanticLabels(scene).any { it.contains(MR.strings.desktop_ui_custom_interval.localized()) })
        } finally {
            scene.close()
            preferencesNode.removeNode()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `all root layouts consume live badge and continue preferences including cover only`() = runTest {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val store = DesktopPreferenceStore(preferencesNode)
        val preferences = LibraryPreferences(store).apply {
            downloadBadge().set(true)
            unreadBadge().set(true)
            localBadge().set(true)
            languageBadge().set(true)
            showContinueReadingButton().set(true)
        }
        val manga = sampleManga(41L, "Layout badges", 0L)
        val remoteManga = sampleManga(42L, "Remote language", 42L)
        val mangaRepository = FakeMangaRepository().apply {
            seed(manga)
            seed(remoteManga)
            libraryManga = listOf(
                sampleLibraryManga(manga).copy(totalChapters = 3L),
                sampleLibraryManga(remoteManga),
            )
        }
        val chapterRepository = FakeChapterRepository().apply {
            seed(
                Chapter.create().copy(
                    id = 4101L,
                    mangaId = manga.id,
                    url = "/layout/chapter-1",
                    name = "Chapter 1",
                    sourceOrder = 1L,
                ),
            )
        }
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(mangaRepository),
            getCategories = GetCategories(FakeCategoryRepository()),
            getChaptersByMangaId = GetChaptersByMangaId(chapterRepository),
            isMangaDownloaded = { true },
            downloadedChapterCount = { if (it.id == manga.id) 7L else 0L },
            sourceManager = FakeDesktopSourceManager(listOf(FakeSource(id = 42L, lang = "fr", name = "French source"))),
            libraryPreferences = preferences,
        )
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true)
        var destination: Screen? = null
        val scene = ImageComposeScene(1_200, 900, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    ProvideLibraryScreenModelFactory(factory = { model }) {
                        Navigator(LibraryRootScreen()) { navigator ->
                            destination = navigator.lastItem
                            if (navigator.lastItem is LibraryRootScreen) CurrentScreen()
                        }
                    }
                }
            }

            suspend fun assertIndicators(mode: SharedLibraryDisplayMode) {
                preferences.displayMode().set(mode)
                render(scene)
                val labels = semanticLabels(scene)
                assertTrue(labels.contains(MR.strings.label_downloaded.localized()), mode.toString())
                assertTrue(labels.contains(MR.strings.action_display_local_badge.localized()), mode.toString())
                assertTrue(labels.contains("7"), mode.toString())
                assertTrue(labels.contains("FR"), mode.toString())
                assertTrue(labels.contains("3"), mode.toString())
                assertTrue(labels.contains(MR.strings.desktop_ui_continue_reading.localized()), mode.toString())
            }

            assertIndicators(SharedLibraryDisplayMode.CompactGrid)
            assertIndicators(SharedLibraryDisplayMode.ComfortableGrid)
            assertIndicators(SharedLibraryDisplayMode.List)
            assertIndicators(SharedLibraryDisplayMode.CoverOnlyGrid)

            preferences.localBadge().set(false)
            render(scene)
            assertTrue(!model.state.value.showLocalBadge)
            assertTrue(!semanticLabels(scene).contains(MR.strings.action_display_local_badge.localized()))
            assertTrue(semanticLabels(scene).contains("FR"))

            preferences.localBadge().set(true)
            preferences.languageBadge().set(false)
            render(scene)
            assertTrue(!model.state.value.showLanguageBadge)
            assertTrue(semanticLabels(scene).contains(MR.strings.action_display_local_badge.localized()))
            assertTrue(!semanticLabels(scene).contains("FR"))

            preferences.unreadBadge().set(false)
            render(scene)
            val hiddenLabels = semanticLabels(scene)
            assertTrue(!hiddenLabels.contains("3"))
            assertTrue(!hiddenLabels.contains(MR.strings.desktop_ui_continue_reading.localized()))

            preferences.unreadBadge().set(true)
            render(scene)
            click(scene, MR.strings.desktop_ui_continue_reading.localized())
            render(scene)
            assertTrue(destination is mihon.desktop.ui.reader.DesktopReaderScreen)
        } finally {
            scene.close()
            preferencesNode.removeNode()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `LibraryTab page projection follows tracker session local download and multiple flags`() = runTest {
        val mangaRepository = FakeMangaRepository().apply {
            libraryManga = listOf(
                sampleLibraryManga(sampleManga(1L, "Downloaded", 10L)).copy(totalChapters = 2L),
                sampleLibraryManga(sampleManga(2L, "Local", 0L)).copy(totalChapters = 2L),
                sampleLibraryManga(sampleManga(3L, "Historical", 30L)).copy(totalChapters = 2L),
            )
        }
        val downloadedChapter = tempDir.resolve("10/Downloaded/Chapter 1")
        Files.createDirectories(downloadedChapter)
        Files.write(
            downloadedChapter.resolve("page.png"),
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        )
        val tracks = MutableStateFlow(listOf(sampleTrack(mangaId = 3L, trackerId = 7L)))
        val sessions = MutableStateFlow(emptySet<Long>())
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(mangaRepository),
            getCategories = GetCategories(FakeCategoryRepository()),
            downloadProvider = DesktopDownloadProvider(tempDir.toFile()),
            getTracksPerManga = GetTracksPerManga(trackRepositoryOf(tracks)),
            trackerSessionProvider = TrackerSessionProvider { sessions },
        )
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true)
        model.setFilter(
            LibraryFilter(
                downloaded = TriState.ENABLED_NOT,
                unread = TriState.ENABLED_IS,
                tracking = mapOf(7L to TriState.ENABLED_IS),
            ),
        )
        var snapshot: LibraryPageSnapshot? = null
        model.libraryMangaFlow().launchIn(backgroundScope)
        runCurrent()

        val scene = ImageComposeScene(1_200, 900, coroutineContext = coroutineContext)
        scene.setContent {
            CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                ProvideLibraryScreenModelFactory(factory = { model }) {
                    ProvideLibraryPageProbe(probe = { snapshot = it }) {
                        Navigator(LibraryRootScreen()) { CurrentScreen() }
                    }
                }
            }
        }

        suspend fun render() {
            repeat(3) {
                scene.render()
                yield()
                runCurrent()
            }
        }

        render()

        assertEquals(emptySet<Long>(), snapshot?.availableTrackerIds)
        assertEquals(listOf(1L, 2L, 3L), model.state.value.allItems.map { it.id })
        assertEquals(listOf(3L), snapshot?.visibleItemIds)

        sessions.value = setOf(7L)
        runCurrent()
        model.toggleTrackingFilter(7L)
        render()
        assertEquals(setOf(7L), snapshot?.availableTrackerIds)
        assertEquals(listOf(3L), snapshot?.visibleItemIds)

        sessions.value = emptySet()
        render()
        assertEquals(emptySet<Long>(), snapshot?.availableTrackerIds)
        assertEquals(listOf(3L), snapshot?.visibleItemIds)

        model.setFilter(LibraryFilter(downloaded = TriState.ENABLED_IS))
        render()
        assertEquals(listOf(1L, 2L), snapshot?.visibleItemIds)

        scene.close()
    }

    private fun sampleManga(id: Long, title: String, source: Long) = Manga.create().copy(
        id = id,
        title = title,
        source = source,
        favorite = true,
    )

    private fun sampleLibraryManga(manga: Manga) = LibraryManga(
        manga = manga,
        categories = emptyList(),
        totalChapters = 0L,
        readCount = 0L,
        bookmarkCount = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = 0L,
    )

    private fun sampleTrack(mangaId: Long, trackerId: Long) = Track(
        id = mangaId,
        mangaId = mangaId,
        trackerId = trackerId,
        remoteId = mangaId,
        libraryId = null,
        title = "Track",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 0L,
        score = 8.0,
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    private fun trackRepositoryOf(tracks: MutableStateFlow<List<Track>>) = object : TrackRepository {
        override suspend fun getTrackById(id: Long) = tracks.value.singleOrNull { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long) = tracks.value.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow(): Flow<List<Track>> = tracks
        override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> =
            tracks.map { values -> values.filter { it.mangaId == mangaId } }
        override suspend fun delete(mangaId: Long, trackerId: Long) = Unit
        override suspend fun insert(track: Track) = Unit
        override suspend fun insertAll(tracks: List<Track>) = Unit
    }

    private fun rootModel(
        items: List<LibraryManga>,
        categories: FakeCategoryRepository = FakeCategoryRepository(),
    ): LibraryScreenModel {
        val mangaRepository = FakeMangaRepository().apply { libraryManga = items }
        return LibraryScreenModel(
            getLibraryManga = GetLibraryManga(mangaRepository),
            getCategories = GetCategories(categories),
        )
    }

    private fun preferenceRootModel(
        items: List<LibraryManga>,
        categories: FakeCategoryRepository,
        preferences: LibraryPreferences,
    ): LibraryScreenModel {
        val mangaRepository = FakeMangaRepository().apply { libraryManga = items }
        return LibraryScreenModel(
            getLibraryManga = GetLibraryManga(mangaRepository),
            getCategories = GetCategories(categories),
            libraryPreferences = preferences,
        )
    }

    private fun rootScene(
        model: LibraryScreenModel,
        onDestination: (Screen) -> Unit = {},
        uriHandler: UriHandler = object : UriHandler {
            override fun openUri(uri: String) = Unit
        },
    ): ImageComposeScene =
        ImageComposeScene(1_200, 900, coroutineContext = kotlinx.coroutines.Dispatchers.Unconfined) {}.also { scene ->
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides mockk<DesktopUiDependencies>(relaxed = true),
                    LocalUriHandler provides uriHandler,
                ) {
                    ProvideLibraryScreenModelFactory(factory = { model }) {
                        Navigator(LibraryRootScreen()) { navigator ->
                            onDestination(navigator.lastItem)
                            if (navigator.lastItem is LibraryRootScreen) CurrentScreen()
                        }
                    }
                }
            }
        }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun semanticLabels(scene: ImageComposeScene): List<String> = nodes(scene).flatMap { node ->
        val text = if (node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)) {
            node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].map { it.text }
        } else {
            emptyList()
        }
        val descriptions = if (node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)) {
            node.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
        } else {
            emptyList()
        }
        text + descriptions
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { candidate ->
            candidate.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) &&
                semanticLabels(candidate).contains(label)
        }
        assertTrue(requireNotNull(node.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action).invoke())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun nodes(scene: ImageComposeScene): List<androidx.compose.ui.semantics.SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
        listOf(node) + node.children.flatMap(::flatten)

    private fun semanticLabels(node: androidx.compose.ui.semantics.SemanticsNode): List<String> {
        val text = if (node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)) {
            node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].map { it.text }
        } else {
            emptyList()
        }
        val descriptions = if (node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)) {
            node.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
        } else {
            emptyList()
        }
        return text + descriptions
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun secondaryPress(scene: ImageComposeScene, node: androidx.compose.ui.semantics.SemanticsNode) {
        val center = node.boundsInRoot.center
        scene.sendPointerEvent(
            PointerEventType.Press,
            Offset(center.x, center.y),
            button = PointerButton.Secondary,
            buttons = PointerButtons(isSecondaryPressed = true),
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun secondaryRelease(scene: ImageComposeScene, node: androidx.compose.ui.semantics.SemanticsNode) {
        val center = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Release, Offset(center.x, center.y), button = PointerButton.Secondary)
    }

    private suspend fun render(scene: ImageComposeScene) {
        repeat(4) {
            scene.render()
            yield()
        }
    }

    private fun composeKeyEvent(key: Key, type: KeyEventType): androidx.compose.ui.input.key.KeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp")
            .invoke(null)
        val factory = events.declaredMethods.single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
        val native = factory.invoke(null, key.keyCode, eventType, 0, false, false, false, false, null)
        return androidx.compose.ui.input.key.KeyEvent(native)
    }

}
