package mihon.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mihon.desktop.settings.saveDesktopPreference
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR

/** Both entry points edit the same shared column preferences, including the automatic value zero. */
@Composable
internal fun LibraryColumnControls(preferences: LibraryPreferences) {
    var failure by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            MR.strings.desktop_appearance_library_grid.localized(),
            Modifier.desktopSettingsAnchor(MR.strings.desktop_appearance_library_grid.localized()),
        )
        Text(
            MR.strings.pref_library_columns.localized(),
            Modifier.desktopSettingsAnchor(MR.strings.pref_library_columns.localized()),
        )
        listOf(
            preferences.portraitColumns() to MR.strings.portrait,
            preferences.landscapeColumns() to MR.strings.landscape,
        )
            .forEach { (preference, title) ->
                val value by preference.changes().collectAsState(preference.get())
                Text("${title.localized()}: ${if (value == 0) MR.strings.label_auto.localized() else value.toString()}")
                Slider(
                    value = value.coerceIn(0, 10).toFloat(),
                    onValueChange = { failure = !saveDesktopPreference(preference, it.toInt()) },
                    valueRange = 0f..10f,
                    steps = 9,
                )
            }
        if (failure) {
            Text(
                MR.strings.desktop_appearance_save_failed.localized(),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
