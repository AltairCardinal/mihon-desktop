package mihon.desktop.ui.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.domain.source.service.CatalogResultFilter
import tachiyomi.domain.source.service.SourceResultMetadata
import tachiyomi.i18n.MR
import java.util.Locale

@Composable
internal fun CatalogResultFilterDialog(
    applied: CatalogResultFilter,
    metadata: List<SourceResultMetadata>,
    onApply: (CatalogResultFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(applied) { mutableStateOf(applied) }
    val languages = (
        listOf("zh", "zh-hans", "zh-hant", "en", "ja", "ko") +
            metadata.flatMap { it.readingLanguageTags } + draft.languageTags
        ).distinct()
    val groups = (
        metadata.flatMap { it.translationGroups.map { credit -> credit.key } } +
            draft.translationGroupKeys
        ).distinct().sorted()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(MR.strings.catalog_result_filter.localized()) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(MR.strings.catalog_result_filter_help.localized(), style = MaterialTheme.typography.bodySmall)
                Text(
                    MR.strings.catalog_result_language.localized(),
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                languages.forEach { tag ->
                    CatalogFilterOption(languageName(tag), tag in draft.languageTags, "catalog-result-language:$tag") {
                        draft = draft.copy(languageTags = draft.languageTags.toggle(tag))
                    }
                }
                CatalogFilterOption(
                    MR.strings.catalog_result_unknown_language.localized(),
                    draft.includeUnknownLanguage,
                    "catalog-result-unknown-language",
                ) { draft = draft.copy(includeUnknownLanguage = !draft.includeUnknownLanguage) }
                Text(
                    MR.strings.catalog_result_groups.localized(),
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (groups.isEmpty()) {
                    Text(
                        MR.strings.catalog_result_no_groups.localized(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                groups.forEach { group ->
                    CatalogFilterOption(group, group in draft.translationGroupKeys, "catalog-result-group:$group") {
                        draft = draft.copy(translationGroupKeys = draft.translationGroupKeys.toggle(group))
                    }
                }
                CatalogFilterOption(
                    MR.strings.catalog_result_unknown_group.localized(),
                    draft.includeUnknownTranslationGroup,
                    "catalog-result-unknown-group",
                ) { draft = draft.copy(includeUnknownTranslationGroup = !draft.includeUnknownTranslationGroup) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(draft) }, modifier = Modifier.testTag("catalog-result-filter-apply")) {
                Text(MR.strings.action_apply.localized())
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = { draft = CatalogResultFilter() },
                    modifier = Modifier.testTag("catalog-result-filter-reset"),
                ) { Text(MR.strings.action_reset.localized()) }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("catalog-result-filter-cancel")) {
                    Text(MR.strings.action_cancel.localized())
                }
            }
        },
    )
}

@Composable
private fun CatalogFilterOption(label: String, checked: Boolean, tag: String, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(tag).toggleable(checked, role = Role.Checkbox) { onToggle() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun CatalogResultMetadataLabel(metadata: SourceResultMetadata, url: String) {
    val language = if (metadata.readingLanguageTags.isEmpty()) {
        MR.strings.catalog_result_unknown.localized()
    } else {
        metadata.readingLanguageTags.joinToString(" / ", transform = ::languageName)
    }
    val inferred = metadata.readingLanguageTags.isNotEmpty() || metadata.translationGroups.isNotEmpty()
    Text(
        text = (
            listOf(language) + metadata.translationGroups.map { it.displayName } +
                if (inferred) listOf(MR.strings.catalog_result_inferred.localized()) else emptyList()
            ).joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(6.dp).testTag("catalog-result-metadata:$url"),
    )
}

private fun languageName(tag: String): String = Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault())

private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value
