package eu.kanade.tachiyomi.source

interface ConfigurableSource : Source {

    fun setupPreferenceScreen(screen: PreferenceScreen)
}

fun ConfigurableSource.preferenceKey(): String = "source_$id"
