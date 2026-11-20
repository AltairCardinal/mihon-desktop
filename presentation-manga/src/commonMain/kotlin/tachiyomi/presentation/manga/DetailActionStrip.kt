package tachiyomi.presentation.manga

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.icerock.moko.resources.StringResource
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.download.service.MangaDownloadDeletion
import tachiyomi.domain.download.service.MangaDownloadDeletionSettings
import tachiyomi.domain.manga.model.MangaDetailAction
import tachiyomi.domain.manga.model.MangaDetailLayout
import tachiyomi.domain.manga.service.MangaDetailPreferences
import tachiyomi.i18n.MR

class DetailActionItem(
    val id: MangaDetailAction,
    val title: String,
    val icon: @Composable () -> Unit,
    val onClick: () -> Unit,
    val active: Boolean = false,
    val modifier: Modifier = Modifier,
    val detail: String? = null,
)

class DetailActionLabels(
    val customize: String,
    val previousPage: String,
    val nextPage: String,
    val limit: String,
    val save: String,
    val cancel: String,
    val deleteTitle: String,
    val deleteDescription: String,
    val inherit: String,
    val on: String,
    val off: String,
    val saveFailed: String,
    val orderHint: String = "",
) {
    fun deletion(mode: MangaDownloadDeletion): String = when (mode) {
        MangaDownloadDeletion.INHERIT -> inherit
        MangaDownloadDeletion.ENABLED -> on
        MangaDownloadDeletion.DISABLED -> off
    }
}

@Composable
fun detailActionLabels(localize: @Composable (StringResource) -> String) = DetailActionLabels(
    customize = localize(MR.strings.manga_actions_customize),
    previousPage = localize(MR.strings.spen_previous_page),
    nextPage = localize(MR.strings.spen_next_page),
    limit = localize(MR.strings.manga_actions_limit),
    save = localize(MR.strings.action_save),
    cancel = localize(MR.strings.action_cancel),
    deleteTitle = localize(MR.strings.manga_delete_after_read),
    deleteDescription = localize(MR.strings.manga_delete_after_read_description),
    inherit = localize(MR.strings.manga_delete_inherit),
    on = localize(MR.strings.manga_delete_on),
    off = localize(MR.strings.manga_delete_off),
    saveFailed = localize(MR.strings.desktop_detail_save_failed),
    orderHint = localize(MR.strings.manga_actions_order_hint),

)

@Composable
fun DetailActionStrip(
    mangaId: Long,
    preferenceStore: PreferenceStore,
    actions: List<DetailActionItem>,
    globalDeletionEnabled: Boolean,
    deleteIcon: @Composable () -> Unit,
    labels: DetailActionLabels,
    deletionOptions: @Composable (MangaDownloadDeletionSettings, (MangaDownloadDeletionSettings) -> Unit) -> Unit,
    deletionDialog: @Composable (String, () -> Unit, @Composable () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    platform: DetailActionPlatform = DetailActionPlatform.Mobile,
) {
    val primaryPreference = remember(preferenceStore) { MangaDetailPreferences(preferenceStore).layout() }
    val deletionPreference =
        remember(preferenceStore, mangaId) { DownloadPreferences(preferenceStore).mangaDeletionSettings(mangaId) }
    val layout by primaryPreference.changes().collectAsState(initial = primaryPreference.get())
    val deletion by deletionPreference.changes().collectAsState(initial = deletionPreference.get())
    var customize by remember(mangaId) { mutableStateOf(false) }
    var editDeletion by remember(mangaId) { mutableStateOf(false) }
    val all = actions + DetailActionItem(
        MangaDetailAction.DELETE_AFTER_READ,
        labels.deleteTitle,
        deleteIcon,
        onClick = { editDeletion = true },
        active = deletion.mode.resolveSlots(if (globalDeletionEnabled) 0 else -1, deletion.slots) >= 0,
        detail = labels.deletion(deletion.mode),
    )
    val ordered = all.sortedBy { layout.order.indexOf(it.id) }
    val primary = ordered.take(MangaDetailAction.MAX_PRIMARY)
    val saveOrder: (List<MangaDetailAction>) -> Unit = { order ->
        primaryPreference.set(
            MangaDetailLayout(
                MangaDetailAction.normalize(order.take(MangaDetailAction.MAX_PRIMARY)),
                (order + layout.order).distinct(),
            ),
        )
    }
    DetailActionRail(mangaId, primary, ordered, platform, labels, { customize = true }, saveOrder, modifier)
    if (customize) {
        DetailActionCustomizationDialog(
            ordered,
            primary,
            labels,
            platform,
            onDismiss = { customize = false },
            onSave = { _, order -> saveOrder(order) },
        )
    }
    if (editDeletion) {
        var failed by remember { mutableStateOf(false) }
        val update: (MangaDownloadDeletionSettings) -> Unit = { settings ->
            try {
                deletionPreference.set(settings)
                failed = false
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                failed = true
            }
        }
        deletionDialog(labels.deleteTitle, { editDeletion = false }) {
            Column(Modifier.testTag("manga-delete-setting")) {
                if (failed) {
                    Text(
                        labels.saveFailed,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("manga-delete-save-error"),
                    )
                }
                DetailDeletionModeChoices(deletion.mode, labels) { update(deletion.copy(mode = it)) }
                if (deletion.mode == MangaDownloadDeletion.ENABLED) {
                    deletionOptions(deletion, update)
                }
            }
        }
    }
}
