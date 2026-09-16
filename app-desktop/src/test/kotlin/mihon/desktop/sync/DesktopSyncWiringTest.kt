package mihon.desktop.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mihon.data.sync.runtime.SyncRuntime
import mihon.desktop.DesktopAppRuntime
import mihon.desktop.di.initDesktopDIForTest
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.security.SyncSecureStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences

@Isolated
class DesktopSyncWiringTest {
    @Test
    fun `native continuation factories use the real shared sync candidate`(@TempDir folder: File) = runBlocking {
        val node = Preferences.userRoot().node("mihon-sync-resume-di-" + UUID.randomUUID())
        val context = initDesktopDIForTest(folder, DesktopPreferenceStore(node))
        try {
            mihon.data.sync.journal.SyncLocalJournal(Injekt.get()).connect(
                "space",
                1,
                mihon.domain.sync.transport.SyncRepository("owner", "sync", "sync"),
                "reader",
                1,
            )
            val manga = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>().insertNetworkManga(
                listOf(tachiyomi.domain.manga.model.Manga.create().copy(source = 42, url = "/manga", title = "Resume")),
            ).single()
            val chapter = Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>().addAll(
                listOf(
                    tachiyomi.domain.chapter.model.Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/chapter",
                        read = true,
                    ),
                ),
            ).single()
            Injekt.get<tachiyomi.domain.reader.interactor.RecordReadingProgress>().await(
                tachiyomi.domain.reader.model.ReadingProgressEvent(
                    chapter.id,
                    2,
                    10,
                    java.util.Date(1000),
                    0,
                    wasRead = true,
                    syncContext = mihon.domain.sync.SyncMutationContext.User,
                ),
            )
            val item = tachiyomi.domain.library.model.LibraryManga(manga, emptyList(), 1, 1, 0, 0, 0, 0)
            val library = mihon.desktop.library.LibraryScreenModelFactory.create().continueReadingRequest(item)
            val detail = mihon.desktop.library.MangaDetailScreenModelFactory.create(manga.id)
                .continueReadingRequest(manga, listOf(chapter))
            assertEquals(2, library?.initialPage)
            assertEquals(2, detail?.initialPage)
            assertTrue(library?.resumeSnapshot?.heads?.isNotEmpty() == true)
            assertEquals(library?.resumeSnapshot, detail?.resumeSnapshot)
            val history = Injekt.get<tachiyomi.domain.history.interactor.GetHistory>().subscribe("").first().single()
            val request = mihon.desktop.history.HistoryScreenModelFactory.create().readerRequestFor(history)
            assertEquals(2, request?.initialPage)
            assertEquals(library?.resumeSnapshot, request?.resumeSnapshot)
        } finally {
            context.closeAndJoin()
            node.removeNode()
        }
    }

    @Test
    fun `default graph exposes one sync runtime and lifecycle reaches the same coordinator`(
        @TempDir folder: File,
    ) = runBlocking {
        val node = Preferences.userRoot().node("mihon-sync-di-test-" + UUID.randomUUID())
        val context = initDesktopDIForTest(folder, DesktopPreferenceStore(node))
        try {
            val runtime = Injekt.get<SyncRuntime>()
            val longSpace = "space".repeat(25)
            runtime.preferences.activeBulkJob(longSpace, 1).set("paused-a")
            runtime.preferences.activeBulkJob("other", 1).set("paused-b")
            val reopened = mihon.domain.sync.runtime.SyncPreferences(DesktopPreferenceStore(node))
            assertEquals("paused-a", reopened.activeBulkJob(longSpace, 1).get())
            assertEquals("paused-b", reopened.activeBulkJob("other", 1).get())
            assertEquals("", reopened.activeBulkJob(longSpace, 2).get())
            assertSame(runtime, Injekt.get<SyncRuntime>())
            assertSame(runtime.panel, mihon.desktop.DesktopUiDependencies.fromInjekt().syncPanel)
            assertTrue(Injekt.get<SyncSecureStore>() is DesktopSyncSecureStore)
            assertSame(runtime.coordinator, Injekt.get<DesktopSyncScheduler>().coordinator)
            assertEquals(SyncRunStatus.SKIPPED, runtime.coordinator.synchronize(SyncTrigger.MANUAL).status)
            val before = runtime.coordinator.activity.value.completion
            Injekt.get<DesktopAppRuntime>().start()
            kotlinx.coroutines.withTimeout(5000) {
                while (runtime.coordinator.activity.value.completion == before) kotlinx.coroutines.delay(10)
            }
            assertEquals(SyncRunStatus.SKIPPED, runtime.coordinator.activity.value.result?.status)
            context.closeAndJoin()
            runtime.panel.dispatch(mihon.data.sync.runtime.SyncPanelAction.Open)
            assertEquals(null, kotlinx.coroutines.withTimeoutOrNull(500) { runtime.panel.state.first { it.visible } })
        } finally {
            context.closeAndJoin()
            node.removeNode()
        }
    }
}
