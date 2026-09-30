package mihon.desktop.ui.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.UUID
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.SortMode
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.download.DownloadItem
import mihon.desktop.domain.fakes.FakeCategoryRepository
import mihon.desktop.library.LibraryScreenModelFactory
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.test.http.LibraryMangaTestModeController
import mihon.desktop.ui.migration.LibraryBatchMigrationConfigScreen
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.interactor.LibraryFilter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Isolated
class LibraryCategoryBehaviorTest {
    @Test
    @OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
    fun `root mark read deletes only newly read chapter downloads through production factory`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore(preferencesNode), startDownloadWorker = false)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var scene: ImageComposeScene? = null
        var model: LibraryScreenModel? = null
        try {
            val repository = Injekt.get<MangaRepository>()
            val target = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 7, url = "/mark-target", title = "Mark target")),
            ).single()
            val unrelated = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 7, url = "/mark-other", title = "Mark other")),
            ).single()
            repository.updateMembershipsAtomically(
                listOf(
                    LibraryMembershipUpdate(target.id, true, 1, emptyList()),
                    LibraryMembershipUpdate(unrelated.id, true, 2, emptyList()),
                ),
            )
            val chapters = Injekt.get<ChapterRepository>().addAll(
                listOf(
                    Chapter.create().copy(mangaId = target.id, url = "/target-unread", name = "Target unread"),
                    Chapter.create().copy(mangaId = target.id, url = "/target-read", name = "Target read", read = true),
                    Chapter.create().copy(mangaId = unrelated.id, url = "/other-unread", name = "Other unread"),
                ),
            )
            val targetUnread = chapters.single { it.name == "Target unread" }
            val targetRead = chapters.single { it.name == "Target read" }
            val otherUnread = chapters.single { it.name == "Other unread" }
            Injekt.get<DownloadPreferences>().removeAfterMarkedAsRead().set(true)
            val manager = Injekt.get<DesktopDownloadManager>()
            manager.enqueue(
                DownloadItem(target.source, target.title, targetUnread.name, targetUnread.id, mangaId = target.id),
            )
            assertTrue(manager.transition(targetUnread.id, mihon.domain.download.DownloadQueueStatus.DOWNLOADING))
            assertTrue(manager.transition(targetUnread.id, mihon.domain.download.DownloadQueueStatus.ERROR))
            val provider = Injekt.get<DesktopDownloadProvider>()
            listOf(targetUnread to target, targetRead to target, otherUnread to unrelated).forEach { (chapter, manga) ->
                provider.chapterDownloadDir(manga.source, manga.title, chapter.name).apply {
                    mkdirs()
                    ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", resolve("page.png"))
                }
            }
            val rootModel = LibraryScreenModelFactory.create().also { model = it }
            scene = ImageComposeScene(1_400, 900, coroutineContext = coroutineContext) {}
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    ProvideLibraryScreenModelFactory(factory = { rootModel }) {
                        Navigator(LibraryRootScreen()) { CurrentScreen() }
                    }
                }
            }
            withTimeout(5_000) {
                while (rootModel.state.value.allItems.size != 2) {
                    render(scene)
                    delay(10)
                }
            }
            longClick(scene, target.title)
            render(scene)
            click(scene, MR.strings.desktop_ui_mark_read.localized())
            withTimeout(5_000) {
                while (
                    !Injekt.get<ChapterRepository>().getChapterById(targetUnread.id)!!.read ||
                    manager.queue.value.any { it.chapterId == targetUnread.id } ||
                    provider.isChapterDownloaded(target.source, target.title, targetUnread.name)
                ) {
                    render(scene)
                    delay(10)
                }
            }
            assertFalse(provider.isChapterDownloaded(target.source, target.title, targetUnread.name))
            assertTrue(provider.isChapterDownloaded(target.source, target.title, targetRead.name))
            assertTrue(provider.isChapterDownloaded(unrelated.source, unrelated.title, otherUnread.name))
        } finally {
            val modelJob = model?.screenModelScope?.coroutineContext?.get(Job)
            scene?.close()
            model?.onDispose()
            modelJob?.cancelAndJoin()
            context.closeAndJoin()
            Dispatchers.resetMain()
            preferencesNode.removeNode()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
    fun `root refresh scopes independently of search and survives navigation while preventing duplicates`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val updated = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val context = initDesktopDIForTest(
            tempDir,
            DesktopPreferenceStore(preferencesNode),
            startDownloadWorker = false,
            updateManga = { manga ->
                updated += manga.id
                if (entered.complete(Unit)) release.await()
                mihon.desktop.domain.LibraryUpdateChecker.UpdateResult(0)
            },
        )
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var scene: ImageComposeScene? = null
        var model: LibraryScreenModel? = null
        var destination: Screen? = null
        try {
            val categories = Injekt.get<CategoryRepository>()
            categories.insert(Category(1, "Refresh A", 0, 0))
            categories.insert(Category(2, "Refresh B", 1, 0))
            categories.insert(Category(3, "Refresh empty", 2, 0))
            val repository = Injekt.get<MangaRepository>()
            val mangas = repository.insertNetworkManga(
                listOf(
                    Manga.create().copy(source = 0, url = "/target-a", title = "Target A", initialized = true),
                    Manga.create().copy(source = 0, url = "/hidden-a", title = "Hidden A", initialized = true),
                    Manga.create().copy(source = 0, url = "/target-b", title = "Target B", initialized = true),
                    Manga.create().copy(source = 0, url = "/default", title = "Default target", initialized = true),
                ),
            )
            repository.updateMembershipsAtomically(
                listOf(
                    LibraryMembershipUpdate(mangas[0].id, true, 1, listOf(1)),
                    LibraryMembershipUpdate(mangas[1].id, true, 2, listOf(1)),
                    LibraryMembershipUpdate(mangas[2].id, true, 3, listOf(2)),
                    LibraryMembershipUpdate(mangas[3].id, true, 4, emptyList()),
                ),
            )
            Injekt.get<DesktopAppPreferences>().apply {
                updateCategoryIncludes.set("2")
                updateCategoryExcludes.set("1")
            }
            scene = ImageComposeScene(1_400, 900, coroutineContext = coroutineContext) {}
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    ProvideLibraryScreenModelFactory(
                        factory = { LibraryScreenModelFactory.create().also { model = it } },
                    ) {
                        Navigator(LibraryRootScreen()) { navigator ->
                            destination = navigator.lastItem
                            CurrentScreen()
                        }
                    }
                }
            }
            withTimeout(5_000) {
                while (
                    model?.state?.value?.allItems?.size != 4 ||
                    model?.state?.value?.categories?.map { it.id }?.containsAll(listOf(1L, 2L, 3L)) != true
                ) {
                    render(scene)
                    delay(10)
                }
            }
            val rootModel = requireNotNull(model)
            rootModel.setSelectedCategoryIndex(rootModel.state.value.categories.indexOfFirst { it.id == 1L })
            rootModel.setSearchQuery("Target")
            render(scene)
            click(scene, MR.strings.check_for_updates.localized())
            render(scene)
            click(scene, MR.strings.action_update_library.localized())
            withTimeout(5_000) { entered.await() }
            click(scene, MR.strings.check_for_updates.localized())
            assertEquals(1, updated.size)
            click(scene, MR.strings.desktop_ui_random_manga.localized())
            render(scene)
            assertEquals(mangas[0].id, (destination as MangaDetailScreen).mangaId)
            click(scene, MR.strings.action_bar_up_description.localized())
            render(scene)
            assertTrue(destination is LibraryRootScreen)
            val returnedModel = requireNotNull(model)
            assertEquals("Target", returnedModel.state.value.searchQuery)
            assertEquals(1L, returnedModel.state.value.categories[returnedModel.state.value.selectedCategoryIndex].id)
            assertTrue(returnedModel.state.value.isUpdating)
            release.complete(Unit)
            withTimeout(5_000) {
                while (returnedModel.state.value.isUpdating) {
                    render(scene)
                    delay(10)
                }
            }
            render(scene)
            assertEquals(setOf(mangas[0].id, mangas[1].id), updated.toSet())

            Injekt.get<DesktopAppPreferences>().apply {
                updateCategoryIncludes.set("0")
                updateCategoryExcludes.set("2")
            }
            val beforeAll = updated.size
            click(scene, MR.strings.check_for_updates.localized())
            render(scene)
            click(scene, MR.strings.ext_update_all.localized())
            withTimeout(5_000) {
                while (updated.size == beforeAll || returnedModel.state.value.isUpdating) {
                    render(scene)
                    delay(10)
                }
            }
            assertEquals(listOf(mangas[3].id), updated.drop(beforeAll))

            returnedModel.setSelectedCategoryIndex(returnedModel.state.value.categories.indexOfFirst { it.id == 3L })
            returnedModel.setSearchQuery(null)
            render(scene)
            click(scene, MR.strings.desktop_ui_random_manga.localized())
            render(scene)
            assertTrue(destination is LibraryRootScreen)
            assertEquals(
                MR.strings.desktop_ui_no_manga_match_your_filters.localized(),
                returnedModel.state.value.operationFeedback,
            )
        } finally {
            release.complete(Unit)
            val modelJob = model?.screenModelScope?.coroutineContext?.get(Job)
            scene?.close()
            model?.onDispose()
            modelJob?.cancelAndJoin()
            context.closeAndJoin()
            Dispatchers.resetMain()
            preferencesNode.removeNode()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
    fun `factory queue identity changes refresh visible download counts`(@TempDir tempDir: File) = runBlocking {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore(preferencesNode), startDownloadWorker = false)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val server = MockWebServer().apply { start() }
        var scene: ImageComposeScene? = null
        var model: LibraryScreenModel? = null
        try {
            val repository = Injekt.get<MangaRepository>()
            val manga = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 7L, url = "/queue-refresh", title = "Queue refresh")),
            ).single()
            repository.updateMembershipsAtomically(
                listOf(LibraryMembershipUpdate(manga.id, true, 1L, emptyList())),
            )
            Injekt.get<LibraryPreferences>().apply {
                downloadBadge().set(true)
                unreadBadge().set(false)
            }
            val chapter = Injekt.get<ChapterRepository>().addAll(
                listOf(Chapter.create().copy(mangaId = manga.id, url = "/queue-refresh/1", name = "Chapter")),
            ).single()
            val rootModel = LibraryScreenModelFactory.create().also { model = it }
            scene = ImageComposeScene(1_400, 900, coroutineContext = coroutineContext) {}
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    ProvideLibraryScreenModelFactory(factory = { rootModel }) {
                        Navigator(LibraryRootScreen()) { CurrentScreen() }
                    }
                }
            }
            withTimeout(5_000) {
                while (rootModel.state.value.allItems.none { it.id == manga.id }) {
                    render(scene)
                    delay(10)
                }
            }
            click(scene, MR.strings.action_filter.localized())
            render(scene)
            click(
                scene,
                MR.strings.desktop_ui_filter_value.localized(
                    Locale.getDefault(),
                    MR.strings.label_downloaded.localized(),
                    tachiyomi.core.common.preference.TriState.DISABLED.label(),
                ),
            )
            render(scene)
            assertFalse(nodes(scene).flatMap { it.semanticLabels() }.contains(manga.title))

            val provider = Injekt.get<DesktopDownloadProvider>()
            val manager = Injekt.get<DesktopDownloadManager>()
            val png = ByteArrayOutputStream().use { output ->
                ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output)
                output.toByteArray()
            }
            server.enqueue(
                MockResponse.Builder()
                    .setHeader("Content-Type", "image/png")
                    .body(Buffer().write(png))
                    .build(),
            )
            manager.enqueue(
                DownloadItem(
                    manga.source,
                    manga.title,
                    chapter.name,
                    chapter.id,
                    mangaId = manga.id,
                    chapterUrl = chapter.url,
                    pageUrls = listOf(server.url("/page.png").toString()),
                ),
            )
            manager.start()
            withTimeout(5_000) {
                while (manager.queue.value.isNotEmpty() || provider.downloadedChapterCount(manga.source, manga.title) != 1) {
                    render(scene)
                    delay(10)
                }
            }
            withTimeout(5_000) {
                while (!nodes(scene).flatMap { it.semanticLabels() }.contains(manga.title)) {
                    render(scene)
                    delay(10)
                }
            }
            val visibleLabels = nodes(scene).flatMap { it.semanticLabels() }
            assertTrue(visibleLabels.contains(manga.title))
            assertTrue(visibleLabels.contains("1"))
            assertTrue(visibleLabels.contains(MR.strings.label_downloaded.localized()))

            longClick(scene, manga.title)
            render(scene)
            click(scene, MR.strings.action_remove.localized())
            render(scene)
            clickToggle(scene, ToggleableState.Off, index = 1)
            click(scene, MR.strings.action_ok.localized())
            withTimeout(5_000) {
                while (
                    provider.hasMangaDownloads(manga.source, manga.title) ||
                    nodes(scene).flatMap { it.semanticLabels() }.contains(manga.title)
                ) {
                    render(scene)
                    delay(10)
                }
            }
            assertFalse(nodes(scene).flatMap { it.semanticLabels() }.contains(manga.title))
            assertTrue(repository.getMangaById(manga.id).favorite)
        } finally {
            val modelJob = model?.screenModelScope?.coroutineContext?.get(Job)
            scene?.close()
            model?.onDispose()
            modelJob?.cancelAndJoin()
            server.close()
            context.closeAndJoin()
            Dispatchers.resetMain()
            preferencesNode.removeNode()
        }
    }

    @Test
    fun `factory reports provider deletion failure instead of claiming success`(@TempDir tempDir: File) = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore(preferencesNode), startDownloadWorker = false)
        var lockedFile: FileInputStream? = null
        try {
            val repository = Injekt.get<MangaRepository>()
            val manga = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 7L, url = "/locked-delete", title = "Locked delete")),
            ).single()
            repository.updateMembershipsAtomically(
                listOf(LibraryMembershipUpdate(manga.id, true, 1L, emptyList())),
            )
            val provider = Injekt.get<DesktopDownloadProvider>()
            val page = provider.chapterDownloadDir(manga.source, manga.title, "Chapter").apply { mkdirs() }
                .resolve("page.png")
            ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", page)
            lockedFile = FileInputStream(page)
            val model = LibraryScreenModelFactory.create().apply { setAllItems(listOf(libraryItem(manga))) }

            model.removeFromLibrary(listOf(manga.id), deleteDownloads = true, removeFromLibrary = false)

            assertTrue(page.exists())
            assertEquals(
                MR.strings.desktop_ui_items_updated_failed.localized(Locale.getDefault(), 0, 1),
                model.state.value.operationFeedback,
            )
        } finally {
            lockedFile?.close()
            context.closeAndJoin()
            preferencesNode.removeNode()
        }
    }

    @Test
    fun `factory download deletion retires only matching legacy and identified queue items`(@TempDir tempDir: File) = runBlocking {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore(preferencesNode), startDownloadWorker = false)
        try {
            val mangaRepository = Injekt.get<MangaRepository>()
            val target = mangaRepository.insertNetworkManga(
                listOf(Manga.create().copy(source = 7L, url = "/target-delete", title = "Shared title")),
            ).single()
            val unrelated = mangaRepository.insertNetworkManga(
                listOf(Manga.create().copy(source = 8L, url = "/unrelated-delete", title = "Shared title")),
            ).single()
            mangaRepository.updateMembershipsAtomically(
                listOf(
                    LibraryMembershipUpdate(target.id, true, 1L, emptyList()),
                    LibraryMembershipUpdate(unrelated.id, true, 2L, emptyList()),
                ),
            )
            val targetChapterInput = Chapter.create().copy(
                id = 701L,
                mangaId = target.id,
                url = "/target-chapter",
                name = "Target chapter",
            )
            val unrelatedChapterInput = Chapter.create().copy(
                id = 801L,
                mangaId = unrelated.id,
                url = "/unrelated-chapter",
                name = "Unrelated chapter",
            )
            val insertedChapters = Injekt.get<ChapterRepository>().addAll(listOf(targetChapterInput, unrelatedChapterInput))
            val targetChapter = insertedChapters.single { it.mangaId == target.id }
            val unrelatedChapter = insertedChapters.single { it.mangaId == unrelated.id }
            val manager = Injekt.get<DesktopDownloadManager>()
            manager.enqueue(
                DownloadItem(
                    sourceId = target.source,
                    mangaTitle = target.title,
                    chapterName = targetChapter.name,
                    chapterId = targetChapter.id,
                    mangaId = 0L,
                    chapterUrl = targetChapter.url,
                ),
            )
            manager.enqueue(
                DownloadItem(
                    sourceId = unrelated.source,
                    mangaTitle = unrelated.title,
                    chapterName = unrelatedChapter.name,
                    chapterId = unrelatedChapter.id,
                    mangaId = unrelated.id,
                    chapterUrl = unrelatedChapter.url,
                ),
            )
            val provider = Injekt.get<DesktopDownloadProvider>()
            listOf(target to targetChapter, unrelated to unrelatedChapter).forEach { (manga, chapter) ->
                provider.chapterDownloadDir(manga.source, manga.title, chapter.name).apply {
                    mkdirs()
                    ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", resolve("page.png"))
                }
            }
            val model = LibraryScreenModelFactory.create().apply {
                setAllItems(listOf(libraryItem(target), libraryItem(unrelated)))
            }
            assertEquals(1L, model.state.value.downloadCountsByManga[target.id])
            assertEquals(
                listOf(targetChapter.id),
                Injekt.get<GetChaptersByMangaId>().awaitOrThrow(target.id).map { it.id },
            )

            model.removeFromLibrary(listOf(target.id), deleteDownloads = true, removeFromLibrary = false)

            assertEquals(listOf(unrelatedChapter.id), manager.queue.value.map { it.chapterId })
            assertFalse(provider.hasMangaDownloads(target.source, target.title))
            assertTrue(provider.hasMangaDownloads(unrelated.source, unrelated.title))
            assertEquals(0L, model.state.value.downloadCountsByManga[target.id])
            assertEquals(1L, model.state.value.downloadCountsByManga[unrelated.id])
        } finally {
            context.closeAndJoin()
            preferencesNode.removeNode()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `removal dialog hides download deletion for mixed local selection`() = runBlocking {
        val items = listOf(
            libraryItem(Manga.create().copy(id = 1L, source = 7L, title = "Remote")),
            libraryItem(Manga.create().copy(id = 2L, source = 0L, title = "Local")),
        )
        val scene = ImageComposeScene(900, 600, coroutineContext = coroutineContext) {}
        try {
            scene.setContent { LibraryRemovalDialog(items, onDismiss = {}, onConfirm = { _, _ -> }) }
            render(scene)
            assertEquals(1, toggleNodes(scene).size)
            assertFalse(nodes(scene).flatMap { it.semanticLabels() }.contains(MR.strings.downloaded_chapters.localized()))
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
    fun `root migration keeps remote and local selection across category actions`(@TempDir tempDir: File) = runBlocking {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore(preferencesNode), startDownloadWorker = false)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var scene: ImageComposeScene? = null
        var model: LibraryScreenModel? = null
        try {
            val categoryRepository = Injekt.get<CategoryRepository>()
            categoryRepository.insert(Category(1L, "Migration A", 0L, 0L))
            categoryRepository.insert(Category(2L, "Migration B", 1L, 0L))
            val repository = Injekt.get<MangaRepository>()
            val remote = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 7L, url = "/migration-remote", title = "Migration remote")),
            ).single()
            val local = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 0L, url = "/migration-local", title = "Migration local")),
            ).single()
            repository.updateMembershipsAtomically(
                listOf(
                    LibraryMembershipUpdate(remote.id, true, 1L, listOf(1L)),
                    LibraryMembershipUpdate(local.id, true, 2L, listOf(2L)),
                ),
            )
            val provider = Injekt.get<DesktopDownloadProvider>()
            listOf(remote, local).forEach {
                provider.chapterDownloadDir(it.source, it.title, "Chapter 1").apply {
                    mkdirs()
                    ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", resolve("page.png"))
                }
            }
            val rootModel = LibraryScreenModelFactory.create().also { model = it }
            var destination: Screen? = null
            scene = ImageComposeScene(1_400, 900, coroutineContext = coroutineContext) {}
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    ProvideLibraryScreenModelFactory(factory = { rootModel }) {
                        Navigator(LibraryRootScreen()) { navigator ->
                            destination = navigator.lastItem
                            CurrentScreen()
                        }
                    }
                }
            }
            withTimeout(5_000) {
                while (rootModel.state.value.allItems.size != 2 || rootModel.state.value.categories.size != 2) {
                    render(scene)
                    delay(10)
                }
            }
            rootModel.setSelectedCategoryIndex(rootModel.state.value.categories.indexOfFirst { it.id == 1L })
            render(scene)
            longClick(scene, remote.title)
            render(scene)
            click(scene, "Migration B")
            render(scene)
            click(scene, MR.strings.action_select_all.localized())
            render(scene)
            assertTrue(
                nodes(scene).flatMap { it.semanticLabels() }
                    .contains(MR.strings.desktop_ui_selected_count.localized(Locale.getDefault(), 2)),
            )
            click(scene, MR.strings.desktop_ui_invert_selection.localized())
            render(scene)
            assertTrue(
                nodes(scene).flatMap { it.semanticLabels() }
                    .contains(MR.strings.desktop_ui_selected_count.localized(Locale.getDefault(), 1)),
            )
            click(scene, MR.strings.action_select_all.localized())
            render(scene)
            assertTrue(
                nodes(scene).flatMap { it.semanticLabels() }
                    .contains(MR.strings.desktop_ui_selected_count.localized(Locale.getDefault(), 2)),
            )
            click(scene, MR.strings.action_remove.localized())
            render(scene)
            assertEquals(1, toggleNodes(scene).size)
            assertFalse(nodes(scene).flatMap { it.semanticLabels() }.contains(MR.strings.downloaded_chapters.localized()))
            clickToggle(scene, ToggleableState.Off)
            render(scene)
            scene.sendKeyEvent(composeKeyEvent(Key.Escape, KeyEventType.KeyDown))
            render(scene)
            assertTrue(
                nodes(scene).flatMap { it.semanticLabels() }
                    .contains(MR.strings.desktop_ui_selected_count.localized(Locale.getDefault(), 2)),
            )
            assertTrue(repository.getMangaById(remote.id).favorite)
            assertTrue(repository.getMangaById(local.id).favorite)
            assertTrue(provider.hasMangaDownloads(remote.source, remote.title))
            assertTrue(provider.hasMangaDownloads(local.source, local.title))
            click(scene, MR.strings.action_migrate.localized())
            render(scene)

            val config = destination as LibraryBatchMigrationConfigScreen
            assertEquals(listOf(remote.id, local.id).sorted(), config.selectedManga.map { it.mangaId }.sorted())
            click(scene, MR.strings.action_cancel.localized())
            render(scene)
            assertTrue(destination is LibraryRootScreen)
            assertTrue(DesktopUiDependencies.fromInjekt().batchMigrationController.queues.value.isEmpty())
        } finally {
            val modelJob = model?.screenModelScope?.coroutineContext?.get(Job)
            scene?.close()
            model?.onDispose()
            modelJob?.cancelAndJoin()
            context.closeAndJoin()
            Dispatchers.resetMain()
            preferencesNode.removeNode()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
    fun `root removal confirmation applies remove download and combined choices`(@TempDir tempDir: File) = runBlocking {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore(preferencesNode), startDownloadWorker = false)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var scene: ImageComposeScene? = null
        var model: LibraryScreenModel? = null
        try {
            val repository = Injekt.get<MangaRepository>()
            val mangas = listOf("Remove only", "Downloads only", "Remove and downloads").mapIndexed { index, title ->
                repository.insertNetworkManga(
                    listOf(Manga.create().copy(source = 7L, url = "/remove-$index", title = title)),
                ).single()
            }
            repository.updateMembershipsAtomically(
                mangas.mapIndexed { index, manga -> LibraryMembershipUpdate(manga.id, true, index.toLong(), emptyList()) },
            )
            val provider = Injekt.get<DesktopDownloadProvider>()
            mangas.forEach {
                provider.chapterDownloadDir(it.source, it.title, "Chapter 1").apply {
                    mkdirs()
                    ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", resolve("page.png"))
                }
            }
            val rootModel = LibraryScreenModelFactory.create().also { model = it }
            scene = ImageComposeScene(1_400, 900, coroutineContext = coroutineContext) {}
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    ProvideLibraryScreenModelFactory(factory = { rootModel }) {
                        Navigator(LibraryRootScreen()) { CurrentScreen() }
                    }
                }
            }
            withTimeout(5_000) {
                while (rootModel.state.value.allItems.size != 3) {
                    render(scene)
                    delay(10)
                }
            }
            assertTrue(provider.hasMangaDownloads(mangas[0].source, mangas[0].title))

            suspend fun openRemoval(title: String) {
                render(scene)
                longClick(scene, title)
                render(scene)
                click(scene, MR.strings.action_remove.localized())
                render(scene)
            }

            suspend fun openContextRemoval(title: String) {
                render(scene)
                val item = nodes(scene).first {
                    it.config.contains(SemanticsActions.OnLongClick) && it.semanticLabels().contains(title)
                }
                val center = item.boundsInRoot.center
                scene.sendPointerEvent(
                    PointerEventType.Press,
                    Offset(center.x, center.y),
                    button = PointerButton.Secondary,
                    buttons = PointerButtons(isSecondaryPressed = true),
                )
                render(scene)
                click(scene, MR.strings.remove_from_library.localized())
                render(scene)
            }

            openContextRemoval(mangas[0].title)
            assertEquals(2, toggleNodes(scene).size)
            val disabledOk = nodes(scene).first { MR.strings.action_ok.localized() in it.semanticLabels() }
            assertTrue(disabledOk.config.contains(SemanticsProperties.Disabled))
            click(scene, MR.strings.action_cancel.localized())
            render(scene)
            assertTrue(repository.getMangaById(mangas[0].id).favorite)
            assertTrue(provider.hasMangaDownloads(mangas[0].source, mangas[0].title))

            openContextRemoval(mangas[0].title)
            val late = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 7L, url = "/late-removal", title = "Late removal bystander")),
            ).single()
            repository.updateMembershipsAtomically(
                listOf(LibraryMembershipUpdate(late.id, true, 99L, emptyList())),
            )
            provider.chapterDownloadDir(late.source, late.title, "Chapter 1").apply {
                mkdirs()
                ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", resolve("page.png"))
            }
            withTimeout(5_000) {
                while (rootModel.state.value.allItems.none { it.id == late.id }) {
                    render(scene)
                    delay(10)
                }
            }
            clickToggle(scene, ToggleableState.Off, index = 0)
            click(scene, MR.strings.action_ok.localized())
            withTimeout(5_000) { while (repository.getMangaById(mangas[0].id).favorite) delay(10) }
            assertTrue(provider.hasMangaDownloads(mangas[0].source, mangas[0].title))
            assertTrue(repository.getMangaById(late.id).favorite)
            assertTrue(provider.hasMangaDownloads(late.source, late.title))

            openRemoval(mangas[1].title)
            clickToggle(scene, ToggleableState.Off, index = 1)
            click(scene, MR.strings.action_ok.localized())
            withTimeout(5_000) {
                while (provider.hasMangaDownloads(mangas[1].source, mangas[1].title)) delay(10)
            }
            assertTrue(repository.getMangaById(mangas[1].id).favorite)
            assertEquals(0L, rootModel.state.value.downloadCountsByManga[mangas[1].id])
            rootModel.setFilter(LibraryFilter(downloaded = tachiyomi.core.common.preference.TriState.ENABLED_IS))
            render(scene)
            assertFalse(nodes(scene).flatMap { it.semanticLabels() }.contains(mangas[1].title))
            assertTrue(nodes(scene).flatMap { it.semanticLabels() }.contains(mangas[2].title))
            rootModel.setFilter(LibraryFilter())

            openRemoval(mangas[2].title)
            clickToggle(scene, ToggleableState.Off, index = 0)
            render(scene)
            clickToggle(scene, ToggleableState.Off, index = 0)
            click(scene, MR.strings.action_ok.localized())
            withTimeout(5_000) {
                while (
                    repository.getMangaById(mangas[2].id).favorite ||
                    provider.hasMangaDownloads(mangas[2].source, mangas[2].title)
                ) {
                    delay(10)
                }
            }
            assertTrue(provider.hasMangaDownloads(mangas[0].source, mangas[0].title))
            assertTrue(repository.getMangaById(late.id).favorite)
            assertTrue(provider.hasMangaDownloads(late.source, late.title))
        } finally {
            val modelJob = model?.screenModelScope?.coroutineContext?.get(Job)
            scene?.close()
            model?.onDispose()
            modelJob?.cancelAndJoin()
            context.closeAndJoin()
            Dispatchers.resetMain()
            preferencesNode.removeNode()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
    fun `root category transaction freezes selection and persists mixed assignments`(@TempDir tempDir: File) = runBlocking {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(
            tempDir,
            DesktopPreferenceStore(preferencesNode),
            startDownloadWorker = false,
        )
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var scene: ImageComposeScene? = null
        var model: LibraryScreenModel? = null
        var navigator: Navigator? = null
        try {
            val categoryRepository = Injekt.get<CategoryRepository>()
            categoryRepository.insert(Category(1L, "A", 0L, 0L))
            categoryRepository.insert(Category(2L, "B", 1L, 0L))
            categoryRepository.insert(Category(3L, "C", 2L, 0L))
            val mangaRepository = Injekt.get<MangaRepository>()
            val first = mangaRepository.insertNetworkManga(
                listOf(Manga.create().copy(source = 1L, url = "/first", title = "First category target")),
            ).single()
            val second = mangaRepository.insertNetworkManga(
                listOf(Manga.create().copy(source = 1L, url = "/second", title = "Second category target")),
            ).single()
            mangaRepository.updateMembershipsAtomically(
                listOf(
                    LibraryMembershipUpdate(first.id, true, 1L, listOf(1L)),
                    LibraryMembershipUpdate(second.id, true, 2L, listOf(1L, 2L)),
                ),
            )
            val rootModel = LibraryScreenModelFactory.create().also { model = it }
            var initialOwner = true
            var childModel: LibraryScreenModel? = null
            scene = ImageComposeScene(1_400, 900, coroutineContext = coroutineContext) {}
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    ProvideLibraryScreenModelFactory(factory = {
                        if (initialOwner) {
                            initialOwner = false
                            rootModel
                        } else {
                            LibraryScreenModelFactory.create().also { childModel = it }
                        }
                    }) {
                        Navigator(LibraryRootScreen()) {
                            navigator = it
                            CurrentScreen()
                        }
                    }
                }
            }
            withTimeout(5_000) {
                while (
                    rootModel.state.value.allItems.size != 2 ||
                    !rootModel.state.value.allCategories.map { it.id }.containsAll(listOf(1L, 2L, 3L)) ||
                    !rootModel.state.value.categories.map { it.id }.containsAll(listOf(1L, 2L, 3L))
                ) {
                    render(scene)
                    delay(10)
                }
            }
            rootModel.setSelectedCategoryIndex(rootModel.state.value.categories.indexOfFirst { it.id == 1L })
            render(scene)

            longClick(scene, first.title)
            render(scene)
            click(scene, second.title)
            render(scene)
            click(scene, MR.strings.categories.localized())
            withTimeout(5_000) {
                while (toggleNodes(scene).size != 3) {
                    render(scene)
                    delay(10)
                }
            }
            assertEquals(1, toggleNodes(scene).count { it.config[SemanticsProperties.ToggleableState] == ToggleableState.On })
            assertEquals(
                1,
                toggleNodes(scene).count { it.config[SemanticsProperties.ToggleableState] == ToggleableState.Indeterminate },
            )
            assertEquals(1, toggleNodes(scene).count { it.config[SemanticsProperties.ToggleableState] == ToggleableState.Off })
            clickToggle(scene, ToggleableState.Off)
            categoryRepository.insert(Category(4L, "Late category", 3L, 0L))
            withTimeout(5_000) {
                while (rootModel.state.value.allCategories.none { it.id == 4L }) {
                    render(scene)
                    delay(10)
                }
            }
            render(scene)
            assertEquals(3, toggleNodes(scene).size)
            click(scene, MR.strings.action_cancel.localized())
            assertEquals(setOf(1L), rootModel.categoryIdsForManga(first.id))
            assertEquals(setOf(1L, 2L), rootModel.categoryIdsForManga(second.id))
            render(scene)
            click(scene, MR.strings.categories.localized())
            withTimeout(5_000) {
                while (toggleNodes(scene).size != 4) {
                    render(scene)
                    delay(10)
                }
            }
            clickToggle(scene, ToggleableState.Off)
            click(scene, MR.strings.action_ok.localized())

            withTimeout(5_000) {
                while (
                    rootModel.categoryIdsForManga(first.id) != setOf(1L, 3L) ||
                    rootModel.categoryIdsForManga(second.id) != setOf(1L, 2L, 3L)
                ) {
                    delay(10)
                }
            }
            render(scene)
            assertTrue(nodes(scene).flatMap { it.semanticLabels() }.contains(MR.strings.action_sort.localized()))

            longClick(scene, first.title)
            render(scene)
            click(scene, MR.strings.categories.localized())
            withTimeout(5_000) {
                while (toggleNodes(scene).size != 4) {
                    render(scene)
                    delay(10)
                }
            }
            click(scene, MR.strings.action_edit_categories.localized())
            render(scene)
            assertTrue(navigator?.lastItem is CategoryManagementScreen)
            assertTrue(childModel != null && childModel !== rootModel)
            assertTrue(nodes(scene).flatMap { it.semanticLabels() }.contains(MR.strings.action_edit_categories.localized()))
            assertFalse(nodes(scene).flatMap { it.semanticLabels() }.contains(MR.strings.action_sort.localized()))

            click(scene, MR.strings.action_delete.localized())
            render(scene)
            click(scene, MR.strings.action_cancel.localized())
            assertTrue(categoryRepository.get(1L) != null)
            render(scene)
            click(scene, MR.strings.action_delete.localized())
            render(scene)
            clickLast(scene, MR.strings.action_delete.localized())
            withTimeout(5_000) { while (categoryRepository.get(1L) != null) delay(10) }
            assertEquals(setOf(3L), rootModel.categoryIdsForManga(first.id))
            assertEquals(setOf(2L, 3L), rootModel.categoryIdsForManga(second.id))
            render(scene)
            click(scene, MR.strings.action_bar_up_description.localized())
            render(scene)
            assertTrue(navigator?.lastItem is LibraryRootScreen)
            assertTrue(rootModel.screenModelScope.coroutineContext[Job]?.isActive == true)
            assertTrue(nodes(scene).flatMap { it.semanticLabels() }.contains(MR.strings.action_sort.localized()))
        } finally {
            val modelJob = model?.screenModelScope?.coroutineContext?.get(Job)
            scene?.close()
            model?.onDispose()
            modelJob?.cancelAndJoin()
            context.closeAndJoin()
            Dispatchers.resetMain()
            preferencesNode.removeNode()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `batch category dialog freezes inputs and cycles mixed through none all and mixed`() = runBlocking {
        val categoryA = Category(1L, "A", 0L, 0L)
        val categoryB = Category(2L, "B", 1L, 0L)
        var categories by mutableStateOf(listOf(categoryA, categoryB))
        var selectedIds by mutableStateOf(listOf(10L, 20L))
        val memberships = mapOf(10L to setOf(1L), 20L to setOf(1L, 2L))
        val deltas = mutableListOf<LibraryCategoryDelta>()
        val scene = ImageComposeScene(900, 600, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                BatchCategoryDialog(
                    categories = categories,
                    selectedMangaIds = selectedIds,
                    loadCategoryIds = { memberships.getValue(it) },
                    onConfirm = deltas::add,
                    onDismiss = {},
                )
            }
            render(scene)

            clickToggle(scene, ToggleableState.Indeterminate)
            categories = categories + Category(3L, "C", 2L, 0L)
            selectedIds = listOf(20L)
            render(scene)
            click(scene, MR.strings.action_ok.localized())
            assertEquals(setOf(2L), deltas.last().removeCategoryIds)
            assertTrue(1L in deltas.last().addCategoryIds)
            assertFalse(2L in deltas.last().addCategoryIds)

            clickToggle(scene, ToggleableState.Off)
            click(scene, MR.strings.action_ok.localized())
            assertTrue(2L in deltas.last().addCategoryIds)
            assertTrue(deltas.last().removeCategoryIds.isEmpty())

            render(scene)
            clickToggle(scene, ToggleableState.On, index = 1)
            click(scene, MR.strings.action_ok.localized())
            assertFalse(2L in deltas.last().addCategoryIds)
            assertTrue(deltas.last().removeCategoryIds.isEmpty())
            assertFalse(nodes(scene).flatMap { it.semanticLabels() }.contains("C"))
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `batch category load failure stays visible and cancellable without confirm`() = runBlocking {
        var dismissed = false
        var confirmed = false
        val scene = ImageComposeScene(900, 600, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                BatchCategoryDialog(
                    categories = listOf(Category(1L, "A", 0L, 0L)),
                    selectedMangaIds = listOf(10L),
                    loadCategoryIds = { error("category read failed") },
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true },
                )
            }
            render(scene)

            assertTrue(nodes(scene).flatMap { it.semanticLabels() }.contains(MR.strings.internal_error.localized()))
            val disabledOk = nodes(scene).first { MR.strings.action_ok.localized() in it.semanticLabels() }
            assertTrue(disabledOk.config.contains(SemanticsProperties.Disabled))
            click(scene, MR.strings.action_cancel.localized())
            assertTrue(dismissed)
            assertFalse(confirmed)
        } finally {
            scene.close()
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `batch category dialog edits categories from empty and nonempty states`() = runBlocking {
        listOf(emptyList(), listOf(Category(1L, "A", 0L, 0L))).forEach { categories ->
            var dismissed = false
            var edited = false
            val scene = ImageComposeScene(900, 600, coroutineContext = coroutineContext) {}
            try {
                scene.setContent {
                    BatchCategoryDialog(
                        categories = categories,
                        selectedMangaIds = listOf(10L),
                        loadCategoryIds = { emptySet() },
                        onConfirm = {},
                        onDismiss = { dismissed = true },
                        onEditCategories = { edited = true },
                    )
                }
                render(scene)
                assertTrue(
                    nodes(scene).flatMap { it.semanticLabels() }
                        .contains(MR.strings.action_edit_categories.localized()),
                )
                click(scene, MR.strings.action_edit_categories.localized())
                assertTrue(dismissed)
                assertTrue(edited)
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `last used category survives either initial category and favorite emission order`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(
            tempDir,
            DesktopPreferenceStore(preferencesNode),
            startDownloadWorker = false,
        )
        try {
            val preferences = Injekt.get<LibraryPreferences>()
            preferences.lastUsedCategory().set(2)
            val rawCategories = listOf(
                Category(Category.UNCATEGORIZED_ID, "Default", 0L, 0L),
                Category(1L, "A", 1L, 0L),
                Category(2L, "B", 2L, 0L),
            )
            val uncategorized = LibraryManga(
                manga = Manga.create().copy(id = 10L, source = 1L, title = "Uncategorized"),
                categories = listOf(Category.UNCATEGORIZED_ID),
                totalChapters = 0L,
                readCount = 0L,
                bookmarkCount = 0L,
                latestUpload = 0L,
                chapterFetchedAt = 0L,
                lastRead = 0L,
            )

            suspend fun verify(categoriesFirst: Boolean, selectedBeforeLibrary: Int? = null, expectedIndex: Int = 2) {
                val categoryFlow = MutableSharedFlow<List<Category>>()
                val libraryFlow = MutableSharedFlow<List<LibraryManga>>()
                val getCategories = mockk<GetCategories> {
                    every { subscribe() } returns categoryFlow
                }
                val getLibrary = mockk<GetLibraryManga> {
                    every { subscribe() } returns libraryFlow
                }
                val model = LibraryScreenModel(
                    getLibraryManga = getLibrary,
                    getCategories = getCategories,
                    libraryPreferences = preferences,
                )
                val categoriesJob = launch(start = CoroutineStart.UNDISPATCHED) { model.observeCategories() }
                val libraryJob = launch(start = CoroutineStart.UNDISPATCHED) { model.libraryMangaFlow().collect {} }
                yield()
                try {
                    if (categoriesFirst) {
                        categoryFlow.emit(rawCategories)
                        assertTrue(model.state.value.selectedCategoryIndex in model.state.value.categories.indices)
                        selectedBeforeLibrary?.let(model::setSelectedCategoryIndex)
                        libraryFlow.emit(listOf(uncategorized))
                    } else {
                        libraryFlow.emit(listOf(uncategorized))
                        categoryFlow.emit(rawCategories)
                    }
                    withTimeout(5_000) {
                        while (model.state.value.categories.map { it.id } != listOf(0L, 1L, 2L)) yield()
                    }
                    assertEquals(expectedIndex, model.state.value.selectedCategoryIndex)
                } finally {
                    categoriesJob.cancelAndJoin()
                    libraryJob.cancelAndJoin()
                }
            }

            verify(categoriesFirst = true)
            verify(categoriesFirst = false)
            verify(categoriesFirst = true, selectedBeforeLibrary = 0, expectedIndex = 0)
        } finally {
            context.closeAndJoin()
            preferencesNode.removeNode()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `test mode category sort persists through the production category chain`(@TempDir tempDir: File) = runBlocking {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore(preferencesNode), startDownloadWorker = false)
        var controller: LibraryMangaTestModeController? = null
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val preferences = Injekt.get<LibraryPreferences>()
            preferences.categorizedDisplaySettings().set(true)
            preferences.sortingMode().set(LibrarySort.default)
            val model = LibraryScreenModelFactory.create()
            model.createCategory("A")
            model.createCategory("B")
            controller = LibraryMangaTestModeController(model)
            val categoryAIndex = model.state.value.categories.indexOfFirst { it.name == "A" }
            assertTrue(controller.execute("select", mapOf("type" to "category", "index" to categoryAIndex.toString())).success)
            assertTrue(controller.execute("sort", mapOf("mode" to "unreadCount", "ascending" to "false")).success)
            assertTrue(controller.execute("search", mapOf("query" to "needle")).success)

            val repository = Injekt.get<CategoryRepository>()
            val categoryA = repository.getAll().single { it.name == "A" }
            val categoryB = repository.getAll().single { it.name == "B" }
            withTimeout(5_000) {
                while (LibrarySort.valueOf(repository.get(categoryA.id)!!.flags).type != LibrarySort.Type.UnreadCount) {
                    delay(10)
                }
            }
            assertEquals(LibrarySort.Type.UnreadCount, LibrarySort.valueOf(repository.get(categoryA.id)!!.flags).type)
            assertEquals(LibrarySort.Type.Alphabetical, LibrarySort.valueOf(categoryB.flags).type)
            assertEquals(LibrarySort.Type.Alphabetical, preferences.sortingMode().get().type)
            assertEquals("needle", controller.snapshot().searchQuery)

            val categoryBIndex = model.state.value.categories.indexOfFirst { it.id == categoryB.id }
            assertTrue(controller.execute("select", mapOf("type" to "category", "index" to categoryBIndex.toString())).success)
            assertEquals(SortMode.TITLE, model.state.value.sortMode)
            val refreshedAIndex = model.state.value.categories.indexOfFirst { it.id == categoryA.id }
            assertTrue(controller.execute("select", mapOf("type" to "category", "index" to refreshedAIndex.toString())).success)
            assertEquals(SortMode.UNREAD_COUNT, model.state.value.sortMode)

            val recreated = LibraryScreenModelFactory.create()
            recreated.refreshCategories()
            recreated.applyCategoryPreferences(categoryA.id)
            assertEquals(SortMode.UNREAD_COUNT, recreated.state.value.sortMode)

            repository.insert(Category(id = 99L, name = "Observed", order = 2L, flags = LibrarySort.default.flag))
            withTimeout(5_000) {
                while (model.state.value.categories.none { it.name == "Observed" }) delay(10)
            }
            preferences.categoryTabs().set(false)
            withTimeout(5_000) {
                while (model.state.value.showCategoryTabs) delay(10)
            }
        } finally {
            controller?.closeAndJoin()
            context.closeAndJoin()
            Dispatchers.resetMain()
            preferencesNode.removeNode()
        }
    }
    @Test
    @OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
    fun `root sort click survives category round trip and model recreation without changing global sort`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(
            tempDir,
            DesktopPreferenceStore(preferencesNode),
            startDownloadWorker = false,
        )
        val mainDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        var scene: ImageComposeScene? = null
        var rootModel: LibraryScreenModel? = null
        var recreatedModel: LibraryScreenModel? = null
        try {
            val preferences = Injekt.get<LibraryPreferences>()
            preferences.categorizedDisplaySettings().set(true)
            preferences.sortingMode().set(LibrarySort(LibrarySort.Type.Alphabetical, LibrarySort.Direction.Ascending))
            val model = LibraryScreenModelFactory.create().also { rootModel = it }
            model.createCategory("A")
            model.createCategory("B")
            val repository = Injekt.get<CategoryRepository>()
            val categoryA = repository.getAll().single { it.name == "A" }
            val categoryB = repository.getAll().single { it.name == "B" }
            val mangaRepository = Injekt.get<MangaRepository>()
            val chapterRepository = Injekt.get<ChapterRepository>()
            val alpha = mangaRepository.insertNetworkManga(
                listOf(Manga.create().copy(source = 1L, url = "/alpha", title = "Alpha high unread")),
            ).single()
            val zulu = mangaRepository.insertNetworkManga(
                listOf(Manga.create().copy(source = 1L, url = "/zulu", title = "Zulu low unread")),
            ).single()
            mangaRepository.updateMembershipsAtomically(
                listOf(
                    LibraryMembershipUpdate(alpha.id, true, 1L, listOf(categoryA.id, categoryB.id)),
                    LibraryMembershipUpdate(zulu.id, true, 2L, listOf(categoryA.id, categoryB.id)),
                ),
            )
            chapterRepository.addAll(
                listOf(
                    Chapter.create().copy(mangaId = alpha.id, url = "/alpha/1", name = "A1"),
                    Chapter.create().copy(mangaId = alpha.id, url = "/alpha/2", name = "A2"),
                    Chapter.create().copy(mangaId = alpha.id, url = "/alpha/3", name = "A3"),
                    Chapter.create().copy(mangaId = zulu.id, url = "/zulu/1", name = "Z1"),
                ),
            )

            scene = ImageComposeScene(1_400, 900, coroutineContext = coroutineContext) {}
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    ProvideLibraryScreenModelFactory(factory = { model }) {
                        Navigator(LibraryRootScreen()) { CurrentScreen() }
                    }
                }
            }

            render(scene)
            val titles = setOf(alpha.title, zulu.title)
            assertEquals(listOf(alpha.title, zulu.title), renderedTitleOrder(scene, titles))
            click(scene, MR.strings.action_sort.localized())
            render(scene)
            click(scene, MR.strings.action_sort_unread_count.localized())
            withTimeout(5_000) {
                while (LibrarySort.valueOf(repository.get(categoryA.id)!!.flags).type != LibrarySort.Type.UnreadCount) {
                    render(scene)
                    delay(10)
                }
            }
            render(scene)
            assertEquals(listOf(zulu.title, alpha.title), renderedTitleOrder(scene, titles))

            click(scene, "B")
            render(scene)
            click(scene, "A")
            render(scene)

            assertEquals(SortMode.UNREAD_COUNT, model.state.value.sortMode)
            assertEquals(listOf(zulu.title, alpha.title), renderedTitleOrder(scene, titles))
            assertEquals(LibrarySort.Type.Alphabetical, preferences.sortingMode().get().type)

            val recreated = LibraryScreenModelFactory.create().also { recreatedModel = it }
            recreated.refreshCategories()
            recreated.applyCategoryPreferences(categoryA.id)
            assertEquals(SortMode.UNREAD_COUNT, recreated.state.value.sortMode)
            assertEquals(LibrarySort.Type.Alphabetical, preferences.sortingMode().get().type)

            preferences.categorizedDisplaySettings().set(false)
            recreated.setSortModeAndDirectionForCategory(categoryB.id, SortMode.DATE_ADDED, ascending = false)
            withTimeout(5_000) {
                while (preferences.sortingMode().get().type != LibrarySort.Type.DateAdded) delay(10)
            }
            recreated.refreshCategories()
            recreated.applyCategoryPreferences(categoryA.id)
            assertEquals(SortMode.DATE_ADDED, recreated.state.value.sortMode)
            assertEquals(LibrarySort.Type.DateAdded, preferences.sortingMode().get().type)
        } finally {
            val modelJobs = listOfNotNull(recreatedModel, rootModel).mapNotNull { it.screenModelScope.coroutineContext[Job] }
            scene?.close()
            recreatedModel?.onDispose()
            rootModel?.onDispose()
            modelJobs.forEach { it.cancelAndJoin() }
            context.closeAndJoin()
            Dispatchers.resetMain()
            preferencesNode.removeNode()
        }
    }

    @Test
    fun `category dialog intents perform create rename reorder and delete through production DI`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(
            tempDir,
            DesktopPreferenceStore(preferencesNode),
            startDownloadWorker = false,
        )
        try {
            val model = LibraryScreenModelFactory.create()
            model.setShowCategoryDialog(true)

            model.createCategory("  First  ")
            model.createCategory("Second")
            assertEquals(listOf("First", "Second"), model.userCategories().map { it.name })

            val first = model.userCategories().first()
            val second = model.userCategories().last()
            model.renameCategory(first.id, "  Renamed  ")
            model.reorderCategory(second.id, 0)
            assertEquals(listOf("Second", "Renamed"), model.userCategories().map { it.name })
            assertEquals(listOf(0L, 1L), model.userCategories().map { it.order })

            model.deleteCategory(second.id)
            assertEquals(listOf("Renamed"), model.userCategories().map { it.name })
            assertEquals(
                model.state.value.categories.indices.map(Int::toLong),
                model.state.value.categories.map { it.order },
            )

            model.setShowCategoryDialog(false)
            assertFalse(model.state.value.showCategoryDialog)
            assertTrue(model.state.value.categories.none { it.id == second.id })
        } finally {
            context.closeAndJoin()
            preferencesNode.removeNode()
        }
    }

    @Test
    fun `category create failure stays visible as operation feedback`() = runBlocking {
        val backing = FakeCategoryRepository()
        val failing = object : CategoryRepository by backing {
            override suspend fun insert(category: Category) {
                error("insert failed")
            }
        }
        val model = LibraryScreenModel(
            createCategory = CreateCategoryWithName(
                categoryRepository = failing,
                preferences = LibraryPreferences(InMemoryPreferenceStore()),
            ),
            getCategories = GetCategories(failing),
        )

        model.createCategory("Broken")

        assertEquals(MR.strings.internal_error.localized(), model.state.value.operationFeedback)
        assertTrue(model.state.value.categories.isEmpty())
    }

    private fun LibraryScreenModel.userCategories() = state.value.categories.filter { it.name.isNotBlank() }

    private fun libraryItem(manga: Manga) = LibraryManga(
        manga = manga,
        categories = emptyList(),
        totalChapters = 0L,
        readCount = 0L,
        bookmarkCount = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = 0L,
    )

    private suspend fun render(scene: ImageComposeScene) {
        repeat(3) {
            scene.render()
            yield()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first {
            it.config.contains(SemanticsActions.OnClick) && it.semanticLabels().contains(label)
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun longClick(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first {
            it.config.contains(SemanticsActions.OnLongClick) && it.semanticLabels().contains(label)
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnLongClick].action).invoke())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun clickLast(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).last {
            it.config.contains(SemanticsActions.OnClick) && it.semanticLabels().contains(label)
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::flatten)

    private fun renderedTitleOrder(scene: ImageComposeScene, titles: Set<String>): List<String> =
        nodes(scene).flatMap { it.semanticLabels() }.filter { it in titles }.distinct()

    private fun SemanticsNode.semanticLabels(): List<String> =
        (if (config.contains(SemanticsProperties.Text)) {
            config[SemanticsProperties.Text].map { it.text }
        } else {
            emptyList()
        }) + if (config.contains(SemanticsProperties.ContentDescription)) {
            config[SemanticsProperties.ContentDescription]
        } else {
            emptyList()
        }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun clickToggle(scene: ImageComposeScene, state: ToggleableState, index: Int = 0) {
        val node = toggleNodes(scene).filter { it.config[SemanticsProperties.ToggleableState] == state }[index]
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun toggleNodes(scene: ImageComposeScene): List<SemanticsNode> = nodes(scene).filter {
        it.config.contains(SemanticsActions.OnClick) && it.config.contains(SemanticsProperties.ToggleableState)
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
