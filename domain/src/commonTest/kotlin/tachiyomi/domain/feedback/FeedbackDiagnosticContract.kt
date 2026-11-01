package tachiyomi.domain.feedback

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mihon.domain.sync.runtime.SyncCoordinator
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunResult
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.feedback.DiagnosticCategory
import tachiyomi.core.common.feedback.DiagnosticEvent
import tachiyomi.core.common.feedback.DiagnosticEvents
import tachiyomi.core.common.feedback.DiagnosticOperation
import tachiyomi.core.common.feedback.DiagnosticPersistence
import tachiyomi.core.common.feedback.DiagnosticSession
import tachiyomi.core.common.feedback.DiagnosticStage
import tachiyomi.core.common.feedback.DiagnosticStore
import tachiyomi.core.common.feedback.SafeCrashReportCodec
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.SetChapterReadStatus
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.UpdateManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceMangaSearchRequest
import tachiyomi.domain.source.service.SourceMangaSearchService
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
abstract class FeedbackDiagnosticContract {
    private class Persistence : DiagnosticPersistence {
        var value: DiagnosticSession? = null
        var fail = false
        override fun load() = value
        override fun save(session: DiagnosticSession?) {
            if (fail) throw IOException("Fictional persistence failure")
            value = session
        }
    }

    @Test
    fun `category keeps edited and removed previews while reading own history`() =
        runBlocking {
            val directory = Files.createTempDirectory("fictional-diagnostic-ui").toFile()
            val timestamp = Instant.parse("2026-10-17T08:00:00Z").toEpochMilli()
            val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = DiagnosticStore(directory, Persistence(), storeScope, { timestamp }, { ZoneId.of("UTC") })
            try {
                store.start(DiagnosticCategory.READER)
                store.record(
                    DiagnosticEvent(
                        DiagnosticCategory.READER,
                        DiagnosticOperation.READER_PAGE,
                        DiagnosticStage.SUCCESS,
                        count = 1,
                    ),
                )
                store.flush()
                var crashReads = 0
                val crashReader = FeedbackLogReader {
                    crashReads++
                    "Fictional safe crash"
                }
                val c = FeedbackDiagnosticController(store, crashReader, this) { "2026-10-17" }
                c.select(FeedbackCategory.READER)
                kotlinx.coroutines.yield()
                // flush the asynchronous production read before observing its callback.
                withTimeout(5000) { c.state.first { !it.reading } }
                assertTrue(c.state.value.content.contains("READER_PAGE"))
                assertEquals(listOf("2026-10-17"), c.state.value.dates)
                assertFalse(c.state.value.noToday)
                assertEquals(0, crashReads)
                c.remove()
                c.refresh()
                kotlinx.coroutines.yield()
                assertEquals("", c.state.value.content)
                c.refresh(explicit = true)
                withTimeout(5000) { c.state.first { !it.reading } }
                assertTrue(c.state.value.content.contains("READER_PAGE"))
                c.edited()
                c.refresh()
                kotlinx.coroutines.yield()
                c.select(FeedbackCategory.OTHER)
                withTimeout(5000) { c.state.first { !it.reading } }
                assertEquals("", c.state.value.content)
                assertTrue(c.state.value.noToday)
                assertEquals(0, crashReads)
            } finally {
                storeScope.cancel()
                directory.deleteRecursively()
            }
        }

    @Test
    fun `recording switch requires confirmation and receipt durably stops it`() =
        runBlocking {
            val directory = Files.createTempDirectory("fictional-diagnostic-session").toFile()
            val persistence = Persistence()
            val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = DiagnosticStore(directory, persistence, storeScope)
            try {
                store.start(DiagnosticCategory.READER)
                store.record(
                    DiagnosticEvent(
                        DiagnosticCategory.READER,
                        DiagnosticOperation.READER_PAGE,
                        DiagnosticStage.SUCCESS,
                    ),
                )
                store.flush()
                val c = FeedbackDiagnosticController(store, FeedbackLogReader { "" }, this)
                c.select(FeedbackCategory.OTHER)
                c.start()
                kotlinx.coroutines.yield()
                assertEquals(FeedbackCategory.OTHER, c.state.value.pendingRecording)
                assertEquals(DiagnosticCategory.READER, persistence.value?.category)
                c.confirmStart(false)
                assertEquals(DiagnosticCategory.READER, persistence.value?.category)
                c.start()
                c.confirmStart(true)
                withTimeout(5000) { store.state.first { it.session?.category == DiagnosticCategory.OTHER } }
                assertEquals(DiagnosticCategory.OTHER, persistence.value?.category)
                assertTrue(store.dates(DiagnosticCategory.READER).isNotEmpty())
                assertTrue(c.received())
                assertNull(persistence.value)
                assertNull(store.state.value.session)
            } finally {
                storeScope.cancel()
                directory.deleteRecursively()
            }
        }

    @Test
    fun `receipt stops recording while verification cancellation preserves it`() =
        runBlocking {
            val directory = Files.createTempDirectory("fictional-native-diagnostic").toFile()
            val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = DiagnosticStore(directory, Persistence(), storeScope)
            try {
                store.start(DiagnosticCategory.READER)
                val diagnostics = FeedbackDiagnosticController(store, FeedbackLogReader { "" }, this)
                val service = object : NativeFeedbackService {
                    override suspend fun capabilities() = NativeFeedbackCapabilities(true, true, true)
                    override suspend fun createSession(contentHash: String) = NativeFeedbackSession(
                        "123e4567-e89b-42d3-a456-426614174000",
                        "s".repeat(43),
                        Long.MAX_VALUE,
                        "https://mihon-feedback.mihon-altaircardinal.workers.dev/verify.html#" +
                            "123e4567-e89b-42d3-a456-426614174000",
                    )
                    override suspend fun pollSession(session: NativeFeedbackSession) =
                        NativeFeedbackVerification(true, Long.MAX_VALUE)
                    override suspend fun submit(
                        payload: NativeFeedbackPayload,
                        session: NativeFeedbackSession,
                    ) = "r".repeat(43)
                }
                val c = NativeFeedbackController(
                    service,
                    FeedbackLogReader { "" },
                    "",
                    this,
                    {},
                    diagnostics = diagnostics,
                )
                c.category(FeedbackCategory.OTHER)
                c.description("A fictional problem report to test actual native integration.")
                c.publicConsent(true)
                assertEquals(FeedbackCategory.OTHER, diagnostics.state.value.category)
                c.submit()
                kotlinx.coroutines.yield()
                c.cancel()
                assertNotNull(store.state.value.session)
                c.submit()
                withTimeout(5000) { c.state.first { it.phase == NativeFeedbackPhase.RECEIVED } }
                withTimeout(5000) { store.state.first { it.session == null } }
                assertNull(store.state.value.session)
            } finally {
                storeScope.cancel()
                directory.deleteRecursively()
            }
        }

    @Test
    fun `memory restores immutable draft and submission identity`() = runBlocking {
        val service = object : NativeFeedbackService {
            override suspend fun createSession(
                contentHash: String,
            ): NativeFeedbackSession = error("Not submitted")
            override suspend fun pollSession(
                session: NativeFeedbackSession,
            ): NativeFeedbackVerification = error("Not polled")
            override suspend fun submit(
                payload: NativeFeedbackPayload,
                session: NativeFeedbackSession,
            ): String = error("Not sent")
        }
        val resource = javaClass.classLoader.getResourceAsStream("feedback/fictional-screenshot.jpg")!!
        val bytes = resource.use { it.readBytes() }
        val image = FeedbackScreenshot(16, 12, bytes)
        val c = NativeFeedbackController(
            service,
            FeedbackLogReader { "" },
            "",
            this,
            {},
            screenshotProcessor = FeedbackScreenshotProcessor { _, _ -> image },
        )
        c.category(FeedbackCategory.READER)
        c.description("A fictional draft preserved while navigating to reproduce a reader issue.")
        c.addScreenshots(listOf(FeedbackScreenshotInput(1) { ByteArrayInputStream(byteArrayOf(0)) }))
        c.state.first { !it.busy }
        val memory = FeedbackDraftMemory().also { it.snapshot = c.snapshot() }
        assertNotNull(memory.snapshot)
        val resumed = NativeFeedbackController(
            service,
            FeedbackLogReader { "" },
            "",
            this,
            {},
            initialSnapshot = memory.snapshot,
        )
        assertEquals(c.state.value.draft, resumed.state.value.draft)
        assertEquals(memory.snapshot!!.submissionId, resumed.snapshot()!!.submissionId)
        assertArrayEquals(image.bytes(), resumed.state.value.draft.screenshots.single().bytes())
        resumed.description("A changed fictional reader draft makes a new immutable submission snapshot.")
        assertNotEquals(memory.snapshot!!.submissionId, resumed.snapshot()!!.submissionId)
    }

    @Test
    fun `crash history uses actual occurrence date`() = runBlocking {
        val directory = Files.createTempDirectory("fictional-crash-history").toFile()
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = DiagnosticStore(directory, Persistence(), storeScope)
        try {
            val report = SafeCrashReportCodec.format(
                IllegalStateException("private fictional message"),
                Instant.parse("2026-10-16T12:00:00Z").toEpochMilli(),
            )
            val c = FeedbackDiagnosticController(
                store,
                FeedbackLogReader { report },
                this,
                { ZoneId.of("UTC") },
                { "2026-10-17" },
            )
            c.select(FeedbackCategory.STARTUP_CRASH)
            withTimeout(5000) { c.state.first { !it.reading } }
            assertEquals("", c.state.value.content)
            assertTrue(c.state.value.noToday)
            assertEquals(listOf("2026-10-16"), c.state.value.dates)
            c.refresh("2026-10-16")
            withTimeout(5000) { c.state.first { !it.reading } }
            assertEquals(report, c.state.value.content)
            assertFalse(c.state.value.content.contains("private fictional"))
        } finally {
            storeScope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `shared use cases emit typed operations`() = runBlocking {
        val directory = Files.createTempDirectory("fictional-business-diagnostics").toFile()
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = DiagnosticStore(directory, Persistence(), storeScope)
        DiagnosticEvents.install(store)
        try {
            val chapters = listOf(
                Chapter.create().copy(
                    id = 987654321,
                    name = "fictional private chapter",
                    url = "/fictional/private",
                ),
            )
            val chapterRepository = java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(ChapterRepository::class.java),
            ) { _, method, _ ->
                if (method.name == "getChapterByMangaId") chapters else Unit
            } as ChapterRepository
            val mangaRepository = java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(MangaRepository::class.java),
            ) { _, _, _ ->
                true
            } as MangaRepository
            store.start(DiagnosticCategory.LIBRARY_PROGRESS)
            UpdateManga(mangaRepository)
                .await(MangaUpdate(987654321, favorite = true))
            SetChapterReadStatus(
                GetChaptersByMangaId(chapterRepository),
                UpdateChapter(chapterRepository),
            ).awaitOrThrow(chapters, true)
            store.flush()
            val date = LocalDate.now().toString()
            val library = store.read(DiagnosticCategory.LIBRARY_PROGRESS, date)?.content.orEmpty()
            assertTrue(library.contains("LIBRARY"))
            assertTrue(library.contains("PROGRESS"))
            assertFalse(library.contains("987654321"))
            store.stop()
            store.start(DiagnosticCategory.BROWSE_DETAILS)
            assertEquals(chapters, GetChaptersByMangaId(chapterRepository).awaitOrThrow(987654321))
            val loadedChapters = GetMangaWithChapters(mangaRepository, chapterRepository).awaitChapters(987654321)
            assertEquals(chapters, loadedChapters)
            val source = java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(Source::class.java),
            ) { _, _, _ ->
                MangasPage(emptyList(), false)
            } as Source
            SourceMangaSearchService().loadPage(
                source,
                1,
                SourceMangaSearchRequest.Popular,
            )
            store.flush()
            val browsing = store.read(DiagnosticCategory.BROWSE_DETAILS, date)?.content.orEmpty()
            assertTrue(browsing.contains("DETAILS"))
            assertTrue(browsing.contains("BROWSE"))
            assertFalse(browsing.contains("private"))
        } finally {
            DiagnosticEvents.install(null)
            storeScope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `sync records typed failures`() = runBlocking {
        val directory = Files.createTempDirectory("fictional-sync-diagnostics").toFile()
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = DiagnosticStore(directory, Persistence(), storeScope)
        DiagnosticEvents.install(store)
        try {
            store.start(DiagnosticCategory.BACKUP_SYNC)
            val coordinator = SyncCoordinator {
                SyncRunResult(
                    SyncRunStatus.FAILED,
                    problem = SyncRunProblem.NETWORK,
                    httpStatus = 503,
                )
            }
            coordinator.synchronize(SyncTrigger.MANUAL)
            store.flush()
            val text = store.read(DiagnosticCategory.BACKUP_SYNC, LocalDate.now().toString())?.content.orEmpty()
            assertTrue(text.contains("SYNC"))
            assertTrue(text.contains("FAILURE"))
            assertTrue(text.contains("503"))
        } finally {
            DiagnosticEvents.install(null)
            storeScope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `interface suggestions and other modes record fixed actions`() = runBlocking {
        val directory = Files.createTempDirectory("fictional-feedback-actions").toFile()
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = DiagnosticStore(directory, Persistence(), storeScope)
        DiagnosticEvents.install(store)
        val service = object : NativeFeedbackService {
            override suspend fun createSession(
                contentHash: String,
            ): NativeFeedbackSession = error("No request")
            override suspend fun pollSession(
                session: NativeFeedbackSession,
            ): NativeFeedbackVerification = error("No poll")
            override suspend fun submit(
                payload: NativeFeedbackPayload,
                session: NativeFeedbackSession,
            ): String = error("No submit")
        }
        try {
            val categories = listOf(
                FeedbackCategory.INTERFACE,
                FeedbackCategory.FEATURE_REQUEST,
                FeedbackCategory.OTHER,
            )
            for (category in categories) {
                store.start(DiagnosticCategory.entries.single { it.id == category.id })
                val c = NativeFeedbackController(service, FeedbackLogReader { "" }, "", this, {})
                c.category(category)
                c.description("A fictional private description that must never enter the structured log.")
                store.flush()
                val diagnosticCategory = DiagnosticCategory.entries.single { it.id == category.id }
                val content = store.read(diagnosticCategory, LocalDate.now().toString())?.content.orEmpty()
                assertTrue(content.contains("FEEDBACK"))
                assertFalse(content.contains("fictional"))
                store.stop()
            }
        } finally {
            DiagnosticEvents.install(null)
            storeScope.cancel()
            directory.deleteRecursively()
        }
    }
}
