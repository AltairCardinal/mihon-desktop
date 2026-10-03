package tachiyomi.domain.manga.repository

import kotlinx.coroutines.flow.Flow
import mihon.domain.migration.MigrationCommit
import mihon.domain.sync.SyncMutationContext
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount

interface LibraryMembershipRepository {
    suspend fun updateAtomically(update: LibraryMembershipUpdate)
}

data class LibraryMembershipUpdate(
    val mangaId: Long,
    val favorite: Boolean,
    val dateAdded: Long,
    val categoryIds: List<Long>,
    val updateCategories: Boolean = true,
    val chapterFlags: Long? = null,
    val viewerFlags: Long? = null,
    val notes: String? = null,
    val syncContext: SyncMutationContext = SyncMutationContext.Metadata,
)

interface MangaRepository : LibraryMembershipRepository {

    suspend fun prepareMigration(commit: MigrationCommit): mihon.domain.migration.MigrationReceipt =
        throw UnsupportedOperationException("Migration preparation is not available")

    suspend fun migrationReceipt(sourceMangaId: Long): mihon.domain.migration.MigrationReceipt? = null

    suspend fun pendingMigrations(): List<mihon.domain.migration.MigrationReceipt> = emptyList()

    suspend fun markMigrationFilesReady(commit: MigrationCommit) {
        throw UnsupportedOperationException("Migration staging is not available")
    }

    suspend fun completeMigrationFiles(commit: MigrationCommit) {
        throw UnsupportedOperationException("Migration cleanup is not available")
    }

    suspend fun acknowledgeMigration(commit: MigrationCommit) {
        throw UnsupportedOperationException("Migration acknowledgement is not available")
    }

    suspend fun abortPreparedMigration(commit: MigrationCommit) {
        throw UnsupportedOperationException("Migration rollback is not available")
    }

    suspend fun commitMigration(commit: MigrationCommit): Manga =
        throw UnsupportedOperationException("Atomic migration is not available")

    suspend fun updateMembershipsAtomically(updates: List<LibraryMembershipUpdate>)

    suspend fun getMangaById(id: Long): Manga

    suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga>

    suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga?

    fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?>

    suspend fun getFavorites(): List<Manga>

    suspend fun getReadMangaNotInLibrary(): List<Manga>

    suspend fun getLibraryManga(): List<LibraryManga>

    fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>>

    fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>>

    suspend fun getDuplicateLibraryManga(id: Long, title: String): List<MangaWithChapterCount>

    suspend fun getUpcomingManga(statuses: Set<Long>): Flow<List<Manga>>

    suspend fun resetViewerFlags(): Boolean

    suspend fun resetViewerFlagsForNonFavorites(): Boolean

    suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>)

    suspend fun update(update: MangaUpdate): Boolean

    suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean

    suspend fun insertNetworkManga(manga: List<Manga>): List<Manga>
}
