package mihon.desktop.ui.extension

import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.PreferenceScreen
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
        androidScreen.preferences.forEach(descriptorScreen::addPreference)
    }
}
