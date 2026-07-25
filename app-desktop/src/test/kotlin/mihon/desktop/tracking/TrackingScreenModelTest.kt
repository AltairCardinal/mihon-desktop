package mihon.desktop.tracking

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.ui.tracking.TrackingMessage
import mihon.desktop.ui.tracking.TrackingMessageException
import mihon.desktop.ui.tracking.TrackingScreenModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackSearchResult
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerProfile
import tachiyomi.domain.track.service.TrackerProviderCatalog
import tachiyomi.domain.track.service.TrackerProviderError
import tachiyomi.domain.track.service.TrackerProviderErrorKind
import tachiyomi.domain.track.service.TrackerProviderOperation
import tachiyomi.domain.track.service.TrackerProviderRequest
import tachiyomi.domain.track.service.TrackerProviderResult
import tachiyomi.domain.track.service.TrackerProviderResultException
import tachiyomi.domain.track.service.TrackerProviderService
import tachiyomi.domain.track.service.TrackerProviderSession
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.domain.track.service.TrackerServiceRegistry

class TrackingScreenModelTest {
    @Test
    fun `bind forwards production chapter read state only to provider services`() = runTest {
        for (hasRead in listOf(false, true)) {
            val repository = FakeTrackRepository()
            val chapters = FakeChapterRepository().apply {
                seed(
                    tachiyomi.domain.chapter.model.Chapter.create().copy(
                        id = 1,
                        mangaId = 42,
                        read = hasRead,
                    ),
                )
            }
            val provider = FakeTrackerService(1, loggedIn = true)
            val model = TrackingScreenModel(
                mangaId = 42,
                mangaTitle = "Manga",
                totalChapters = 12,
                repository = repository,
                chapterRepository = chapters,
                registry = registry(provider),
            ).also { it.load() }

            model.bind(1, TrackSearchResult(10, "Manga", 12))

            assertEquals(listOf(hasRead), provider.bindReadStates)
        }
    }

    @Test
    fun `load merges persisted manga tracks with every registered service`() = runTest {
        val bound = track(trackerId = 1)
        val repository = FakeTrackRepository(mutableListOf(bound))
        val loggedIn = FakeTrackerService(1, loggedIn = true)
        val unavailable = FakeTrackerService(2, loggedIn = false, unavailableReason = "Source is not configured")
        val model = TrackingScreenModel(42, "Manga", 12, repository, registry(loggedIn, unavailable))

        model.load()

        assertFalse(model.state.value.loading)
        assertEquals(2, model.state.value.services.size)
        assertEquals(bound, model.state.value.services.single { it.profile.id == 1L }.track)
        assertEquals(null, model.state.value.services.single { it.profile.id == 2L }.track)
        assertEquals("Source is not configured", model.state.value.services.single { it.profile.id == 2L }.profile.unavailableReason)
    }

    @Test
    fun `bind uses real service result and persists returned track`() = runTest {
        val repository = FakeTrackRepository()
        val service = FakeTrackerService(1, loggedIn = true)
        val model = TrackingScreenModel(42, "Real title", 12, repository, registry(service))
        model.load()

        val results = model.search(1, "Real title")
        model.bind(1, results.single())

        assertEquals(listOf("Real title"), service.searches)
        assertNotNull(repository.rows.singleOrNull())
        assertEquals(repository.rows.single(), model.state.value.services.single().track)
        assertEquals(TrackingMessage.Bound, model.state.value.feedback)
    }

    @Test
    fun `update accepts only provider choices and chapter boundary then persists atomically`() = runTest {
        val original = track(trackerId = 1)
        val repository = FakeTrackRepository(mutableListOf(original))
        val service = FakeTrackerService(1, loggedIn = true)
        val model = TrackingScreenModel(42, "Manga", 12, repository, registry(service))
        model.load()

        assertTrue(runCatching { model.update(1, TrackEdit(status = 99)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { model.update(1, TrackEdit(score = 7.5)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { model.update(1, TrackEdit(lastChapterRead = 13.0)) }.exceptionOrNull() is IllegalArgumentException)
        model.update(1, TrackEdit(status = 2, score = 10.0, lastChapterRead = 12.0))

        assertEquals(2, repository.rows.single().status)
        assertEquals(10.0, repository.rows.single().score)
        assertEquals(12.0, repository.rows.single().lastChapterRead)
        assertEquals(TrackingMessage.Updated, model.state.value.feedback)
    }

    @Test
    fun `remote update failure keeps persisted and visible local track`() = runTest {
        val original = track(trackerId = 1)
        val repository = FakeTrackRepository(mutableListOf(original))
        val service = FakeTrackerService(1, loggedIn = true).apply { updateFailure = IllegalStateException("remote failed") }
        val model = TrackingScreenModel(42, "Manga", null, repository, registry(service))
        model.load()

        val failure = runCatching { model.update(1, TrackEdit(lastChapterRead = 2.0)) }.exceptionOrNull()

        assertEquals("remote failed", failure?.message)
        assertEquals(original, repository.rows.single())
        assertEquals(original, model.state.value.services.single().track)
    }

    @Test
    fun `unbind deletes only selected service binding and logout clears service session after confirmation action`() = runTest {
        val repository = FakeTrackRepository(mutableListOf(track(1), track(2)))
        val service = FakeTrackerService(1, loggedIn = true)
        val model = TrackingScreenModel(42, "Manga", 12, repository, registry(service, FakeTrackerService(2, true)))
        model.load()

        model.unbind(1)
        assertEquals(TrackingMessage.Removed, model.state.value.feedback)
        assertTrue(service.providerRequests.isEmpty())
        model.logout(1)

        assertEquals(listOf(2L), repository.rows.map { it.trackerId })
        assertTrue(service.loggedOut)
        assertFalse(service.profile.value.loggedIn)
        assertEquals(TrackingMessage.LoggedOut, model.state.value.feedback)
    }

    @Test
    fun `unbind removes and refreshes local binding before optional remote delete settles`() = runTest {
        val original = track(1)
        val events = mutableListOf<String>()
        val repository = FakeTrackRepository(mutableListOf(original), events)
        val service = FakeTrackerService(1, loggedIn = true, supportsDelete = true)
        val model = TrackingScreenModel(42, "Manga", 12, repository, registry(service)).also { it.load() }
        service.events = events
        service.executeGate = CompletableDeferred()
        service.providerFailure = TrackerProviderError(
            TrackerProviderOperation.DELETE,
            TrackerProviderErrorKind.RATE_LIMITED,
            429,
        )

        val pending = async { runCatching { model.unbind(1, removeRemoteTrack = true) } }
        runCurrent()

        assertEquals(listOf("local", "remote"), events)
        assertTrue(repository.rows.isEmpty())
        assertEquals(null, model.state.value.services.single().track)
        assertEquals(TrackingMessage.Removed, model.state.value.feedback)

        service.executeGate!!.complete(Unit)
        val failure = assertInstanceOf(
            TrackerProviderResultException::class.java,
            pending.await().exceptionOrNull(),
        )
        assertEquals(TrackerProviderErrorKind.RATE_LIMITED, failure.error.kind)
        assertTrue(service.providerRequests.single() is TrackerProviderRequest.Delete)
    }

    @Test
    fun `logged out and unavailable providers cannot block local unbind`() = runTest {
        listOf(
            FakeTrackerService(1, loggedIn = false, supportsDelete = true),
            FakeTrackerService(1, loggedIn = false, unavailableReason = "Provider unavailable", supportsDelete = true),
        ).forEach { service ->
            val repository = FakeTrackRepository(mutableListOf(track(1)))
            val model = TrackingScreenModel(42, "Manga", 12, repository, registry(service)).also { it.load() }

            model.unbind(1, removeRemoteTrack = false)

            assertTrue(repository.rows.isEmpty())
            assertEquals(null, model.state.value.services.single().track)
            assertEquals(TrackingMessage.Removed, model.state.value.feedback)
            assertTrue(service.providerRequests.isEmpty())
        }
    }

    @Test
    fun `remote authentication availability and timeout failures preserve completed local unbind`() = runTest {
        listOf(
            FakeTrackerService(1, loggedIn = false, supportsDelete = true).apply {
                providerFailure = TrackerProviderError(
                    TrackerProviderOperation.DELETE,
                    TrackerProviderErrorKind.AUTHENTICATION,
                )
            },
            FakeTrackerService(1, loggedIn = false, unavailableReason = "Provider unavailable", supportsDelete = true).apply {
                providerFailure = TrackerProviderError(
                    TrackerProviderOperation.DELETE,
                    TrackerProviderErrorKind.NOT_CONFIGURED,
                )
            },
        ).forEach { service ->
            val repository = FakeTrackRepository(mutableListOf(track(1)))
            val model = TrackingScreenModel(42, "Manga", 12, repository, registry(service)).also { it.load() }

            assertInstanceOf(
                TrackerProviderResultException::class.java,
                runCatching { model.unbind(1, removeRemoteTrack = true) }.exceptionOrNull(),
            )
            assertTrue(repository.rows.isEmpty())
            assertEquals(null, model.state.value.services.single().track)
            assertEquals(TrackingMessage.Removed, model.state.value.feedback)
            assertTrue(service.providerRequests.single() is TrackerProviderRequest.Delete)
        }

        val timedOutRepository = FakeTrackRepository(mutableListOf(track(1)))
        val timedOutService = FakeTrackerService(1, loggedIn = true, supportsDelete = true).apply {
            executeGate = CompletableDeferred()
        }
        val timedOutModel = TrackingScreenModel(
            42,
            "Manga",
            12,
            timedOutRepository,
            registry(timedOutService),
        ).also { it.load() }

        assertInstanceOf(
            TimeoutCancellationException::class.java,
            runCatching {
                withTimeout(1) { timedOutModel.unbind(1, removeRemoteTrack = true) }
            }.exceptionOrNull(),
        )
        assertTrue(timedOutRepository.rows.isEmpty())
        assertEquals(null, timedOutModel.state.value.services.single().track)
        assertEquals(TrackingMessage.Removed, timedOutModel.state.value.feedback)
    }

    @Test
    fun `validation failures stay typed and never call services or write repository`() = runTest {
        val repository = FakeTrackRepository(mutableListOf(track(1)))
        val service = FakeTrackerService(1, loggedIn = true)
        val model = TrackingScreenModel(42, "Manga", 12, repository, registry(service)).also { it.load() }

        assertTypedFailure(TrackingMessage.SearchTitleEmpty) { model.search(1, " ") }
        assertTypedFailure(TrackingMessage.UnsupportedStatus("Service 1")) { model.update(1, TrackEdit(status = 99)) }
        assertTypedFailure(TrackingMessage.UnsupportedScore("Service 1")) { model.update(1, TrackEdit(score = 7.5)) }
        assertTypedFailure(TrackingMessage.NegativeChapter) { model.update(1, TrackEdit(lastChapterRead = -1.0)) }
        assertTypedFailure(TrackingMessage.ChapterOutOfRange(12)) { model.update(1, TrackEdit(lastChapterRead = 13.0)) }
        assertTypedFailure(TrackingMessage.UnknownService) { model.search(404, "Manga") }

        val emptyRepository = FakeTrackRepository()
        val emptyService = FakeTrackerService(2, loggedIn = true)
        val emptyModel = TrackingScreenModel(42, "Manga", 12, emptyRepository, registry(emptyService)).also { it.load() }
        assertTypedFailure(TrackingMessage.NotBound) { emptyModel.update(2, TrackEdit(status = 1)) }
        val noMangaModel = TrackingScreenModel(null, "Manga", 12, emptyRepository, registry(emptyService)).also { it.load() }
        assertTypedFailure(TrackingMessage.MangaRequired) { noMangaModel.bind(2, TrackSearchResult(10, "Manga", 12)) }

        val unavailable = FakeTrackerService(3, loggedIn = false, unavailableReason = "")
        val unavailableModel = TrackingScreenModel(42, "Manga", 12, emptyRepository, registry(unavailable)).also { it.load() }
        assertTypedFailure(TrackingMessage.ServiceUnavailable) { unavailableModel.search(3, "Manga") }
        val providerReason = FakeTrackerService(4, loggedIn = false, unavailableReason = "Provider needs setup")
        val providerModel = TrackingScreenModel(42, "Manga", 12, emptyRepository, registry(providerReason)).also { it.load() }
        assertTypedFailure(TrackingMessage.External("Provider needs setup")) { providerModel.search(4, "Manga") }
        val loggedOut = FakeTrackerService(5, loggedIn = false)
        val loggedOutModel = TrackingScreenModel(42, "Manga", 12, emptyRepository, registry(loggedOut)).also { it.load() }
        assertTypedFailure(TrackingMessage.LoginRequired) { loggedOutModel.search(5, "Manga") }

        assertEquals(0, repository.insertCalls + repository.deleteCalls + emptyRepository.insertCalls + emptyRepository.deleteCalls)
        assertEquals(0, service.bindCalls + service.updateCalls + emptyService.bindCalls + emptyService.updateCalls)
        assertTrue(listOf(unavailable, providerReason, loggedOut).all { it.searches.isEmpty() })
    }

    @Test
    fun `load and report error keep typed fallback and external exception detail`() = runTest {
        val repository = FakeTrackRepository()
        val fallbackModel = TrackingScreenModel(42, "Manga", 12, repository, failingRegistry(IllegalStateException()))
        fallbackModel.load()
        assertEquals(TrackingMessage.LoadFailed, fallbackModel.state.value.error)

        val externalModel = TrackingScreenModel(42, "Manga", 12, repository, failingRegistry(IllegalStateException("Registry detail")))
        externalModel.load()
        assertEquals(TrackingMessage.External("Registry detail"), externalModel.state.value.error)
        val typedFailure = runCatching { externalModel.search(1, "Manga") }.exceptionOrNull()!!
        externalModel.reportError(typedFailure, TrackingMessage.LoadFailed)
        assertEquals(TrackingMessage.UnknownService, externalModel.state.value.error)
        externalModel.reportError(IllegalStateException("Provider detail"), TrackingMessage.LoadFailed)
        assertEquals(TrackingMessage.External("Provider detail"), externalModel.state.value.error)
        listOf(
            TrackingMessage.LoadFailed,
            TrackingMessage.LoginCancelled,
            TrackingMessage.LoginFailed,
            TrackingMessage.LogoutFailed,
            TrackingMessage.UnbindFailed,
        ).forEach { fallback ->
            externalModel.reportError(IllegalStateException(), fallback)
            assertEquals(fallback, externalModel.state.value.error)
        }
    }

    private fun TrackingScreenModel(
        mangaId: Long?,
        mangaTitle: String?,
        totalChapters: Long?,
        repository: TrackRepository,
        registry: TrackerServiceRegistry,
    ) = mihon.desktop.ui.tracking.TrackingScreenModel(
        mangaId = mangaId,
        mangaTitle = mangaTitle,
        totalChapters = totalChapters,
        repository = repository,
        chapterRepository = FakeChapterRepository(),
        registry = registry,
    )

    private fun registry(vararg services: TrackerService) = object : TrackerServiceRegistry {
        override val services = services.toList()
    }

    private fun failingRegistry(failure: Throwable) = object : TrackerServiceRegistry {
        override val services = emptyList<TrackerService>()
        override fun refresh() = throw failure
    }

    private suspend fun assertTypedFailure(expected: TrackingMessage, block: suspend () -> Any?) {
        val failure = assertInstanceOf(TrackingMessageException::class.java, runCatching { block() }.exceptionOrNull())
        assertEquals(expected, failure.trackingMessage)
    }

    private fun track(trackerId: Long) = Track(
        id = trackerId,
        mangaId = 42,
        trackerId = trackerId,
        remoteId = trackerId * 10,
        libraryId = null,
        title = "Manga",
        lastChapterRead = 1.0,
        totalChapters = 12,
        status = 1,
        score = 0.0,
        remoteUrl = "https://example/$trackerId",
        startDate = 0,
        finishDate = 0,
        private = false,
    )

    private class FakeTrackRepository(
        val rows: MutableList<Track> = mutableListOf(),
        private val events: MutableList<String>? = null,
    ) : TrackRepository {
        var insertCalls = 0
        var deleteCalls = 0
        override suspend fun getTrackById(id: Long) = rows.firstOrNull { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long) = rows.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow(): Flow<List<Track>> = flowOf(rows)
        override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> = flowOf(rows.filter { it.mangaId == mangaId })
        override suspend fun delete(mangaId: Long, trackerId: Long) {
            deleteCalls++
            rows.removeAll { it.mangaId == mangaId && it.trackerId == trackerId }
            events?.add("local")
        }
        override suspend fun insert(track: Track) { insertCalls++; rows.removeAll { it.mangaId == track.mangaId && it.trackerId == track.trackerId }; rows += track }
        override suspend fun insertAll(tracks: List<Track>) { tracks.forEach { insert(it) } }
    }

    private class FakeTrackerService(
        id: Long,
        loggedIn: Boolean,
        unavailableReason: String? = null,
        supportsDelete: Boolean = false,
    ) : TrackerProviderService {
        override val profile = MutableStateFlow(TrackerProfile(id, "Service $id", TrackerAuthentication.OAUTH, loggedIn, unavailableReason = unavailableReason))
        override val configuration = TrackerProviderCatalog.configuration(id).copy(supportsDelete = supportsDelete)
        override val session: TrackerProviderSession
            get() = TrackerProviderSession(profile.value.id, profile.value.loggedIn, profile.value.username)
        override val statuses = listOf(1L to "Reading", 2L to "Completed")
        override val scores = listOf(0.0, 10.0)
        val searches = mutableListOf<String>()
        val providerRequests = mutableListOf<TrackerProviderRequest>()
        var providerFailure: TrackerProviderError? = null
        var executeGate: CompletableDeferred<Unit>? = null
        var events: MutableList<String>? = null
        var updateFailure: Throwable? = null
        var loggedOut = false
        var bindCalls = 0
        val bindReadStates = mutableListOf<Boolean>()
        var updateCalls = 0

        override suspend fun search(query: String): List<TrackSearchResult> {
            searches += query
            return listOf(TrackSearchResult(10, query, 12))
        }

        override suspend fun bind(mangaId: Long, result: TrackSearchResult): Track {
            bindCalls++
            return Track(
                id = profile.value.id,
                mangaId = mangaId,
                trackerId = profile.value.id,
                remoteId = result.remoteId,
                libraryId = null,
                title = result.title,
                lastChapterRead = 0.0,
                totalChapters = result.totalChapters,
                status = 1,
                score = 0.0,
                remoteUrl = result.remoteUrl,
                startDate = 0,
                finishDate = 0,
                private = false,
            )
        }
        override suspend fun bind(
            mangaId: Long,
            result: TrackSearchResult,
            hasReadChapters: Boolean,
        ): Track {
            bindReadStates += hasReadChapters
            return bind(mangaId, result)
        }
        override suspend fun update(track: Track, edit: TrackEdit): Track {
            updateCalls++
            updateFailure?.let { throw it }
            return track.copy(
                status = edit.status ?: track.status,
                score = edit.score ?: track.score,
                lastChapterRead = edit.lastChapterRead ?: track.lastChapterRead,
            )
        }
        override suspend fun execute(request: TrackerProviderRequest): TrackerProviderResult {
            providerRequests += request
            events?.add("remote")
            executeGate?.await()
            providerFailure?.let { return TrackerProviderResult.Failure(it) }
            return TrackerProviderResult.Success(request.track)
        }
        override suspend fun logout() { loggedOut = true; profile.value = profile.value.copy(loggedIn = false) }
    }
}
