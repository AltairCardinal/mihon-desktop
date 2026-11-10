package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.reader.settings.SeriesReaderSettings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel

@Composable
fun ReadingModeSelectDialog(
    onDismissRequest: () -> Unit,
    screenModel: ReaderSettingsScreenModel,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SeriesReaderSettings(screenModel)
        }
    }
}
