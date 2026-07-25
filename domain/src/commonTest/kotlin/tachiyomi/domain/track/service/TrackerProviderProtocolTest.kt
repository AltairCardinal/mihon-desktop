package tachiyomi.domain.track.service

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track

class TrackerProviderProtocolTest {
    @Test
    fun `AniList keeps original implicit authorization and complete update mutation`() {
        val auth = TrackerProviderProtocols.aniList.authorization("16329", "mihon://anilist-auth", "state")
        assertEquals("token", auth.parameters.getValue("response_type"))
        assertFalse(TrackerProviderProtocols.aniList.supportsAuthorizationCodeExchange)

        val update = TrackerProviderProtocols.aniList.update(
            libraryId = 44,
            progress = 12,
            status = "CURRENT",
            scoreRaw = 80,
            private = true,
            startedAt = ProviderFuzzyDate(2024, 1, 2),
            completedAt = ProviderFuzzyDate(2025, 3, 4),
        )
        assertTrue(update.query.contains("\u0024progress: Int"))
        assertTrue(update.query.contains("\u0024status: MediaListStatus"))
        assertTrue(update.query.contains("\u0024scoreRaw: Int"))
        assertTrue(update.query.contains("progress: \u0024progress"))
        assertTrue(update.query.contains("status: \u0024status"))
        assertTrue(update.query.contains("scoreRaw: \u0024scoreRaw"))
        assertTrue(update.query.contains("\u0024startedAt: FuzzyDateInput"))
        assertTrue(update.query.contains("\u0024completedAt: FuzzyDateInput"))
        assertTrue(update.query.contains("startedAt: \u0024startedAt"))
        assertTrue(update.query.contains("completedAt: \u0024completedAt"))
        assertEquals(12, update.progress)
        assertEquals("CURRENT", update.status)
        assertEquals(80, update.scoreRaw)
        assertEquals(ProviderFuzzyDate(2024, 1, 2), update.startedAt)
        assertEquals(ProviderFuzzyDate(2025, 3, 4), update.completedAt)
    }

    @Test
    fun `secret providers construct original password code and refresh grants`() {
        val kitsu = TrackerProviderProtocols.kitsu.passwordToken("client", "secret", "user", "pass")
        assertEquals("password", kitsu.getValue("grant_type"))
        assertEquals("secret", kitsu.getValue("client_secret"))

        listOf(TrackerProviderProtocols.shikimori, TrackerProviderProtocols.bangumi).forEach { protocol ->
            val code = protocol.authorizationCodeToken("client", "secret", "code", "mihon://callback")
            assertEquals("authorization_code", code.getValue("grant_type"))
            assertEquals("secret", code.getValue("client_secret"))
            assertEquals("mihon://callback", code.getValue("redirect_uri"))
            val refresh = protocol.refreshToken("client", "secret", "refresh", "mihon://callback")
            assertEquals("refresh_token", refresh.getValue("grant_type"))
            assertEquals("secret", refresh.getValue("client_secret"))
        }
    }

    @Test
    fun `Kitsu bind creates library entry and update requires saved entry id`() {
        val bind = TrackerProviderProtocols.kitsu.bind(
            mediaId = 13,
            userId = "7",
            status = "planned",
            progress = 0,
            private = false,
        )
        assertEquals(13, bind.mediaId)
        assertEquals("7", bind.userId)
        assertEquals("planned", bind.status)

        assertThrows(IllegalArgumentException::class.java) {
            TrackerProviderProtocols.kitsu.update(0, "current", 2, 16, false)
        }
        assertEquals(91, TrackerProviderProtocols.kitsu.update(91, "current", 2, 16, false).libraryId)
    }

    @Test
    fun `chapter edit preserves fixed main initial auto complete and MangaUpdates policies`() = runTest {
        val calls = mutableListOf<String>()
        val port = FakePort(calls)
        val workflow = TrackerProviderWorkflow(clock = { 1234L })

        val result = workflow.execute(
            port,
            TrackerProviderRequest.Edit(track(), TrackEdit(lastChapterRead = 1.0)),
        )

        assertEquals(listOf("refresh", "update"), calls)
        assertEquals(TrackerProviderSession(1, true, "reader"), port.session)
        assertEquals(
            track().copy(lastChapterRead = 1.0, status = 2),
            (result as TrackerProviderResult.Success).track,
        )

        listOf(3L, 5L, 7L).forEach { status ->
            val existing = track().copy(lastChapterRead = 2.0, status = status)
            val updated = workflow.execute(
                FakePort(mutableListOf()),
                TrackerProviderRequest.Edit(existing, TrackEdit(lastChapterRead = 3.0)),
            )
            assertEquals(status, (updated as TrackerProviderResult.Success).track!!.status)
        }
        val completed = workflow.execute(
            FakePort(mutableListOf()),
            TrackerProviderRequest.Edit(
                track().copy(lastChapterRead = 9.0, status = 4),
                TrackEdit(lastChapterRead = 10.0),
            ),
        ) as TrackerProviderResult.Success
        assertEquals(track().copy(lastChapterRead = 10.0, status = 4, finishDate = 1234L), completed.track)
        val noDateInitial = workflow.execute(
            FakePort(mutableListOf(), supportsReadingDates = false),
            TrackerProviderRequest.Edit(track().copy(lastChapterRead = 9.0), TrackEdit(lastChapterRead = 10.0)),
        ) as TrackerProviderResult.Success
        assertEquals(1234L, noDateInitial.track!!.finishDate)
        val noDateAuto = workflow.execute(
            FakePort(mutableListOf(), supportsReadingDates = false),
            TrackerProviderRequest.Edit(
                track().copy(lastChapterRead = 9.0, finishDate = 77),
                TrackEdit(lastChapterRead = 10.0, didReadChapter = true),
            ),
        ) as TrackerProviderResult.Success
        assertEquals(77L, noDateAuto.track!!.finishDate)
        val autoStarted = workflow.execute(
            FakePort(mutableListOf()),
            TrackerProviderRequest.Edit(track(), TrackEdit(lastChapterRead = 1.0, didReadChapter = true)),
        ) as TrackerProviderResult.Success
        assertEquals(1234L, autoStarted.track!!.startDate)
        val rereading = workflow.execute(
            FakePort(mutableListOf()),
            TrackerProviderRequest.Edit(
                track().copy(lastChapterRead = 2.0, status = 6),
                TrackEdit(lastChapterRead = 3.0, didReadChapter = true),
            ),
        ) as TrackerProviderResult.Success
        assertEquals(6, rereading.track!!.status)
        val rereadingFinished = workflow.execute(
            FakePort(mutableListOf()),
            TrackerProviderRequest.Edit(
                track().copy(lastChapterRead = 9.0, status = 6),
                TrackEdit(lastChapterRead = 10.0, didReadChapter = true),
            ),
        ) as TrackerProviderResult.Success
        assertEquals(track().copy(lastChapterRead = 10.0, status = 4, finishDate = 1234L), rereadingFinished.track)
        val mangaUpdates = workflow.execute(
            FakePort(mutableListOf(), chapterReadPolicy = TrackerChapterReadPolicy.ALWAYS_READING),
            TrackerProviderRequest.Edit(
                track().copy(lastChapterRead = 9.0),
                TrackEdit(lastChapterRead = 10.0, didReadChapter = true),
            ),
        ) as TrackerProviderResult.Success
        assertEquals(track().copy(lastChapterRead = 10.0, status = 2), mangaUpdates.track)

        val explicitCompletion = workflow.execute(
            FakePort(mutableListOf()),
            TrackerProviderRequest.Edit(track().copy(lastChapterRead = 3.0), TrackEdit(status = 4)),
        ) as TrackerProviderResult.Success
        assertEquals(10.0, explicitCompletion.track!!.lastChapterRead)
    }

    @Test
    fun `session delete and provider errors return stable results before transport`() = runTest {
        val port =
            FakePort(
                mutableListOf(),
                failure = TrackerProviderException(
                    TrackerProviderErrorKind.RATE_LIMITED,
                    429,
                    retryAfterSeconds = 37,
                ),
            )
        val workflow = TrackerProviderWorkflow()

        val failure = workflow.execute(port, TrackerProviderRequest.Edit(track(), TrackEdit(status = 3)))
        assertEquals(
            TrackerProviderError(
                TrackerProviderOperation.EDIT,
                TrackerProviderErrorKind.RATE_LIMITED,
                429,
                retryAfterSeconds = 37,
            ),
            (failure as TrackerProviderResult.Failure).error,
        )

        port.failure = null
        assertTrue(workflow.execute(port, TrackerProviderRequest.Delete(track())) is TrackerProviderResult.Success)
        assertEquals(listOf("delete"), port.calls.takeLast(1))

        val unsupportedPort =
            FakePort(mutableListOf(), failure = IllegalStateException("must not refresh"), supportsDelete = false)
        val unsupported = workflow.execute(
            unsupportedPort,
            TrackerProviderRequest.Delete(track()),
        )
        assertEquals(
            TrackerProviderErrorKind.UNSUPPORTED,
            (unsupported as TrackerProviderResult.Failure).error.kind,
        )
        assertTrue(unsupportedPort.calls.isEmpty())

        listOf(
            FakePort(mutableListOf(), loggedIn = false) to TrackerProviderErrorKind.AUTHENTICATION,
            FakePort(mutableListOf(), sessionId = 2) to TrackerProviderErrorKind.INVALID_REQUEST,
            FakePort(mutableListOf(), configurationId = 2) to TrackerProviderErrorKind.INVALID_REQUEST,
        ).forEach { (invalidPort, kind) ->
            val invalid = workflow.execute(invalidPort, TrackerProviderRequest.Edit(track(), TrackEdit(status = 3)))
            assertEquals(kind, (invalid as TrackerProviderResult.Failure).error.kind)
            assertTrue(invalidPort.calls.isEmpty())
        }
    }

    @Test
    fun `provider catalog preserves fixed main capabilities for public and enhanced trackers`() {
        val configurations = (1L..9L).associateWith(TrackerProviderCatalog::configuration)

        assertEquals(setOf(1L, 2L, 3L, 4L, 7L), configurations.filterValues { it.supportsDelete }.keys)
        assertEquals(setOf(1L, 2L, 3L), configurations.filterValues { it.supportsReadingDates }.keys)
        assertEquals(setOf(2L, 3L, 5L), configurations.filterValues { it.supportsPrivateTracking }.keys)
        assertEquals(7L, configurations.getValue(1).rereadingStatus)
        assertEquals(6L, configurations.getValue(2).rereadingStatus)
        assertEquals(0L, configurations.getValue(7).readingStatus)
        assertEquals(TrackerChapterReadPolicy.ALWAYS_READING, configurations.getValue(7).chapterReadPolicy)
        assertEquals(
            listOf(2L to 3L, 2L to 3L, 2L to 3L),
            listOf(6L, 8L, 9L).map {
                configurations.getValue(it).readingStatus to
                    configurations.getValue(it).completionStatus
            },
        )
        assertThrows(IllegalArgumentException::class.java) { TrackerProviderCatalog.configuration(10) }
    }

    @Test
    fun `HTTP status mapping returns stable provider failures and result exception`() {
        assertEquals(TrackerProviderErrorKind.AUTHENTICATION, trackerProviderHttpError(401).kind)
        assertEquals(TrackerProviderErrorKind.AUTHENTICATION, trackerProviderHttpError(403).kind)
        assertEquals(TrackerProviderErrorKind.NOT_FOUND, trackerProviderHttpError(404).kind)
        assertEquals(TrackerProviderErrorKind.RATE_LIMITED, trackerProviderHttpError(429).kind)
        assertEquals(TrackerProviderErrorKind.SERVER, trackerProviderHttpError(500).kind)
        assertEquals(TrackerProviderErrorKind.UNKNOWN, trackerProviderHttpError(418).kind)
        assertEquals(37, trackerProviderHttpError(429, retryAfterSeconds = 37).retryAfterSeconds)
        assertEquals(120, trackerProviderHttpError(503, retryAfterSeconds = 120).retryAfterSeconds)
        assertEquals(null, trackerProviderHttpError(429).retryAfterSeconds)

        val failure = TrackerProviderResult.Failure(
            TrackerProviderError(
                TrackerProviderOperation.EDIT,
                TrackerProviderErrorKind.RATE_LIMITED,
                429,
                retryAfterSeconds = 37,
            ),
        )
        val exception = assertThrows(TrackerProviderResultException::class.java) { failure.trackOrThrow() }
        assertEquals(TrackerProviderErrorKind.RATE_LIMITED, exception.error.kind)
        assertEquals(37, exception.error.retryAfterSeconds)
        assertTrue(exception.message!!.contains("RATE_LIMITED"))
    }

    private fun track() = Track(1, 2, 1, 3, 4, "Manga", 0.0, 10, 5, 0.0, "", 0, 0, false)

    private class FakePort(
        val calls: MutableList<String>,
        var failure: Throwable? = null,
        supportsDelete: Boolean = true,
        loggedIn: Boolean = true,
        configurationId: Long = 1,
        sessionId: Long = configurationId,
        chapterReadPolicy: TrackerChapterReadPolicy = TrackerChapterReadPolicy.AUTO_COMPLETE,
        supportsReadingDates: Boolean = true,
    ) : TrackerProviderPort {
        override val configuration = TrackerProviderConfiguration(
            id = configurationId,
            authentication = TrackerAuthentication.OAUTH,
            readingStatus = 2,
            completionStatus = 4,
            rereadingStatus = 6,
            supportsReadingDates = supportsReadingDates,
            supportsDelete = supportsDelete,
            chapterReadPolicy = chapterReadPolicy,
        )
        override val session = TrackerProviderSession(sessionId, loggedIn, "reader")
        override suspend fun refresh(track: Track): Track {
            calls += "refresh"
            failure?.let { throw it }
            return track
        }
        override suspend fun update(track: Track): Track {
            calls += "update"
            return track
        }
        override suspend fun delete(track: Track) {
            calls += "delete"
        }
    }
}
