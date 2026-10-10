package mihon.desktop.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import eu.kanade.tachiyomi.source.model.SManga
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.history.HistoryScreenModel
import tachiyomi.domain.history.service.HistoryDialog

@Composable
internal fun HistoryMigrationDialog(
    dialog: HistoryDialog.Migrate,
    model: HistoryScreenModel,
    onDismiss: () -> Unit,
    onOpenCurrent: () -> Unit,
) {
    val dependencies = LocalDesktopUiDependencies.current
    val target = dialog.target
    val remote = remember(target) {
        SManga.create().apply {
            url = target.url
            title = target.title
            artist = target.artist
            author = target.author
            description = target.description
            genre = target.genre?.joinToString(", ")
            status = target.status.toInt()
            thumbnail_url = target.thumbnailUrl
        }
    }
    mihon.desktop.ui.migration.MigrationConfirmation(
        dialog.current.id,
        dialog.current.title,
        dependencies.sourceManager.get(target.source) as? eu.kanade.tachiyomi.source.CatalogueSource,
        remote,
        null,
        onDismiss,
        onDismiss,
        onOpenCurrent,
    )
}
