package mihon.desktop.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import cafe.adriel.voyager.core.model.rememberScreenModel
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.MigrationOptions
import mihon.desktop.history.HistoryScreenModel
import mihon.desktop.library.LibraryScreenModelFactory
import mihon.desktop.ui.library.CategoryManagementDialog
import mihon.desktop.ui.migration.MigrationConfirmDialog
import tachiyomi.domain.history.service.HistoryDialog

@Composable
internal fun HistoryManageCategories(onDismiss: () -> Unit) {
    val model = remember { LibraryScreenModelFactory.create() }
    DisposableEffect(model) { onDispose { model.onDispose() } }
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.observeCategories() }
    CategoryManagementDialog(state.categories, model::createCategory, model::renameCategory, model::deleteCategory, model::reorderCategory, onDismiss)
}

@Composable
internal fun HistoryMigrationDialog(dialog: HistoryDialog.Migrate, model: HistoryScreenModel, onDismiss: () -> Unit, onOpenCurrent: () -> Unit) {
    val scope = rememberCoroutineScope()
    val dependencies = LocalDesktopUiDependencies.current
    fun migrate(options: MigrationOptions, replace: Boolean) {
        scope.launch {
            try {
                val target = dialog.target
                val source = requireNotNull(dependencies.sourceManager.get(target.source)) { "Source unavailable" }
                val remote = SManga.create().apply {
                    url = target.url
                    title = target.title
                    artist = target.artist
                    author = target.author
                    description = target.description
                    genre = target.genre?.joinToString(", ")
                    status = target.status.toInt()
                    thumbnail_url = target.thumbnailUrl
                }
                val chapters = source.getChapterList(remote)
                require(chapters.isNotEmpty()) { "No chapters" }
                dependencies.migrateManga.await(dialog.current, remote, target.source, chapters, options, replace)
                onDismiss()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                model.controller.internalError()
            }
        }
    }
    MigrationConfirmDialog(dialog.current.title, dialog.target.title, onDismiss, { migrate(it, false) }, { migrate(it, true) }, onOpenCurrent)
}
