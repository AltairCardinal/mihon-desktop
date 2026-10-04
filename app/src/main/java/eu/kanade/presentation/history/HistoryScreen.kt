package eu.kanade.presentation.history

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import uy.kohesive.injekt.api.get

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
                contentDescription = item.title,
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
