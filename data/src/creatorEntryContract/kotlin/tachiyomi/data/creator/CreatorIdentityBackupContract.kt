package tachiyomi.data.creator

import eu.kanade.tachiyomi.data.backup.models.BackupAuthorArchiveSection
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.backup.AuthorArchiveBackupContributor
import tachiyomi.data.backup.SqlDelightAuthorArchiveBackupContributor
import tachiyomi.domain.creator.model.SetCreatorDisplayNameRequest

/** Platform runners transfer this graph through their actual backup creator, codec, and restorer. */
suspend fun verifyCreatorIdentityBackup(
    sourceHandler: DatabaseHandler,
    targetHandler: DatabaseHandler,
    transfer: suspend (AuthorArchiveBackupContributor, AuthorArchiveBackupContributor) -> BackupAuthorArchiveSection,
) {
    var sequence = 0
    val source = CreatorRepositoryImpl(sourceHandler, portableKeyFactory = { "portable-${++sequence}" })
    val target = CreatorRepositoryImpl(targetHandler)
    val first = source.upsertCreator("One")
    val second = source.upsertCreator("ONE")
    val third = source.upsertCreator("著者繁體")
    source.mergeCreatorIdentities(second.id, first.id)
    source.mergeCreatorIdentities(third.id, first.id)
    source.addManualCreatorAlias(first.id, "著者繁体")
    val before = source.getIdentitySnapshot(first.id)
    source.setCreatorDisplayName(SetCreatorDisplayNameRequest(first.id, before.revision, "ONE", "primary-command"))
    source.followCreator(first.id)
    val export = SqlDelightAuthorArchiveBackupContributor(
        sourceHandler,
        awaitIdentityReady = source::awaitIdentityReady,
    )
    val import = SqlDelightAuthorArchiveBackupContributor(
        targetHandler,
        awaitIdentityReady = target::awaitIdentityReady,
    )
    val transferred = transfer(export, import)
    check(transferred.version == 5)
    val expected = source.getIdentitySnapshot(first.id)
    val restoredId = checkNotNull(target.resolveCreatorIdByExactName("One"))
    val restored = target.getIdentitySnapshot(restoredId)
    check(restored.names.toSet() == expected.names.toSet()) { "Exact case and script variants must survive" }
    check(restored.displayName == "ONE" && restored.followed)
    val firstImport = checkNotNull(import.createSection())
    import.restoreSection(transferred)
    check(import.createSection() == firstImport) { "Repeated import must preserve the complete portable graph" }
    check(firstImport.creators.map { it.portableKey }.toSet() == setOf("portable-1", "portable-2", "portable-3"))
    check(firstImport.creators.count { it.status == "ACTIVE" } == 1)
    check(firstImport.creators.filter { it.status == "MERGED" }.all { it.mergedIntoPortableKey == "portable-1" })
    targetHandler.await {
        val restoredNames = author_archiveQueries.getArchiveIdentityNames().executeAsList().map { it.name_text }.toSet()
        check(restoredNames == expected.names.toSet())
    }
}

/** Run immediately after actual module initialization, before a creator repository read. */
suspend fun verifyCreatorBackupReadiness(handler: DatabaseHandler, contributor: AuthorArchiveBackupContributor) {
    handler.await(inTransaction = true) {
        author_archiveQueries.upsertArchiveCreatorFromBackup(
            "before-author-page",
            "Before author page",
            "before author page",
            null,
            false,
            1,
            1,
        )
        val id = author_archiveQueries.getArchiveCreatorIdByPortableKey("before-author-page").executeAsOne()
        author_archiveQueries.upsertArchiveAlias(
            id, "Historical alias", "historical alias", "USER", "fixture", 1.0, true, 1, 1,
        )
    }
    val backup = checkNotNull(contributor.createSection())
    check(
        backup.creators.single { it.portableKey == "before-author-page" }.names.map { it.text }.toSet() ==
            setOf("Before author page", "Historical alias"),
    ) { "Backup DI must await exact identity preparation" }
}
