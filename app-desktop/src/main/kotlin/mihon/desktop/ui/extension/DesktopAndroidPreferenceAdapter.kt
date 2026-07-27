package mihon.desktop.ui.extension

import androidx.preference.EditTextPreference as AndroidEditTextPreference
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.PreferenceScreen
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourcePreferenceScreenSetup
import eu.kanade.tachiyomi.source.setupSourcePreferenceScreen
import eu.kanade.tachiyomi.source.preference.EditTextPreference as JvmEditTextPreference
import mihon.desktop.compat.AndroidCompat
import java.lang.reflect.InvocationTargetException

internal object DesktopAndroidPreferenceAdapter {
    fun setupPreferenceScreen(
        source: Source?,
        descriptorScreen: PreferenceScreen,
    ): SourcePreferenceScreenSetup = setupSourcePreferenceScreen(source, descriptorScreen) { configurable, screen ->
        val legacyMethod = try {
            configurable.javaClass.getMethod(
                "setupPreferenceScreen",
                androidx.preference.PreferenceScreen::class.java,
            )
        } catch (_: NoSuchMethodException) {
            null
        }
        if (legacyMethod == null) {
            configurable.setupPreferenceScreen(screen)
        } else {
            val androidScreen = androidx.preference.PreferenceScreen(AndroidCompat.context)
            try {
                legacyMethod.invoke(configurable, androidScreen)
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
            androidScreen.preferences.forEach { androidPreference ->
                val convertedStart = screen.preferences.size
                screen.addPreference(androidPreference)
                if (androidPreference is AndroidEditTextPreference) {
                    screen.preferences
                        .drop(convertedStart)
                        .filterIsInstance<JvmEditTextPreference>()
                        .singleOrNull { it.key == androidPreference.key }
                        ?.validator = androidPreference.desktopValidator()
                }
            }
        }
    }
}
