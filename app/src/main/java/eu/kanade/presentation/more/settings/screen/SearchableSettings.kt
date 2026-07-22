package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import cafe.adriel.voyager.core.screen.Screen
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScaffold
import eu.kanade.presentation.util.LocalBackPress
import mihon.domain.settings.SearchablePreference
import mihon.domain.settings.SearchableSettingsScreen

interface SearchableSettings : Screen {

    @Composable
    @ReadOnlyComposable
    fun getTitleRes(): StringResource

    @Composable
    fun getPreferences(): List<Preference>

    @Composable
    fun RowScope.AppBarAction() {
    }

    @Composable
    override fun Content() {
        val handleBack = LocalBackPress.current
        PreferenceScaffold(
            titleRes = getTitleRes(),
            onBackPressed = if (handleBack != null) handleBack::invoke else null,
            actions = { AppBarAction() },
            itemsProvider = { getPreferences() },
        )
    }

    companion object {
        // HACK: for the background blipping thingy.
        // The title of the target PreferenceItem
        // Set before showing the destination screen and reset after
        // See BasePreferenceWidget.highlightBackground
        var highlightKey: String? = null
    }
}

internal fun SearchableSettings.toSearchableSettingsScreen(
    title: String,
    preferences: List<Preference>,
): SearchableSettingsScreen<Screen> {
    return SearchableSettingsScreen(
        route = this,
        title = title,
        preferences = preferences.map(Preference::toSearchablePreference),
    )
}

private fun Preference.toSearchablePreference(): SearchablePreference = when (this) {
    is Preference.PreferenceGroup -> SearchablePreference.Group(
        title = title,
        entries = preferenceItems.map(Preference.PreferenceItem<*, *>::toSearchableEntry),
        enabled = enabled,
    )
    is Preference.PreferenceItem<*, *> -> toSearchableEntry()
}

private fun Preference.PreferenceItem<*, *>.toSearchableEntry(): SearchablePreference.Entry {
    return SearchablePreference.Entry(
        title = title,
        summary = subtitle,
        enabled = enabled,
        type = if (this is Preference.PreferenceItem.InfoPreference) {
            SearchablePreference.EntryType.Info
        } else {
            SearchablePreference.EntryType.Standard
        },
    )
}
