package mihon.desktop.test

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.network.DesktopNetworkMaintenancePort
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.test.http.TrackingTestBridge
import mihon.desktop.tracking.DesktopAuthenticatingTrackerService
import mihon.desktop.tracking.TrackingTestModeController
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackSearchResult
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerProfile
import tachiyomi.domain.track.service.TrackerServiceRegistry
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

@Isolated
class TrackingTestModeLifecycleTest {
    @Test
    fun `real TestMode owns tracking bridge across stop race and restart`(@TempDir tempDir: File) = runBlocking {
        val searchStarted = CompletableDeferred<Unit>()
        val allowSearchReturn = CompletableDeferred<Unit>()
        val service = BlockingSearchService(searchStarted, allowSearchReturn)
        val registry = object : TrackerServiceRegistry {
            override val services = listOf(service)
        }
        val context = initDesktopDIForTest(
            appDir = tempDir,
            preferenceStore = DesktopPreferenceStore(),
            startDownloadWorker = false,
            trackerServiceRegistry = registry,
        )
        val args = TestArguments(
            testMode = true,
            httpPort = 0,
            headless = true,
            screenshotDir = tempDir.resolve("screenshots").absolutePath,
        )
        try {
            assertSame(Injekt.get<DesktopNetworkHelper>(), Injekt.get<DesktopNetworkMaintenancePort>())
            assertNull(TrackingTestBridge.controller)

            TestMode.start(args)
            val first = withTimeout(2_000) {
                requireNotNull(TrackingTestBridge.controller)
            }
            val searching = async {
                first.execute(
                    "tracking_search",
                    mapOf("trackerId" to "176", "title" to "Manga"),
                )
            }
            searchStarted.await()
            val stopping = async(Dispatchers.IO) {
                TestMode.stop()
            }
            withTimeout(2_000) {
                while (!first.snapshot().closed) kotlinx.coroutines.yield()
            }
            assertNull(withTimeoutOrNull(100) { stopping.await() })

            val replacement = trackingControllerFromInjekt()
            TrackingTestBridge.install(replacement)
            allowSearchReturn.complete(Unit)
            stopping.await()
            runCatching { searching.await() }
            assertTrue(first.snapshot().closed)
            assertSame(replacement, TrackingTestBridge.controller)
            replacement.close()

            TestMode.start(args)
            val restarted = withTimeout(2_000) {
                requireNotNull(TrackingTestBridge.controller)
            }
            assertNotSame(replacement, restarted)
            assertFalse(restarted.snapshot().closed)

            TestMode.stop()
            assertNull(TrackingTestBridge.controller)
            assertTrue(restarted.snapshot().closed)
        } finally {
            allowSearchReturn.complete(Unit)
            if (TestMode.isActive()) TestMode.stop()
            TrackingTestBridge.controller?.let {
                it.close()
                TrackingTestBridge.clear(it)
            }
            context.closeAndJoin()
        }
    }

    private fun trackingControllerFromInjekt() = TrackingTestModeController(
        repository = Injekt.get<TrackRepository>(),
        chapterRepository = Injekt.get<ChapterRepository>(),
        registry = Injekt.get<TrackerServiceRegistry>(),
    )

    private class BlockingSearchService(
        private val started: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : DesktopAuthenticatingTrackerService {
        override val profile = MutableStateFlow(
            TrackerProfile(
                id = 176,
                name = "Blocking tracking",
                authentication = TrackerAuthentication.USERNAME_PASSWORD,
                loggedIn = true,
            ),
        )
        override val statuses = listOf(1L to "Reading")
        override val scores = listOf(0.0, 10.0)

        override fun authorizationUrl(redirectUri: String, state: String) = redirectUri

        override suspend fun finishOAuth(code: String, redirectUri: String) = Unit

        override suspend fun login(username: String, password: String) = Unit

        override suspend fun loginWithApiKey(apiKey: String) = Unit

        override suspend fun search(query: String): List<TrackSearchResult> {
            started.complete(Unit)
            withContext(NonCancellable) {
                release.await()
            }
            return emptyList()
        }

        override suspend fun bind(mangaId: Long, result: TrackSearchResult) =
            Track(0, mangaId, 176, result.remoteId, null, result.title, 0.0, 0, 0, 0.0, "", 0, 0, false)

        override suspend fun update(track: Track, edit: TrackEdit) = track

        override suspend fun logout() = Unit
    }
}
