package mihon.desktop.network

import tachiyomi.domain.source.service.AuthenticatedCookie
import tachiyomi.domain.source.service.AuthenticatedSession

fun interface DesktopChallengeRecoveryPort {
    suspend fun recover(
        challenge: CloudflareChallenge,
        intent: ChallengeRecoveryIntent,
    ): ChallengeRecoveryState
}

data class DesktopChallengeTarget(
    val host: String,
    val secure: Boolean,
)

fun CloudflareChallenge.target(): DesktopChallengeTarget =
    DesktopChallengeTarget(request.url.host, request.url.isHttps)

fun CloudflareChallenge.clearanceSession(value: String): AuthenticatedSession {
    val target = target()
    return AuthenticatedSession(
        cookies = listOf(
            AuthenticatedCookie(
                name = "cf_clearance",
                value = value,
                domain = target.host,
                hostOnly = true,
                path = "/",
                expiresAt = null,
                secure = target.secure,
                httpOnly = true,
            ),
        ),
    )
}
