package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageR2LPagerViewer
import mihon.domain.reader.ReaderSettings
import mihon.domain.reader.ReaderSettingsCodec
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.reader.settings.DefaultReaderModePreference
import tachiyomi.presentation.reader.settings.ReaderModeSettingsContent
import tachiyomi.presentation.reader.settings.ReaderSettingsTextScope

@Composable
internal fun SeriesReaderSettings(screenModel: ReaderSettingsScreenModel) {
    val manga by screenModel.mangaFlow.collectAsState()
    val viewer by screenModel.viewerFlow.collectAsState()
    val defaults = screenModel.preferences.collectDefaultReaderSettings()
    val flags = manga?.viewerFlags ?: 0L
    ReaderSettingsControls(
        settings = ReaderSettingsCodec.custom(flags, defaults),
        defaults = defaults,
        followingDefault = ReaderSettingsCodec.isFollowingDefault(flags),
        automaticDualPage = viewer is DualPageR2LPagerViewer,
        onChange = screenModel::changeReaderSettings,
        onFollowDefault = screenModel::followDefault,
    )
}

@Composable
internal fun ReaderPreferences.collectDefaultReaderSettings(): ReaderSettings {
    // Legacy mode/matching keys remain authoritative; hidden values stay in the saved snapshot.
    val mode by defaultReadingMode().collectAsState()
    val matching by automaticSpreadMatching().collectAsState()
    val snapshot by defaultReaderSettingsSnapshot().collectAsState()
    return remember(mode, matching, snapshot) { defaultReaderSettings() }
}

/** Android resource/persistence adapter around the very same controls accepted on Windows. */
@Composable
internal fun ReaderSettingsControls(
    settings: ReaderSettings,
    onChange: (ReaderSettings) -> Unit,
    followingDefault: Boolean? = null,
    defaults: ReaderSettings = settings,
    automaticDualPage: Boolean = false,
    onFollowDefault: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resolve = remember(context, configuration) {
        { resource: dev.icerock.moko.resources.StringResource -> resource.getString(context) }
    }
    ReaderSettingsTextScope(resolve) {
        Column(
            Modifier.fillMaxWidth().padding(
                horizontal = if (followingDefault == null) 0.dp else SettingsItemsPaddings.Horizontal,
                vertical = if (followingDefault == null) 0.dp else 8.dp,
            ),
        ) {
            if (followingDefault == null) {
                DefaultReaderModePreference(settings, onChange)
            } else {
                ReaderModeSettingsContent(
                    settings = if (followingDefault) defaults else settings,
                    followsGlobal = followingDefault,
                    onFollowGlobal = { onFollowDefault(true) },
                    onCustomize = { onFollowDefault(false) },
                    onChange = onChange,
                    actualDualPage = automaticDualPage,
                )
            }
        }
    }
}

@Composable
internal fun SettingsExplanation(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Native pager boolean options, outside per-work mode choices. */
@Composable
internal fun PagerSplitSettings(screenModel: ReaderSettingsScreenModel) {
    val preferences = screenModel.preferences
    val split by preferences.dualPageSplitPaged().collectAsState()
    val label = stringResource(MR.strings.pref_dual_page_split)
    val toggleSplit = {
        val enabled = !split
        if (enabled) preferences.dualPageRotateToFit().set(false)
        preferences.dualPageSplitPaged().set(enabled)
    }
    Column {
        Column(
            Modifier.clearAndSetSemantics {
                text = AnnotatedString(label)
                onClick {
                    toggleSplit()
                    true
                }
                role = Role.Checkbox
                toggleableState = if (split) ToggleableState.On else ToggleableState.Off
            },
        ) {
            CheckboxItem(
                label = label,
                checked = split,
                onClick = toggleSplit,
            )
        }
        if (split) {
            CheckboxItem(
                label = stringResource(MR.strings.pref_dual_page_invert),
                pref = preferences.dualPageInvertPaged(),
            )
        }
    }
}
