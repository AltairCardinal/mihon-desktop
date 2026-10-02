package mihon.desktop.domain

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.settings.LibraryUpdateInterval
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class LibraryUpdateSchedulerTest {

    private val store = InMemoryPreferenceStore()
    private val prefs = DesktopAppPreferences(store)
    private val chapterRepo = FakeChapterRepository()
    private val mangaRepo = FakeMangaRepository()
    private val checker = LibraryUpdateChecker(chapterRepo, mangaRepo)
    private val getLibraryManga = GetLibraryManga(mangaRepo)

    private val noopSourceManager = object : SourceManager {
        override val isInitialized: StateFlow<Boolean> = MutableStateFlow(true)
        override val catalogueSources = flowOf(emptyList<CatalogueSource>())
        override fun get(sourceKey: Long): Source? = null
        override fun getOrStub(sourceKey: Long): Source = throw UnsupportedOperationException()
        override fun getOnlineSources(): List<HttpSource> = emptyList()
        override fun getCatalogueSources(): List<CatalogueSource> = emptyList()
        override fun getStubSources(): List<StubSource> = emptyList()
    }

    // ─────────────────────────────────────────────
    // Lifecycle tests
    // ─────────────────────────────────────────────

    @Test
    fun `new chapters are forwarded to shared auto download policy`() = runTest {
        val manga = Manga.create().copy(id = 10, source = 42, url = "/manga/10", title = "Manga", favorite = true)
        val chapter = Chapter.create().copy(id = 20, mangaId = manga.id, name = "Chapter", url = "/20")
        var forwarded: Pair<Manga, List<Chapter>>? = null
        val scheduler = LibraryUpdateScheduler(
            appPreferences = prefs,
            updateChecker = null,
            getLibraryManga = null,
            sourceManager = null,
            scope = this,
            libraryProvider = { listOf(LibraryManga(manga, emptyList(), 0, 0, 0, 0, 0, 0)) },
            updateManga = { LibraryUpdateChecker.UpdateResult(1, listOf(chapter)) },
            autoDownload = { candidate, chapters -> forwarded = candidate to chapters },
        )

        scheduler.runNow().join()

        forwarded shouldBe (manga to listOf(chapter))
    }

    @Test
    fun `manual update can be scoped to the active library category`() = runTest {
        prefs.updateCategoryIncludes.set("2")
        prefs.updateCategoryExcludes.set("1")
        val first = Manga.create().copy(id = 10L, source = 42, url = "/manga/10", title = "First", favorite = true)
        val second = Manga.create().copy(id = 20L, source = 42, url = "/manga/20", title = "Second", favorite = true)
        val updated = mutableListOf<Long>()
        val scheduler = LibraryUpdateScheduler(
            appPreferences = prefs,
            updateChecker = null,
            getLibraryManga = null,
            sourceManager = null,
            scope = this,
            libraryProvider = {
                listOf(
                    LibraryManga(first, listOf(1L), 0, 0, 0, 0, 0, 0),
                    LibraryManga(second, listOf(2L), 0, 0, 0, 0, 0, 0),
                )
            },
            updateManga = { manga ->
                updated += manga.id
                LibraryUpdateChecker.UpdateResult(0)
            },
        )

        scheduler.runNow(categoryId = 1L).join()

        assertEquals(listOf(10L), updated)
    }

    @Test
    fun `actual update job is exposed only while the occurrence is active`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scheduler = LibraryUpdateScheduler(
            appPreferences = prefs,
            updateChecker = null,
            getLibraryManga = null,
            sourceManager = null,
            scope = backgroundScope,
            libraryProvider = {
                listOf(
                    LibraryManga(
                        Manga.create().copy(id = 1, source = 42, url = "/manga/1", favorite = true),
                        listOf(0),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                    ),
                )
            },
            updateManga = {
                entered.complete(Unit)
                release.await()
                LibraryUpdateChecker.UpdateResult(0)
            },
        )

        val first = scheduler.runNow()
        entered.await()
        assertSame(first, scheduler.currentUpdateJob())
        assertSame(first, scheduler.runNow())
        release.complete(Unit)
        first.join()
        assertEquals(null, scheduler.currentUpdateJob())
        val next = scheduler.runNow()
        assertTrue(next !== first)
        next.join()
        scheduler.stopAndJoin()
    }

    @Test
    fun `isRunning is false before start`() = runTest {
        val scheduler = LibraryUpdateScheduler(
            appPreferences = prefs,
            updateChecker = checker,
            getLibraryManga = getLibraryManga,
            sourceManager = noopSourceManager,
            scope = this,
        )
        assertFalse(scheduler.isRunning)
    }

    @Test
    fun `isRunning is true after start`() = runTest {
        val scheduler = LibraryUpdateScheduler(
            appPreferences = prefs,
            updateChecker = checker,
            getLibraryManga = getLibraryManga,
            sourceManager = noopSourceManager,
            scope = this,
        )
        scheduler.start()
        assertTrue(scheduler.isRunning)
        scheduler.stop()
    }

    @Test
    fun `isRunning is false after stop`() = runTest {
        val scheduler = LibraryUpdateScheduler(
            appPreferences = prefs,
            updateChecker = checker,
            getLibraryManga = getLibraryManga,
            sourceManager = noopSourceManager,
            scope = this,
        )
        scheduler.start()
        scheduler.stop()
        assertFalse(scheduler.isRunning)
    }

    @Test
    fun `start called twice is idempotent`() = runTest {
        val scheduler = LibraryUpdateScheduler(
            appPreferences = prefs,
            updateChecker = checker,
            getLibraryManga = getLibraryManga,
            sourceManager = noopSourceManager,
            scope = this,
        )
        scheduler.start()
        scheduler.start() // should be a no-op
        assertTrue(scheduler.isRunning)
        scheduler.stop()
        assertFalse(scheduler.isRunning)
    }

    @Test
    fun `start after initial completion returns the same completed job`() = runTest {
        val scheduler = LibraryUpdateScheduler(
            appPreferences = prefs,
            updateChecker = checker,
            getLibraryManga = getLibraryManga,
            sourceManager = noopSourceManager,
            scope = this,
        )

        val initial = scheduler.start()
        initial.join()
        val repeated = scheduler.start()

        assertSame(initial, repeated)
        assertTrue(repeated.isCompleted)
        scheduler.stop()
    }

    @Test
    fun `stopAndJoin completes when called on scheduler single thread`() {
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            newSingleThreadContext("library-update-stop-test").use { dispatcher ->
                val updateStarted = CompletableDeferred<Unit>()
                val scheduler = LibraryUpdateScheduler(
                    appPreferences = prefs,
                    updateChecker = null,
                    getLibraryManga = null,
                    sourceManager = null,
                    scope = CoroutineScope(dispatcher),
                    libraryProvider = {
                        updateStarted.complete(Unit)
                        awaitCancellation()
                    },
                )

                runBlocking {
                    scheduler.runNow()
                    updateStarted.await()
                    withContext(dispatcher) { scheduler.stopAndJoin() }
                }
            }
        }
    }

    @Test
    fun `stop followed by stopAndJoin still waits for detached update work`() = runTest {
        val updateStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanupCompleted = CompletableDeferred<Unit>()
        val scheduler = LibraryUpdateScheduler(
            appPreferences = prefs,
            updateChecker = null,
            getLibraryManga = null,
            sourceManager = null,
            scope = backgroundScope,
            libraryProvider = {
                updateStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { releaseCleanup.await() }
                    cleanupCompleted.complete(Unit)
                }
            },
        )

        scheduler.runNow()
        updateStarted.await()
        scheduler.stop()
        val stopped = async { scheduler.stopAndJoin() }
        runCurrent()

        assertFalse(stopped.isCompleted)
        releaseCleanup.complete(Unit)
        stopped.await()
        assertTrue(cleanupCompleted.isCompleted)
    }

    @Test
    fun `stop before start does not crash`() = runTest {
        val scheduler = LibraryUpdateScheduler(
            appPreferences = prefs,
            updateChecker = checker,
            getLibraryManga = getLibraryManga,
            sourceManager = noopSourceManager,
            scope = this,
        )
        scheduler.stop() // must not throw
        assertFalse(scheduler.isRunning)
    }

    @Test
    fun `completed detached update work is not retained after stop`() = runTest {
        val scheduler = LibraryUpdateScheduler(
            appPreferences = prefs,
            updateChecker = null,
            getLibraryManga = null,
            sourceManager = null,
            scope = backgroundScope,
            libraryProvider = { emptyList() },
        )

        scheduler.runNow().join()
        scheduler.stop()

        assertTrue(scheduler.stoppingJobCount() == 0)
    }

    // ─────────────────────────────────────────────
    // Update-triggering behaviour
    // ─────────────────────────────────────────────

    @Test
    fun `scheduler stays running after advancing time with interval OFF`() = runTest {
        prefs.libraryUpdateInterval.set(LibraryUpdateInterval.OFF)
        val scheduler = LibraryUpdateScheduler(
            appPreferences = prefs,
            updateChecker = checker,
            getLibraryManga = getLibraryManga,
            sourceManager = noopSourceManager,
            scope = this,
        )
        scheduler.start()
        // Advance past several check intervals; scheduler must not crash
        advanceTimeBy(LibraryUpdateScheduler.CHECK_INTERVAL_MS * 3 + 1_000)
        assertTrue(scheduler.isRunning, "Scheduler should still be running after time advance")
        scheduler.stop()
    }

    private fun Any.stoppingJobCount(): Int =
        (javaClass.getDeclaredField("stoppingJobs").apply { isAccessible = true }.get(this) as Set<*>).size
}
