package eu.kanade.tachiyomi.ui.browse.author

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
import tachiyomi.presentation.core.i18n.stringResource

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
        ) { Text(stringResource(MR.strings.creator_title_attribution_title)) }
        if (!state.open) {
            titleAttributionFeedback(state)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("creator-title-attribution-feedback"),
                )
            }
        }
    }
    if (state.open) {
        AlertDialog(
            onDismissRequest = session::dismiss,
            title = { Text(stringResource(MR.strings.creator_title_attribution_title)) },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sourceName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    Text(
                        stringResource(MR.strings.creator_title_attribution_description),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().focusRequester(toggleFocus)
                            .toggleable(
                                state.draftEnabled,
                                enabled = !state.busy,
                                role = Role.Checkbox,
                                onValueChange = session::select,
                            )
                            .testTag("creator-title-attribution-toggle"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = state.draftEnabled, enabled = !state.busy, onCheckedChange = null)
                        Text(stringResource(MR.strings.creator_title_attribution_enabled))
                    }
                    Text(
                        stringResource(MR.strings.creator_title_attribution_preview),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (state.preview.isEmpty()) {
                        Text(
                            stringResource(MR.strings.creator_title_attribution_no_preview),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        state.preview.forEachIndexed { index, mention ->
                            Text(
                                creatorTitleCreditText(mention),
                                modifier = Modifier.testTag("creator-title-attribution-preview-$index"),
                            )
                        }
                    }
                    val raw = state.manga?.let {
                        listOfNotNull(it.author, it.artist).distinct().joinToString(" / ")
                    }.orEmpty()
                    if (raw.isNotBlank()) {
                        Text(
                            stringResource(MR.strings.creator_title_attribution_original, raw),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(
                        onClick = { session.reindex() },
                        enabled = !state.busy && state.draftEnabled == state.enabled,
                        modifier = Modifier.testTag("creator-title-attribution-reindex"),
                    ) { Text(stringResource(MR.strings.creator_title_attribution_reindex)) }
                    if (state.reindexing) {
                        Text(
                            stringResource(MR.strings.creator_title_attribution_progress, state.progress),
                            modifier = Modifier.testTag("creator-title-attribution-progress"),
                        )
                    }
                    titleAttributionFeedback(state)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("creator-title-attribution-feedback"),
                        )
                    }
                    state.error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("creator-title-attribution-error"),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { session.save() },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("creator-title-attribution-save"),
                ) { Text(stringResource(MR.strings.action_save)) }
            },
            dismissButton = {
                TextButton(
                    onClick = session::dismiss,
                    enabled = !state.busy || state.reindexing,
                    modifier = Modifier.testTag("creator-title-attribution-close"),
                ) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
}

@Composable
internal fun creatorTitleCreditText(mention: CreatorMention): String {
    val circle = mention.evidence.any { it.field == CreatorMetadataField.TITLE_CIRCLE }
    val creator = mention.evidence.any { it.field == CreatorMetadataField.TITLE_CREATOR }
    val label = when {
        circle && creator -> stringResource(MR.strings.creator_title_credit_both)
        circle -> stringResource(MR.strings.creator_title_credit_circle)
        creator -> stringResource(MR.strings.creator_title_credit_creator)
        else -> return mention.displayName
    }
    return "$label: ${mention.displayName}"
}

@Composable
private fun titleAttributionFeedback(state: CreatorTitleAttributionState): String? = when (state.feedback) {
    CreatorTitleAttributionFeedback.ENABLED -> stringResource(MR.strings.creator_title_attribution_enabled_result)
    CreatorTitleAttributionFeedback.DISABLED -> stringResource(MR.strings.creator_title_attribution_disabled_result)
    CreatorTitleAttributionFeedback.REINDEXED -> stringResource(
        MR.strings.creator_title_attribution_reindexed_result,
        state.progress,
    )
    CreatorTitleAttributionFeedback.CANCELLED -> stringResource(
        MR.strings.creator_title_attribution_cancelled_result,
        state.progress,
    )
    CreatorTitleAttributionFeedback.FAILED -> stringResource(
        MR.strings.creator_title_attribution_failed_result,
        state.progress,
    )
    null -> null
}
