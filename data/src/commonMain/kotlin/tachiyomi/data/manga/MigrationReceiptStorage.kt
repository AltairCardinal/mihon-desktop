package tachiyomi.data.manga

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.domain.migration.MigrationCommit
import mihon.domain.migration.MigrationReceipt
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.data.Database
import tachiyomi.domain.chapter.service.ChapterDirectoryEffects
import tachiyomi.domain.chapter.service.ChapterDirectoryPhase

internal fun Database.pendingMigration(sourceMangaId: Long): ChapterDirectoryPhase? =
    chapter_directory_phasesQueries.getPending().executeAsList()
        .map { Json.decodeFromString<ChapterDirectoryPhase>(it) }
        .filter { it.migrationReceipt?.request?.sourceMangaId == sourceMangaId }
        .also { check(it.size <= 1) { "Source has conflicting migration confirmations" } }
        .singleOrNull()

internal fun Database.requireMigration(commit: MigrationCommit): ChapterDirectoryPhase {
    val phase = requireNotNull(pendingMigration(commit.sourceMangaId)) { "Migration preparation is missing" }
    check(phase.migrationReceipt?.request == commit) { "Migration confirmation was replaced" }
    return phase
}

internal fun Database.validateMigrationIdentity(commit: MigrationCommit) {
    require(commit.sourceMangaId != commit.targetMangaId) { "Cannot migrate onto the same manga" }
    val source = mangasQueries.getMangaById(commit.sourceMangaId, MangaMapper::mapManga).executeAsOne()
    val target = mangasQueries.getMangaById(commit.targetMangaId, MangaMapper::mapManga).executeAsOne()
    require(source.source == commit.sourceId && source.url == commit.sourceUrl) { "Source identity changed" }
    require(target.source == commit.targetSourceId && target.url == commit.targetUrl) { "Target identity changed" }
    require(source.source != target.source || source.url != target.url) { "Cannot migrate onto the same manga" }
}

internal fun Database.prepareMigrationReceipt(commit: MigrationCommit): MigrationReceipt {
    require(!commit.operationId.isNullOrBlank()) { "Migration confirmation identity is missing" }
    validateMigrationIdentity(commit)
    pendingMigration(commit.sourceMangaId)?.let {
        check(it.migrationReceipt?.request == commit) { "Source already has a pending migration" }
        return requireNotNull(it.migrationReceipt)
    }
    check(chapter_directory_phasesQueries.getForManga(commit.sourceMangaId).executeAsOneOrNull() == null) {
        "Source directory has unfinished work"
    }
    check(chapter_directory_phasesQueries.getForManga(commit.targetMangaId).executeAsOneOrNull() == null) {
        "Target directory has unfinished work"
    }
    check(
        chapter_directory_phasesQueries.getPending().executeAsList().none {
            Json.decodeFromString<ChapterDirectoryPhase>(it).migrationReceipt?.request?.sourceMangaId ==
                commit.targetMangaId
        },
    ) { "Target is already participating in a migration" }
    val target = mangasQueries.getMangaById(commit.targetMangaId, MangaMapper::mapManga).executeAsOne()
    val receipt = MigrationReceipt(
        commit,
        filesReady = commit.flags.none { it == MigrationFlag.CUSTOM_COVER || it == MigrationFlag.REMOVE_DOWNLOAD },
    )
    val phase = ChapterDirectoryPhase(
        id = requireNotNull(commit.operationId), mangaId = commit.targetMangaId,
        effects = ChapterDirectoryEffects(
            sourceId = target.source,
            sourceName = "",
            mangaUrl = target.url,
            mangaTitle = target.title,
            origin = "MIGRATION",
            observedAt = commit.now,
        ),
        currentTitle = target.title, files = emptyList(), addedIds = emptyList(), downloadIds = emptyList(),
        observationPending = false, downloadsPending = false, migrationReceipt = receipt,
    )
    chapter_directory_phasesQueries.insertPhase(phase.mangaId, phase.id, Json.encodeToString(phase))
    return receipt
}

internal fun Database.writeMigrationReceipt(phase: ChapterDirectoryPhase, receipt: MigrationReceipt) {
    chapter_directory_phasesQueries.updatePhase(
        Json.encodeToString(phase.copy(migrationReceipt = receipt)),
        phase.mangaId,
        phase.id,
    )
}
