package mihon.desktop.feedback

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.desktop.backup.DesktopBackupCreator
import mihon.desktop.backup.DesktopBackupRestorer
import mihon.desktop.backup.models.Backup
import mihon.desktop.backup.models.BackupCategory
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.download.DownloadItem
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.reader.DesktopReaderChapterContentPort
import mihon.desktop.reader.DesktopReaderChapterContext
import mihon.desktop.reader.SourcePageFetcher
import mihon.domain.reader.materialize.ReaderChapterContentRequest
import mihon.domain.reader.session.ReaderChapterId
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.feedback.DiagnosticCategory
import tachiyomi.core.common.feedback.DiagnosticOperation
import tachiyomi.core.common.feedback.DiagnosticRequestContext
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@Isolated
class DesktopDiagnosticProductionWiringTest {
    @Test
    fun `feedback runtime startup records only an explicitly restored startup category`(@TempDir root: File) =
        runBlocking {
            FeedbackDiagnosticFixture(root.resolve("diagnostics")).use { fixture ->
                DesktopFeedbackRuntime(fixture.store)
                assertTrue(fixture.content(DiagnosticCategory.STARTUP_CRASH).isEmpty())
                fixture.store.start(DiagnosticCategory.STARTUP_CRASH)
                DesktopFeedbackRuntime(fixture.store)
                assertTrue(fixture.content(DiagnosticCategory.STARTUP_CRASH).contains("APPLICATION"))
            }
        }

    @Test
    fun `actual local reader enumeration reports no private filenames`(@TempDir root: File) = runBlocking {
        FeedbackDiagnosticFixture(root.resolve("diagnostics")).use { fixture ->
            fixture.store.start(DiagnosticCategory.DOWNLOAD_LOCAL)
            val local = root.resolve("PRIVATE_LOCAL_CHAPTER").apply { mkdirs() }
            ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", local.resolve("PRIVATE_PAGE.png"))
            val context = DesktopReaderChapterContext(1, 42, "", "", "", 1.0, 0, 0, false, local.path)
            val port =
                DesktopReaderChapterContentPort(
                    context,
                    DesktopDownloadProvider(root.resolve("downloads")),
                    mockk(relaxed = true),
                )
            val pages = port.loadChapterContent(ReaderChapterContentRequest(ReaderChapterId(1), 1))
            assertTrue(pages.isNotEmpty())
            val log = fixture.content(DiagnosticCategory.DOWNLOAD_LOCAL)
            assertTrue(log.contains("LOCAL_FILE"))
            assertFalse(log.contains("PRIVATE") || log.contains(root.path))
        }
    }

    @Test
    fun `actual reader HTTP adapter reports safe status even with source-owned clients`(@TempDir root: File) =
        runBlocking {
            FeedbackDiagnosticFixture(root.resolve("diagnostics")).use { fixture ->
                fixture.store.start(DiagnosticCategory.READER)
                MockWebServer().also { it.start() }.use { server ->
                    server.enqueue(MockResponse(code = 503, body = "PRIVATE_BODY"))
                    val fetcher = SourcePageFetcher(mockk<CatalogueSource>(relaxed = true), OkHttpClient())
                    fetcher.fetchToDestination(
                        Page(0, imageUrl = server.url("/PRIVATE_IMAGE").toString()),
                        root.resolve("private-image.jpg"),
                    )
                    val log = fixture.content(DiagnosticCategory.READER)
                    assertTrue(log.contains("READER_IMAGE") && log.contains("503"))
                    assertFalse(log.contains("PRIVATE") || log.contains(root.path))
                }
            }
        }

    @Test
    fun `production desktop clients classify safe network events and exclude feedback`(@TempDir root: File) =
        runBlocking {
            FeedbackDiagnosticFixture(root.resolve("diagnostics")).use { fixture ->
                fixture.store.start(DiagnosticCategory.READER)
                MockWebServer().also { it.start() }.use { server ->
                    repeat(2) { server.enqueue(MockResponse(code = 503, body = "PRIVATE_BODY")) }
                    DesktopNetworkHelper(root.resolve("cache"), root.resolve("cookies.json")).use { helper ->
                        val request = Request.Builder().url(server.url("/PRIVATE_PATH"))
                            .header("Authorization", "Bearer PRIVATE_TOKEN")
                            .tag(
                                DiagnosticRequestContext::class.java,
                                DiagnosticRequestContext(DiagnosticCategory.READER, DiagnosticOperation.READER_IMAGE),
                            )
                            .build()
                        helper.client.newCall(request).execute().close()
                        val first = fixture.content(DiagnosticCategory.READER)
                        assertTrue(first.contains("READER_IMAGE") && first.contains("503"))
                        assertFalse(first.contains("PRIVATE"))
                        helper.nativeFeedbackClient().newCall(request).execute().close()
                        org.junit.jupiter.api.Assertions.assertEquals(first, fixture.content(DiagnosticCategory.READER))
                    }
                }
            }
        }

    @Test
    fun `production download queue reports acceptance without chapter identity`(@TempDir root: File) = runBlocking {
        FeedbackDiagnosticFixture(root.resolve("diagnostics")).use { fixture ->
            fixture.store.start(DiagnosticCategory.DOWNLOAD_LOCAL)
            val manager = DesktopDownloadManager(
                provider = DesktopDownloadProvider(root.resolve("downloads")),
                downloadPreferences = DesktopDownloadPreferences(InMemoryPreferenceStore()),
            )
            assertTrue(manager.enqueue(DownloadItem(42, "PRIVATE_MANGA", "PRIVATE_CHAPTER", 7123456)))
            val log = fixture.content(DiagnosticCategory.DOWNLOAD_LOCAL)
            assertTrue(log.contains("DOWNLOAD"))
            assertFalse(log.contains("PRIVATE") || log.contains("7123456"))
            manager.stopAndJoin()
        }
    }

    @Test
    fun `production backup file and restore report only fixed operations`(@TempDir root: File) = runBlocking {
        FeedbackDiagnosticFixture(root.resolve("diagnostics")).use { fixture ->
            fixture.store.start(DiagnosticCategory.BACKUP_SYNC)
            val backup =
                Backup(emptyList(), backupCategories = listOf(BackupCategory(name = "PRIVATE_CATEGORY", order = 0)))
            val file = DesktopBackupCreator.writeBackupFile(backup, root.resolve("PRIVATE_BACKUP_PATH"))
            assertTrue(DesktopBackupCreator.readBackupFile(file) != null)
            DesktopBackupRestorer(
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
            ).restore(backup)
            val log = fixture.content(DiagnosticCategory.BACKUP_SYNC)
            assertTrue(log.contains("BACKUP"))
            assertTrue(log.contains("RESTORE"))
            assertFalse(log.contains("PRIVATE") || log.contains(root.path))
        }
    }
}
