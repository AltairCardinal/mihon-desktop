package tachiyomi.presentation.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderScrollMode
import mihon.domain.reader.ReaderSettings
import mihon.domain.reader.ReaderSettingsLayout
import mihon.domain.reader.ReaderSettingsMode
import tachiyomi.i18n.MR

/** One summary row opens the same immediate-save editor on both platforms. */
@Composable
fun DefaultReaderModePreference(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val title = MR.strings.reader_settings_default_mode_title.readerText()
    Column(
        Modifier.fillMaxWidth().readerSettingsActivationKeys(Role.Button) { open = true }
            .clickable(role = Role.Button) { open = true }.padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            defaultReaderModeSummary(settings),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    ReaderModeChoices(settings, onChange)
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(MR.strings.action_close.readerText()) } },
        )
    }
}

@Composable
private fun defaultReaderModeSummary(settings: ReaderSettings): String = buildList {
    add(
        if (settings.mode == ReaderSettingsMode.HORIZONTAL) {
            MR.strings.reader_settings_horizontal.readerText()
        } else {
            MR.strings.reader_settings_vertical.readerText()
        },
    )
    if (settings.mode == ReaderSettingsMode.HORIZONTAL) {
        add(
            when (settings.layout) {
                ReaderSettingsLayout.AUTO -> MR.strings.reader_settings_auto.readerText()
                ReaderSettingsLayout.SINGLE -> MR.strings.reader_settings_single.readerText()
                ReaderSettingsLayout.DOUBLE -> MR.strings.reader_settings_dual.readerText()
            },
        )
        add(
            if (settings.direction == ReaderDirection.LTR) {
                MR.strings.desktop_reader_direction_ltr.readerText()
            } else {
                MR.strings.desktop_reader_direction_rtl.readerText()
            },
        )
        if (settings.layout != ReaderSettingsLayout.SINGLE) {
            add(
                MR.strings.desktop_ui_auto_spread_matching.readerText() + ": " +
                    if (settings.match) MR.strings.on.readerText() else MR.strings.off.readerText(),
            )
        }
    } else {
        add(
            if (settings.scroll == ReaderScrollMode.CONTINUOUS) {
                MR.strings.reader_settings_continuous.readerText()
            } else {
                MR.strings.reader_settings_paged.readerText()
            },
        )
        if (settings.scroll == ReaderScrollMode.CONTINUOUS) {
            add(
                MR.strings.reader_settings_page_gaps.readerText() + ": " +
                    if (settings.gap) MR.strings.on.readerText() else MR.strings.off.readerText(),
            )
        }
    }
}.joinToString(" · ")
