package mihon.desktop.test.http

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.journal.SyncOutboxStore
import mihon.data.sync.runtime.SyncRuntime
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.desktop.platform.DesktopTestProfile
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCancellationDecision
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.SyncProtocol
import mihon.domain.sync.SyncReceiverDecisionResult
import mihon.domain.sync.transport.SyncRepository
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import java.io.File
import java.nio.file.Files

/** Fixed, offline acceptance records. No caller-supplied event, space, SQL, credentials, or repository. */
internal class AuthorSyncTestFixture(
    private val profile: String,
    private val handler: DatabaseHandler,
    private val runtime: SyncRuntime,
    private val archive: CreatorArchiveRepository,
    private val creators: CreatorRepository,
) {
    private val mutex = Mutex()
    private val journal = SyncLocalJournal(handler)
    private val inbox = SyncInboxStore(handler)
    private val author = SyncObjectKey(SyncObjectType.AUTHOR, portableKey = "ga06-acceptance-old-author")
    private val alias = SyncObjectKey(SyncObjectType.AUTHOR, portableKey = "ga06-acceptance-alias")

    suspend fun execute(step: String) = mutex.withLock {
        require(step in setOf("add", "remove", "confirm_remove", "replay", "refollow", "verify_local_cancel"))
        val directory = File(profile).canonicalFile
        check(DesktopTestProfile.root == directory) { "Fixture requires the startup-isolated test profile" }
        val marker = directory.resolve(".mihon-test-profile")
        check(!Files.isSymbolicLink(marker.toPath()) && marker.readText(Charsets.UTF_8) == "mihon-desktop-test-profile-v1\n")
        check(DesktopPlatformPaths.current().databaseFile.canonicalFile.toPath().startsWith(directory.toPath()))
        handler.await {
            sync_journalQueries.getActiveSpace().executeAsOneOrNull()?.let {
                check(it.space_id == SPACE && it.generation == 1L) { "Another sync space is configured" }
            }
        }
        journal.connect(SPACE, 1, SyncRepository("ga06-fixture", "offline-acceptance", "mihon-sync"), "local", 1)
        val add = event(1, author, SyncEffectKind.ADD)
        val aliasAdd = event(2, alias, SyncEffectKind.ADD)
        val remove = event(3, author, SyncEffectKind.REMOVE, add)
        val refollow = event(4, author, SyncEffectKind.ADD, remove)
        when (step) {
            "add" -> {
                receive(add)
                receive(aliasAdd)
                check(followed())
            }
            "remove" -> {
                receive(remove)
                check(runtime.projector.pending(SPACE, 1).any { it.objectKey == author })
            }
            "confirm_remove" -> {
                val item = runtime.projector.pending(SPACE, 1).single { it.objectKey == author }
                check(runtime.projector.decide(SPACE, 1, item, SyncCancellationDecision.CONFIRM) == SyncReceiverDecisionResult.APPLIED)
                check(!followed())
            }
            "replay" -> {
                check(inbox.ingest(batch(add)).duplicate)
                project()
                check(!followed())
            }
            "refollow" -> {
                receive(refollow)
                check(followed())
            }
            "verify_local_cancel" -> {
                check(!followed())
                val outgoing = requireNotNull(SyncOutboxStore(handler).nextBatch(SPACE, 1))
                check(outgoing.events.any { event -> event.effects.any { it.kind == SyncEffectKind.REMOVE } })
            }
        }
    }

    private suspend fun followed(): Boolean {
        val oldId = handler.await {
            author_archiveQueries.getArchiveCreatorIdByPortableKey(requireNotNull(author.portableKey)).executeAsOne()
        }
        val root = archive.getIdentitySnapshot(oldId).id
        return creators.getFollowedCreators().any { it.creatorId == root }
    }

    private suspend fun receive(event: SyncEventEnvelope) {
        check(inbox.ingest(batch(event)).accepted)
        project()
    }

    private suspend fun project() {
        repeat(30) { if (runtime.projector.project(SPACE, 1) == 0) return }
        error("Fixture projection did not settle")
    }

    private fun event(
        sequence: Long,
        key: SyncObjectKey,
        kind: SyncEffectKind,
        parent: SyncEventEnvelope? = null,
    ) = SyncEventEnvelope(
        protocolVersion = SyncProtocol.CURRENT_VERSION,
        spaceId = SPACE,
        generation = 1,
        actorId = "remote-fixture",
        epoch = 1,
        seq = sequence,
        category = SyncCategory.FOLLOW,
        effects = listOf(SyncEffect("following", key, SyncField.FOLLOWING, kind, listOfNotNull(parent?.ref("following")))),
        origin = SyncOrigin.USER,
        occurredAt = sequence,
        batchId = "ga06-fixture-$sequence",
    )

    private fun batch(event: SyncEventEnvelope): SyncBatch {
        val key = event.effects.single().objectKey
        return SyncBatch(
            SyncProtocol.CURRENT_VERSION, SPACE, 1, requireNotNull(event.batchId), listOf(event),
            listOf(SyncObjectDescriptor(key, if (key == author) "GA06 验收作者" else "GA06 验收别名")),
        )
    }

    private companion object {
        const val SPACE = "ga06-author-acceptance"
    }
}
