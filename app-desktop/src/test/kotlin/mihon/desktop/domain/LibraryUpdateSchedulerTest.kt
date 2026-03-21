package mihon.desktop.domain

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.settings.LibraryUpdateInterval
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryUpdateSchedulerTest {

    private val store = InMemoryPreferenceStore()
    private val prefs = DesktopAppPreferences(store)
    private val chapterRepo = FakeChapterRepository()
    private val mangaRepo = FakeMangaRepository()
    private val checker = LibraryUpdateChecker(chapterRepo)
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
        scheduler.start()  // should be a no-op
        assertTrue(scheduler.isRunning)
        scheduler.stop()
        assertFalse(scheduler.isRunning)
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
        scheduler.stop()  // must not throw
        assertFalse(scheduler.isRunning)
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
}
