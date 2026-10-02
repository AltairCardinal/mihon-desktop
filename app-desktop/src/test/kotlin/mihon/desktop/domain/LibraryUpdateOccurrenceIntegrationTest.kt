package mihon.desktop.domain

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.download.DownloadItem
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.FileTaskCheckpointStore
import mihon.domain.chapter.interactor.DownloadNewChapterPolicy
import mihon.domain.task.TaskStatus
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.creator.CreatorRepositoryImpl
import tachiyomi.data.download.PersistentDownloadStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.creator.model.SourceDateExtensionIdentity
import tachiyomi.domain.creator.model.SourceDateField
import tachiyomi.domain.creator.model.SourceDateQualityIdentity
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@org.junit.jupiter.api.parallel.Isolated
class LibraryUpdateOccurrenceIntegrationTest {
    @TempDir lateinit var directory: File

    @Test
    fun `production SINGLE local refresh consumes the existing file discovery without remote or automatic download`() {
        runBlocking<Unit> {
            val local = directory.resolve("local/Local manga/Chapter 1").also { it.mkdirs() }
            val image = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)
            javax.imageio.ImageIO.write(image, "png", local.resolve("001.png"))
            val node = java.util.prefs.Preferences.userRoot().node("mihon-ri14-local-${java.util.UUID.randomUUID()}")
            val context = mihon.desktop.di.initDesktopDIForTest(
                directory.resolve("local-db"),
                tachiyomi.core.common.preference.DesktopPreferenceStore(node),
                builtInSources = emptyList(),
            )
            try {
                val repository = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
                val chapters = Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>()
                val manga = repository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = 0,
                            url = local.parentFile.absolutePath,
                            title = "Local manga",
                            description = "User local description",
                            initialized = true,
                            favorite = true,
                        ),
                    ),
                ).single()
                val existing = chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = local.absolutePath,
                            name = local.name,
                            chapterNumber = 1.0,
                            read = true,
                            bookmark = true,
                            lastPageRead = 7,
                        ),
                    ),
                ).single()
                org.junit.jupiter.api.Assertions.assertTrue(
                    repository.update(tachiyomi.domain.manga.model.MangaUpdate(manga.id, notes = "Local user note")),
                )
                val second = local.parentFile.resolve("Chapter 2").also { it.mkdirs() }
                javax.imageio.ImageIO.write(image, "png", second.resolve("001.png"))
                val preferences = Injekt.get<tachiyomi.domain.library.service.LibraryPreferences>()
                preferences.lastUpdatedTimestamp().set(123456)
                Injekt.get<tachiyomi.domain.download.service.DownloadPreferences>().downloadNewChapters().set(true)
                val scheduler = Injekt.get<LibraryUpdateScheduler>()
                scheduler.runSingle(manga.id).join()
                assertEquals(TaskStatus.Completed, scheduler.taskSnapshot()!!.status)
                assertEquals(
                    mihon.desktop.task.LibraryUnitStatus.SUCCESS,
                    scheduler.taskSnapshot()!!.libraryUpdate!!.units.single().status,
                )
                val stored = chapters.getChapterByMangaId(manga.id).single { it.id == existing.id }
                assertEquals(2, chapters.getChapterByMangaId(manga.id).size)
                assertEquals(true, stored.read)
                assertEquals(true, stored.bookmark)
                assertEquals(7L, stored.lastPageRead)
                assertEquals("User local description", repository.getMangaById(manga.id).description)
                assertEquals("Local user note", repository.getMangaById(manga.id).notes)
                assertEquals(local.absolutePath, stored.url)
                val pages = mihon.desktop.source.LocalSourceReader.readChapter(
                    mihon.desktop.source.LocalChapterEntry(stored.name, File(stored.url)),
                )
                assertEquals(1, pages.size)
                assertEquals(2, javax.imageio.ImageIO.read(pages.single().file).width)
                assertEquals(123456L, preferences.lastUpdatedTimestamp().get())
                assertEquals(true, repository.getMangaById(manga.id).favorite)
                assertEquals(emptyList<DownloadItem>(), Injekt.get<DesktopDownloadManager>().queue.value)
                val accepted = chapters.getChapterByMangaId(manga.id)
                org.junit.jupiter.api.Assertions.assertTrue(
                    repository.update(
                        tachiyomi.domain.manga.model.MangaUpdate(
                            manga.id,
                            url = directory.resolve("missing-local").absolutePath,
                        ),
                    ),
                )
                scheduler.runSingle(manga.id).join()
                assertEquals(TaskStatus.Failed, scheduler.taskSnapshot()!!.status)
                assertEquals(true, scheduler.taskSnapshot()!!.libraryUpdate!!.units.single().localSource)
                assertEquals(false, scheduler.taskSnapshot()!!.libraryUpdate!!.units.single().sourceUnavailable)
                assertEquals(
                    accepted,
                    chapters.getChapterByMangaId(manga.id),
                    "Missing local paths cannot become a complete empty directory",
                )
            } finally {
                context.closeAndJoin()
                node.removeNode()
            }
        }
    }

    @Test
    fun `cancelled committed receipt is repaired before fresh scope without redelivery or misattributed counts`() {
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(response("first", 1))
                server.enqueue(
                    MockResponse.Builder().body(
                        """
                        {"data":[{"id":"first",
                        "attributes":{"chapter":"1",
                        "title":"Chapter 1"},
                        "relationships":[]},
                        {"id":"late",
                        "attributes":{"chapter":"2",
                        "title":"Chapter 2"},
                        "relationships":[]}],
                        "total":2}
                        """,
                    ).build(),
                )
                val source = mihon.desktop.source.MangaDexSource(
                    okhttp3.OkHttpClient(),
                    Json,
                    server.url("/").toString().trimEnd('/'),
                    browserJsonFetcher = null,
                )
                ChapterDirectorySyncIntegrationTest.Storage(directory.resolve("cancelled-receipt.db")).use { storage ->
                    val manga = storage.mangas.insertNetworkManga(
                        listOf(
                            Manga.create().copy(
                                source = source.id,
                                url = "/manga/work",
                                title = "Work",
                                favorite = true,
                            ),
                        ),
                    ).single()
                    val tasks =
                        DesktopTaskScheduler(
                            FileTaskCheckpointStore(directory.resolve("cancelled-receipt.json").toPath()),
                        )
                    val manager = manager(storage)
                    val archive = CreatorRepositoryImpl(storage.handler)
                    val checker = checker(storage, tasks, manager, archive)
                    val started = kotlinx.coroutines.CompletableDeferred<Unit>()
                    val release = kotlinx.coroutines.CompletableDeferred<Unit>()
                    var gate = true
                    val sources = mockk<SourceManager>()
                    every { sources.get(source.id) } returns source
                    val scheduler =
                        LibraryUpdateScheduler(
                            DesktopAppPreferences(InMemoryPreferenceStore()),
                            checker,
                            GetLibraryManga(storage.mangas),
                            sources,
                            taskScheduler = tasks,
                            updateManga = { current ->
                                val receipt = tachiyomi.domain.chapter.service.DirectoryTaskReceipt(
                                    tasks.snapshot("library-update")!!.task.idempotencyKey,
                                    current.id,
                                )
                                val result = checker.checkForUpdates(current, source, taskReceipt = receipt)
                                if (gate) {
                                    started.complete(Unit)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                        release.await()
                                    }
                                }
                                result
                            },
                        )
                    try {
                        val original = scheduler.runNow()
                        kotlinx.coroutines.withTimeout(5000) { started.await() }
                        val occurrence = scheduler.taskSnapshot()!!.task.idempotencyKey
                        org.junit.jupiter.api.Assertions.assertTrue(
                            storage.chapters.pendingDirectoryPhase(manga.id)!!.effectsComplete,
                        )
                        org.junit.jupiter.api.Assertions.assertTrue(scheduler.cancelUpdate())
                        release.complete(Unit)
                        original.join()
                        assertEquals(TaskStatus.Cancelled, scheduler.taskSnapshot()!!.status)
                        assertEquals(1, server.requestCount)
                        scheduler.retryFailed().join()
                        assertEquals(TaskStatus.Cancelled, scheduler.taskSnapshot()!!.status)
                        assertEquals(1, server.requestCount, "Failed-only never runs cancelled unprocessed objects")
                        gate = false
                        scheduler.runNow().join()
                        assertEquals(TaskStatus.Completed, scheduler.taskSnapshot()!!.status)
                        org.junit.jupiter.api.Assertions.assertNotEquals(
                            occurrence,
                            scheduler.taskSnapshot()!!.task.idempotencyKey,
                        )
                        assertEquals(2, server.requestCount)
                        assertEquals(1, scheduler.taskSnapshot()!!.libraryUpdate!!.units.single().newChapterCount)
                        assertEquals(2, manager.queue.value.size)
                        assertEquals(null, storage.chapters.pendingDirectoryPhase(manga.id))
                    } finally {
                        release.complete(Unit)
                        scheduler.stopAndJoin()
                        manager.stopAndJoin()
                    }
                }
            }
        }
    }

    @Test
    fun `production SINGLE refreshes only the actual favorite or nonfavorite and ignores batch policy`() {
        runBlocking<Unit> {
            for (favorite in listOf(true, false)) {
                MockWebServer().use { server ->
                    server.dispatcher = object : mockwebserver3.Dispatcher() {
                        override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                            if (request.url!!.encodedPath.endsWith("/feed")) {
                                response("new", 2)
                            } else {
                                MockResponse.Builder().body(
                                    """
                                    {"data":{"id":"work",
                                    "attributes":{"title":{"en":"Fresh title"},
                                    "description":{"en":"Fresh details"},
                                    "status":"ongoing",
                                    "tags":[]},
                                    "relationships":[]}}
                                    """,
                                ).build()
                            }
                    }
                    server.start()
                    val source = mihon.desktop.source.MangaDexSource(
                        okhttp3.OkHttpClient(),
                        Json,
                        server.url("/").toString().trimEnd('/'),
                        browserJsonFetcher = null,
                    )
                    val node = java.util.prefs.Preferences.userRoot().node(
                        "mihon-ri14-single-${java.util.UUID.randomUUID()}",
                    )
                    val context = mihon.desktop.di.initDesktopDIForTest(
                        directory.resolve("single-$favorite"),
                        tachiyomi.core.common.preference.DesktopPreferenceStore(node),
                        builtInSources = listOf(source),
                    )
                    try {
                        val repository = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
                        val chapters = Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>()
                        val target = repository.insertNetworkManga(
                            listOf(
                                Manga.create().copy(
                                    source = source.id,
                                    url = "/manga/work",
                                    title = "Work",
                                    favorite = favorite,
                                    initialized = true,
                                    nextUpdate = Long.MAX_VALUE,
                                ),
                            ),
                        ).single()
                        val other = repository.insertNetworkManga(
                            listOf(
                                Manga.create().copy(
                                    source = source.id,
                                    url = "/manga/other",
                                    title = "Other",
                                    favorite = true,
                                ),
                            ),
                        ).single()
                        chapters.addAll(
                            listOf(
                                Chapter.create().copy(
                                    mangaId = target.id,
                                    url = "/old",
                                    name = "Old unread",
                                    chapterNumber = 1.0,
                                ),
                            ),
                        )
                        val preferences = Injekt.get<tachiyomi.domain.library.service.LibraryPreferences>()
                        preferences.lastUpdatedTimestamp().set(123456L)
                        preferences.updateCategoriesExclude().set(setOf("0"))
                        Injekt.get<mihon.desktop.settings.DesktopLibraryCategoryPolicy>().recover()
                        val scheduler = Injekt.get<LibraryUpdateScheduler>()
                        scheduler.runSingle(target.id).join()
                        val result = requireNotNull(scheduler.taskSnapshot()!!.libraryUpdate)
                        assertEquals(mihon.desktop.task.LibraryUpdateScope.SINGLE, result.scope)
                        assertEquals(listOf(target.id), result.units.map { it.mangaId })
                        assertEquals(mihon.desktop.task.LibraryUnitStatus.SUCCESS, result.units.single().status)
                        assertEquals("Fresh details", repository.getMangaById(target.id).description)
                        assertEquals(favorite, repository.getMangaById(target.id).favorite)
                        assertEquals(emptyList<Chapter>(), chapters.getChapterByMangaId(other.id))
                        assertEquals(
                            123456L,
                            preferences.lastUpdatedTimestamp().get(),
                            "Single detail refresh does not advance the whole library periodic clock",
                        )
                        assertEquals(2, server.requestCount)
                        assertEquals(emptyList<DownloadItem>(), Injekt.get<DesktopDownloadManager>().queue.value)
                    } finally {
                        context.closeAndJoin()
                        node.removeNode()
                    }
                }
            }
        }
    }

    @Test
    fun `production prediction Boolean refusal retains the original phase for network free recovery`() {
        predictionRefusal(false)
    }

    @Test
    fun `production prediction exception retains the original phase for network free recovery`() = predictionRefusal(
        true,
    )

    private fun predictionRefusal(throws: Boolean) = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response("first", 1))
            val source = mihon.desktop.source.MangaDexSource(
                okhttp3.OkHttpClient(),
                Json,
                server.url("/").toString().trimEnd('/'),
                browserJsonFetcher = null,
            )
            val node = java.util.prefs.Preferences.userRoot().node(
                "mihon-ri14-prediction-${java.util.UUID.randomUUID()}",
            )
            var reject = true
            val context = mihon.desktop.di.initDesktopDIForTest(
                directory.resolve("prediction-$throws"),
                tachiyomi.core.common.preference.DesktopPreferenceStore(node),
                builtInSources = listOf(source),
                mangaRepositoryOverride = { actual ->
                    object : tachiyomi.domain.manga.repository.MangaRepository by actual {
                        override suspend fun update(update: tachiyomi.domain.manga.model.MangaUpdate): Boolean {
                            if (reject && throws && update.nextUpdate != null) {
                                throw IOException("prediction write refused")
                            }
                            return actual.update(update)
                        }
                    }
                },
            )
            try {
                val repository = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
                val chapters = Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>()
                val checker = Injekt.get<LibraryUpdateChecker>()
                val manga = repository.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = source.id,
                            url = "/manga/work",
                            title = "Work",
                            favorite = true,
                            initialized = true,
                            fetchInterval = -7,
                        ),
                    ),
                ).single()
                val driver = Injekt.get<app.cash.sqldelight.db.SqlDriver>()
                if (!throws) {
                    driver.execute(
                        null,
                        "CREATE TRIGGER reject_prediction BEFORE UPDATE ON mangas WHEN OLD._id=${manga.id} AND " +
                            "COALESCE(NEW.next_update,0)<>COALESCE(OLD.next_update,0) " +
                            "BEGIN SELECT RAISE(ABORT,'prediction refused'); END",
                        0,
                    )
                }
                org.junit.jupiter.api.Assertions.assertThrows(Exception::class.java) {
                    runBlocking { checker.checkForUpdates(manga, source) }
                }
                val pending = requireNotNull(chapters.pendingDirectoryPhase(manga.id))
                assertEquals(1, server.requestCount)
                assertEquals(0L, repository.getMangaById(manga.id).nextUpdate)
                val original = chapters.getChapterByMangaId(manga.id).single()
                reject = false
                if (!throws) driver.execute(null, "DROP TRIGGER reject_prediction", 0)
                checker.checkForUpdates(repository.getMangaById(manga.id), source)
                assertEquals(
                    1,
                    server.requestCount,
                    "Recover the accepted prediction instead of fetching a later response",
                )
                assertEquals(original.id, chapters.getChapterByMangaId(manga.id).single().id)
                org.junit.jupiter.api.Assertions.assertTrue(
                    repository.getMangaById(manga.id).nextUpdate > pending.effects.observedAt,
                )
                assertEquals(null, chapters.pendingDirectoryPhase(manga.id))
            } finally {
                context.closeAndJoin()
                node.removeNode()
            }
        }
    }

    @Test
    fun `checker metadata preserves user title custom cover and atomic directory`() {
        runBlocking<Unit> {
            MockWebServer().use { server ->
                var detailCalls = 0
                var chapterTitle = "Chapter 1"
                var description = "New description"
                server.dispatcher = object : mockwebserver3.Dispatcher() {
                    override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                        if (request.url!!.encodedPath.endsWith("/feed")) {
                            return MockResponse.Builder().body(
                                """
                                {"data":[{"id":"first",
                                "attributes":{"chapter":"1",
                                "title":"$chapterTitle"},
                                "relationships":[]}],
                                "total":1}
                                """,
                            ).build()
                        }
                        detailCalls++
                        return MockResponse.Builder().body(
                            """
                            {"data":{"id":"work",
                            "attributes":{"title":{"en":"Source title"},
                            "description":{"en":"$description"},
                            "status":"ongoing",
                            "tags":[]},
                            "relationships":[{"type":"author",
                            "attributes":{"name":"Source author"}},
                            {"type":"artist",
                            "attributes":{"name":"Source artist"}},
                            {"type":"cover_art",
                            "attributes":{"fileName":"new.jpg"}}]}}
                            """,
                        ).build()
                    }
                }
                server.start()
                val source = mihon.desktop.source.MangaDexSource(
                    okhttp3.OkHttpClient(),
                    Json,
                    server.url("/").toString().trimEnd('/'),
                    browserJsonFetcher = null,
                )
                val node = java.util.prefs.Preferences.userRoot().node(
                    "mihon-ri14-metadata-${java.util.UUID.randomUUID()}",
                )
                val context = mihon.desktop.di.initDesktopDIForTest(
                    directory.resolve("metadata"),
                    tachiyomi.core.common.preference.DesktopPreferenceStore(node),
                    builtInSources = listOf(source),
                )
                try {
                    val repository = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
                    val chapters = Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>()
                    val manga = repository.insertNetworkManga(
                        listOf(
                            Manga.create().copy(
                                source = source.id,
                                url = "/manga/work",
                                title = "User title",
                                description = "Old description",
                                initialized = true,
                                favorite = true,
                                notes = "Private note",
                                coverLastModified = 200,
                            ),
                        ),
                    ).single()
                    org.junit.jupiter.api.Assertions.assertTrue(
                        repository.update(tachiyomi.domain.manga.model.MangaUpdate(manga.id, notes = "Private note")),
                    )
                    val covers = Injekt.get<DesktopCustomCoverStore>()
                    val bytes = byteArrayOf(7, 8, 9)
                    covers.write(manga.id, bytes)
                    val preferences = Injekt.get<tachiyomi.domain.library.service.LibraryPreferences>()
                    val checker = Injekt.get<LibraryUpdateChecker>()
                    checker.checkForUpdates(manga, source)
                    assertEquals(0, detailCalls)
                    assertEquals("Old description", repository.getMangaById(manga.id).description)
                    preferences.autoUpdateMetadata().set(true)
                    checker.checkForUpdates(repository.getMangaById(manga.id), source)
                    var current = repository.getMangaById(manga.id)
                    assertEquals(
                        "New description",
                        current.description,
                        "The production binding must consume the enabled source detail policy",
                    )
                    assertEquals("User title", current.title)
                    assertEquals("Source author", current.author)
                    assertEquals("Source artist", current.artist)
                    assertEquals("Private note", current.notes)
                    assertEquals(200L, current.coverLastModified)
                    org.junit.jupiter.api.Assertions.assertArrayEquals(
                        bytes,
                        covers.getCustomCoverFile(manga.id).readBytes(),
                    )
                    assertEquals(
                        covers.getCustomCoverFile(manga.id).absolutePath,
                        covers.resolveModel(manga.id, current.thumbnailUrl),
                    )
                    preferences.updateMangaTitles().set(true)
                    checker.checkForUpdates(current, source)
                    current = repository.getMangaById(manga.id)
                    assertEquals("Source title", current.title)
                    val originalChapter = chapters.getChapterByMangaId(manga.id).single()
                    val driver = Injekt.get<app.cash.sqldelight.db.SqlDriver>()
                    driver.execute(
                        null,
                        "CREATE TRIGGER reject_metadata_directory BEFORE UPDATE ON chapters " +
                            "BEGIN SELECT RAISE(ABORT,'directory refused'); END",
                        0,
                    )
                    try {
                        chapterTitle = "Renamed chapter"
                        description = "Refused description"
                        org.junit.jupiter.api.Assertions.assertThrows(Exception::class.java) {
                            runBlocking { checker.checkForUpdates(current, source) }
                        }
                        assertEquals(current, repository.getMangaById(manga.id))
                        assertEquals(originalChapter, chapters.getChapterByMangaId(manga.id).single())
                    } finally {
                        driver.execute(null, "DROP TRIGGER reject_metadata_directory", 0)
                    }
                } finally {
                    context.closeAndJoin()
                    node.removeNode()
                }
            }
        }
    }

    @Test
    fun `real repository candidates preserve all smart skips even for an explicit category`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                    val work = requireNotNull(request.url).encodedPath.substringAfter("/manga/").substringBefore("/")
                    return response(work, 1)
                }
            }
            server.start()
            val source = mihon.desktop.source.MangaDexSource(
                okhttp3.OkHttpClient(),
                Json,
                server.url("/").toString().trimEnd('/'),
                browserJsonFetcher = null,
            )
            ChapterDirectorySyncIntegrationTest.Storage(directory.resolve("smart.db")).use { storage ->
                val all = storage.mangas.insertNetworkManga(
                    listOf(
                        "eligible",
                        "once",
                        "completed",
                        "unread",
                        "unstarted",
                        "future",
                        "local",
                        "missing",
                    ).map { work ->
                        Manga.create().copy(
                            source = when (work) {
                                "local" -> 0
                                "missing" -> 42
                                else -> source.id
                            },
                            url = "/manga/$work",
                            title = work,
                            initialized = true,
                            updateStrategy = if (work ==
                                "once"
                            ) {
                                eu.kanade.tachiyomi.source.model.UpdateStrategy.ONLY_FETCH_ONCE
                            } else {
                                eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE
                            },
                            status = if (work == "completed") 2 else 1,
                            nextUpdate = if (work == "future") Long.MAX_VALUE else 0,
                        )
                    },
                )
                storage.mangas.updateMembershipsAtomically(
                    all.map {
                        tachiyomi.domain.manga.repository.LibraryMembershipUpdate(it.id, true, 1, emptyList())
                    },
                )
                storage.chapters.addAll(
                    all.filter { it.title in setOf("once", "unread", "unstarted") }.map { manga ->
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "/old",
                            name = "Old",
                            chapterNumber = 1.0,
                            read = false,
                            lastPageRead = if (manga.title == "unread") 1 else 0,
                        )
                    },
                )
                val node = java.util.prefs.Preferences.userRoot().node(
                    "mihon-ri14-smart-${java.util.UUID.randomUUID()}",
                )
                val preferenceStore = tachiyomi.core.common.preference.DesktopPreferenceStore(node)
                val preferences = tachiyomi.domain.library.service.LibraryPreferences(preferenceStore)
                val tasks = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("smart.json").toPath()))
                val sources = mockk<SourceManager>()
                var restoredSource = false
                val restored = object : eu.kanade.tachiyomi.source.Source by source {
                    override val id = 42L
                }
                every { sources.get(any()) } answers {
                    when (firstArg<Long>()) {
                        source.id -> source
                        42L -> restored.takeIf { restoredSource }
                        else -> null
                    }
                }
                val checker =
                    LibraryUpdateChecker(
                        storage.chapters,
                        storage.mangas,
                        confirmTaskReceipt = tasks::confirmLibraryReceipt,
                    )
                val scheduler = LibraryUpdateScheduler(
                    DesktopAppPreferences(preferenceStore).also { it.updateCategoryExcludes.set("0") },
                    checker,
                    GetLibraryManga(storage.mangas),
                    sources,
                    taskScheduler = tasks,
                    libraryPreferences = preferences,
                )
                try {
                    scheduler.runNow(0).join()
                    assertEquals(
                        1,
                        server.requestCount,
                        "Manual categories bypass only scope, not smart rules or source eligibility",
                    )
                    val context = scheduler.taskSnapshot()!!.libraryUpdate!!
                    assertEquals(
                        listOf("eligible"),
                        context.units.filter {
                            it.status ==
                                mihon.desktop.task.LibraryUnitStatus.SUCCESS
                        }.map { it.title },
                    )
                    assertEquals(6, context.units.count { it.status == mihon.desktop.task.LibraryUnitStatus.SKIPPED })
                    assertEquals(
                        listOf("missing"),
                        context.units.filter {
                            it.status ==
                                mihon.desktop.task.LibraryUnitStatus.FAILED
                        }.map { it.title },
                    )
                    assertEquals(
                        1,
                        storage.chapters.getChapterByMangaId(all.first().id).size,
                        "Zero chapter favorites remain eligible",
                    )
                    restoredSource = true
                    scheduler.retryFailed().join()
                    val recovered = scheduler.taskSnapshot()!!.libraryUpdate!!.units.single()
                    assertEquals(mihon.desktop.task.LibraryUnitStatus.SUCCESS, recovered.status)
                    assertEquals(
                        false,
                        recovered.sourceUnavailable,
                        "Recovered sources cannot retain their old failure label",
                    )
                    assertEquals(2, server.requestCount)
                } finally {
                    scheduler.stopAndJoin()
                    node.removeNode()
                }
            }
        }
    }

    @Test
    fun `completed effects survive checkpoint refusal and restart without another response or redelivery`() =
        checkpointRefusal(failedOnly = false)

    @Test
    fun `explicit failed retry repairs an already committed unit without another source response`() =
        checkpointRefusal(failedOnly = true)

    private fun checkpointRefusal(failedOnly: Boolean) =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.start()
                val source = mihon.desktop.source.MangaDexSource(
                    okhttp3.OkHttpClient(),
                    Json,
                    server.url("/").toString().trimEnd('/'),
                    browserJsonFetcher = null,
                )
                server.enqueue(response("first", 1))
                // A later source response must never become part of the old accepted occurrence.
                server.enqueue(response("late", 2))
                val databaseFile = directory.resolve("checkpoint.db")
                val taskFile = directory.resolve("tasks.json").toPath()
                var reject = true
                val tasks = DesktopTaskScheduler(
                    FileTaskCheckpointStore(taskFile) { temporary, target ->
                        val completesUnit = Files.readString(temporary).contains("\"status\":\"SUCCESS\"")
                        if (reject && completesUnit) {
                            throw IOException("checkpoint refused")
                        }
                        Files.move(
                            temporary,
                            target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE,
                        )
                        true
                    },
                )
                var mangaId = 0L
                var chapterId = 0L
                var originalObservation: tachiyomi.domain.creator.model.SourceDateQualitySnapshot? = null
                val identity =
                    SourceDateQualityIdentity("test.extension", "1", source.id, SourceDateField.CHAPTER_UPDATED)
                ChapterDirectorySyncIntegrationTest.Storage(databaseFile).use { storage ->
                    val manga = storage.mangas.insertNetworkManga(
                        listOf(
                            Manga.create().copy(
                                source = source.id,
                                url = "/manga/work",
                                title = "Work",
                                favorite = true,
                            ),
                        ),
                    ).single()
                    mangaId = manga.id
                    val manager = manager(storage)
                    try {
                        val archive = CreatorRepositoryImpl(storage.handler)
                        scheduler(storage, source, tasks, manager, archive).runNow().join()
                        assertEquals(TaskStatus.Failed, tasks.snapshot("library-update")!!.status)
                        chapterId = storage.chapters.getChapterByMangaId(manga.id).single().id
                        assertEquals(listOf(chapterId), manager.queue.value.map { it.chapterId })
                        originalObservation = archive.getSourceDateQualitySnapshot(identity)
                        assertNotNull(originalObservation)
                        assertNotNull(
                            storage.chapters.pendingDirectoryPhase(manga.id),
                            "Successful effects require a SQL receipt until the task checkpoint is durable",
                        )
                    } finally {
                        manager.stopAndJoin()
                    }
                }
                reject = false
                ChapterDirectorySyncIntegrationTest.Storage(databaseFile, false).use { storage ->
                    val manager = manager(storage)
                    val archive = CreatorRepositoryImpl(storage.handler)
                    val restored =
                        scheduler(
                            storage,
                            source,
                            DesktopTaskScheduler(FileTaskCheckpointStore(taskFile)),
                            manager,
                            archive,
                        )
                    try {
                        if (failedOnly) restored.retryFailed().join() else restored.start().join()
                        assertEquals(1, server.requestCount)
                        assertEquals(originalObservation, archive.getSourceDateQualitySnapshot(identity))
                        assertEquals(listOf(chapterId), manager.queue.value.map { it.chapterId })
                        assertEquals(
                            listOf("/chapter/first"),
                            storage.chapters.getChapterByMangaId(mangaId).map {
                                it.url
                            },
                        )
                        assertEquals(1, restored.taskSnapshot()!!.libraryUpdate!!.units.single().newChapterCount)
                        assertEquals(TaskStatus.Completed, restored.taskSnapshot()!!.status)
                        assertEquals(null, storage.chapters.pendingDirectoryPhase(mangaId))
                    } finally {
                        restored.stopAndJoin()
                        manager.stopAndJoin()
                    }
                }
            }
        }

    @Test
    fun `durable completed unit retains its result when final phase acknowledgement fails and restarts`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.start()
                val source = mihon.desktop.source.MangaDexSource(
                    okhttp3.OkHttpClient(),
                    Json,
                    server.url("/").toString().trimEnd('/'),
                    browserJsonFetcher = null,
                )
                server.enqueue(response("first", 1))
                server.enqueue(response("late", 2))
                val path = directory.resolve("ack.db")
                val taskFile = directory.resolve("ack-tasks.json").toPath()
                var mangaId = 0L
                ChapterDirectorySyncIntegrationTest.Storage(path).use { storage ->
                    val manga = storage.mangas.insertNetworkManga(
                        listOf(
                            Manga.create().copy(
                                source = source.id,
                                url = "/manga/work",
                                title = "Work",
                                favorite = true,
                            ),
                        ),
                    ).single()
                    mangaId = manga.id
                    storage.driver.execute(
                        null,
                        "CREATE TRIGGER reject_receipt_ack BEFORE DELETE ON chapter_directory_phases " +
                            "BEGIN SELECT RAISE(ABORT,'receipt ack refused'); END",
                        0,
                    )
                    val manager = manager(storage)
                    try {
                        val tasks = DesktopTaskScheduler(FileTaskCheckpointStore(taskFile))
                        scheduler(
                            storage,
                            source,
                            tasks,
                            manager,
                            CreatorRepositoryImpl(storage.handler),
                        ).runNow().join()
                        assertEquals(
                            setOf(manga.id),
                            tasks.snapshot("library-update")!!.completedUnitIds,
                            "Task completion precedes the final receipt acknowledgement",
                        )
                        assertNotNull(storage.chapters.pendingDirectoryPhase(manga.id))
                    } finally {
                        manager.stopAndJoin()
                    }
                    storage.driver.execute(null, "DROP TRIGGER reject_receipt_ack", 0)
                }
                ChapterDirectorySyncIntegrationTest.Storage(path, false).use { storage ->
                    val manager = manager(storage)
                    val restored = scheduler(
                        storage,
                        source,
                        DesktopTaskScheduler(FileTaskCheckpointStore(taskFile)),
                        manager,
                        CreatorRepositoryImpl(storage.handler),
                    )
                    try {
                        restored.start().join()
                        assertEquals(1, server.requestCount)
                        assertEquals(setOf(mangaId), restored.taskSnapshot()!!.completedUnitIds)
                        assertEquals(1, restored.taskSnapshot()!!.libraryUpdate!!.units.single().newChapterCount)
                        assertEquals(TaskStatus.Completed, restored.taskSnapshot()!!.status)
                        assertEquals(null, storage.chapters.pendingDirectoryPhase(mangaId))
                    } finally {
                        restored.stopAndJoin()
                        manager.stopAndJoin()
                    }
                }
            }
        }

    @Test
    fun `fresh library occurrence first restores an older non task directory phase using its original effects`() =
        runBlocking<Unit> {
            MockWebServer().use { server ->
                server.start()
                val source = mihon.desktop.source.MangaDexSource(
                    okhttp3.OkHttpClient(),
                    Json,
                    server.url("/").toString().trimEnd('/'),
                    browserJsonFetcher = null,
                )
                server.enqueue(response("first", 1))
                server.enqueue(
                    MockResponse.Builder().body(
                        """{"data":[{"id":"first","attributes":{"chapter":"1"},"relationships":[]},
                    {"id":"late","attributes":{"chapter":"2"},"relationships":[]}],"total":2}""",
                    ).build(),
                )
                ChapterDirectorySyncIntegrationTest.Storage(directory.resolve("legacy-phase.db")).use { storage ->
                    val manga = storage.mangas.insertNetworkManga(
                        listOf(
                            Manga.create().copy(
                                source = source.id,
                                url = "/manga/work",
                                title = "Work",
                                favorite = true,
                            ),
                        ),
                    ).single()
                    val tasks =
                        DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("legacy-tasks.json").toPath()))
                    val archive = CreatorRepositoryImpl(storage.handler)
                    val manager = manager(storage)
                    try {
                        storage.driver.execute(
                            null,
                            "CREATE TRIGGER reject_old_ack BEFORE DELETE ON chapter_directory_phases " +
                                "BEGIN SELECT RAISE(ABORT,'old ack refused'); END",
                            0,
                        )
                        org.junit.jupiter.api.Assertions.assertThrows(Exception::class.java) {
                            runBlocking {
                                checker(storage, tasks, manager, archive).checkForUpdates(manga, source, "BROWSE")
                            }
                        }
                        val oldPhase = storage.chapters.pendingDirectoryPhase(manga.id)!!
                        assertEquals(null, oldPhase.effects.taskReceipt)
                        storage.driver.execute(null, "DROP TRIGGER reject_old_ack", 0)
                        val scheduler = scheduler(storage, source, tasks, manager, archive)
                        scheduler.runNow().join()
                        assertEquals(TaskStatus.Completed, scheduler.taskSnapshot()!!.status)
                        assertEquals(2, server.requestCount)
                        assertEquals(
                            1,
                            scheduler.taskSnapshot()!!.libraryUpdate!!.units.single().newChapterCount,
                            "Only additions from the new occurrence belong to its result",
                        )
                        assertEquals(
                            setOf("/chapter/first", "/chapter/late"),
                            storage.chapters.getChapterByMangaId(manga.id).map { it.url }.toSet(),
                        )
                        assertEquals(2, manager.queue.value.size)
                        assertEquals(null, storage.chapters.pendingDirectoryPhase(manga.id))
                    } finally {
                        manager.stopAndJoin()
                    }
                }
            }
        }

    private fun response(id: String, number: Int) = MockResponse.Builder().body(
        """{"data":[{"id":"$id","attributes":{"chapter":"$number","title":"Chapter $number"},
        "relationships":[]}],"total":1}""",
    ).build()

    private fun manager(storage: ChapterDirectorySyncIntegrationTest.Storage) = DesktopDownloadManager(
        DesktopDownloadProvider(directory.resolve("downloads")),
        store = PersistentDownloadStore(storage.database),
    )

    private fun scheduler(
        storage: ChapterDirectorySyncIntegrationTest.Storage,
        source: mihon.desktop.source.MangaDexSource,
        tasks: DesktopTaskScheduler,
        manager: DesktopDownloadManager,
        archive: CreatorRepositoryImpl,
    ): LibraryUpdateScheduler {
        val sources = mockk<SourceManager>()
        every { sources.get(source.id) } returns source
        return LibraryUpdateScheduler(
            DesktopAppPreferences(InMemoryPreferenceStore()),
            checker(storage, tasks, manager, archive),
            GetLibraryManga(storage.mangas),
            sources,
            taskScheduler = tasks,
        )
    }
    private fun checker(
        storage: ChapterDirectorySyncIntegrationTest.Storage,
        tasks: DesktopTaskScheduler,
        manager: DesktopDownloadManager,
        archive: CreatorRepositoryImpl,
    ) = LibraryUpdateChecker(
        storage.chapters,
        storage.mangas,
        archive,
        sourceDateExtensionIdentityProvider = { SourceDateExtensionIdentity("test.extension", "1") },
        downloadPolicy = { DownloadNewChapterPolicy(true, false) },
        confirmTaskReceipt = tasks::confirmLibraryReceipt,
        downloadCommitted = { manga, chapters ->
            for (chapter in chapters) {
                val accepted = manager.enqueue(
                    DownloadItem(
                        manga.source,
                        manga.title,
                        chapter.name,
                        chapter.id,
                        mangaId = manga.id,
                        chapterUrl = chapter.url,
                    ),
                )
                check(accepted || manager.queue.value.any { it.chapterId == chapter.id })
            }
        },
    )
}
