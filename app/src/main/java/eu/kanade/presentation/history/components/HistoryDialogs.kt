package eu.kanade.presentation.history.components

import androidx.compose.runtime.Composable

@Composable
fun HistoryDeleteDialog(onDismissRequest: () -> Unit, onDelete: (Boolean) -> Unit) =
    mihon.presentation.history.HistoryDeleteDialog(onDismissRequest, onDelete)

@Composable
fun HistoryDeleteAllDialog(onDismissRequest: () -> Unit, onDelete: () -> Unit) =
    mihon.presentation.history.HistoryDeleteAllDialog(onDismissRequest, onDelete)
