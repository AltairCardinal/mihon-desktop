package mihon.desktop.platform

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.ResolvableSource
import eu.kanade.tachiyomi.source.online.UriType
import kotlinx.coroutines.CancellationException
import mihon.desktop.backup.BackupRestoreScreenModelFactory
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.domain.platform.ExternalAction
import mihon.domain.platform.ExternalActionInput
import mihon.domain.platform.ExternalActionParser
import tachiyomi.domain.source.service.SourceManager

class DesktopDeepLinkHandler(
    private val sourceManager: SourceManager,
    private val saveSourceMangaForDetails: SaveSourceMangaForDetails,
) {
    suspend fun resolve(input: ExternalActionInput): DesktopExternalActionTarget =
        resolve(ExternalActionParser.resolve(input))

    private suspend fun resolve(action: ExternalAction): DesktopExternalActionTarget = when (action) {
        ExternalAction.NoOp -> DesktopExternalActionTarget.Rejected(DesktopExternalActionTarget.Rejection.NoAction)
        is ExternalAction.Search -> resolveSearch(action.query)
        is ExternalAction.AddRepository -> DesktopExternalActionTarget.ExtensionRepo(action.url)
        is ExternalAction.RestoreBackup -> BackupRestoreScreenModelFactory.resolveExternalAction(action)
        is ExternalAction.Rejected -> DesktopExternalActionTarget.Rejected(
            DesktopExternalActionTarget.Rejection.ParserRejected,
            action.reason,
        )
    }

    private suspend fun resolveSearch(query: String): DesktopExternalActionTarget = try {
        sourceManager.getCatalogueSources().forEach { catalogueSource ->
            val source = catalogueSource as? ResolvableSource ?: return@forEach
            when (source.getUriType(query)) {
                UriType.Unknown -> Unit
                UriType.Manga -> return resolveManga(query, catalogueSource, source)
                UriType.Chapter -> return resolveChapter(query, catalogueSource, source)
            }
        }
        DesktopExternalActionTarget.GlobalSearch(query)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DesktopExternalActionTarget.Rejected(DesktopExternalActionTarget.Rejection.SourceResolutionFailed)
    }

    private suspend fun resolveManga(
        query: String,
        catalogueSource: CatalogueSource,
        source: ResolvableSource,
    ): DesktopExternalActionTarget {
        val listedManga = source.getManga(query)
            ?: return DesktopExternalActionTarget.GlobalSearch(query)
        val manga = saveSourceMangaForDetails.awaitSearchResults(listOf(listedManga), catalogueSource.id).single()
        return DesktopExternalActionTarget.Manga(manga.id)
    }

    private suspend fun resolveChapter(
        query: String,
        catalogueSource: CatalogueSource,
        source: ResolvableSource,
    ): DesktopExternalActionTarget {
        val listedManga = source.getManga(query)
            ?: return DesktopExternalActionTarget.GlobalSearch(query)
        val linkedChapter = source.getChapter(query)
        val resolved = saveSourceMangaForDetails.awaitLinkedChapter(catalogueSource, listedManga, linkedChapter)
        return resolved.chapter?.let { DesktopExternalActionTarget.Chapter(resolved.manga.id, it.id) }
            ?: DesktopExternalActionTarget.Manga(resolved.manga.id)
    }
}
