package eu.kanade.tachiyomi.ui.security

import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.AndroidSecuritySettingsConsumer
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference

class AndroidSecuritySettingsWiringTest {

    @Test
    fun `unsupported authentication disables an enabled lock and its delay`() {
        val preference = InMemoryPreference("lock", true, false)
        val availability = AndroidSecuritySettingsConsumer.enforceAvailability(false, preference)
        assertFalse(availability.authenticatorEnabled)
        assertFalse(availability.lockDelayEnabled)
        assertFalse(preference.get())
    }

    @Test
    fun `lock and delay changes require supported successful authentication`() = runTest {
        var attempts = 0
        suspend fun authenticate(result: Boolean) = AndroidSecuritySettingsConsumer.changeHandler<Boolean>(true) {
            attempts++
            result
        }(true)

        assertTrue(authenticate(true))
        assertFalse(authenticate(false))
        val lockItem = Preference.PreferenceItem.SwitchPreference(
            preference = InMemoryPreference("lock", false, false),
            title = "Lock",
            onValueChanged = AndroidSecuritySettingsConsumer.changeHandler(false) { error("must not authenticate") },
        )
        val delayItem = Preference.PreferenceItem.ListPreference(
            preference = InMemoryPreference("delay", 0, 0),
            entries = persistentMapOf(0 to "Always"),
            title = "Delay",
            onValueChanged = AndroidSecuritySettingsConsumer.changeHandler(false) { error("must not authenticate") },
        )
        assertFalse(lockItem.onValueChanged(true))
        assertFalse(delayItem.onValueChanged(0))
        assertTrue(attempts == 2)
    }
}
