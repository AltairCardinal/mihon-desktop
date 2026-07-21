package eu.kanade.tachiyomi.ui.security

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.ui.base.delegate.AndroidAppLockStateMachine
import eu.kanade.tachiyomi.ui.base.delegate.shouldProtectSecureScreen
import mihon.domain.security.AuthenticationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AndroidSecuritySharedPolicyTest {

    @Test
    fun `first process activity requires unlock when app lock is enabled`() {
        assertTrue(AndroidAppLockStateMachine(lockEnabled = true).state.requiresUnlock)
        assertFalse(AndroidAppLockStateMachine(lockEnabled = false).state.requiresUnlock)
    }

    @Test
    fun `never immediate and delayed lock settings follow shared policy`() {
        val never = unlockedMachine()
        never.onApplicationStopped(true, -1, 1_000, false)
        assertFalse(never.onApplicationStart(true, -1, null, 90_000, false).requiresUnlock)

        val immediate = unlockedMachine()
        immediate.onApplicationStopped(true, 0, 1_000, false)
        assertTrue(immediate.onApplicationStart(true, 0, null, 1_001, false).requiresUnlock)

        val delayed = unlockedMachine()
        assertEquals(1_000L, delayed.onApplicationStopped(true, 5, 1_000, false).lastClosedAtMillis)
        assertFalse(delayed.onApplicationStart(true, 5, 1_000, 300_999, false).requiresUnlock)
        assertTrue(unlockedMachine().onApplicationStart(true, 5, 1_000, 301_000, false).requiresUnlock)
    }

    @Test
    fun `locked stop resume cleanup and failed authentication preserve policy state`() {
        val machine = unlockedMachine()
        machine.onApplicationStopped(true, 5, 1_000, false)
        machine.overrideRequiresUnlock(true)
        assertEquals(1_000L, machine.onApplicationStopped(true, 5, 9_000, false).lastClosedAtMillis)
        machine.onUnlockAuthentication(AuthenticationResult.Failed)
        assertTrue(machine.state.requiresUnlock)

        val resumed = unlockedMachine().onApplicationStart(true, 5, 1_000, 2_000, false)
        assertFalse(resumed.requiresUnlock)
        assertNull(resumed.lastClosedAtMillis)
    }

    @Test
    fun `secure screen adapter covers always incognito and never`() {
        assertTrue(shouldProtectSecureScreen(SecurityPreferences.SecureScreenMode.ALWAYS, incognito = false))
        assertTrue(shouldProtectSecureScreen(SecurityPreferences.SecureScreenMode.INCOGNITO, incognito = true))
        assertFalse(shouldProtectSecureScreen(SecurityPreferences.SecureScreenMode.INCOGNITO, incognito = false))
        assertFalse(shouldProtectSecureScreen(SecurityPreferences.SecureScreenMode.NEVER, incognito = true))
    }

    private fun unlockedMachine() = AndroidAppLockStateMachine(lockEnabled = true).apply {
        onUnlockAuthentication(AuthenticationResult.Success)
    }
}
