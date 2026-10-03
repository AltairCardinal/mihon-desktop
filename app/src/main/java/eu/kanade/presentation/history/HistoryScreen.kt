package eu.kanade.presentation.history

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.history.components.HistoryItem
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.tachiyomi.ui.history.HistoryScreenModel
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.api.get
import java.time.LocalDate

@Composable
fun HistoryScreen(
    state: tachiyomi.domain.history.service.HistoryState,
    snackbarHostState: SnackbarHostState,
    onSearchQueryChange: (String?) -> Unit,
    onClickCover: (mangaId: Long) -> Unit,
    onClickResume: (mangaId: Long, chapterId: Long) -> Unit,
    onClickFavorite: (mangaId: Long) -> Unit,
    onDialogChange: (tachiyomi.domain.history.service.HistoryDialog?) -> Unit,
) {
    val preferences = androidx.compose.runtime.remember {
        uy.kohesive.injekt.Injekt.get<eu.kanade.domain.ui.UiPreferences>()
    }
    mihon.presentation.history.HistoryContent(
        state = state,
        datePreferences = mihon.presentation.history.HistoryDatePreferences(
            preferences.relativeTime().get(),
            preferences.dateFormat().get(),
        ),
        onSearchQueryChange = onSearchQueryChange,
        onCover = { onClickCover(it.mangaId) },
        onResume = { onClickResume(it.mangaId, it.chapterId) },
        onFavorite = { onClickFavorite(it.mangaId) },
        onDelete = { onDialogChange(tachiyomi.domain.history.service.HistoryDialog.Delete(it)) },
        onClear = { onDialogChange(tachiyomi.domain.history.service.HistoryDialog.DeleteAll) },
        cover = { item, modifier, click ->
            eu.kanade.presentation.manga.components.MangaCover.Book(
                modifier = modifier,
                data = item.coverData,
                onClick = click,
            )
        },
        snackbar = { SnackbarHost(snackbarHostState) },
    )
}

@PreviewLightDark
@Composable
internal fun HistoryScreenPreviews(
    @PreviewParameter(HistoryScreenModelStateProvider::class)
    historyState: tachiyomi.domain.history.service.HistoryState,
) {
    TachiyomiPreviewTheme {
        HistoryScreen(
            state = historyState,
            snackbarHostState = SnackbarHostState(),
            onSearchQueryChange = {},
            onClickCover = {},
            onClickResume = { _, _ -> run {} },
            onDialogChange = {},
            onClickFavorite = {},
        )
    }
}
