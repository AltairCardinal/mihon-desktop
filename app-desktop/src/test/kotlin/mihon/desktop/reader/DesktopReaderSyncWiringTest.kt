package mihon.desktop.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.download.DownloadQueueStatus
import mihon.domain.reader.partial.PartialCommittedPage
import mihon.domain.reader.partial.PartialDownloadSnapshot
import mihon.domain.reader.partial.PartialDownloadSnapshotLookup
import mihon.domain.reader.partial.PartialPageTable
import mihon.domain.reader.partial.PartialPageTableEntry
import mihon.domain.sync.SyncMutationContext
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.reader.model.ReadingSyncScope
import tachiyomi.domain.reader.model.ReadingSyncSnapshot
import tachiyomi.domain.reader.repository.ReadingProgressRepository
import tachiyomi.domain.source.service.SourceManager
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopReaderSyncWiringTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `failed causal snapshot opens the existing chapter error and can be retried`() = runTest {
        val repository = CapturingRepository(failFirstOpen = true)
        val runtime = runtime(repository)
        try {
            advanceUntilIdle()
            val state = runtime.session.state.value.snapshot.activeChapter.loadState
            assertTrue(state is mihon.domain.reader.session.ReaderChapterLoadState.Error, state.toString())
            assertTrue(repository.records.isEmpty())
            runtime.session.retryChapter()
            advanceUntilIdle()
            settle(runtime, 0)
            advanceUntilIdle()
            assertEquals(listOf(2L), repository.records.map { it.second?.scope?.epoch })
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `real runtime opens a causal session before accepting user progress`() = runTest {
        val repository = CapturingRepository()
        val runtime = runtime(repository)
        try {
            advanceUntilIdle()
            assertEquals(listOf(1L), repository.opened)
            settle(runtime, 0)
            advanceUntilIdle()
            assertTrue(repository.records.isNotEmpty())
            assertTrue(repository.records.all { it.first.syncContext == SyncMutationContext.User })
            assertTrue(repository.records.all { it.second?.scope?.epoch == 1L })
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `queued progress keeps its activation after reopening and reader disposal`() = runTest {
        val repository = CapturingRepository(blockFirstRecord = true)
        val runtime = runtime(repository)
        try {
            advanceUntilIdle()
            settle(runtime, 0)
            runCurrent()
            settle(runtime, 1)
            runCurrent()
            runtime.session.activate(context())
            advanceUntilIdle()
            assertEquals(listOf(1L, 1L), repository.opened)
            settle(runtime, 2)
            runCurrent()
            runtime.close()
            repository.recordGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(0, 1, 2), repository.records.map { it.first.lastPageRead })
            assertEquals(listOf(1L, 1L, 2L), repository.records.map { it.second?.scope?.epoch })
        } finally {
            repository.recordGate.complete(Unit)
            runtime.close()
        }
    }

    @Test
    fun `late session opening cannot replace the newer activation`() = runTest {
        val repository = CapturingRepository(blockFirstOpen = true)
        val runtime = runtime(repository)
        try {
            runCurrent()
            runtime.session.activate(context())
            advanceUntilIdle()
            repository.openGate.complete(Unit)
            advanceUntilIdle()
            settle(runtime, 0)
            advanceUntilIdle()
            assertEquals(listOf(1L, 1L), repository.opened)
            assertEquals(listOf(2L), repository.records.map { it.second?.scope?.epoch })
        } finally {
            repository.openGate.complete(Unit)
            runtime.close()
        }
    }

    @Test
    fun `progress accepted while incognito cannot upload after incognito is disabled`() = runTest {
        val repository = CapturingRepository(blockFirstRecord = true)
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore()).apply { incognitoMode.set(true) }
        val runtime = runtime(repository, preferences)
        try {
            advanceUntilIdle()
            settle(runtime, 0)
            runCurrent()
            settle(runtime, 1)
            runCurrent()
            preferences.incognitoMode.set(false)
            repository.recordGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(2, repository.records.size)
            assertTrue(repository.records.all { !it.first.syncContext.uploadAllowed })
        } finally {
            repository.recordGate.complete(Unit)
            runtime.close()
        }
    }

    private fun TestScope.runtime(
        repository: CapturingRepository,
        preferences: DesktopAppPreferences? = null,
    ): DesktopReaderRuntime {
        val pages = List(3) { index ->
            directory.resolve("$index.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        }
        val lookup = PartialDownloadSnapshotLookup { _, identity ->
            PartialDownloadSnapshot(
                chapterId = 1L,
                identity = identity,
                attemptGeneration = 1L,
                queueStatus = DownloadQueueStatus.DOWNLOADING,
                pageTable = PartialPageTable.complete(
                    pages.mapIndexed { index, _ ->
                        PartialPageTableEntry(index, index, "/page/$index", "https://fixture.invalid/$index.png")
                    },
                ),
                committedPages = pages.mapIndexed { index, file ->
                    PartialCommittedPage(index, index, file.absolutePath, 1L)
                },
            )
        }
        val source = mockk<CatalogueSource>(relaxed = true) { every { id } returns 42L }
        val sources = object : SourceManager {
            override val isInitialized = kotlinx.coroutines.flow.MutableStateFlow(true)
            override val catalogueSources = kotlinx.coroutines.flow.flowOf(listOf(source))
            override fun get(sourceKey: Long) = source
            override fun getOrStub(sourceKey: Long) = source
            override fun getOnlineSources(): List<eu.kanade.tachiyomi.source.online.HttpSource> = emptyList()
            override fun getCatalogueSources() = listOf(source)
            override fun getStubSources(): List<tachiyomi.domain.source.model.StubSource> = emptyList()
        }
        return DesktopReaderRuntimeFactory(
            prefs = ReaderPreferences(),
            downloadProvider = DesktopDownloadProvider(directory.resolve("downloads")),
            sourceManager = sources,
            networkHelper = NetworkHelper(OkHttpClient()),
            progressTracker = ReaderProgressTracker(RecordReadingProgress(repository), preferences),
            mangaRepository = null,
            encodedCacheDirectory = directory.resolve("encoded"),
            partialDownloadSnapshotLookup = lookup,
        ).createRuntime(context(), this)
    }

    private fun settle(runtime: DesktopReaderRuntime, index: Int) {
        val page = runtime.session.state.value.snapshot.activeChapter.pages[index].id
        runtime.session.settleViewport(setOf(page), page)
    }

    private fun context() = DesktopReaderChapterContext(
        chapterId = 1L, sourceId = 42L, chapterUrl = "/chapter/1", mangaTitle = "Manga",
        chapterTitle = "Chapter 1", chapterNumber = 1.0, chapterIndex = 0, initialPage = 0,
        wasRead = false, mangaId = 1L,
    )

    private class CapturingRepository(
        private val blockFirstOpen: Boolean = false,
        private val blockFirstRecord: Boolean = false,
        private val failFirstOpen: Boolean = false,
    ) : ReadingProgressRepository {
        val opened = mutableListOf<Long>()
        val records = mutableListOf<Pair<ReadingProgressEvent, ReadingSyncSnapshot?>>()
        val openGate = CompletableDeferred<Unit>()
        val recordGate = CompletableDeferred<Unit>()

        override suspend fun beginSyncSession(chapterId: Long): ReadingSyncSnapshot {
            opened += chapterId
            val ordinal = opened.size.toLong()
            if (failFirstOpen && ordinal == 1L) error("Snapshot storage unavailable")
            if (blockFirstOpen && ordinal == 1L) withContext(NonCancellable) { openGate.await() }
            return ReadingSyncSnapshot(ReadingSyncScope("space", 0, "actor", ordinal))
        }

        override suspend fun record(event: ReadingProgressEvent) {
            capture(event, null)
        }

        override suspend fun record(event: ReadingProgressEvent, snapshot: ReadingSyncSnapshot): ReadingSyncSnapshot {
            capture(event, snapshot)
            return snapshot
        }

        private suspend fun capture(event: ReadingProgressEvent, snapshot: ReadingSyncSnapshot?) {
            records += event to snapshot
            if (blockFirstRecord && records.size == 1) recordGate.await()
        }
    }
}
