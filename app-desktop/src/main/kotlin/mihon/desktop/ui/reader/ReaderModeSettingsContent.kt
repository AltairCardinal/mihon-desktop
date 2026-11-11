package mihon.desktop.ui.reader

import androidx.compose.runtime.Composable
import mihon.domain.reader.ReaderSettings
import tachiyomi.presentation.reader.settings.ReaderSettingsTextScope
import tachiyomi.presentation.reader.settings.ReaderModeChoices as SharedReaderModeChoices
import tachiyomi.presentation.reader.settings.ReaderModeSettingsContent as SharedReaderModeSettingsContent
import tachiyomi.presentation.reader.settings.ReaderModeSwitch as SharedReaderModeSwitch

/** Desktop resource adapter; Android and Desktop render the same approved controls. */
@Composable
internal fun ReaderModeSettingsContent(
    settings: ReaderSettings,
    followsGlobal: Boolean,
    onFollowGlobal: () -> Unit,
    onCustomize: () -> Unit,
    onChange: (ReaderSettings) -> Unit,
    actualDualPage: Boolean,
    singlePageOptions: @Composable () -> Unit = {},
) {
    ReaderSettingsTextScope({ it.localized() }) {
        SharedReaderModeSettingsContent(
            settings,
            followsGlobal,
            onFollowGlobal,
            onCustomize,
            onChange,
            actualDualPage,
            singlePageOptions,
        )
    }
}

@Composable
internal fun ReaderModeChoices(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit) {
    ReaderSettingsTextScope({ it.localized() }) { SharedReaderModeChoices(settings, onChange) }
}

@Composable
internal fun ReaderModeSwitch(
    title: String,
    hint: String,
    checked: Boolean,
    showState: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    ReaderSettingsTextScope({ it.localized() }) { SharedReaderModeSwitch(title, hint, checked, showState, onChange) }
}
