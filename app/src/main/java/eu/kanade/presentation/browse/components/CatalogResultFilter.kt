package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.CatalogResultFilter
import tachiyomi.domain.source.service.SourceResultMetadata
import tachiyomi.domain.source.service.TranslationGroupCredit
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale

@Composable
internal fun CatalogResultFilterControl(
    filter: CatalogResultFilter,
    metadata: List<SourceResultMetadata>,
    onApply: (CatalogResultFilter) -> Unit,
) {
    var isOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(filter) }
    FilterChip(
        selected = filter.isActive,
        onClick = {
            draft = filter
            isOpen = true
        },
        modifier = Modifier.testTag("catalog-result-filter-open"),
        leadingIcon = { Icon(Icons.Outlined.FilterList, contentDescription = null) },
        label = { Text(stringResource(MR.strings.catalog_result_filter)) },
    )
    if (!isOpen) return

    val languages = remember(metadata, draft.languageTags) {
        (
            listOf("zh", "zh-hans", "zh-hant", "en", "ja", "ko") +
                metadata.flatMap { it.readingLanguageTags } + draft.languageTags
            ).distinct()
    }
    val groups = remember(metadata, draft.translationGroupKeys) {
        val known = metadata.flatMap { it.translationGroups }.associateBy { it.key }
        (
            known.values + draft.translationGroupKeys.filterNot { it in known }.map {
                TranslationGroupCredit(it, it, it)
            }
            ).sortedBy { it.displayName }
    }
    AlertDialog(
        onDismissRequest = { isOpen = false },
        title = { Text(stringResource(MR.strings.catalog_result_filter)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(MR.strings.catalog_result_filter_help), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(MR.strings.catalog_result_language),
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow {
                    languages.forEach { tag ->
                        FilterChip(
                            selected = tag in draft.languageTags,
                            onClick = {
                                draft = draft.copy(languageTags = draft.languageTags.toggle(tag))
                            },
                            modifier = Modifier.padding(end = 4.dp).testTag("catalog-result-language-$tag"),
                            label = { Text(languageName(tag)) },
                        )
                    }
                }
                Box(Modifier.testTag("catalog-result-unknown-language")) {
                    CheckboxItem(
                        label = stringResource(MR.strings.catalog_result_unknown_language),
                        checked = draft.includeUnknownLanguage,
                        onClick = { draft = draft.copy(includeUnknownLanguage = !draft.includeUnknownLanguage) },
                    )
                }
                Text(
                    stringResource(MR.strings.catalog_result_groups),
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (groups.isEmpty()) {
                    Text(
                        stringResource(MR.strings.catalog_result_no_groups),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                groups.forEach { group ->
                    CheckboxItem(
                        label = group.displayName,
                        checked = group.key in draft.translationGroupKeys,
                        onClick = {
                            draft = draft.copy(translationGroupKeys = draft.translationGroupKeys.toggle(group.key))
                        },
                    )
                }
                Box(Modifier.testTag("catalog-result-unknown-group")) {
                    CheckboxItem(
                        label = stringResource(MR.strings.catalog_result_unknown_group),
                        checked = draft.includeUnknownTranslationGroup,
                        onClick = {
                            draft = draft.copy(includeUnknownTranslationGroup = !draft.includeUnknownTranslationGroup)
                        },
                    )
                }
                TextButton(
                    onClick = { draft = CatalogResultFilter() },
                    modifier = Modifier.testTag("catalog-result-filter-reset"),
                ) {
                    Text(stringResource(MR.strings.action_reset))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(draft)
                    isOpen = false
                },
                modifier = Modifier.testTag("catalog-result-filter-apply"),
            ) {
                Text(stringResource(MR.strings.action_apply))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { isOpen = false },
                modifier = Modifier.testTag("catalog-result-filter-cancel"),
            ) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
internal fun BrowseSourceResultMetadata(manga: Manga, modifier: Modifier = Modifier) {
    val metadata = remember(manga) { SourceResultMetadata.from(manga) }
    val language = if (metadata.readingLanguageTags.isEmpty() || metadata.language.conflict) {
        stringResource(MR.strings.catalog_result_unknown)
    } else {
        metadata.readingLanguageTags.joinToString(" / ") { languageName(it) }
    }
    val groups = metadata.translationGroups.firstOrNull()?.displayName?.let {
        if (metadata.translationGroups.size > 1) "$it +${metadata.translationGroups.size - 1}" else it
    }
    Text(
        text = listOfNotNull(language, groups).joinToString(" · "),
        modifier = modifier.fillMaxWidth().testTag("catalog-result-metadata-${manga.url}"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun catalogResultVisibleIndices(
    manga: List<Manga>,
    filter: CatalogResultFilter,
    hideInLibrary: Boolean,
): List<Int> {
    val seen = hashSetOf<String>()
    return manga.indices.filter { index ->
        val item = manga[index]
        seen.add(item.url) && (!hideInLibrary || !item.favorite) && filter.matches(SourceResultMetadata.from(item))
    }
}

private fun languageName(tag: String): String = Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault())

private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value
