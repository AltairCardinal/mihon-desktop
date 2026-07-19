package mihon.desktop.ui.extension

import androidx.preference.EditTextPreference as AndroidEditTextPreference
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.PreferenceScreen
import eu.kanade.tachiyomi.source.preference.EditTextPreference as JvmEditTextPreference
import mihon.desktop.compat.AndroidCompat
import java.lang.reflect.InvocationTargetException

internal object DesktopAndroidPreferenceAdapter {
    fun setupPreferenceScreen(source: ConfigurableSource, descriptorScreen: PreferenceScreen) {
        val legacyMethod = try {
            source.javaClass.getMethod(
                "setupPreferenceScreen",
                androidx.preference.PreferenceScreen::class.java,
            )
        } catch (_: NoSuchMethodException) {
            null
        }
        if (legacyMethod == null) {
            source.setupPreferenceScreen(descriptorScreen)
            return
        }

        val androidScreen = androidx.preference.PreferenceScreen(AndroidCompat.context)
        try {
            legacyMethod.invoke(source, androidScreen)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
        androidScreen.preferences.forEach { androidPreference ->
            val convertedStart = descriptorScreen.preferences.size
            descriptorScreen.addPreference(androidPreference)
            if (androidPreference is AndroidEditTextPreference) {
                descriptorScreen.preferences
                    .drop(convertedStart)
                    .filterIsInstance<JvmEditTextPreference>()
                    .singleOrNull { it.key == androidPreference.key }
                    ?.validator = androidPreference.desktopValidator()
            }
        }
    }
}
