package mihon.desktop.ui.authors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tachiyomi.domain.creator.model.CreatorMention
import tachiyomi.domain.creator.model.CreatorMetadataField
import tachiyomi.domain.creator.service.CreatorTitleAttributionEditor
import tachiyomi.domain.creator.service.CreatorTitleAttributionFeedback
import tachiyomi.domain.creator.service.CreatorTitleAttributionSession
import tachiyomi.domain.creator.service.CreatorTitleAttributionState
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR

@Composable
internal fun CreatorTitleAttributionControl(manga: Manga, sourceName: String?, editor: CreatorTitleAttributionEditor) {
    val scope = rememberCoroutineScope()
    val session = remember(editor, manga.id, manga.source) { CreatorTitleAttributionSession(editor, scope) }
    val state by session.state.collectAsState()
    val triggerFocus = remember { FocusRequester() }
    val toggleFocus = remember { FocusRequester() }
    DisposableEffect(session) { onDispose(session::dispose) }
    LaunchedEffect(manga) { session.refresh(manga) }
    LaunchedEffect(state.focusRevision) {
        if (state.focusRevision > 0) {
            withFrameNanos { }
            triggerFocus.requestFocus()
        }
    }
    LaunchedEffect(state.open) {
        if (state.open) {
            withFrameNanos { }
            toggleFocus.requestFocus()
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        TextButton(
            onClick = { session.open(manga) },
            enabled = !state.busy,
            modifier = Modifier.focusRequester(triggerFocus).testTag("creator-title-attribution-open"),
        ) { Text(MR.strings.creator_title_attribution_title.localized()) }
        if (!state.open) {
            titleAttributionFeedback(state)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("creator-title-attribution-feedback"))
            }
        }
    }
    if (state.open) {
        AlertDialog(
            onDismissRequest = session::dismiss,
            title = { Text(MR.strings.creator_title_attribution_title.localized()) },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sourceName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    Text(MR.strings.creator_title_attribution_description.localized(), style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth().focusRequester(toggleFocus)
                            .toggleable(state.draftEnabled, enabled = !state.busy, role = Role.Checkbox, onValueChange = session::select)
                            .testTag("creator-title-attribution-toggle"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = state.draftEnabled, enabled = !state.busy, onCheckedChange = null)
                        Text(MR.strings.creator_title_attribution_enabled.localized())
                    }
                    Text(MR.strings.creator_title_attribution_preview.localized(), style = MaterialTheme.typography.labelLarge)
                    if (state.preview.isEmpty()) {
                        Text(MR.strings.creator_title_attribution_no_preview.localized(), style = MaterialTheme.typography.bodySmall)
                    } else {
                        state.preview.forEachIndexed { index, mention ->
                            Text(creatorTitleCreditText(mention), modifier = Modifier.testTag("creator-title-attribution-preview-$index"))
                        }
                    }
                    val raw = state.manga?.let { listOfNotNull(it.author, it.artist).distinct().joinToString(" / ") }.orEmpty()
                    if (raw.isNotBlank()) Text(MR.strings.creator_title_attribution_original.localized(java.util.Locale.getDefault(), raw), style = MaterialTheme.typography.bodySmall)
                    TextButton(
                        onClick = { session.reindex() },
                        enabled = !state.busy && state.draftEnabled == state.enabled,
                        modifier = Modifier.testTag("creator-title-attribution-reindex"),
                    ) { Text(MR.strings.creator_title_attribution_reindex.localized()) }
                    if (state.reindexing) {
                        Text(MR.strings.creator_title_attribution_progress.localized(java.util.Locale.getDefault(), state.progress), modifier = Modifier.testTag("creator-title-attribution-progress"))
                    }
                    titleAttributionFeedback(state)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("creator-title-attribution-feedback"))
                    }
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("creator-title-attribution-error"))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { session.save() }, enabled = !state.busy,
                    modifier = Modifier.testTag("creator-title-attribution-save"),
                ) { Text(MR.strings.action_save.localized()) }
            },
            dismissButton = {
                TextButton(
                    onClick = session::dismiss, enabled = !state.busy || state.reindexing,
                    modifier = Modifier.testTag("creator-title-attribution-close"),
                ) { Text(MR.strings.action_cancel.localized()) }
            },
        )
    }
}


internal fun creatorTitleCreditText(mention: CreatorMention): String {
    val circle = mention.evidence.any { it.field == CreatorMetadataField.TITLE_CIRCLE }
    val creator = mention.evidence.any { it.field == CreatorMetadataField.TITLE_CREATOR }
    val label = when {
        circle && creator -> MR.strings.creator_title_credit_both.localized()
        circle -> MR.strings.creator_title_credit_circle.localized()
        creator -> MR.strings.creator_title_credit_creator.localized()
        else -> return mention.displayName
    }
    return "$label: ${mention.displayName}"
}


private fun titleAttributionFeedback(state: CreatorTitleAttributionState): String? = when (state.feedback) {
    CreatorTitleAttributionFeedback.ENABLED -> MR.strings.creator_title_attribution_enabled_result.localized()
    CreatorTitleAttributionFeedback.DISABLED -> MR.strings.creator_title_attribution_disabled_result.localized()
    CreatorTitleAttributionFeedback.REINDEXED -> MR.strings.creator_title_attribution_reindexed_result.localized(java.util.Locale.getDefault(), state.progress)
    CreatorTitleAttributionFeedback.CANCELLED -> MR.strings.creator_title_attribution_cancelled_result.localized(java.util.Locale.getDefault(), state.progress)
    CreatorTitleAttributionFeedback.FAILED -> MR.strings.creator_title_attribution_failed_result.localized(java.util.Locale.getDefault(), state.progress)
    null -> null
}
