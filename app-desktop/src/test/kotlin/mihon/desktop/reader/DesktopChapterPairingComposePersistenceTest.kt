package mihon.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.ui.reader.DualPageDisplayUnitIdKey
import mihon.domain.reader.content.DownloadChapterIdentity
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterPairingRepositoryImpl
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
class DesktopChapterPairingComposePersistenceTest {
    @TempDir lateinit var tempDir: File

    @Test
    fun `deleting a chapter download preserves its saved pairing and reading progress`() = runBlocking {
        val databaseFile = tempDir.resolve("download-delete.sqlite")
        openDatabase(databaseFile, create = true).use { (handler, driver) ->
            seed(driver)
            val repository = ChapterPairingRepositoryImpl(handler)
            repository.replace(7, 10, 0, 8, setOf(3))
            val provider = DesktopDownloadProvider(tempDir.resolve("downloads"))
            val identity = DownloadChapterIdentity(
                sourceDisplayName = "Source",
                mangaTitle = "M",
                chapterName = "C",
                scanlator = null,
                chapterUrl = "/current",
                disallowNonAsciiFilenames = false,
            )
            val artifact = provider.canonicalChapterDownloadDir(identity).apply { mkdirs() }
            assertTrue(ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "jpg", artifact.resolve("001.jpg")))
            assertTrue(provider.isChapterDownloaded(42, identity))

            assertTrue(provider.deleteChapterDownload(42, identity))

            assertTrue(!artifact.exists())
            assertTrue(!provider.isChapterDownloaded(42, identity))
            assertEquals(setOf(3), repository.load(7, 10).record?.forcedSinglePages)
            assertEquals(5L, queryLong(driver, "SELECT last_page_read FROM chapters WHERE _id=7"))
        }
        openDatabase(databaseFile).use { (handler, driver) ->
            assertEquals(setOf(3), ChapterPairingRepositoryImpl(handler).load(7, 10).record?.forcedSinglePages)
            assertEquals(5L, queryLong(driver, "SELECT last_page_read FROM chapters WHERE _id=7"))
        }
    }

    @Test
    fun `real reader adjustment button saves to file and reopened screen restores containing spread`() = runBlocking {
        val databaseFile = tempDir.resolve("pairing.sqlite")
        openDatabase(databaseFile, create = true).use { (first, driver) ->
            seed(driver)
            val repository = ChapterPairingRepositoryImpl(first)
            val coordinator = DesktopChapterPairingCoordinator(repository)
            val completedDecodes = ConcurrentHashMap.newKeySet<Int>()
            val fixture = mounted(tempDir.resolve("first"), coordinator, initialPage = 1, completedDecodes = completedDecodes)
            try {
                fixture.releaseBackgroundGates(excluding = setOf(ReaderIoGatePoint.NON_CURRENT_PAGE))
                awaitDecodedPages(fixture, completedDecodes, setOf(1, 2))
                awaitAdjustButton(fixture)
                val button = adjustButton(fixture)
                assertTrue(requireNotNull(button.config[SemanticsActions.OnClick].action).invoke())
                withTimeout(10_000) {
                    while (repository.load(7, 10).record?.forcedSinglePages != setOf(1)) yield()
                }
                assertEquals(5L, queryLong(driver, "SELECT last_page_read FROM chapters WHERE _id=7"))
            } finally {
                fixture.close()
                coordinator.stop()
                coordinator.awaitStopped()
            }
        }
        openDatabase(databaseFile).use { (reopened, _) ->
            val coordinator = DesktopChapterPairingCoordinator(ChapterPairingRepositoryImpl(reopened))
            val completedDecodes = ConcurrentHashMap.newKeySet<Int>()
            val fixture = mounted(tempDir.resolve("reopened"), coordinator, initialPage = 5, completedDecodes = completedDecodes)
            try {
                fixture.releaseBackgroundGates(excluding = setOf(ReaderIoGatePoint.NON_CURRENT_PAGE))
                awaitDecodedPages(fixture, completedDecodes, setOf(4, 5))
                val visibleFrames = withTimeout(10_000) {
                    var frames = visibleFrames(fixture)
                    repeat(200) {
                        if (frames.isNotEmpty()) return@withTimeout frames
                        fixture.scene.render().close()
                        frames = visibleFrames(fixture)
                        yield()
                    }
                    assertTrue(frames.isNotEmpty(), "Reopened reader did not present a spread")
                    frames
                }
                assertEquals(1, visibleFrames.size, "One settled spread must contain the viewport center")
                assertEquals(
                    setOf(4, 5),
                    visibleFrames.single().config[DualPageDisplayUnitIdKey].slots.mapNotNull { it.pageId?.sourcePageIndex }.toSet(),
                    "Reopened reader must show [5,6], not the default [6,7]",
                )
            } finally {
                fixture.close()
                coordinator.stop()
                coordinator.awaitStopped()
            }
        }
    }

    private fun mounted(
        root: File,
        coordinator: DesktopChapterPairingCoordinator,
        initialPage: Int,
        completedDecodes: MutableSet<Int>,
    ) =
        MountedReaderPresentationFixture(
            root = root,
            coroutineContext = Dispatchers.Default.limitedParallelism(1),
            case = MountedReaderPresentationCase(MountedReaderPresentationMode.DUAL, MountedReaderContentRoute.DIRECTORY),
            pageCount = 8,
            mangaId = 10,
            pairingCoordinator = coordinator,
            initialPage = initialPage,
            pageImageDecoder = DesktopReaderPageImageDecoder { _, key ->
                completedDecodes += key.pageIndex
                null
            },
        )

    private suspend fun awaitAdjustButton(fixture: MountedReaderPresentationFixture) {
        try {
            withTimeout(10_000) {
                repeat(12) {
                    fixture.scene.render().close()
                    if (runCatching { adjustButton(fixture) }.isSuccess) return@withTimeout
                    fixture.scene.sendPointerEvent(PointerEventType.Press, Offset(320f, 240f), button = PointerButton.Primary)
                    fixture.scene.sendPointerEvent(PointerEventType.Release, Offset(320f, 240f), button = PointerButton.Primary)
                    fixture.scene.render().close()
                    yield()
                }
                throw AssertionError("Adjust button did not appear after 12 center taps")
            }
        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            val descriptions = nodes(fixture).flatMap { node ->
                if (node.config.contains(SemanticsProperties.ContentDescription)) {
                    node.config[SemanticsProperties.ContentDescription]
                } else emptyList()
            }
            throw AssertionError("Adjust button unavailable; text=${texts(fixture)}, descriptions=$descriptions", failure)
        }
    }

    private suspend fun awaitDecodedPages(
        fixture: MountedReaderPresentationFixture,
        completedDecodes: Set<Int>,
        pages: Set<Int>,
    ) {
        withTimeout(10_000) {
            repeat(400) {
                fixture.scene.render().close()
                if (pages.all(completedDecodes::contains) && visibleFrames(fixture).isNotEmpty()) return@withTimeout
                yield()
            }
            throw AssertionError("Visible pages $pages did not complete decode; completed=$completedDecodes")
        }
    }

    private fun adjustButton(fixture: MountedReaderPresentationFixture): SemanticsNode =
        nodes(fixture).first { node ->
            node.config.contains(SemanticsActions.OnClick) &&
                node.config.contains(SemanticsProperties.ContentDescription) &&
                node.config[SemanticsProperties.ContentDescription].any { it == "Adjust Spread" }
        }

    private fun texts(fixture: MountedReaderPresentationFixture): List<String> = nodes(fixture).flatMap { node ->
        if (node.config.contains(SemanticsProperties.Text)) {
            node.config[SemanticsProperties.Text].map { it.text }
        } else emptyList()
    }

    private fun nodes(fixture: MountedReaderPresentationFixture): List<SemanticsNode> =
        fixture.scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun visibleFrames(fixture: MountedReaderPresentationFixture): List<SemanticsNode> = nodes(fixture).filter { node ->
        node.config.contains(DualPageDisplayUnitIdKey) && node.boundsInRoot.contains(Offset(320f, 200f))
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun openDatabase(path: File, create: Boolean = false): DatabaseFixture {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePath}")
        if (create) Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        val handler = JvmDatabaseHandler(
            Database(driver, historyAdapter = History.Adapter(DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter)),
            driver,
        )
        return DatabaseFixture(handler, driver)
    }

    private fun seed(driver: JdbcSqliteDriver) {
        driver.execute(null, "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
            "chapter_flags, cover_last_modified, date_added) VALUES (10, 42, '/', 'M', 0, 0, 0, 0, 0, 0, 0)", 0)
        driver.execute(null, "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, " +
            "chapter_number, source_order, date_fetch, date_upload) VALUES (7, 10, '/current', 'C', 0, 0, 5, 1, 0, 0, 0)", 0)
    }

    private fun queryLong(driver: JdbcSqliteDriver, sql: String) = driver.executeQuery(null, sql, { cursor ->
        app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L)
    }, 0).value

    private data class DatabaseFixture(val handler: JvmDatabaseHandler, val driver: JdbcSqliteDriver) : AutoCloseable {
        override fun close() = handler.close()
    }
}
