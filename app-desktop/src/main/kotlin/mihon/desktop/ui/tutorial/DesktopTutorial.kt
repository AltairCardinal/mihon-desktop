package mihon.desktop.ui.tutorial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR

/** Stable identities are independent of copy, locale and the host screen. */
enum class DesktopTutorial(val id: String, val title: StringResource, val message: StringResource) {
    READER_ADJUST_SPREAD(
        "reader-adjust-spread",
        MR.strings.desktop_tutorial_pairing_title,
        MR.strings.desktop_tutorial_pairing_message,
    ),
    READER_FULLSCREEN(
        "reader-fullscreen",
        MR.strings.desktop_tutorial_fullscreen_title,
        MR.strings.desktop_tutorial_fullscreen_message,
    ),
}

/** Confirmation is local application state; mounting or hiding a hint never acknowledges it. */
class DesktopTutorialState(private val preference: Preference<Set<String>>) {
    private var acknowledged by mutableStateOf(preference.get())

    fun shouldShow(tutorial: DesktopTutorial, eligible: Boolean): Boolean = eligible && tutorial.id !in acknowledged

    fun acknowledge(tutorial: DesktopTutorial): Boolean {
        acknowledged = acknowledged + tutorial.id
        return try {
            preference.set(preference.get() + acknowledged)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Tutorial memory must not interrupt the host action. A new session may remind again.
            false
        }
    }
}
