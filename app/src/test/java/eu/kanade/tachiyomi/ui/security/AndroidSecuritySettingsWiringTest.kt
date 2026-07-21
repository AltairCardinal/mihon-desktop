package eu.kanade.tachiyomi.ui.security

import eu.kanade.presentation.more.settings.screen.AndroidSecuritySettingsPolicy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AndroidSecuritySettingsWiringTest {

    @Test
    fun `unsupported authentication disables an enabled lock and its delay`() {
        val availability = AndroidSecuritySettingsPolicy.availability(false, true)
        assertFalse(availability.authenticatorEnabled)
        assertFalse(availability.lockDelayEnabled)
    }

    @Test
    fun `lock and delay changes require supported successful authentication`() = runTest {
        var attempts = 0
        suspend fun authenticate(result: Boolean) = AndroidSecuritySettingsPolicy.confirmChange(true) {
            attempts++
            result
        }

        assertTrue(authenticate(true))
        assertFalse(authenticate(false))
        assertFalse(AndroidSecuritySettingsPolicy.confirmChange(false) { error("must not authenticate") })
        assertTrue(attempts == 2)
    }
}
