package mihon.desktop.ui.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import io.mockk.mockk
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.core.screen.Screen
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.fakes.FakeCategoryRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.download.DesktopDownloadProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.interactor.LibraryFilter
import tachiyomi.domain.library.model.LibraryDisplayMode as SharedLibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackerSessionProvider
import tachiyomi.i18n.MR
import java.nio.file.Path

class LibraryPageCompositionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `all root layouts consume live badge and continue preferences including cover only`() = runTest {
        val preferencesNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
        val store = DesktopPreferenceStore(preferencesNode)
        val preferences = LibraryPreferences(store).apply {
            downloadBadge().set(true)
            unreadBadge().set(true)
            localBadge().set(true)
            languageBadge().set(true)
            showContinueReadingButton().set(true)
        }
        val manga = sampleManga(41L, "Layout badges", 0L)
        val mangaRepository = FakeMangaRepository().apply {
            seed(manga)
            libraryManga = listOf(sampleLibraryManga(manga).copy(totalChapters = 3L))
        }
        val chapterRepository = FakeChapterRepository().apply {
            seed(
                Chapter.create().copy(
                    id = 4101L,
                    mangaId = manga.id,
                    url = "/layout/chapter-1",
                    name = "Chapter 1",
                    sourceOrder = 1L,
                ),
            )
        }
        val model = LibraryScreenModel(
            getLibraryManga = GetLibraryManga(mangaRepository),
            getCategories = GetCategories(FakeCategoryRepository()),
            getChaptersByMangaId = GetChaptersByMangaId(chapterRepository),
            isMangaDownloaded = { true },
            libraryPreferences = preferences,
        )
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true)
        var destination: Screen? = null
        val scene = ImageComposeScene(1_200, 900, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    ProvideLibraryScreenModelFactory(factory = { model }) {
                        Navigator(LibraryRootScreen()) { navigator ->
                            destination = navigator.lastItem
                            if (navigator.lastItem is LibraryRootScreen) CurrentScreen()
                        }
                    }
                }
            }

            suspend fun assertIndicators(mode: SharedLibraryDisplayMode) {
                preferences.displayMode().set(mode)
                render(scene)
                val labels = semanticLabels(scene)
                assertTrue(labels.contains(MR.strings.label_downloaded.localized()), mode.toString())
                assertTrue(labels.contains(MR.strings.action_display_local_badge.localized()), mode.toString())
                assertTrue(labels.contains("3"), mode.toString())
                assertTrue(labels.contains(MR.strings.desktop_ui_continue_reading.localized()), mode.toString())
            }

            assertIndicators(SharedLibraryDisplayMode.CompactGrid)
            assertIndicators(SharedLibraryDisplayMode.ComfortableGrid)
            assertIndicators(SharedLibraryDisplayMode.List)
            assertIndicators(SharedLibraryDisplayMode.CoverOnlyGrid)

            preferences.languageBadge().set(false)
            render(scene)
            assertTrue(!model.state.value.showLanguageBadge)
            assertTrue(semanticLabels(scene).contains(MR.strings.action_display_local_badge.localized()))

            preferences.unreadBadge().set(false)
            render(scene)
            val hiddenLabels = semanticLabels(scene)
            assertTrue(!hiddenLabels.contains("3"))
            assertTrue(!hiddenLabels.contains(MR.strings.desktop_ui_continue_reading.localized()))

            preferences.unreadBadge().set(true)
            render(scene)
            click(scene, MR.strings.desktop_ui_continue_reading.localized())
            render(scene)
            assertTrue(destination is mihon.desktop.ui.reader.DesktopReaderScreen)
        } finally {
            scene.close()
            preferencesNode.removeNode()
        }
    }

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
            getTracksPerManga = GetTracksPerManga(trackRepositoryOf(tracks)),
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
        model.libraryMangaFlow().launchIn(backgroundScope)
        runCurrent()

        val scene = ImageComposeScene(1_200, 900, coroutineContext = coroutineContext)
        scene.setContent {
            CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                ProvideLibraryScreenModelFactory(factory = { model }) {
                    ProvideLibraryPageProbe(probe = { snapshot = it }) {
                        Navigator(LibraryRootScreen()) { CurrentScreen() }
                    }
                }
            }
        }

        suspend fun render() {
            repeat(3) {
                scene.render()
                yield()
                runCurrent()
            }
        }

        render()

        assertEquals(emptySet<Long>(), snapshot?.availableTrackerIds)
        assertEquals(listOf(1L, 2L, 3L), model.state.value.allItems.map { it.id })
        assertEquals(listOf(3L), snapshot?.visibleItemIds)

        sessions.value = setOf(7L)
        runCurrent()
        model.toggleTrackingFilter(7L)
        render()
        assertEquals(setOf(7L), snapshot?.availableTrackerIds)
        assertEquals(listOf(3L), snapshot?.visibleItemIds)

        sessions.value = emptySet()
        render()
        assertEquals(emptySet<Long>(), snapshot?.availableTrackerIds)
        assertEquals(listOf(3L), snapshot?.visibleItemIds)

        model.setFilter(LibraryFilter(downloaded = TriState.ENABLED_IS))
        render()
        assertEquals(listOf(1L, 2L), snapshot?.visibleItemIds)

        scene.close()
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

    @OptIn(ExperimentalComposeUiApi::class)
    private fun semanticLabels(scene: ImageComposeScene): List<String> = nodes(scene).flatMap { node ->
        val text = if (node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)) {
            node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].map { it.text }
        } else {
            emptyList()
        }
        val descriptions = if (node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)) {
            node.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
        } else {
            emptyList()
        }
        text + descriptions
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { candidate ->
            candidate.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) &&
                semanticLabels(candidate).contains(label)
        }
        assertTrue(requireNotNull(node.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action).invoke())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun nodes(scene: ImageComposeScene): List<androidx.compose.ui.semantics.SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
        listOf(node) + node.children.flatMap(::flatten)

    private fun semanticLabels(node: androidx.compose.ui.semantics.SemanticsNode): List<String> {
        val text = if (node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)) {
            node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].map { it.text }
        } else {
            emptyList()
        }
        val descriptions = if (node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)) {
            node.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
        } else {
            emptyList()
        }
        return text + descriptions
    }

    private suspend fun render(scene: ImageComposeScene) {
        repeat(4) {
            scene.render()
            yield()
        }
    }

}
