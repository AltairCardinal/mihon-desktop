package mihon.desktop.ui.library

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import io.mockk.mockk
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.fakes.FakeCategoryRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.download.DesktopDownloadProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.library.interactor.LibraryFilter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackerSessionProvider
import java.nio.file.Path

class LibraryPageCompositionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `LibraryTab page projection follows tracker session local download and multiple flags`() = runTest {
        val mangaRepository = FakeMangaRepository().apply {
            libraryManga = listOf(
                sampleLibraryManga(sampleManga(1L, "Downloaded", 10L)).copy(totalChapters = 2L),
                sampleLibraryManga(sampleManga(2L, "Local", 0L)).copy(totalChapters = 2L),
                sampleLibraryManga(sampleManga(3L, "Historical", 30L)).copy(totalChapters = 2L),
            )
        }
        val downloadedChapter = tempDir.resolve("10/Downloaded/Chapter 1")
        Files.createDirectories(downloadedChapter)
        Files.write(
            downloadedChapter.resolve("page.png"),
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        )
        val tracks = MutableStateFlow(listOf(sampleTrack(mangaId = 3L, trackerId = 7L)))
        val sessions = MutableStateFlow(emptySet<Long>())
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(mangaRepository),
            getCategories = GetCategories(FakeCategoryRepository()),
            downloadProvider = DesktopDownloadProvider(tempDir.toFile()),
            trackRepository = trackRepositoryOf(tracks),
            trackerSessionProvider = TrackerSessionProvider { sessions },
        )
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true)
        model.setFilter(
            LibraryFilter(
                downloaded = TriState.ENABLED_NOT,
                unread = TriState.ENABLED_IS,
                tracking = mapOf(7L to TriState.ENABLED_IS),
            ),
        )
        var snapshot: LibraryPageSnapshot? = null
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        var composition: Composition? = null
        val recomposerJob = launch(frameClock, start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        model.libraryMangaFlow().launchIn(backgroundScope)
        runCurrent()

        fun render(frame: Long) {
            composition?.dispose()
            val nextComposition = Composition(UnitTestApplier(), recomposer)
            composition = nextComposition
            nextComposition.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    ProvideLibraryScreenModelFactory(factory = { model }) {
                        ProvideLibraryPageProbe(probe = { snapshot = it }) {
                            LibraryTab.Content()
                        }
                    }
                }
            }
            frameClock.sendFrame(frame)
        }
        render(0L)
        runCurrent()

        assertEquals(emptySet<Long>(), snapshot?.availableTrackerIds)
        assertEquals(listOf(1L, 2L, 3L), model.state.value.allItems.map { it.id })
        assertEquals(listOf(3L), snapshot?.visibleItemIds)

        sessions.value = setOf(7L)
        runCurrent()
        model.toggleTrackingFilter(7L)
        render(1L)
        runCurrent()
        assertEquals(setOf(7L), snapshot?.availableTrackerIds)
        assertEquals(listOf(3L), snapshot?.visibleItemIds)

        sessions.value = emptySet()
        runCurrent()
        render(2L)
        runCurrent()
        assertEquals(emptySet<Long>(), snapshot?.availableTrackerIds)
        assertEquals(listOf(3L), snapshot?.visibleItemIds)

        model.setFilter(LibraryFilter(downloaded = TriState.ENABLED_IS))
        render(3L)
        runCurrent()
        assertEquals(listOf(1L, 2L), snapshot?.visibleItemIds)

        composition?.dispose()
        recomposer.close()
        recomposerJob.cancelAndJoin()
    }

    private fun sampleManga(id: Long, title: String, source: Long) = Manga.create().copy(
        id = id,
        title = title,
        source = source,
        favorite = true,
    )

    private fun sampleLibraryManga(manga: Manga) = LibraryManga(
        manga = manga,
        categories = emptyList(),
        totalChapters = 0L,
        readCount = 0L,
        bookmarkCount = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = 0L,
    )

    private fun sampleTrack(mangaId: Long, trackerId: Long) = Track(
        id = mangaId,
        mangaId = mangaId,
        trackerId = trackerId,
        remoteId = mangaId,
        libraryId = null,
        title = "Track",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 0L,
        score = 8.0,
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    private fun trackRepositoryOf(tracks: MutableStateFlow<List<Track>>) = object : TrackRepository {
        override suspend fun getTrackById(id: Long) = tracks.value.singleOrNull { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long) = tracks.value.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow(): Flow<List<Track>> = tracks
        override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> =
            tracks.map { values -> values.filter { it.mangaId == mangaId } }
        override suspend fun delete(mangaId: Long, trackerId: Long) = Unit
        override suspend fun insert(track: Track) = Unit
        override suspend fun insertAll(tracks: List<Track>) = Unit
    }

    private class UnitTestApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
        override fun remove(index: Int, count: Int) = Unit
    }
}
