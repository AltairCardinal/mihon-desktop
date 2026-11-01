package mihon.desktop.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import mihon.desktop.BuildInfo
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.DesktopNotification
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.domain.SortMode
import mihon.desktop.domain.fakes.FakeCategoryRepository
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeHistoryRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.source.FakeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.SetChapterReadStatus
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.interactor.LibraryFilter
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.prefs.Preferences
import tachiyomi.domain.library.model.LibraryDisplayMode as SharedLibraryDisplayMode

class LibraryPageCompositionTest {
    @TempDir
    lateinit var tempDir: Path

    private fun bannerTask(status: mihon.domain.task.TaskStatus, categoryId: Long? = 0L) = mihon.desktop.task.StoredTask(
        task = mihon.domain.task.BackgroundTask("library-update", "banner-test"),
        status = status,
        libraryUpdate = mihon.desktop.task.LibraryUpdateContext(
            trigger = mihon.desktop.task.LibraryUpdateTrigger.MANUAL,
            scope = mihon.desktop.task.LibraryUpdateScope.ALL,
            categoryId = categoryId,
            units = listOf(
                mihon.desktop.task.LibraryUpdateUnit(1, 1, "/1", mihon.desktop.task.LibraryUnitStatus.SUCCESS, 3),
                mihon.desktop.task.LibraryUpdateUnit(2, 1, "/2", mihon.desktop.task.LibraryUnitStatus.FAILED),
            ),
        ),
    )

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `historical completed update does not restore the banner`() = runTest {
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(FakeMangaRepository()),
            getCategories = GetCategories(FakeCategoryRepository()),
            backgroundUpdateObservations = kotlinx.coroutines.flow.flowOf(
                mihon.desktop.domain.LibraryUpdateObservation(bannerTask(mihon.domain.task.TaskStatus.Completed)),
            ),
        )
        val scene = rootScene(model)
        try {
            settleContextMenu(scene)
            assertTrue(MR.strings.desktop_library_update_results.localized() !in semanticLabels(scene))
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `category owner hides progress elsewhere and suppresses completion while away`() = runTest {
        val observations = MutableStateFlow(mihon.desktop.domain.LibraryUpdateObservation(bannerTask(mihon.domain.task.TaskStatus.Running, 1L)))
        val categories = FakeCategoryRepository().apply {
            insert(Category(1, "First", 1, 0))
            insert(Category(2, "Second", 2, 0))
        }
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(FakeMangaRepository()),
            getCategories = GetCategories(categories),
            backgroundUpdateObservations = observations,
        )
        val scene = rootScene(model)
        try {
            settleContextMenu(scene)
            val label = MR.strings.desktop_library_update_results.localized()
            assertTrue(label in semanticLabels(scene))
            libraryUpdateSummary(observations.value.task!!)!!.split(" | ").forEach {
                assertTrue(it in semanticLabels(scene), "Each banner column must be a real text node: $it")
            }
            pointerClick(scene, "Second")
            settleContextMenu(scene)
            assertTrue(label !in semanticLabels(scene))
            pointerClick(scene, "First")
            settleContextMenu(scene)
            assertTrue(label in semanticLabels(scene))
            pointerClick(scene, "Second")
            settleContextMenu(scene)
            observations.value = mihon.desktop.domain.LibraryUpdateObservation(bannerTask(mihon.domain.task.TaskStatus.Completed, 1L))
            settleContextMenu(scene)
            assertTrue(label !in semanticLabels(scene))
            pointerClick(scene, "First")
            settleContextMenu(scene)
            assertTrue(label !in semanticLabels(scene))
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `leaving library for details preserves running banner but hides off-page completion`() = runTest {
        val task = bannerTask(mihon.domain.task.TaskStatus.Running)
        val running = kotlinx.coroutines.Job()
        val observations = MutableStateFlow(mihon.desktop.domain.LibraryUpdateObservation(task))
        var starts = 0
        val item = sampleLibraryManga(sampleManga(1L, "Banner work", 1L))
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(FakeMangaRepository().apply { libraryManga = listOf(item) }),
            getCategories = GetCategories(FakeCategoryRepository()),
            backgroundUpdateObservations = observations,
            backgroundUpdateSnapshot = { observations.value.task },
            backgroundUpdateJob = { running },
            startBackgroundUpdate = { starts++; running },
        )
        var navigator: Navigator? = null
        val scene = rootScene(model, onNavigator = { navigator = it })
        try {
            settleContextMenu(scene)
            val label = MR.strings.desktop_library_update_results.localized()
            assertTrue(label in semanticLabels(scene))
            pointerClick(scene, "Banner work")
            settleContextMenu(scene)
            assertTrue(navigator!!.lastItem is MangaDetailScreen)
            navigator!!.pop()
            settleContextMenu(scene)
            assertTrue(label in semanticLabels(scene), "A running update must stay visible on return")
            pointerClick(scene, "Banner work")
            settleContextMenu(scene)
            observations.value = mihon.desktop.domain.LibraryUpdateObservation(bannerTask(mihon.domain.task.TaskStatus.Completed))
            settleContextMenu(scene)
            navigator!!.pop()
            settleContextMenu(scene)
            assertTrue(label !in semanticLabels(scene), "Completion while away must not restore the banner")
            model.refreshLibrary(listOf(item))
            settleContextMenu(scene)
            assertTrue(label in semanticLabels(scene))
            assertEquals(0, starts)
        } finally {
            scene.close()
            running.cancel()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `banner owner survives category reorder and duplicate refresh until a new task starts`() = runTest {
        val task = bannerTask(mihon.domain.task.TaskStatus.Running, 1L)
        val observations = MutableStateFlow(mihon.desktop.domain.LibraryUpdateObservation(task))
        val categories = FakeCategoryRepository().apply {
            insert(Category(1, "First", 1, 0))
            insert(Category(2, "Second", 2, 0))
        }
        var starts = 0
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(FakeMangaRepository()),
            getCategories = GetCategories(categories),
            backgroundUpdateObservations = observations,
            backgroundUpdateSnapshot = { observations.value.task },
            startBackgroundUpdate = {
                starts++
                observations.value = mihon.desktop.domain.LibraryUpdateObservation(
                    bannerTask(mihon.domain.task.TaskStatus.Running, null).copy(
                        task = mihon.domain.task.BackgroundTask("library-update", "new-task"),
                    ),
                )
                kotlinx.coroutines.Job().apply { complete() }
            },
        )
        val scene = rootScene(model)
        try {
            settleContextMenu(scene)
            val label = MR.strings.desktop_library_update_results.localized()
            assertTrue(label in semanticLabels(scene))
            model.setCategories(listOf(Category(2, "Second", 0, 0), Category(1, "First", 1, 0)))
            settleContextMenu(scene)
            assertEquals(1L, model.state.value.categories[model.state.value.selectedCategoryIndex].id)
            assertTrue(label in semanticLabels(scene))
            pointerClick(scene, "Second")
            settleContextMenu(scene)
            assertTrue(label !in semanticLabels(scene))
            model.refreshLibrary(emptyList())
            settleContextMenu(scene)
            assertEquals(0, starts)
            assertTrue(label !in semanticLabels(scene), "Duplicate refresh must not move an existing banner")
            pointerClick(scene, "First")
            settleContextMenu(scene)
            assertTrue(label in semanticLabels(scene))
            observations.value = mihon.desktop.domain.LibraryUpdateObservation(task.copy(status = mihon.domain.task.TaskStatus.Completed))
            settleContextMenu(scene)
            pointerClick(scene, "Second")
            settleContextMenu(scene)
            model.refreshLibrary(emptyList())
            settleContextMenu(scene)
            assertEquals(1, starts)
            assertTrue(label in semanticLabels(scene), "A new ALL-scope task belongs to its triggering category")
            pointerClick(scene, "First")
            settleContextMenu(scene)
            assertTrue(label !in semanticLabels(scene))
        } finally { scene.close() }
    }

    private object BannerOtherTab : cafe.adriel.voyager.navigator.tab.Tab {
        override val options: cafe.adriel.voyager.navigator.tab.TabOptions
            @Composable get() = cafe.adriel.voyager.navigator.tab.TabOptions(99u, "Other", null)
        @Composable override fun Content() { androidx.compose.material3.Text("Other page") }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `main navigation preserves running banner on return and dismisses completed banner`() = runTest {
        val task = bannerTask(mihon.domain.task.TaskStatus.Running)
        val observations = MutableStateFlow(mihon.desktop.domain.LibraryUpdateObservation(task))
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(FakeMangaRepository()),
            getCategories = GetCategories(FakeCategoryRepository()),
            backgroundUpdateObservations = observations,
        )
        val scene = ImageComposeScene(1200, 900, coroutineContext = kotlinx.coroutines.Dispatchers.Unconfined) {}
        try {
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides mockk<DesktopUiDependencies>(relaxed = true) {
                        every { syncPanel } returns null
                    },
                ) {
                    ProvideLibraryScreenModelFactory({ model }) {
                        ProvideLibraryNavigationHost(VoyagerLibraryNavigationHost()) {
                            cafe.adriel.voyager.navigator.tab.TabNavigator(LibraryTab) { tabs ->
                                mihon.desktop.ui.home.HomeNavigationHost(
                                    current = tabs.current,
                                    onSelect = { tabs.current = it },
                                    showNavigation = true,
                                    badgeCount = 0,
                                    tabs = listOf(LibraryTab, BannerOtherTab),
                                ) { cafe.adriel.voyager.navigator.tab.CurrentTab() }
                            }
                        }
                    }
                }
            }
            settleContextMenu(scene)
            val label = MR.strings.desktop_library_update_results.localized()
            assertTrue(label in semanticLabels(scene))
            pointerClick(scene, "Other")
            settleContextMenu(scene)
            assertTrue("Other page" in semanticLabels(scene))
            pointerClick(scene, MR.strings.label_library.localized())
            settleContextMenu(scene)
            assertTrue(label in semanticLabels(scene), "Switching tabs during an update must retain progress")
            observations.value = mihon.desktop.domain.LibraryUpdateObservation(bannerTask(mihon.domain.task.TaskStatus.Completed))
            settleContextMenu(scene)
            assertTrue(label in semanticLabels(scene))
            pointerClick(scene, "Other")
            settleContextMenu(scene)
            pointerClick(scene, MR.strings.label_library.localized())
            settleContextMenu(scene)
            assertTrue(label !in semanticLabels(scene), "Completed feedback is dismissed on the next navigation")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `banner summary reports new chapters and failures rather than processing counters`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            assertEquals("更新完成 | 1个作品有更新，合计3个新章节 | 1个作品更新失败", libraryUpdateSummary(bannerTask(mihon.domain.task.TaskStatus.Completed)))
            val empty = bannerTask(mihon.domain.task.TaskStatus.Completed).let { it.copy(libraryUpdate = it.libraryUpdate!!.copy(units = emptyList())) }
            assertEquals("更新完成", libraryUpdateSummary(empty))
            val failedOnly = empty.copy(libraryUpdate = empty.libraryUpdate!!.copy(
                units = listOf(bannerTask(mihon.domain.task.TaskStatus.Completed).libraryUpdate!!.units.last()),
            ))
            assertEquals("更新完成 | 1个作品更新失败", libraryUpdateSummary(failedOnly))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `root category bar localizes blank system category and keeps custom names`() = runTest {
        val originalLocale = Locale.getDefault()
        try {
            for ((locale, expected) in listOf(Locale.ENGLISH to "default", Locale.SIMPLIFIED_CHINESE to "默认")) {
                Locale.setDefault(locale)
                val categories = FakeCategoryRepository().apply {
                    insert(Category(1L, "Custom", 1L, 0L))
                }
                val item = sampleLibraryManga(sampleManga(1L, "Uncategorized work", 1L))
                    .copy(categories = listOf(0L))
                val model = rootModel(listOf(item), categories)
                val scene = rootScene(model)
                try {
                    render(scene)
                    model.setCategories(listOf(Category(0L, "", 0L, 0L)) + categories.getAll())
                    render(scene)
                    assertTrue(semanticLabels(scene).contains(expected), "Missing $expected for $locale")
                    assertTrue(semanticLabels(scene).contains("Custom"))
                    click(scene, "Custom")
                    render(scene)
                    click(scene, expected)
                    render(scene)
                    assertTrue(semanticLabels(scene).contains("Uncategorized work"))
                    model.setSearchQuery("Uncategorized")
                    render(scene)
                    assertTrue(semanticLabels(scene).contains("$expected (1)"))
                } finally {
                    scene.close()
                }
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `wheel over root category bar scrolls horizontally without changing category`() = runTest {
        val categories = FakeCategoryRepository().apply {
            repeat(25) { insert(Category(it + 1L, "Category $it", it.toLong(), 0L)) }
        }
        val model = rootModel(emptyList(), categories)
        val scene = rootScene(model)
        try {
            render(scene)
            val rangeKey = androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange
            fun bar() = nodes(scene).first { it.config.contains(rangeKey) }
            val position = bar().boundsInRoot.center
            val initialSelection = model.state.value.selectedCategoryIndex
            val initialScroll = bar().config[rangeKey].value()
            scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = Offset(0f, 3f))
            render(scene)
            val afterDown = bar().config[rangeKey].value()
            assertTrue(afterDown > initialScroll, "Vertical wheel must scroll the category bar right")
            scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = Offset(0f, -3f))
            render(scene)
            assertTrue(bar().config[rangeKey].value() < afterDown)
            scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = Offset(3f, 0f))
            render(scene)
            assertTrue(bar().config[rangeKey].value() > initialScroll)
            val beforeOutside = bar().config[rangeKey].value()
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(600f, 800f), scrollDelta = Offset(0f, 3f))
            render(scene)
            assertEquals(beforeOutside, bar().config[rangeKey].value())
            assertEquals(initialSelection, model.state.value.selectedCategoryIndex)
            scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = Offset(0f, 1_000f))
            render(scene)
            assertEquals(bar().config[rangeKey].maxValue(), bar().config[rangeKey].value())
            scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = Offset(0f, -1_000f))
            render(scene)
            assertEquals(0f, bar().config[rangeKey].value())
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `category indicator survives uncategorized tab appearing and disappearing`() = runTest {
        val categories = FakeCategoryRepository().apply {
            insert(Category(1L, "H", 1L, 0L))
        }
        val categorized = sampleLibraryManga(sampleManga(1L, "Categorized", 1L)).copy(categories = listOf(1L))
        val uncategorized = sampleLibraryManga(sampleManga(2L, "Uncategorized work", 1L)).copy(categories = listOf(0L))
        val model = rootModel(listOf(categorized), categories)
        val scene = rootScene(model)
        try {
            render(scene)
            model.setCategories(listOf(Category(0L, "Default", 0L, 0L)) + categories.getAll())
            render(scene)
            assertEquals(listOf(1L), model.state.value.categories.map { it.id })
            repeat(3) {
                model.setAllItems(listOf(categorized, uncategorized))
                assertEquals(1, model.state.value.selectedCategoryIndex)
                render(scene)
                assertTrue(semanticLabels(scene).contains("Categorized"))
                click(scene, MR.strings.desktop_library_default_category.localized())
                render(scene)
                assertTrue(semanticLabels(scene).contains("Uncategorized work"))
                click(scene, "H")
                render(scene)
                model.setAllItems(listOf(categorized))
                assertEquals(0, model.state.value.selectedCategoryIndex)
                render(scene)
                assertTrue(semanticLabels(scene).contains("Categorized"))
            }
        } finally {
            scene.close()
        }
    }

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
            every { notificationService } returns notifications
            every { syncPanel } returns null
            every { libraryPreferences } returns null
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
            assertTrue(
                requireNotNull(item.config[androidx.compose.ui.semantics.SemanticsActions.OnLongClick].action).invoke(),
            )
            render(scene)
            click(scene, MR.strings.action_mark_as_read.localized())
            entered.await()

            scene.close()
            release.complete(Unit)
            assertTrue(
                withTimeoutOrNull(1_000) {
                    finished.await()
                    true
                } == true,
            )
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
    fun `update results follows summary on the same row and opens the real results dialog`() = runTest {
        for (width in listOf(1_200, 420)) {
            val repository = FakeMangaRepository()
            val task = mihon.desktop.task.StoredTask(
                task = mihon.domain.task.BackgroundTask("layout-update", "layout-update"),
                status = mihon.domain.task.TaskStatus.Running,
                libraryUpdate = mihon.desktop.task.LibraryUpdateContext(
                    trigger = mihon.desktop.task.LibraryUpdateTrigger.MANUAL,
                    scope = mihon.desktop.task.LibraryUpdateScope.ALL,
                    categoryId = 0L,
                    units = emptyList(),
                ),
            )
            val model = LibraryScreenModel(
                getLibraryManga = GetLibraryManga(repository),
                getCategories = GetCategories(FakeCategoryRepository()),
                backgroundUpdateObservations = kotlinx.coroutines.flow.flowOf(
                    mihon.desktop.domain.LibraryUpdateObservation(task),
                ),
            )
            val scene = rootScene(model, sceneWidth = width)
            try {
                val statusDeadline = System.nanoTime() + 2_000_000_000L
                while (model.state.value.updateStatusText == null && System.nanoTime() < statusDeadline) {
                    settleContextMenu(scene)
                }
                settleContextMenu(scene)
                val summary = requireNotNull(model.state.value.updateStatusText)
                assertTrue("|" !in semanticLabels(scene), "Empty chapter and failure columns must not render separators")
                val label = MR.strings.desktop_library_update_results.localized()
                val text = nodes(scene).first { semanticLabels(it).contains(summary.substringBefore(" | ")) }.boundsInWindow
                val button = nodes(scene).first {
                    it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) &&
                        semanticLabels(it).contains(label)
                }.boundsInWindow
                assertTrue(button.left >= text.right, "Results must follow the summary")
                assertTrue(button.top < text.bottom && button.bottom > text.top, "Results must share the summary row")
                assertTrue(button.right <= width, "Results button must remain inside a narrow window")
                pointerClick(scene, label)
                settleContextMenu(scene)
                assertTrue(semanticLabels(scene).contains(MR.strings.action_close.localized()))
            } finally {
                scene.close()
            }
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `context menu follows actual secondary click in every layout and stays inside window edges`() = runTest {
        val items = (1L..80L).map { sampleLibraryManga(sampleManga(it, "Context $it", 7L)) }
        val model = rootModel(items)
        val scene = rootScene(model)
        try {
            for (mode in LibraryDisplayMode.entries) {
                model.setDisplayMode(mode)
                settleContextMenu(scene)
                val item = contextItems(scene).first()
                for (point in listOf(
                    item.boundsInWindow.topLeft + Offset(12f, 12f),
                    item.boundsInWindow.topLeft + Offset(item.boundsInWindow.width - 12f, 24f),
                )) {
                    secondaryClick(scene, point)
                    settleContextMenu(scene)
                    val menu = contextMenuBounds(scene)
                    if (point.x + menu.width < 1_200f) {
                        assertEquals(point.x, menu.left, 2f, "$mode must anchor at the clicked x")
                    }
                    assertTrue(menu.top >= point.y && menu.top <= point.y + 10f, "$mode: $point -> $menu")
                    escapeContextMenu(scene)
                    settleContextMenu(scene)
                }
                val edgeItem = contextItems(scene)
                    .filter { it.boundsInWindow.top >= 0f && it.boundsInWindow.bottom <= 880f }
                    .maxBy { it.boundsInWindow.right + it.boundsInWindow.bottom }
                val edge = Offset(
                    (edgeItem.boundsInWindow.right - 12f).coerceAtMost(1_180f),
                    edgeItem.boundsInWindow.bottom - 12f,
                )
                secondaryClick(scene, edge)
                settleContextMenu(scene)
                val menu = contextMenuBounds(scene)
                assertTrue(menu.left >= 0 && menu.top >= 0 && menu.right <= 1_200 && menu.bottom <= 900, "$mode: $menu")
                escapeContextMenu(scene)
                settleContextMenu(scene)
            }
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `context all unread pointer click preserves manga and reports success empty and failure`() = runTest {
        for (outcome in listOf("success", "empty", "queued", "failure")) {
            val manga = sampleManga(94L, "Download context", 7L)
            val other = sampleManga(95L, "Selected other", 7L)
            val repository = FakeMangaRepository().apply {
                seed(manga)
                seed(other)
                libraryManga = listOf(manga, other).map(::sampleLibraryManga)
            }
            val chapters = FakeChapterRepository().apply {
                for (id in 1L..4L) {
                    seed(
                        Chapter.create().copy(
                            id = id,
                            mangaId = manga.id,
                            name = "Chapter $id",
                            url = "/$id",
                            sourceOrder = id,
                            read =
                            id == 4L || outcome == "empty",
                        ),
                    )
                }
                seed(Chapter.create().copy(id = 5L, mangaId = other.id, name = "Other", url = "/other"))
            }
            val enqueued = java.util.concurrent.CopyOnWriteArrayList<Long>()
            val model = LibraryScreenModel(
                getLibraryManga = GetLibraryManga(repository),
                getCategories = GetCategories(FakeCategoryRepository()),
                getChaptersByMangaId = GetChaptersByMangaId(chapters),
                isChapterQueued = { it.id == 1L || outcome == "queued" },
                enqueueDownload = {
                    if (outcome == "failure") error("download unavailable")
                    enqueued += it.chapterId
                },
                sourceManager = FakeDesktopSourceManager(
                    listOf(mihon.desktop.source.FakeHttpSource(7, "en", "Context HTTP source")),
                ),
            )
            val scene = rootScene(model)
            try {
                settleContextMenu(scene)
                val selected = contextItems(scene).first { semanticLabels(it).contains(other.title) }
                assertTrue(
                    requireNotNull(
                        selected.config[androidx.compose.ui.semantics.SemanticsActions.OnLongClick].action,
                    ).invoke(),
                )
                settleContextMenu(scene)
                val target = contextItems(scene).first { semanticLabels(it).contains(manga.title) }
                secondaryClick(scene, target.boundsInWindow.center)
                settleContextMenu(scene)
                val labels = semanticLabels(scene)
                assertTrue(
                    labels.indexOf("Download all unread chapters") >
                        labels.indexOf(MR.strings.desktop_ui_download_next_unread.localized()),
                )
                pointerClick(scene, "Download all unread chapters")
                val expected = when (outcome) {
                    "success" -> "Added 2 chapters to the download queue"
                    "empty" -> "Added 0 chapters to the download queue: No unread chapters"
                    "queued" -> "No unread chapters available to add"
                    else -> MR.strings.desktop_ui_download_failed.localized()
                }
                val deadline = System.nanoTime() + 2_000_000_000L
                while (expected !in semanticLabels(scene) && System.nanoTime() < deadline) {
                    settleContextMenu(scene)
                }
                assertTrue(expected in semanticLabels(scene), "Missing download bubble: $outcome")
                assertEquals(if (outcome == "success") listOf(3L, 2L) else emptyList<Long>(), enqueued.toList())
                assertEquals(null, model.state.value.batchCategoryResultMessage)
                val bubble = nodes(scene).first {
                    it.config.contains(androidx.compose.ui.semantics.SemanticsActions.Dismiss)
                }
                if (outcome == "success") {
                    withContext(kotlinx.coroutines.Dispatchers.Default) { delay(4_250) }
                } else {
                    assertTrue(
                        requireNotNull(
                            bubble.config[androidx.compose.ui.semantics.SemanticsActions.Dismiss].action,
                        ).invoke(),
                    )
                }
                settleContextMenu(scene)
                assertTrue(expected !in semanticLabels(scene), "Download feedback must disappear without leaving a row")
                model.setSearchQuery("")
                settleContextMenu(scene)
                assertTrue(expected !in semanticLabels(scene), "Recomposition must not replay old feedback")
                if (outcome == "empty") {
                    val sameTarget = contextItems(scene).first { semanticLabels(it).contains(manga.title) }
                    secondaryClick(scene, sameTarget.boundsInWindow.center)
                    settleContextMenu(scene)
                    pointerClick(scene, "Download all unread chapters")
                    val repeatDeadline = System.nanoTime() + 2_000_000_000L
                    while (expected !in semanticLabels(scene) &&
                        System.nanoTime() < repeatDeadline
                    ) {
                        settleContextMenu(scene)
                    }
                    assertTrue(expected in semanticLabels(scene), "A repeated action must show an identical new bubble")
                }
                assertEquals(1, selectionCount(scene), "Context action must preserve the unrelated selection")
                assertEquals(null, model.state.value.contextMenuManga)
            } finally {
                scene.close()
            }
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `context menu dismisses on escape outside scroll and category change without clearing selection`() = runTest {
        val categories = FakeCategoryRepository().apply {
            insert(Category(1L, "First category", 0L, 0L))
            insert(Category(2L, "Second category", 1L, 0L))
        }
        val items = (1L..80L).map {
            sampleLibraryManga(sampleManga(it, "Context $it", 7L)).copy(categories = listOf(1L, 2L))
        }
        val model = rootModel(items, categories)
        val scene = rootScene(model)
        try {
            settleContextMenu(scene)
            val selected = contextItems(scene).first()
            assertTrue(
                requireNotNull(
                    selected.config[androidx.compose.ui.semantics.SemanticsActions.OnLongClick].action,
                ).invoke(),
            )
            settleContextMenu(scene)
            for (dismissal in listOf("escape", "outside", "scroll", "category")) {
                secondaryClick(scene, contextItems(scene)[1].boundsInWindow.center)
                settleContextMenu(scene)
                assertTrue(model.state.value.contextMenuManga != null)
                when (dismissal) {
                    "escape" -> escapeContextMenu(scene)
                    "outside" -> {
                        scene.sendPointerEvent(
                            PointerEventType.Press,
                            Offset(1f, 1f),
                            button = PointerButton.Primary,
                            buttons = PointerButtons(isPrimaryPressed = true),
                        )
                        scene.sendPointerEvent(PointerEventType.Release, Offset(1f, 1f), button = PointerButton.Primary)
                    }
                    "scroll" -> scene.sendPointerEvent(
                        PointerEventType.Scroll,
                        contextMenuBounds(scene).center,
                        scrollDelta = Offset(0f, 5f),
                    )
                    else -> model.setSelectedCategoryIndex(1)
                }
                settleContextMenu(scene)
                assertEquals(null, model.state.value.contextMenuManga, dismissal)
                assertEquals(1, selectionCount(scene), dismissal)
            }
        } finally {
            scene.close()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun contextItems(scene: ImageComposeScene) = nodes(scene).filter {
        it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun contextMenuBounds(scene: ImageComposeScene): androidx.compose.ui.geometry.Rect {
        val menuItems = nodes(scene).filter {
            it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) &&
                semanticLabels(it).any { label ->
                    label in listOf(
                        MR.strings.desktop_ui_mark_all_read.localized(),
                        MR.strings.remove_from_library.localized(),
                    )
                }
        }
        assertEquals(2, menuItems.size)
        val first = menuItems.first().boundsInWindow
        val last = menuItems.last().boundsInWindow
        return androidx.compose.ui.geometry.Rect(first.left, first.top, last.right, last.bottom)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun secondaryClick(scene: ImageComposeScene, point: Offset) {
        scene.sendPointerEvent(
            PointerEventType.Press,
            point,
            button = PointerButton.Secondary,
            buttons = PointerButtons(isSecondaryPressed = true),
        )
        scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Secondary)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun pointerClick(scene: ImageComposeScene, label: String) {
        val point = nodes(scene).first {
            it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) &&
                semanticLabels(it).contains(label)
        }.boundsInWindow.center
        scene.sendPointerEvent(
            PointerEventType.Press,
            point,
            button = PointerButton.Primary,
            buttons = PointerButtons(isPrimaryPressed = true),
        )
        scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
    }

    private var contextFrameNanos = System.nanoTime()

    private suspend fun settleContextMenu(scene: ImageComposeScene) {
        repeat(4) {
            contextFrameNanos = maxOf(contextFrameNanos + 100_000_000L, System.nanoTime())
            scene.render(contextFrameNanos)
            yield()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun escapeContextMenu(scene: ImageComposeScene) {
        scene.sendKeyEvent(composeKeyEvent(Key.Escape, KeyEventType.KeyDown))
        scene.sendKeyEvent(composeKeyEvent(Key.Escape, KeyEventType.KeyUp))
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
            assertTrue(!semanticLabels(scene).contains("Download all unread chapters"))
            assertTrue(semanticLabels(scene).contains(MR.strings.remove_from_library.localized()))
            secondaryRelease(scene, item)
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `remote manga context actions use the production mark and download model ports`() = runTest {
        val manga = sampleManga(92L, "Remote context", 7L).copy(
            chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC,
        )
        val mangaRepository = FakeMangaRepository().apply {
            seed(manga)
            libraryManga = listOf(sampleLibraryManga(manga).copy(totalChapters = 2L))
        }
        val chapters = FakeChapterRepository().apply {
            seed(
                Chapter.create().copy(
                    id = 921L,
                    mangaId = manga.id,
                    name = "Chapter",
                    url = "/chapter",
                    read = false,
                    chapterNumber = 1.0,
                ),
            )
        }
        chapters.seed(
            Chapter.create().copy(id = 922L, mangaId = manga.id, name = "Second", url = "/second", chapterNumber = 2.0),
        )
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
            sourceManager = mihon.desktop.source.FakeDesktopSourceManager(
                listOf(mihon.desktop.source.FakeHttpSource(7, "en", "Context HTTP source")),
            ),
        )
        val scene = rootScene(model)
        try {
            settleContextMenu(scene)
            var item = nodes(scene).first {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) &&
                    semanticLabels(it).contains(manga.title)
            }
            secondaryClick(scene, item.boundsInWindow.center)
            settleContextMenu(scene)
            pointerClick(scene, MR.strings.desktop_ui_download_next_unread.localized())
            withContext(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) { while (model.state.value.downloadResultMessage == null) delay(10) }
            }
            assertEquals(listOf(manga.id), enqueued.map { it.mangaId })
            assertEquals(listOf(921L), enqueued.map { it.chapterId })

            settleContextMenu(scene)
            item = nodes(scene).first {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) &&
                    semanticLabels(it).contains(manga.title)
            }
            secondaryClick(scene, item.boundsInWindow.center)
            settleContextMenu(scene)
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
            val field = nodes(scene).single {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText)
            }
            assertTrue(field.config[androidx.compose.ui.semantics.SemanticsProperties.Focused])
            assertTrue(
                requireNotNull(
                    field.config[androidx.compose.ui.semantics.SemanticsActions.SetText].action,
                ).invoke(AnnotatedString("Visible")),
            )
            render(scene)
            click(scene, MR.strings.action_reset.localized())
            render(scene)
            assertTrue(nodes(scene).any { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText) })
            assertTrue(!semanticLabels(scene).contains(MR.strings.action_global_search.localized()))
            val clearedField = nodes(scene).single {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText)
            }
            assertTrue(
                requireNotNull(
                    clearedField.config[androidx.compose.ui.semantics.SemanticsActions.SetText].action,
                ).invoke(AnnotatedString("Visible")),
            )
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
            val field = nodes(scene).single {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText)
            }
            assertTrue(
                requireNotNull(
                    field.config[androidx.compose.ui.semantics.SemanticsActions.SetText].action,
                ).invoke(AnnotatedString("Escape")),
            )
            val item = nodes(scene).first {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) &&
                    semanticLabels(it).contains(title)
            }
            assertTrue(
                requireNotNull(item.config[androidx.compose.ui.semantics.SemanticsActions.OnLongClick].action).invoke(),
            )
            render(scene)

            scene.sendKeyEvent(composeKeyEvent(Key.Escape, KeyEventType.KeyDown))
            render(scene)
            assertTrue(nodes(scene).any { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText) })
            assertTrue(semanticLabels(scene).contains(MR.strings.action_filter.localized()))

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
            assertTrue(
                requireNotNull(item.config[androidx.compose.ui.semantics.SemanticsActions.OnLongClick].action).invoke(),
            )
            render(scene)

            assertEquals(1, selectionCount(scene))
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
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) &&
                    semanticLabels(it).contains(title)
            }
            assertTrue(
                requireNotNull(item.config[androidx.compose.ui.semantics.SemanticsActions.OnLongClick].action).invoke(),
            )
            render(scene)

            val labels = semanticLabels(scene)
            assertEquals(1, selectionCount(scene))
            assertEquals(1, labels.count { it == MR.strings.action_select_all.localized() })
            assertEquals(1, labels.count { it == MR.strings.desktop_ui_invert_selection.localized() })
            assertTrue(labels.contains(MR.strings.action_download.localized()))
            assertTrue(labels.contains(MR.strings.action_menu.localized()))
            click(scene, MR.strings.action_menu.localized())
            render(scene)
            assertTrue(semanticLabels(scene).contains(MR.strings.action_delete.localized()))
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
    fun `local empty library filters preserve source NoMatch and inactive conditions allow guide`() = runTest {
        for (kind in listOf("unread", "downloaded", "tracking", "interval")) {
            val node = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
            val preferences = LibraryPreferences(DesktopPreferenceStore(node))
            val sessions = kotlinx.coroutines.flow.MutableStateFlow(setOf(2L))
            preferences.downloadedOnly().set(true)
            when (kind) {
                "unread" -> preferences.filterUnread().set(TriState.ENABLED_IS)
                "downloaded" -> preferences.filterDownloaded().set(TriState.ENABLED_NOT)
                "tracking" -> preferences.filterTracking(2).set(TriState.ENABLED_IS)
                else -> {
                    preferences.filterIntervalCustom().set(TriState.ENABLED_IS)
                    preferences.autoUpdateMangaRestrictions().set(
                        setOf(LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD),
                    )
                }
            }
            val model = LibraryScreenModel(
                getLibraryManga = GetLibraryManga(FakeMangaRepository()),
                getCategories = GetCategories(FakeCategoryRepository()),
                libraryPreferences = preferences,
                trackerSessionProvider = TrackerSessionProvider { sessions },
            )
            val scene = rootScene(model)
            try {
                render(scene)
                assertTrue(
                    semanticLabels(scene).contains(MR.strings.error_no_match.localized()),
                    "$kind is an effective local condition",
                )
                assertTrue(!semanticLabels(scene).contains(MR.strings.getting_started_guide.localized()))
                when (kind) {
                    "unread" -> preferences.filterUnread().set(TriState.DISABLED)
                    "downloaded" -> preferences.filterDownloaded().set(TriState.DISABLED)
                    "tracking" -> sessions.value = emptySet()
                    else -> preferences.autoUpdateMangaRestrictions().set(emptySet())
                }
                render(scene)
                assertTrue(
                    semanticLabels(scene).contains(MR.strings.getting_started_guide.localized()),
                    "inactive $kind must permit the genuine empty-library guide",
                )
                assertTrue(model.state.value.hasActiveFilters, "global downloaded-only remains effective")
                if (kind == "tracking") assertEquals(TriState.ENABLED_IS, preferences.filterTracking(2).get())
                if (kind == "interval") assertEquals(TriState.ENABLED_IS, preferences.filterIntervalCustom().get())
            } finally {
                scene.close()
                node.removeNode()
            }
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
            val downloadedNode = nodes(scene).first {
                it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Role) &&
                    it.config[androidx.compose.ui.semantics.SemanticsProperties.Role] ==
                    androidx.compose.ui.semantics.Role.Checkbox &&
                    semanticLabels(it).contains(MR.strings.label_downloaded.localized())
            }
            assertTrue(downloadedNode.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled))
            assertEquals(
                MR.strings.desktop_ui_filter_include.localized(),
                downloadedNode.config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription],
            )
            assertTrue(
                labels.none {
                    it.startsWith(MR.strings.desktop_ui_global_downloaded_only.localized(Locale.getDefault(), ""))
                },
            )
            val interval = nodes(scene).first {
                it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Role) &&
                    it.config[androidx.compose.ui.semantics.SemanticsProperties.Role] ==
                    androidx.compose.ui.semantics.Role.Checkbox &&
                    semanticLabels(it).contains(MR.strings.desktop_ui_custom_interval.localized())
            }
            assertTrue(interval.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled))
            assertTrue(labels.contains(MR.strings.desktop_library_interval_requires_restriction.localized()))

            click(scene, MR.strings.action_sort.localized())
            render(scene)
            assertTrue(semanticLabels(scene).contains(MR.strings.action_sort_tracker_score.localized()))
            click(scene, MR.strings.action_sort_tracker_score.localized())
            render(scene)
            assertEquals(SortMode.TRACKER_MEAN, model.state.value.sortMode)
            preferences.downloadedOnly().set(false)
            render(scene)
            assertTrue(semanticLabels(scene).contains(MR.strings.desktop_library_unrated.localized()))
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
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { syncPanel } returns null
        }
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
            assertTrue(hiddenLabels.contains(MR.strings.desktop_ui_continue_reading.localized()))

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
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { syncPanel } returns null
        }
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
        sceneWidth: Int = 1_200,
        onNavigator: (Navigator) -> Unit = {},
    ): ImageComposeScene =
        ImageComposeScene(sceneWidth, 900, coroutineContext = kotlinx.coroutines.Dispatchers.Unconfined) {
        }.also { scene ->
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides mockk<DesktopUiDependencies>(relaxed = true) {
                        every { syncPanel } returns null
                    },
                    LocalUriHandler provides uriHandler,
                ) {
                    ProvideLibraryScreenModelFactory(factory = { model }) {
                        Navigator(LibraryRootScreen()) { navigator ->
                            onNavigator(navigator)
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
        val contentDescription = androidx.compose.ui.semantics.SemanticsProperties.ContentDescription
        val descriptions = if (node.config.contains(contentDescription)) {
            node.config[contentDescription]
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

    @OptIn(ExperimentalComposeUiApi::class)
    private fun selectionCount(scene: ImageComposeScene): Int {
        val bar = nodes(scene).single {
            it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" } ==
                "library-selection-top-bar"
        }
        return flatten(bar).filter { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text) }
            .flatMap(::semanticLabels).single().toInt()
    }

    private fun flatten(
        node: androidx.compose.ui.semantics.SemanticsNode,
    ): List<androidx.compose.ui.semantics.SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun semanticLabels(node: androidx.compose.ui.semantics.SemanticsNode): List<String> {
        val text = if (node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)) {
            node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].map { it.text }
        } else {
            emptyList()
        }
        val contentDescription = androidx.compose.ui.semantics.SemanticsProperties.ContentDescription
        val descriptions = if (node.config.contains(contentDescription)) {
            node.config[contentDescription]
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

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `all library layouts continue earliest unread chapter before old synchronized read chapter`() = runTest {
        val node = Preferences.userRoot().node("/mihon-sync-reader-test/${UUID.randomUUID()}")
        val preferences = LibraryPreferences(DesktopPreferenceStore(node))
        preferences.showContinueReadingButton().set(true)
        val manga = sampleManga(81L, "Synced reread", 42L)
        val mangas = FakeMangaRepository().apply {
            seed(manga)
            libraryManga = listOf(sampleLibraryManga(manga).copy(totalChapters = 2, readCount = 1))
        }
        val chapters = FakeChapterRepository().apply {
            seed(Chapter.create().copy(id = 8101, mangaId = 81, url = "/first", lastPageRead = 4, sourceOrder = 0))
            seed(Chapter.create().copy(id = 8102, mangaId = 81, url = "/old-sync", read = true, sourceOrder = 1))
        }
        val snapshot = tachiyomi.domain.reader.model.ReadingSyncSnapshot()
        val progress = tachiyomi.domain.reader.interactor.RecordReadingProgress(
            object : tachiyomi.domain.reader.repository.ReadingProgressRepository {
                override suspend fun record(event: tachiyomi.domain.reader.model.ReadingProgressEvent) = Unit
                override suspend fun resumePosition(mangaId: Long) =
                    tachiyomi.domain.reader.model.ReadingResumePosition(8102, 2, snapshot)
            },
        )
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(mangas),
            getCategories = GetCategories(FakeCategoryRepository()),
            getChaptersByMangaId = GetChaptersByMangaId(chapters),
            libraryPreferences = preferences,
            readingProgress = progress,
        )
        var destination: Screen? = null
        val scene = ImageComposeScene(1200, 900, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides mockk<DesktopUiDependencies>(relaxed = true) {
                        every { syncPanel } returns null
                    },
                ) {
                    ProvideLibraryScreenModelFactory(factory = { model }) {
                        Navigator(LibraryRootScreen()) { navigator ->
                            destination = navigator.lastItem
                            if (navigator.lastItem is LibraryRootScreen) CurrentScreen()
                        }
                    }
                }
            }
            for (mode in listOf(
                SharedLibraryDisplayMode.CompactGrid,
                SharedLibraryDisplayMode.ComfortableGrid,
                SharedLibraryDisplayMode.List,
                SharedLibraryDisplayMode.CoverOnlyGrid,
            )) {
                preferences.displayMode().set(mode)
                render(scene)
                assertTrue(
                    semanticLabels(scene).contains(MR.strings.desktop_ui_continue_reading.localized()),
                    mode.toString(),
                )
            }
            click(scene, MR.strings.desktop_ui_continue_reading.localized())
            render(scene)
            val reader = destination as mihon.desktop.ui.reader.DesktopReaderScreen
            assertEquals(8101L, reader.chapterId)
            assertEquals(4, reader.initialPage)
            assertEquals(null, reader.initialContext().resumeSnapshot)
        } finally {
            scene.close()
            node.removeNode()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `all read library layouts hide ordinary continue despite synchronized resume`() = runTest {
        val node = Preferences.userRoot().node("/mihon-sync-reader-test/${UUID.randomUUID()}")
        val preferences = LibraryPreferences(DesktopPreferenceStore(node))
        preferences.showContinueReadingButton().set(true)
        val manga = sampleManga(82L, "Finished synced manga", 42L)
        val mangas = FakeMangaRepository().apply {
            seed(manga)
            libraryManga = listOf(sampleLibraryManga(manga).copy(totalChapters = 1, readCount = 1))
        }
        val chapters = FakeChapterRepository().apply {
            seed(Chapter.create().copy(id = 8201, mangaId = manga.id, url = "/read", read = true))
        }
        val progress = tachiyomi.domain.reader.interactor.RecordReadingProgress(
            object : tachiyomi.domain.reader.repository.ReadingProgressRepository {
                override suspend fun record(event: tachiyomi.domain.reader.model.ReadingProgressEvent) = Unit
                override suspend fun resumePosition(mangaId: Long) =
                    tachiyomi.domain.reader.model.ReadingResumePosition(
                        8201,
                        2,
                        tachiyomi.domain.reader.model.ReadingSyncSnapshot(),
                    )
            },
        )
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(mangas),
            getCategories = GetCategories(FakeCategoryRepository()),
            getChaptersByMangaId = GetChaptersByMangaId(chapters),
            libraryPreferences = preferences,
            readingProgress = progress,
        )
        val scene = ImageComposeScene(1200, 900, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides mockk<DesktopUiDependencies>(relaxed = true) {
                        every { syncPanel } returns null
                    },
                ) {
                    ProvideLibraryScreenModelFactory(factory = { model }) {
                        Navigator(LibraryRootScreen()) { CurrentScreen() }
                    }
                }
            }
            for (mode in listOf(
                SharedLibraryDisplayMode.CompactGrid,
                SharedLibraryDisplayMode.ComfortableGrid,
                SharedLibraryDisplayMode.List,
                SharedLibraryDisplayMode.CoverOnlyGrid,
            )) {
                preferences.displayMode().set(mode)
                render(scene)
                assertTrue(
                    !semanticLabels(scene).contains(MR.strings.desktop_ui_continue_reading.localized()),
                    mode.toString(),
                )
            }
        } finally {
            scene.close()
            node.removeNode()
        }
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
        val factory = events.declaredMethods.single {
            it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default")
        }
        val native = factory.invoke(null, key.keyCode, eventType, 0, false, false, false, false, null)
        return androidx.compose.ui.input.key.KeyEvent(native)
    }
}
