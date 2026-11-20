package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.TabbedDialogPaddings
import tachiyomi.domain.download.service.MangaDownloadDeletionSettings
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.manga.DetailDeletionOptions

/** Platform strings only; both hosts render the shared deletion controls. */
@Composable
fun MangaDeletionOptions(
    settings: MangaDownloadDeletionSettings,
    onChange: (MangaDownloadDeletionSettings) -> Unit,
) {
    DetailDeletionOptions(
        settings = settings,
        onChange = onChange,
        retentionTitle = stringResource(MR.strings.pref_remove_after_read),
        retentionEntries = listOf(
            MR.strings.last_read_chapter,
            MR.strings.second_to_last,
            MR.strings.third_to_last,
            MR.strings.fourth_to_last,
            MR.strings.fifth_to_last,
        ).map { stringResource(it) },
        bookmarkTitle = stringResource(MR.strings.pref_remove_bookmarked_chapters),
        cancelLabel = stringResource(MR.strings.action_cancel),
    )
}

/** The same adaptive container as ReaderSettingsDialog, without tabs for this single section. */
@Composable
fun MangaDeletionSettingsSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismiss) {
        BoxWithConstraints {
            Column(
                Modifier.heightIn(max = maxHeight * 0.75f)
                    .testTag("manga-delete-reader-sheet")
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = TabbedDialogPaddings.Horizontal, vertical = 16.dp),
                )
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .padding(
                            start = TabbedDialogPaddings.Horizontal,
                            end = TabbedDialogPaddings.Horizontal,
                            top = TabbedDialogPaddings.Vertical,
                        ),
                ) { content() }
            }
        }
    }
}
