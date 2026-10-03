package mihon.desktop.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.GetAvailableScanlators
import mihon.desktop.domain.GetExcludedScanlators
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.domain.fakes.FakeCatalogueSource
import mihon.desktop.domain.fakes.FakeCategoryRepository
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.download.DownloadItem
import mihon.desktop.download.DownloadStatus
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.ui.authors.AuthorDetailScreen
import mihon.desktop.ui.reader.DesktopReaderScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.model.CreatorIdentityOption
import tachiyomi.domain.creator.model.CreatorPortableKey
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.UpdateLibraryMembership
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR

@OptIn(ExperimentalComposeUiApi::class)
class MangaDetailLibraryEntryWiringTest {

    @Test
    fun `real MangaDetailScreen chapter pointer opens the requested reader entry`() = readerEntry(false)

    @Test
    fun `real MangaDetailScreen continue button opens earliest unread before old synchronized chapter`() = readerEntry(true)

    private fun readerEntry(resume: Boolean) = runBlocking {
        val mangaRepository = FakeMangaRepository()
        val manga = Manga.create().copy(
            id = 48L,
            source = 42L,
            url = "/reader-manga",
            title = "Reader screen fixture",
            initialized = true,
        )
        mangaRepository.seed(manga)
        val chapterRepository = FakeChapterRepository()
        val chapter = Chapter.create().copy(
            id = 4_801L,
            mangaId = manga.id,
            url = "/reader-manga/chapter-1",
            name = "Pointer chapter",
            lastPageRead = 4L,
            read = false,
            sourceOrder = 1L,
        )
        chapterRepository.seed(chapter)
        val synced = Chapter.create().copy(
            id = 4_802L,
            mangaId = manga.id,
            url = "/reader-manga/chapter-2",
            name = "Synced read chapter",
            read = true,
            sourceOrder = 2L,
        )
        chapterRepository.seed(synced)
        val snapshot = tachiyomi.domain.reader.model.ReadingSyncSnapshot()
        val progress = tachiyomi.domain.reader.interactor.RecordReadingProgress(
            object : tachiyomi.domain.reader.repository.ReadingProgressRepository {
                override suspend fun openChapter(target: tachiyomi.domain.reader.model.ReaderChapterIdentity): tachiyomi.domain.reader.model.ReaderOpenContext {
                    assertEquals(
                        tachiyomi.domain.reader.model.ReaderChapterIdentity(manga.id, manga.source, manga.url, chapter.id, chapter.url),
                        target,
                    )
                    val currentManga = mangaRepository.getMangaById(target.mangaId)
                    val currentChapter = requireNotNull(chapterRepository.getChapterById(target.chapterId))
                    return tachiyomi.domain.reader.model.ReaderOpenContext(
                        currentManga,
                        currentChapter,
                        currentChapter.lastPageRead.toInt(),
                        snapshot,
                        resumedWithinChapter = false,
                    )
                }

                override suspend fun record(event: tachiyomi.domain.reader.model.ReadingProgressEvent) = Unit
                override suspend fun resumePosition(mangaId: Long) =
                    tachiyomi.domain.reader.model.ReadingResumePosition(synced.id, 2, snapshot)
            },
        )
        val model = MangaDetailScreenModel(
            readingProgress = progress,
            mangaId = manga.id,
            getMangaWithChapters = GetMangaWithChapters(mangaRepository, chapterRepository),
            sourceManager = EmptySourceManager,
            getAvailableScanlators = GetAvailableScanlators(chapterRepository),
            getExcludedScanlators = mockk {
                every { subscribe(manga.id) } returns flowOf(emptySet())
            },
            getCategories = GetCategories(FakeCategoryRepository()),
            downloadQueue = MutableStateFlow(emptyList()),
        )
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns DesktopAppPreferences(DesktopPreferenceStore())
            every { saveSourceMangaForDetails } returns SaveSourceMangaForDetails(
                NetworkToLocalManga(mangaRepository),
                mangaRepository,
                chapterRepository,
            )
        }
        lateinit var navigator: Navigator
        val scene = ImageComposeScene(1_200, 1_200, coroutineContext = coroutineContext) {}

        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    ProvideMangaDetailScreenModelFactory(factory = { model }) {
                        MaterialTheme {
                            Navigator(MangaDetailScreen(manga.id)) { currentNavigator ->
                                navigator = currentNavigator
                                if (currentNavigator.lastItem is MangaDetailScreen) CurrentScreen()
                            }
                        }
                    }
                }
            }

            renderUntil(scene) { nodes(scene).any { it.hasText(chapter.name) } }
            val chapterTitle = nodes(scene).first { it.hasText(chapter.name) }
            if (resume) {
                assertEquals(synced.id, model.state.value.syncedResumeChapterId)
                assertTrue(model.state.value.chapters.first { it.id == synced.id }.read)
                // Material's separately placed FAB is rendered but absent from this scene's semantic subtree.
                // Exercise the actual pointer target in the fixed 1200 x 1200 viewport.
                scene.render()
                tap(scene, Offset(1120f, 1154f))
            } else {
                tap(scene, chapterTitle.boundsInRoot.center)
            }
            withTimeout(5_000) {
                while (navigator.lastItem !is DesktopReaderScreen) {
                    scene.render()
                    delay(10)
                }
            }

            val reader = navigator.lastItem as DesktopReaderScreen
            assertEquals(chapter.id, reader.chapterId)
            assertEquals(chapter.lastPageRead.toInt(), reader.initialPage)
            assertEquals(snapshot, reader.initialContext().resumeSnapshot)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `real MangaDetailScreen selection mode consumes unselected row clicks before navigation`() = runBlocking {
        val mangaRepository = FakeMangaRepository()
        val manga = Manga.create().copy(
            id = 49L,
            source = 42L,
            url = "/selection-manga",
            title = "Selection screen fixture",
            initialized = true,
        )
        mangaRepository.seed(manga)
        val chapterRepository = FakeChapterRepository()
        val firstChapter = Chapter.create().copy(
            id = 4_901L,
            mangaId = manga.id,
            url = "/selection-manga/chapter-1",
            name = "First selection chapter",
            sourceOrder = 1L,
        )
        val secondChapter = Chapter.create().copy(
            id = 4_902L,
            mangaId = manga.id,
            url = "/selection-manga/chapter-2",
            name = "Second selection chapter",
            lastPageRead = 2L,
            sourceOrder = 2L,
        )
        chapterRepository.seed(firstChapter)
        chapterRepository.seed(secondChapter)
        val model = MangaDetailScreenModel(
            mangaId = manga.id,
            getMangaWithChapters = GetMangaWithChapters(mangaRepository, chapterRepository),
            sourceManager = EmptySourceManager,
            getAvailableScanlators = GetAvailableScanlators(chapterRepository),
            getExcludedScanlators = mockk {
                every { subscribe(manga.id) } returns flowOf(emptySet())
            },
            getCategories = GetCategories(FakeCategoryRepository()),
            downloadQueue = MutableStateFlow(emptyList()),
        )
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns DesktopAppPreferences(DesktopPreferenceStore())
            every { saveSourceMangaForDetails } returns SaveSourceMangaForDetails(
                NetworkToLocalManga(mangaRepository),
                mangaRepository,
                chapterRepository,
            )
        }
        lateinit var navigator: Navigator
        val scene = ImageComposeScene(1_200, 1_200, coroutineContext = coroutineContext) {}

        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    ProvideMangaDetailScreenModelFactory(factory = { model }) {
                        MaterialTheme {
                            Navigator(MangaDetailScreen(manga.id)) { currentNavigator ->
                                navigator = currentNavigator
                                if (currentNavigator.lastItem is MangaDetailScreen) CurrentScreen()
                            }
                        }
                    }
                }
            }

            renderUntil(scene) {
                nodes(scene).any { it.hasText(firstChapter.name) } &&
                    nodes(scene).any { it.hasText(secondChapter.name) }
            }
            invokeLongClick(scene, firstChapter.name)
            renderUntil(scene) {
                checkboxStateNear(scene, firstChapter.name) == ToggleableState.On &&
                    checkboxStateNear(scene, secondChapter.name) == ToggleableState.Off
            }

            val secondTitle = nodes(scene).first { it.hasText(secondChapter.name) }
            tap(scene, secondTitle.boundsInRoot.center)
            renderUntil(scene) {
                checkboxStateNear(scene, firstChapter.name) == ToggleableState.On &&
                    checkboxStateNear(scene, secondChapter.name) == ToggleableState.On
            }
            assertTrue(navigator.lastItem is MangaDetailScreen)
            assertTrue(
                nodes(scene).none { node ->
                    node.hasContentDescription(MR.strings.desktop_ui_continue_reading.localized())
                },
            )

            tap(scene, requireNotNull(checkboxNear(scene, firstChapter.name)).boundsInRoot.center)
            renderUntil(scene) {
                checkboxStateNear(scene, firstChapter.name) == ToggleableState.Off &&
                    checkboxStateNear(scene, secondChapter.name) == ToggleableState.On
            }
            assertTrue(navigator.lastItem is MangaDetailScreen)
            tap(scene, requireNotNull(checkboxNear(scene, firstChapter.name)).boundsInRoot.center)
            renderUntil(scene) {
                checkboxStateNear(scene, firstChapter.name) == ToggleableState.On &&
                    checkboxStateNear(scene, secondChapter.name) == ToggleableState.On
            }

            invokeDescriptionClick(scene, MR.strings.desktop_ui_clear_selection.localized())
            renderUntil(scene) {
                nodes(scene).none { node -> node.config.contains(SemanticsProperties.ToggleableState) }
            }

            val secondTitleAfterClear = nodes(scene).first { it.hasText(secondChapter.name) }
            tap(scene, secondTitleAfterClear.boundsInRoot.center)
            withTimeout(5_000) {
                while (navigator.lastItem !is DesktopReaderScreen) {
                    scene.render()
                    delay(10)
                }
            }
            val reader = navigator.lastItem as DesktopReaderScreen
            assertEquals(secondChapter.id, reader.chapterId)
            assertEquals(secondChapter.lastPageRead.toInt(), reader.initialPage)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `real MangaDetailScreen retry action forwards the failed chapter id`() = runBlocking {
        val mangaRepository = FakeMangaRepository()
        val manga = Manga.create().copy(
            id = 47L,
            source = 42L,
            url = "/retry-manga",
            title = "Retry screen fixture",
            initialized = true,
        )
        mangaRepository.seed(manga)
        val chapterRepository = FakeChapterRepository()
        val chapter = Chapter.create().copy(
            id = 4_701L,
            mangaId = manga.id,
            url = "/retry-manga/chapter-1",
            name = "Retryable chapter",
        )
        chapterRepository.seed(chapter)
        var retriedChapterId: Long? = null
        val model = MangaDetailScreenModel(
            mangaId = manga.id,
            getMangaWithChapters = GetMangaWithChapters(mangaRepository, chapterRepository),
            sourceManager = EmptySourceManager,
            getAvailableScanlators = GetAvailableScanlators(chapterRepository),
            getExcludedScanlators = mockk {
                every { subscribe(manga.id) } returns flowOf(emptySet())
            },
            getCategories = GetCategories(FakeCategoryRepository()),
            downloadQueue = MutableStateFlow(
                listOf(
                    DownloadItem(
                        sourceId = manga.source,
                        mangaTitle = manga.title,
                        chapterName = chapter.name,
                        chapterId = chapter.id,
                        status = DownloadStatus.ERROR,
                        progress = 90,
                        pageUrls = List(92) { index -> "https://fixture.invalid/$index.jpg" },
                    ),
                ),
            ),
            retryDownload = { chapterId -> retriedChapterId = chapterId },
        )
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns DesktopAppPreferences(DesktopPreferenceStore())
            every { saveSourceMangaForDetails } returns SaveSourceMangaForDetails(
                NetworkToLocalManga(mangaRepository),
                mangaRepository,
                chapterRepository,
            )
        }
        val scene = ImageComposeScene(1_200, 1_200, coroutineContext = coroutineContext) {}

        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    ProvideMangaDetailScreenModelFactory(factory = { model }) {
                        MaterialTheme {
                            Navigator(MangaDetailScreen(manga.id)) { CurrentScreen() }
                        }
                    }
                }
            }
            val retryDescription = MR.strings.desktop_ui_download_retry_error.localized()
            renderUntil(scene) {
                nodes(scene).any { node ->
                    node.config.contains(SemanticsActions.OnClick) && node.hasContentDescription(retryDescription)
                }
            }

            val retryNode = nodes(scene).single { node ->
                node.config.contains(SemanticsActions.OnClick) && node.hasContentDescription(retryDescription)
            }
            assertTrue(requireNotNull(retryNode.config[SemanticsActions.OnClick].action).invoke())
            assertEquals(chapter.id, retriedChapterId)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `real MangaDetailScreen automatically refreshes an initially empty chapter list`() = verifyCombinedSourceRefresh(manual = false)

    @Test
    fun `real MangaDetailScreen refresh button uses combined Source-only update and persists memo`() = verifyCombinedSourceRefresh(manual = true)

    private fun verifyCombinedSourceRefresh(manual: Boolean) = runBlocking {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val handler = JvmDatabaseHandler(database, driver)
        val mangaRepository = tachiyomi.data.manga.MangaRepositoryImpl(handler, tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter)
        val manga = mangaRepository.insertNetworkManga(
            listOf(
                Manga.create().copy(
                    id = 45L,
                    source = 42L,
                    url = "/auto-refresh",
                    title = "Auto refresh fixture",
                    initialized = true,
                ),
            ),
        ).single()
        val chapterRepository = tachiyomi.data.chapter.ChapterRepositoryImpl(handler)
        if (manual) chapterRepository.addAll(listOf(Chapter.create().copy(mangaId = manga.id, url = "/auto-refresh/chapter-1", name = "Auto-loaded chapter", read = true, bookmark = true, lastPageRead = 7)))
        val memo = Json.parseToJsonElement("""{"token":"Desktop UI"}""") as JsonObject
        var calls = 0
        val source = object : Source {
            override val id = 42L
            override val name = "Combined UI source"
            override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): eu.kanade.tachiyomi.source.model.SMangaUpdate {
                calls++
                assertEquals(true, fetchDetails)
                assertEquals(true, fetchChapters)
                manga.memo = memo
                return eu.kanade.tachiyomi.source.model.SMangaUpdate(
                    manga,
                    listOf(
                        SChapter.create().apply {
                            url = "/auto-refresh/chapter-1"
                            name = "Auto-loaded chapter"
                            this.memo = memo
                        },
                    ),
                )
            }
        }
        val sourceManager = SingleSourceManager(source)
        val saveSourceMangaForDetails = SaveSourceMangaForDetails(
            NetworkToLocalManga(mangaRepository),
            mangaRepository,
            chapterRepository,
        )
        val model = MangaDetailScreenModel(
            mangaId = manga.id,
            getMangaWithChapters = GetMangaWithChapters(mangaRepository, chapterRepository),
            sourceManager = sourceManager,
            getAvailableScanlators = GetAvailableScanlators(chapterRepository),
            getExcludedScanlators = mockk {
                every { subscribe(manga.id) } returns flowOf(emptySet())
            },
            getCategories = GetCategories(FakeCategoryRepository()),
            downloadQueue = MutableStateFlow(emptyList()),
        )
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns DesktopAppPreferences(DesktopPreferenceStore())
            every { this@mockk.saveSourceMangaForDetails } returns saveSourceMangaForDetails
        }
        val scene = ImageComposeScene(1_200, 900, coroutineContext = coroutineContext) {}

        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    ProvideMangaDetailScreenModelFactory(factory = { model }) {
                        MaterialTheme {
                            Navigator(MangaDetailScreen(manga.id)) { CurrentScreen() }
                        }
                    }
                }
            }

            if (manual) {
                renderUntil(scene) { nodes(scene).any { it.hasText("Auto-loaded chapter") } }
                invokeDescriptionClick(scene, MR.strings.check_for_updates.localized())
            }
            renderUntil(scene) { model.state.value.chapters.any { it.memo == memo } }
            assertEquals(1, calls)
            assertEquals(memo, mangaRepository.getMangaById(manga.id).memo)
            val chapter = chapterRepository.getChapterByMangaId(manga.id).single()
            assertEquals(memo, chapter.memo)
            if (manual) {
                assertTrue(chapter.read)
                assertTrue(chapter.bookmark)
                assertEquals(7, chapter.lastPageRead)
            }
        } finally {
            scene.close()
            driver.close()
        }
    }

    @Test
    fun `real uncollected MangaDetailScreen creator chip retries SQL failure and opens unique author`() = runBlocking {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                StringListColumnAdapter,
                UpdateStrategyColumnAdapter,
            ),
        )
        val handler = JvmDatabaseHandler(database, driver)
        val mangaRepository = tachiyomi.data.manga.MangaRepositoryImpl(
            handler,
            tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter,
        )
        val manga = mangaRepository.insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = 42L,
                    url = "/identity",
                    title = "Identity fixture",
                    author = "Jane Doe",
                    favorite = false,
                ),
            ),
        ).single()
        val chapterRepository = FakeChapterRepository()
        var failOnce = true
        val archiveRepository = tachiyomi.data.creator.CreatorRepositoryImpl(handler, identityMutationHook = {
            if (failOnce) {
                failOnce = false
                error("fixture identity failure")
            }
        })
        val model = MangaDetailScreenModel(
            mangaId = manga.id,
            getMangaWithChapters = GetMangaWithChapters(mangaRepository, chapterRepository),
            sourceManager = EmptySourceManager,
            getAvailableScanlators = GetAvailableScanlators(chapterRepository),
            getExcludedScanlators = mockk {
                every { subscribe(manga.id) } returns flowOf(emptySet())
            },
            getCategories = GetCategories(FakeCategoryRepository()),
            manageCreatorIdentity = ManageCreatorIdentity(archiveRepository),
            downloadQueue = MutableStateFlow(emptyList()),
        )
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns DesktopAppPreferences(DesktopPreferenceStore())
            every { libraryPreferences } returns null
            every { saveSourceMangaForDetails } returns SaveSourceMangaForDetails(
                NetworkToLocalManga(mangaRepository),
                mangaRepository,
                chapterRepository,
            )
        }
        lateinit var navigator: Navigator
        val scene = ImageComposeScene(1_200, 900, coroutineContext = coroutineContext) {}

        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    ProvideMangaDetailScreenModelFactory(factory = { model }) {
                        MaterialTheme {
                            Navigator(MangaDetailScreen(manga.id)) { currentNavigator ->
                                navigator = currentNavigator
                                CurrentScreen()
                            }
                        }
                    }
                }
            }
            val creatorChip = "Jane Doe"
            renderUntil(scene) { nodes(scene).any { it.hasText(creatorChip) } }
            click(scene, creatorChip)
            renderUntil(scene) { nodes(scene).any { it.hasText("fixture identity failure") } }
            handler.await {
                assertTrue(author_archiveQueries.getArchiveCreatorsForBackup().executeAsList().isEmpty())
            }
            click(scene, MR.strings.action_ok.localized())
            click(scene, creatorChip)
            withTimeout(5_000) {
                while (navigator.lastItem !is AuthorDetailScreen) {
                    scene.render()
                    delay(10)
                }
            }
            val opened = (navigator.lastItem as AuthorDetailScreen).creatorId
            assertEquals(archiveRepository.resolveCreatorIdByExactName("Jane Doe"), opened)
            assertTrue(!mangaRepository.getMangaById(manga.id).favorite)
            handler.await {
                assertEquals("AUTOMATIC", author_archiveQueries.getArchiveMangaLink(manga.id, opened).executeAsOne().origin)
            }
        } finally {
            scene.close()
            handler.close()
        }
    }

    @Test
    fun `real MangaDetailScreen add action mounts category dialog and commits selection`() = runBlocking {
        val mangaRepository = FakeMangaRepository()
        val manga = Manga.create().copy(id = 43L, title = "Screen fixture", favorite = false)
        mangaRepository.seed(manga)
        val chapterRepository = FakeChapterRepository()
        val categoryRepository = FakeCategoryRepository().apply {
            insert(Category(id = 11L, name = "Screen selected", order = 0L, flags = 0L))
            insert(Category(id = 12L, name = "Screen unselected", order = 1L, flags = 0L))
        }
        val excludedScanlators = mockk<GetExcludedScanlators> {
            every { subscribe(manga.id) } returns flowOf(emptySet())
        }
        val model = MangaDetailScreenModel(
            mangaId = manga.id,
            getMangaWithChapters = GetMangaWithChapters(mangaRepository, chapterRepository),
            sourceManager = EmptySourceManager,
            getAvailableScanlators = GetAvailableScanlators(chapterRepository),
            getExcludedScanlators = excludedScanlators,
            getCategories = GetCategories(categoryRepository),
            setMangaCategories = SetMangaCategories(mangaRepository),
            downloadQueue = MutableStateFlow(emptyList()),
            updateLibraryMembership = UpdateLibraryMembership(mangaRepository),
        )
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns DesktopAppPreferences(DesktopPreferenceStore())
            every { saveSourceMangaForDetails } returns SaveSourceMangaForDetails(
                NetworkToLocalManga(mangaRepository),
                mangaRepository,
                chapterRepository,
            )
        }
        val scene = ImageComposeScene(1_200, 900, coroutineContext = coroutineContext) {}

        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    ProvideMangaDetailScreenModelFactory(factory = { model }) {
                        MaterialTheme {
                            Navigator(MangaDetailScreen(manga.id)) { CurrentScreen() }
                        }
                    }
                }
            }
            val addToLibrary = MR.strings.add_to_library.localized()
            val confirm = MR.strings.action_ok.localized()
            renderUntil(scene) { nodes(scene).any { it.hasText(addToLibrary) } }

            click(scene, addToLibrary)

            renderUntil(scene) { nodes(scene).any { it.hasText("Screen selected") } }
            click(scene, "Screen selected")
            click(scene, confirm)
            withTimeout(5_000) {
                while (!mangaRepository.get(manga.id)!!.favorite) delay(10)
            }
            assertEquals(listOf(11L), mangaRepository.getMangaCategoryIds(manga.id))
        } finally {
            scene.close()
        }
    }

    @Test
    fun `real MangaDetailScreen adds directly when there are no user categories`() = runBlocking {
        val mangaRepository = FakeMangaRepository()
        val manga = Manga.create().copy(id = 46L, title = "No categories fixture", favorite = false)
        mangaRepository.seed(manga)
        val chapterRepository = FakeChapterRepository()
        val model = MangaDetailScreenModel(
            mangaId = manga.id,
            getMangaWithChapters = GetMangaWithChapters(mangaRepository, chapterRepository),
            sourceManager = EmptySourceManager,
            getAvailableScanlators = GetAvailableScanlators(chapterRepository),
            getExcludedScanlators = mockk {
                every { subscribe(manga.id) } returns flowOf(emptySet())
            },
            getCategories = GetCategories(FakeCategoryRepository()),
            downloadQueue = MutableStateFlow(emptyList()),
            updateLibraryMembership = UpdateLibraryMembership(mangaRepository),
        )
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns DesktopAppPreferences(DesktopPreferenceStore())
            every { saveSourceMangaForDetails } returns SaveSourceMangaForDetails(
                NetworkToLocalManga(mangaRepository),
                mangaRepository,
                chapterRepository,
            )
        }
        val scene = ImageComposeScene(1_200, 900, coroutineContext = coroutineContext) {}

        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    ProvideMangaDetailScreenModelFactory(factory = { model }) {
                        MaterialTheme {
                            Navigator(MangaDetailScreen(manga.id)) { CurrentScreen() }
                        }
                    }
                }
            }
            val addToLibrary = MR.strings.add_to_library.localized()
            renderUntil(scene) { nodes(scene).any { it.hasText(addToLibrary) } }

            click(scene, addToLibrary)

            withTimeout(5_000) {
                while (!mangaRepository.get(manga.id)!!.favorite) {
                    scene.render()
                    delay(10)
                }
            }
            assertTrue(
                nodes(scene).none {
                    it.hasText(MR.strings.desktop_ui_no_categories_create_categories_from_library_first.localized())
                },
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun `add to library dialog passes selected category ids through the production caller`() = runBlocking {
        val mangaRepository = FakeMangaRepository()
        val manga = Manga.create().copy(id = 42L, title = "Fixture manga", favorite = false)
        mangaRepository.seed(manga)
        val categoryRepository = FakeCategoryRepository().apply {
            insert(Category(id = 7L, name = "Selected category", order = 0L, flags = 0L))
            insert(Category(id = 9L, name = "Unselected category", order = 1L, flags = 0L))
        }
        val model = MangaDetailScreenModel(
            mangaId = manga.id,
            getCategories = GetCategories(categoryRepository),
            updateLibraryMembership = UpdateLibraryMembership(mangaRepository),
        )
        val scene = ImageComposeScene(700, 500, coroutineContext = coroutineContext) {}

        try {
            scene.setContent {
                MangaDetailLibraryCategoryDialog(
                    manga = manga,
                    mode = MangaCategoryDialogMode.ADD_TO_LIBRARY,
                    model = model,
                    onDismiss = {},
                )
            }
            renderUntil(scene) { nodes(scene).any { node -> node.hasText("Selected category") } }
            click(scene, "Selected category")
            click(scene, MR.strings.action_ok.localized())

            withTimeout(5_000) {
                while (!mangaRepository.get(manga.id)!!.favorite) delay(10)
            }
            assertTrue(mangaRepository.get(manga.id)!!.favorite)
            assertEquals(listOf(7L), mangaRepository.getMangaCategoryIds(manga.id))
        } finally {
            scene.close()
        }
    }

    private suspend fun renderUntil(scene: ImageComposeScene, predicate: () -> Boolean) {
        withTimeout(5_000) {
            while (!predicate()) {
                scene.render()
                delay(10)
            }
        }
    }

    private fun click(scene: ImageComposeScene, label: String) {
        invokeClick(scene, label)
        scene.render()
    }

    private fun tap(scene: ImageComposeScene, position: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, position)
        scene.sendPointerEvent(PointerEventType.Release, position)
        scene.render()
    }

    private fun invokeLongClick(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { candidate ->
            candidate.config.contains(SemanticsActions.OnLongClick) &&
                flatten(candidate).any { it.hasText(label) }
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnLongClick].action).invoke())
    }

    private fun checkboxNear(scene: ImageComposeScene, label: String): SemanticsNode? {
        val title = nodes(scene).firstOrNull { it.hasText(label) } ?: return null
        return nodes(scene)
            .filter { it.config.contains(SemanticsProperties.ToggleableState) }
            .minByOrNull { kotlin.math.abs(it.boundsInRoot.center.y - title.boundsInRoot.center.y) }
    }

    private fun checkboxStateNear(scene: ImageComposeScene, label: String): ToggleableState? =
        checkboxNear(scene, label)?.config?.get(SemanticsProperties.ToggleableState)

    private fun invokeClick(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { candidate ->
            candidate.config.contains(SemanticsActions.OnClick) &&
                flatten(candidate).any { it.hasText(label) }
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun invokeDescriptionClick(scene: ImageComposeScene, description: String) {
        val node = nodes(scene).first { candidate ->
            candidate.config.contains(SemanticsActions.OnClick) && candidate.hasContentDescription(description)
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun SemanticsNode.hasText(text: String): Boolean {
        val values = if (config.contains(SemanticsProperties.Text)) {
            config[SemanticsProperties.Text]
        } else {
            emptyList()
        }
        return values.any { it.text == text }
    }

    private fun SemanticsNode.hasContentDescription(description: String): Boolean =
        config.contains(SemanticsProperties.ContentDescription) &&
            description in config[SemanticsProperties.ContentDescription]

    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private object EmptySourceManager : SourceManager {
        override val isInitialized: StateFlow<Boolean> = MutableStateFlow(true)
        override val catalogueSources: Flow<List<CatalogueSource>> = flowOf(emptyList())

        override fun get(sourceKey: Long): Source? = null

        override fun getOrStub(sourceKey: Long): Source = error("No source for $sourceKey")

        override fun getOnlineSources(): List<HttpSource> = emptyList()

        override fun getCatalogueSources(): List<CatalogueSource> = emptyList()

        override fun getStubSources(): List<StubSource> = emptyList()
    }

    private class SingleSourceManager(
        private val source: Source,
    ) : SourceManager {
        override val isInitialized: StateFlow<Boolean> = MutableStateFlow(true)
        override val catalogueSources: Flow<List<CatalogueSource>> = flowOf(listOfNotNull(source as? CatalogueSource))

        override fun get(sourceKey: Long): Source? = source.takeIf { it.id == sourceKey }

        override fun getOrStub(sourceKey: Long): Source = get(sourceKey) ?: error("No source for $sourceKey")

        override fun getOnlineSources(): List<HttpSource> = emptyList()

        override fun getCatalogueSources(): List<CatalogueSource> = listOfNotNull(source as? CatalogueSource)

        override fun getStubSources(): List<StubSource> = emptyList()
    }
}
