package mihon.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.ui.reader.ReaderPagerNativePresentationKey
import mihon.desktop.ui.reader.ReaderPagerPresentationBinding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.reader.SqlDelightReadingProgressRepository
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import java.io.File

/** Native paint -> factory model -> real reader session -> real tracker -> SQL transaction. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SharedDualPageRuntimeProgressProductionTest {
    @TempDir lateinit var tempDir: File

    @Test
    fun `partial native viewport persists first page then adds delayed partner once without navigation`() = runTest {
        val (driver, database) = database()
        val tracker = ReaderProgressTracker(RecordReadingProgress(SqlDelightReadingProgressRepository(database)))
        val partner = CompletableDeferred<Unit>()
        val delegate = SkiaDesktopReaderPageImageDecoder()
        val fixture = MountedReaderPresentationFixture(
            tempDir,
            currentCoroutineContext(),
            MountedReaderPresentationCase(MountedReaderPresentationMode.DUAL, MountedReaderContentRoute.ONLINE),
            pageImageDecoder = DesktopReaderPageImageDecoder { encoded, key ->
                if (key.pageIndex == 2) partner.await()
                delegate.decode(encoded, key)
            },
            mangaId = 10,
            progressTracker = tracker,
        )
        fun lastRead() = database.chaptersQueries.getChapterById(7).executeAsOne().last_page_read
        fun pumpUntil(done: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!done() && System.nanoTime() < deadline) {
                fixture.scene.render().close()
                runCurrent()
                Thread.sleep(5)
            }
            assertTrue(done(), "Native runtime progress did not reach the SQL repository")
        }
        try {
            fixture.releaseBackgroundGates()
            pumpUntil { lastRead() == 1L }
            assertEquals(1L, database.reading_eventsQueries.countByChapter(7).executeAsOne())
            assertEquals(false, database.chaptersQueries.getChapterById(7).executeAsOne().read)
            partner.complete(Unit)
            pumpUntil { lastRead() == 2L }
            repeat(40) {
                fixture.scene.render().close()
                runCurrent()
                Thread.sleep(5)
            }
            assertEquals(2L, database.reading_eventsQueries.countByChapter(7).executeAsOne())
            assertEquals(false, database.chaptersQueries.getChapterById(7).executeAsOne().read)
            assertEquals(setOf(1, 2), fixture.visiblePagesAtViewportCenter())
        } finally {
            partner.complete(Unit)
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun `partner first pixels persist with logical current pending and append cannot regress`() = runTest {
        val (driver, database) = database()
        val tracker = ReaderProgressTracker(RecordReadingProgress(SqlDelightReadingProgressRepository(database)))
        val current = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val delegate = SkiaDesktopReaderPageImageDecoder()
        val fixture = MountedReaderPresentationFixture(
            tempDir,
            currentCoroutineContext(),
            MountedReaderPresentationCase(MountedReaderPresentationMode.DUAL, MountedReaderContentRoute.ONLINE),
            pageImageDecoder = DesktopReaderPageImageDecoder { encoded, key ->
                if (key.pageIndex == 1) {
                    entered.complete(Unit)
                    current.await()
                }
                delegate.decode(encoded, key)
            },
            mangaId = 10,
            progressTracker = tracker,
            pageCount = 6,
        )
        fun lastRead() = database.chaptersQueries.getChapterById(7).executeAsOne().last_page_read
        var diagnostic: () -> String = { "SQL=${lastRead()}" }
        var frameTimeNanos = 0L
        fun renderFrame() {
            frameTimeNanos += 16_000_000L
            fixture.scene.render(frameTimeNanos).close()
        }
        fun pumpUntil(done: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!done() && System.nanoTime() < deadline) {
                renderFrame()
                runCurrent()
                Thread.sleep(5)
            }
            assertTrue(done(), "Partner-first native runtime did not reach SQL: ${diagnostic()}")
        }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun model() = fixture.scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
            .first {
                it.config.contains(ReaderPagerNativePresentationKey) &&
                    it.boundsInRoot.contains(Offset(320f, 200f))
            }
            .config[ReaderPagerNativePresentationKey].let { native ->
            native.javaClass.getDeclaredField("binding").apply { isAccessible = true }.get(native)
                as ReaderPagerPresentationBinding
        }.model
        diagnostic = {
            val active = model()
            val owner = active.javaClass.getDeclaredField("dualSession").apply { isAccessible = true }
                .get(active) as mihon.domain.reader.SharedDualPageSession
            "page=${active.state.value.currentPage}/visible=${active.state.value.visiblePageIds}/" +
                "location=${owner.location}/unit=${owner.targetUnit}/history=${owner.displayedHistory}/" +
                "SQL=${lastRead()}"
        }
        try {
            fixture.releaseBackgroundGates()
            pumpUntil { lastRead() == 2L }
            assertTrue(entered.isCompleted, "The original current decoder remains in flight")
            assertTrue(!current.isCompleted)
            assertTrue(fixture.renderContainsFixturePixels())
            assertEquals(1, model().state.value.currentPage)
            assertEquals(1, model().runtime!!.session.state.value.context.initialPage)
            assertEquals(setOf(2), model().state.value.visiblePageIds.map { it.sourcePageIndex }.toSet())
            assertEquals(1L, database.reading_eventsQueries.countByChapter(7).executeAsOne())
            // Ordinary LTR navigation visits the next whole group even while C is still decoding.
            fixture.scene.sendPointerEvent(PointerEventType.Press, Offset(600f, 240f), button = PointerButton.Primary)
            fixture.scene.sendPointerEvent(PointerEventType.Release, Offset(600f, 240f), button = PointerButton.Primary)
            runCurrent()
            assertEquals(3, model().state.value.currentPage)
            pumpUntil { lastRead() == 4L }
            fixture.scene.sendPointerEvent(PointerEventType.Press, Offset(40f, 240f), button = PointerButton.Primary)
            fixture.scene.sendPointerEvent(PointerEventType.Release, Offset(40f, 240f), button = PointerButton.Primary)
            runCurrent()
            assertEquals(2, model().state.value.currentPage)
            current.complete(Unit)
            pumpUntil { model().state.value.visiblePageIds.map { it.sourcePageIndex }.toSet() == setOf(1, 2) }
            val appendedEvents = database.reading_eventsQueries.countByChapter(7).executeAsOne()
            repeat(20) {
                renderFrame()
                runCurrent()
                Thread.sleep(5)
                assertEquals(4L, lastRead(), "Returning and appending earlier C cannot regress E/F actual progress")
            }
            assertEquals(setOf(1, 2), model().state.value.visiblePageIds.map { it.sourcePageIndex }.toSet())
            val returnedAnchor = model().state.value.currentPage
            assertTrue(returnedAnchor in 1..2)
            // The runtime materialization context retains its initial page; scheduling has its own anchor.
            assertEquals(1, model().runtime!!.session.state.value.context.initialPage)
            assertEquals(appendedEvents, database.reading_eventsQueries.countByChapter(7).executeAsOne())
            val actualCurrent = model().runtime!!.session.state.value.snapshot.activeChapter.pages[1]
            assertTrue(actualCurrent.loadState is mihon.domain.reader.session.ReaderPageLoadState.Ready)
            assertEquals(false, database.chaptersQueries.getChapterById(7).executeAsOne().read)
        } finally {
            current.complete(Unit)
            fixture.close()
            driver.close()
        }
    }

    private fun database(): Pair<JdbcSqliteDriver, Database> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = tachiyomi.data.History.Adapter(DateColumnAdapter),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, status, favorite, last_update, next_update, initialized, viewer, chapter_flags, cover_last_modified, date_added, update_strategy, calculate_interval, last_modified_at, favorite_modified_at, version, notes, is_syncing) VALUES (10, 42, '/', 'Manga', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, NULL, 0, '', 0)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, chapter_number, source_order, date_fetch, date_upload) VALUES (7, 10, '/current', 'Chapter 1', 0, 0, 0, 1, 0, 0, 0)",
            0,
        )
        return driver to database
    }
}
