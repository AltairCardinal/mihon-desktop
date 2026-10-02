package mihon.desktop.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRefreshAcceptanceTest {
    @Test
    fun `cancellation during the original scope query stays an owned cancellation`(
        @org.junit.jupiter.api.io.TempDir root: java.io.File,
    ) = runTest {
        val entered = CompletableDeferred<Unit>()
        val scheduler = LibraryUpdateScheduler(
            DesktopAppPreferences(InMemoryPreferenceStore()),
            null,
            null,
            null,
            scope = this,
            taskScheduler = mihon.desktop.task.DesktopTaskScheduler(
                mihon.desktop.task.FileTaskCheckpointStore(root.toPath().resolve("tasks.json")),
            ),
            libraryProvider = {
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            },
        )
        try {
            val accepted = requireNotNull(scheduler.acceptNow())
            entered.await()
            accepted.job.cancel()
            org.junit.jupiter.api.Assertions.assertTrue(
                accepted.awaitCompletion().requestCancelled,
                "Provider cancellation belongs to the request even before its unit loop",
            )
        } finally {
            scheduler.stopAndJoin()
        }
    }

    @Test
    fun `accepted request cancelled before its lazy body starts has an explicit owned result`() = runTest {
        val owner = kotlinx.coroutines.Job().apply { cancel() }
        val scheduler = LibraryUpdateScheduler(
            DesktopAppPreferences(InMemoryPreferenceStore()),
            null,
            null,
            null,
            scope = kotlinx.coroutines.CoroutineScope(
                owner + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            ),
        )
        val accepted = requireNotNull(scheduler.acceptNow())
        val result = runCatching { accepted.awaitCompletion() }.getOrNull()
        org.junit.jupiter.api.Assertions.assertEquals(
            true,
            result?.requestCancelled,
            "Cancelled acceptance returns its cancellation without a missing-result exception",
        )
    }

    @Test
    fun `initial store refusal is an owned failure without borrowing historical completion`(
        @org.junit.jupiter.api.io.TempDir root: java.io.File,
    ) = runTest {
        var reject = false
        val tasks = mihon.desktop.task.DesktopTaskScheduler(
            mihon.desktop.task.FileTaskCheckpointStore(root.toPath().resolve("tasks.json")) {
                    from,
                    to,
                ->
                if (reject) throw java.io.IOException("Checkpoint refused")
                java.nio.file.Files.move(
                    from,
                    to,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                true
            },
        )
        val manga = Manga.create().copy(id = 1, source = 42, url = "/1", favorite = true)
        var calls = 0
        val scheduler = LibraryUpdateScheduler(
            DesktopAppPreferences(InMemoryPreferenceStore()),
            null,
            null,
            null,
            scope = this,
            taskScheduler = tasks,
            libraryProvider = { listOf(LibraryManga(manga, emptyList(), 0, 0, 0, 0, 0, 0)) },
            updateManga = {
                calls++
                LibraryUpdateChecker.UpdateResult(0)
            },
        )
        try {
            scheduler.runNow().join()
            org.junit.jupiter.api.Assertions.assertEquals(
                mihon.domain.task.TaskStatus.Completed,
                scheduler.taskSnapshot()?.status,
            )
            reject = true
            val result = requireNotNull(scheduler.acceptNow()).awaitCompletion()
            org.junit.jupiter.api.Assertions.assertNotNull(result.launchFailure)
            org.junit.jupiter.api.Assertions.assertNull(
                result.task,
                "This request did not accept the historical successful occurrence",
            )
            org.junit.jupiter.api.Assertions.assertEquals(1, calls)
            org.junit.jupiter.api.Assertions.assertEquals(
                mihon.domain.task.TaskStatus.Completed,
                scheduler.taskSnapshot()?.status,
            )
        } finally {
            scheduler.stopAndJoin()
        }
    }

    @Test
    fun `accepted completion retains its original occurrence after another scope starts`(
        @org.junit.jupiter.api.io.TempDir root: java.io.File,
    ) = runTest {
        val release = CompletableDeferred<Unit>()
        val manga = Manga.create().copy(id = 1, source = 42, url = "/1", favorite = true)
        val tasks = mihon.desktop.task.DesktopTaskScheduler(
            mihon.desktop.task.FileTaskCheckpointStore(root.toPath().resolve("tasks.json")),
        )
        var calls = 0
        val scheduler = LibraryUpdateScheduler(
            appPreferences = DesktopAppPreferences(InMemoryPreferenceStore()),
            updateChecker = null,
            getLibraryManga = null,
            sourceManager = null,
            scope = this,
            taskScheduler = tasks,
            libraryProvider = { listOf(LibraryManga(manga, listOf(1L), 0, 0, 0, 0, 0, 0)) },
            updateManga = {
                if (calls++ ==
                    0
                ) {
                    throw java.io.IOException("Original failed")
                }
                release.await()
                LibraryUpdateChecker.UpdateResult(0)
            },
        )
        try {
            val accepted = requireNotNull(scheduler.acceptNow(1))
            accepted.job.join()
            val original = requireNotNull(scheduler.taskSnapshot())
            org.junit.jupiter.api.Assertions.assertEquals(mihon.domain.task.TaskStatus.Failed, original.status)
            scheduler.runNow()
            runCurrent()
            org.junit.jupiter.api.Assertions.assertEquals(
                mihon.domain.task.TaskStatus.Running,
                scheduler.taskSnapshot()?.status,
            )
            val result = accepted.awaitCompletion()
            org.junit.jupiter.api.Assertions.assertEquals(
                original.task.idempotencyKey,
                result.task?.task?.idempotencyKey,
                "Completion belongs to the accepted occurrence",
            )
            org.junit.jupiter.api.Assertions.assertEquals(mihon.domain.task.TaskStatus.Failed, result.task?.status)
        } finally {
            release.complete(Unit)
            scheduler.stopAndJoin()
        }
    }

    @Test
    fun `busy scope never accepts another single or category as the existing job`() = runTest {
        val release = CompletableDeferred<Unit>()
        val manga = Manga.create().copy(id = 1, source = 42, url = "/1", favorite = true)
        val scheduler = LibraryUpdateScheduler(
            appPreferences = DesktopAppPreferences(InMemoryPreferenceStore()),
            updateChecker = null,
            getLibraryManga = null,
            sourceManager = null,
            scope = this,
            libraryProvider = { listOf(LibraryManga(manga, emptyList(), 0, 0, 0, 0, 0, 0)) },
            updateManga = {
                release.await()
                LibraryUpdateChecker.UpdateResult(0)
            },
        )
        try {
            scheduler.runNow()
            runCurrent()
            assertNull(scheduler.tryRunSingle(2), "A running scope cannot count as acceptance of manga B")
            assertNull(scheduler.tryRunNow(7), "A running scope cannot count as acceptance of category 7")
        } finally {
            release.complete(Unit)
            scheduler.stopAndJoin()
        }
    }
}
