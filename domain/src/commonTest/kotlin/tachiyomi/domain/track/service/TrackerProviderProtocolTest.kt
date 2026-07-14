package tachiyomi.domain.track.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
