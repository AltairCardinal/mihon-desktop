package eu.kanade.tachiyomi.ui.security

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.ui.base.delegate.AndroidAppLockLifecycleConsumer
import eu.kanade.tachiyomi.ui.base.delegate.AndroidSecureScreenConsumer
import mihon.domain.security.AuthenticationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AndroidSecuritySharedPolicyTest {

    @Test
    fun `first process activity requires unlock when app lock is enabled`() {
        assertTrue(AndroidAppLockLifecycleConsumer(lockEnabled = true).state.requiresUnlock)
        assertFalse(AndroidAppLockLifecycleConsumer(lockEnabled = false).state.requiresUnlock)
    }

    @Test
    fun `never immediate and delayed lock settings follow shared policy`() {
        val never = unlockedConsumer()
        never.onApplicationStopped(true, -1, 1_000, false)
        assertFalse(never.onApplicationStart(true, -1, null, 90_000, false).requiresUnlock)

        val immediate = unlockedConsumer()
        immediate.onApplicationStopped(true, 0, 1_000, false)
        assertTrue(immediate.onApplicationStart(true, 0, null, 1_001, false).requiresUnlock)

        val delayed = unlockedConsumer()
        assertEquals(1_000L, delayed.onApplicationStopped(true, 5, 1_000, false).lastClosedAtMillis)
        assertFalse(delayed.onApplicationStart(true, 5, 1_000, 300_999, false).requiresUnlock)
        assertTrue(unlockedConsumer().onApplicationStart(true, 5, 1_000, 301_000, false).requiresUnlock)
    }

    @Test
    fun `locked stop resume cleanup and failed authentication preserve policy state`() {
        var persisted: Long? = null
        var deletes = 0
        val consumer = unlockedConsumer()
        consumer.onApplicationStopped(true, 5, 1_000, false) { persisted = it }
        consumer.overrideRequiresUnlock(true)
        consumer.onApplicationStopped(true, 5, 9_000, false) { persisted = it }
        assertEquals(1_000L, persisted)
        consumer.onUnlockAuthentication(AuthenticationResult.Failed)
        assertTrue(consumer.state.requiresUnlock)

        val resumed = unlockedConsumer().onApplicationStart(true, 5, 1_000, 2_000, false) { deletes++ }
        assertFalse(resumed.requiresUnlock)
        assertNull(resumed.lastClosedAtMillis)
        assertEquals(1, deletes)
    }

    @Test
    fun `secure screen adapter covers always incognito and never`() {
        val applied = mutableListOf<Boolean>()
        val consumer = AndroidSecureScreenConsumer(applied::add)
        consumer.apply(SecurityPreferences.SecureScreenMode.ALWAYS, incognito = false)
        consumer.apply(SecurityPreferences.SecureScreenMode.INCOGNITO, incognito = true)
        consumer.apply(SecurityPreferences.SecureScreenMode.INCOGNITO, incognito = false)
        consumer.apply(SecurityPreferences.SecureScreenMode.NEVER, incognito = true)
        assertEquals(listOf(true, true, false, false), applied)
    }

    private fun unlockedConsumer() = AndroidAppLockLifecycleConsumer(lockEnabled = true).apply {
        onUnlockAuthentication(AuthenticationResult.Success)
    }
}
