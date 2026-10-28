package eu.kanade.tachiyomi.ui.browse.author

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Actions of the author page.
 *
 * The manual work search opens the global search with the author name, which searches every
 * source for that name, like the original app did from the manga details.
 */
@Composable
internal fun CreatorActionRow(
    followed: Boolean,
    scanning: Boolean,
    manualSearchScreen: GlobalSearchScreen?,
    onToggleFollow: () -> Unit,
    onScan: () -> Unit,
    onManualSearch: (GlobalSearchScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onToggleFollow) {
            Text(
                stringResource(
                    if (followed) MR.strings.desktop_ui_unfollow else MR.strings.desktop_ui_follow,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(onClick = onScan, enabled = !scanning) {
            Text(
                stringResource(MR.strings.desktop_ui_check_new_works),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(
            onClick = { manualSearchScreen?.let(onManualSearch) },
            enabled = manualSearchScreen != null,
        ) {
            Text(
                stringResource(MR.strings.desktop_ui_manual_search_works),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The global search that looks for the works of the given author name.
 *
 * The name is the search query, so every source is searched with it. A blank name has nothing to
 * search for and therefore no screen.
 */
internal fun authorManualSearchScreen(displayName: String?): GlobalSearchScreen? =
    displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { GlobalSearchScreen(searchQuery = it) }
