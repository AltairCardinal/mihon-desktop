package mihon.domain.security

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppSecurityPolicyTest {

    @Test
    fun `enabled app first process starts locked`() {
        assertTrue(AppLockPolicy.initial(lockEnabled = true).requiresUnlock)
        assertFalse(AppLockPolicy.initial(lockEnabled = false).requiresUnlock)
    }

    @Test
    fun `lock delay never always and elapsed minutes preserve fixed-main decisions`() {
        val unlocked = AppLockState(requiresUnlock = false, lastClosedAtMillis = 1_000)

        assertFalse(AppLockPolicy.onApplicationStart(unlocked, true, -1, nowMillis = 999_999).requiresUnlock)
        assertTrue(AppLockPolicy.onApplicationStart(unlocked, true, 0, nowMillis = 1_000).requiresUnlock)
        assertFalse(AppLockPolicy.onApplicationStart(unlocked, true, 5, nowMillis = 300_999).requiresUnlock)
        assertTrue(AppLockPolicy.onApplicationStart(unlocked, true, 5, nowMillis = 301_000).requiresUnlock)
    }

    @Test
    fun `close time is only recorded for unlocked delayed locks and is cleared after resume`() {
        val recorded = AppLockPolicy.onApplicationStopped(
            state = AppLockState(requiresUnlock = false),
            lockEnabled = true,
            delayMinutes = 5,
            nowMillis = 100,
        )
        assertEquals(100, recorded.lastClosedAtMillis)
        assertEquals(
            77,
            AppLockPolicy.onApplicationStopped(
                state = AppLockState(requiresUnlock = true, lastClosedAtMillis = 77),
                lockEnabled = true,
                delayMinutes = 5,
                nowMillis = 100,
            ).lastClosedAtMillis,
        )
        assertEquals(
            null,
            AppLockPolicy.onApplicationStart(recorded, true, 5, nowMillis = 101).lastClosedAtMillis,
        )
    }

    @Test
    fun `authentication success unlocks and every non success outcome fails closed`() {
        val locked = AppLockState(requiresUnlock = true)

        assertFalse(AppLockPolicy.onAuthentication(locked, AuthenticationResult.Success).requiresUnlock)
        AuthenticationResult.entries.filterNot { it == AuthenticationResult.Success }.forEach { result ->
            assertTrue(AppLockPolicy.onAuthentication(locked, result).requiresUnlock)
        }
    }

    @Test
    fun `secure screen matrix only protects always and incognito modes`() {
        assertTrue(SecureScreenPolicy.isProtected(SecurityPreferences.SecureScreenMode.ALWAYS, incognito = false))
        assertTrue(SecureScreenPolicy.isProtected(SecurityPreferences.SecureScreenMode.ALWAYS, incognito = true))
        assertFalse(SecureScreenPolicy.isProtected(SecurityPreferences.SecureScreenMode.INCOGNITO, incognito = false))
        assertTrue(SecureScreenPolicy.isProtected(SecurityPreferences.SecureScreenMode.INCOGNITO, incognito = true))
        assertFalse(SecureScreenPolicy.isProtected(SecurityPreferences.SecureScreenMode.NEVER, incognito = true))
    }
}
