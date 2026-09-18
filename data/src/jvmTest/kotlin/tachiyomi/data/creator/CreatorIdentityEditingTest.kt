package tachiyomi.data.creator

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.model.AddCreatorAliasesRequest
import tachiyomi.domain.creator.model.CanonicalWorkArchiveGroup
import tachiyomi.domain.creator.model.CreatorAliasCandidates
import tachiyomi.domain.creator.model.CreatorIdentityRequestConflict
import tachiyomi.domain.creator.model.CreatorIdentitySnapshot
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.CreatorWorkArchive
import tachiyomi.domain.creator.model.CreatorWorkArchiveFilter
import tachiyomi.domain.creator.model.SetCreatorDisplayNameRequest
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.StaleCreatorIdentityException
import tachiyomi.domain.creator.service.CreatorIdentityEditor
import tachiyomi.domain.creator.service.OpenCreatorWorkVersion

class CreatorIdentityEditingTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var handler: JvmDatabaseHandler
    private lateinit var repository: CreatorRepositoryImpl

    @BeforeEach
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        handler = JvmDatabaseHandler(
            Database(
                driver,
                historyAdapter = History.Adapter(DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            ),
            driver,
        )
        repository = CreatorRepositoryImpl(handler)
    }

    @org.junit.jupiter.api.AfterEach
    fun close() = handler.close()

    @Test
    fun `late name refresh cannot cancel a newer confirmation`() = runBlocking<Unit> {
        verifyLateNameRefresh("became-main")
    }

    @Test
    fun `late name refresh cannot replace a newer confirmation snapshot or key`() = runBlocking<Unit> {
        verifyLateNameRefresh("still-alias")
    }

    @Test
    fun `late name refresh failure cannot overwrite a newer confirmation error`() = runBlocking<Unit> {
        verifyLateNameRefresh("failure")
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.verifyLateNameRefresh(outcome: String) {
        val target = repository.upsertCreator("Main")
        repository.addManualCreatorAlias(target.id, "A")
        repository.addManualCreatorAlias(target.id, "B")
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val requests = mutableListOf<SetCreatorDisplayNameRequest>()
        val delayed = object : tachiyomi.domain.creator.repository.CreatorArchiveRepository by repository {
            override suspend fun getIdentitySnapshot(creatorId: Long): CreatorIdentitySnapshot {
                entered.complete(Unit)
                release.await()
                if (outcome == "failure") error("old refresh failed")
                return repository.getIdentitySnapshot(creatorId)
            }

            override suspend fun setCreatorDisplayName(request: SetCreatorDisplayNameRequest): CreatorIdentitySnapshot {
                requests += request
                if (requests.size == 1) throw StaleCreatorIdentityException()
                error("B write failed")
            }
        }
        val editor = CreatorIdentityEditor(target.id, ManageCreatorIdentity(delayed), this)
        try {
            kotlinx.coroutines.withTimeout(5000) { editor.state.first { it.identity != null } }
            editor.chooseDisplayName("A")
            editor.saveDisplayName().join()
            editor.state.value.stale shouldBe true
            val refresh = editor.refreshDisplayName()
            entered.await()
            editor.cancelDisplayName()
            editor.chooseDisplayName("B")
            editor.saveDisplayName().join()
            val before = editor.state.value
            val requestBefore = requests.last()
            if (outcome == "became-main") {
                val current = repository.getIdentitySnapshot(target.id)
                repository.setCreatorDisplayName(
                    SetCreatorDisplayNameRequest(target.id, current.revision, "A", "external"),
                )
            } else {
                repository.addManualCreatorAlias(target.id, "Another")
            }
            release.complete(Unit)
            refresh.join()
            editor.state.value.pendingName shouldBe "B"
            editor.state.value.error shouldBe before.error
            editor.state.value.focusSequence shouldBe before.focusSequence
            editor.saveDisplayName().join()
            requests.last() shouldBe requestBefore
        } finally {
            release.complete(Unit)
            editor.close()
        }
    }

    @Test
    fun `current name refresh renews stale snapshot and saves the chosen alias`() = runBlocking<Unit> {
        val target = repository.upsertCreator("Main")
        repository.addManualCreatorAlias(target.id, "Alias")
        val editor = CreatorIdentityEditor(target.id, ManageCreatorIdentity(repository), this)
        try {
            kotlinx.coroutines.withTimeout(5000) { editor.state.first { it.identity != null } }
            editor.chooseDisplayName("Alias")
            repository.addManualCreatorAlias(target.id, "Another")
            editor.saveDisplayName().join()
            editor.state.value.stale shouldBe true
            editor.refreshDisplayName().join()
            editor.state.value.pendingName shouldBe "Alias"
            editor.state.value.stale shouldBe false
            editor.saveDisplayName().join()
            editor.state.value.pendingName shouldBe null
            repository.getIdentitySnapshot(target.id).displayName shouldBe "Alias"
        } finally {
            editor.close()
        }
    }

    @Test
    fun `work filters preserve separate same title groups and full archive`() = runBlocking<Unit> {
        val creator = repository.upsertCreator("Author")
        repository.upsertSourceWork(42, "/version", null, "Book", "Author", null, null, null)
        repository.upsertSourceWorkCreator(
            SourceWorkNaturalKey(42, "/version"), creator.id, CreatorRole.AUTHOR, 0,
            CreatorRelationOrigin.AUTOMATIC, CreatorRelationVerification.VERIFIED, "Author", 1.0, "fixture",
        )
        val version = repository.getCreatorWorkArchive(creator.id).pending.single()
        val archive = CreatorWorkArchive(
            listOf(
                CanonicalWorkArchiveGroup(1, "one", "Book", listOf(version)),
                CanonicalWorkArchiveGroup(
                    2,
                    "two",
                    "Book",
                    listOf(version.copy(sourceWorkId = 2, naturalKey = SourceWorkNaturalKey(43, "/other"))),
                ),
            ),
            emptyList(),
            emptyList(),
        )
        CreatorWorkArchiveFilter(query = "missing").apply(archive).works.size shouldBe 0
        CreatorWorkArchiveFilter(query = "book").apply(archive).works.map { it.workId } shouldBe listOf(1L, 2L)
        CreatorWorkArchiveFilter(sourceId = 43).apply(archive).works.map { it.workId } shouldBe listOf(2L)
        archive.works.size shouldBe 2
    }

    @Test
    fun `identity observer retry clears error and continues following changes`() = runBlocking<Unit> {
        val target = repository.upsertCreator("Before")
        repository.addManualCreatorAlias(target.id, "After")
        var first = true
        val resumed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val flaky = object : tachiyomi.domain.creator.repository.CreatorArchiveRepository by repository {
            override fun observeIdentitySnapshot(creatorId: Long) = kotlinx.coroutines.flow.flow {
                if (first) {
                    first = false
                    error("load failed")
                }
                repository.observeIdentitySnapshot(creatorId).collect {
                    emit(it)
                    resumed.complete(Unit)
                }
            }
        }
        val editor = CreatorIdentityEditor(target.id, ManageCreatorIdentity(flaky), this)
        try {
            kotlinx.coroutines.withTimeout(5000) { editor.state.first { it.error != null } }
            editor.retryIdentity()
            resumed.await()
            editor.state.value.error shouldBe null
            val current = repository.getIdentitySnapshot(target.id)
            repository.setCreatorDisplayName(
                SetCreatorDisplayNameRequest(target.id, current.revision, "After", "observe"),
            )
            kotlinx.coroutines.withTimeout(5000) { editor.state.first { it.identity?.displayName == "After" } }
        } finally {
            editor.close()
        }
    }

    @Test
    fun `submitting editor rejects double click and cancellation until transaction result`() = runBlocking<Unit> {
        val target = repository.upsertCreator("Main")
        val source = repository.upsertCreator("Other")
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var writes = 0
        val delayed = object : tachiyomi.domain.creator.repository.CreatorArchiveRepository by repository {
            override suspend fun addCreatorAliases(
                request: AddCreatorAliasesRequest,
            ): CreatorIdentitySnapshot {
                writes++
                entered.complete(Unit)
                release.await()
                return repository.addCreatorAliases(request)
            }
        }
        val editor = CreatorIdentityEditor(target.id, ManageCreatorIdentity(delayed), this)
        try {
            editor.openAliases().join()
            editor.select(source.id)
            val pending = editor.submit()
            entered.await()
            editor.dismiss()
            editor.state.value.open shouldBe true
            editor.submit().join()
            writes shouldBe 1
            release.complete(Unit)
            pending.join()
            editor.state.value.open shouldBe false
        } finally {
            release.complete(Unit)
            editor.close()
        }
    }

    @Test
    fun `version projection keeps cover and opens existing manga without source availability`() = runBlocking<Unit> {
        val creator = repository.upsertCreator("Artist")
        val key = SourceWorkNaturalKey(42, "/work")
        repository.upsertSourceWork(42, "/work", null, "Book", "Artist", null, "https://cover.invalid/a.jpg", null)
        repository.upsertSourceWorkCreator(
            key, creator.id, CreatorRole.AUTHOR, 0, CreatorRelationOrigin.AUTOMATIC,
            CreatorRelationVerification.VERIFIED, "Artist", 1.0, "test",
        )
        val version = repository.getCreatorWorkArchive(creator.id).pending.single()
        version.thumbnailUrl shouldBe "https://cover.invalid/a.jpg"
        var saves = 0
        val opener = OpenCreatorWorkVersion {
            saves++
            99
        }
        opener.await(version.copy(mangaId = 12)) shouldBe 12L
        saves shouldBe 0
        opener.await(version) shouldBe 99L
        saves shouldBe 1
    }

    @Test
    fun `version 26 upgrade preserves authors and command receipt survives file reopen`() = runBlocking<Unit> {
        val file = java.nio.file.Files.createTempFile("identity-editor", ".db")
        fun open(): Pair<JdbcSqliteDriver, JvmDatabaseHandler> {
            val disk = JdbcSqliteDriver("jdbc:sqlite:$file")
            disk.execute(null, "PRAGMA foreign_keys=ON", 0)
            return disk to JvmDatabaseHandler(
                Database(
                    disk,
                    historyAdapter = History.Adapter(DateColumnAdapter),
                    mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
                ),
                disk,
            )
        }
        var (disk, diskHandler) = open()
        try {
            Database.Schema.create(disk)
            disk.execute(null, "DROP TABLE author_archive_identity_commands", 0)
            disk.execute(null, "DROP TRIGGER author_archive_watch_identity_revision", 0)
            disk.execute(null, "DROP TRIGGER author_archive_watch_insert_identity_revision", 0)
            val oldRepository = CreatorRepositoryImpl(diskHandler)
            val target = oldRepository.upsertCreator("Existing")
            val source = oldRepository.upsertCreator("Imported")
            Database.Schema.migrate(disk, 26, 27)
            val choices = oldRepository.getAliasCandidates(target.id)
            val request = AddCreatorAliasesRequest(
                target.id,
                choices.target.revision,
                choices.candidates.associate { it.id to it.revision },
                "persisted",
            )
            val expected = oldRepository.addCreatorAliases(request)
            diskHandler.close()
            val reopened = open()
            disk = reopened.first
            diskHandler = reopened.second
            val restored = CreatorRepositoryImpl(diskHandler)
            restored.addCreatorAliases(request) shouldBe expected
            restored.getIdentitySnapshot(source.id).id shouldBe target.id
            val graph = diskHandler.await {
                author_identity_editingQueries.getIdentityCommand("persisted").executeAsOne().recovery_graph
            }
            Json.parseToJsonElement(graph)
            restored.followCreator(target.id, null, null)
            restored.unfollowCreator(target.id)
            restored.getIdentitySnapshot(target.id).followed shouldBe false
        } finally {
            diskHandler.close()
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    @Test
    fun `watch insertion cancellation and reenable invalidate snapshots only when changed`() = runBlocking<Unit> {
        val creator = repository.upsertCreator("Watching")
        val initial = repository.getIdentitySnapshot(creator.id)
        repository.followCreator(creator.id, null, null)
        val followed = repository.getIdentitySnapshot(creator.id)
        (followed.revision > initial.revision) shouldBe true
        repository.followCreator(creator.id, null, null)
        repository.getIdentitySnapshot(creator.id).revision shouldBe followed.revision
        repository.unfollowCreator(creator.id)
        val cancelled = repository.getIdentitySnapshot(creator.id)
        (cancelled.revision > followed.revision) shouldBe true
        repository.unfollowCreator(creator.id)
        repository.getIdentitySnapshot(creator.id).revision shouldBe cancelled.revision
        repository.followCreator(creator.id, null, null)
        (repository.getIdentitySnapshot(creator.id).revision > cancelled.revision) shouldBe true
    }

    @Test
    fun `late candidate load cannot replace a reopened editor session`() = runBlocking<Unit> {
        val target = repository.upsertCreator("Target")
        val a = repository.upsertCreator("A")
        val old = repository.getAliasCandidates(target.id)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val delayed = object : tachiyomi.domain.creator.repository.CreatorArchiveRepository by repository {
            override suspend fun getAliasCandidates(creatorId: Long): CreatorAliasCandidates {
                if (calls++ == 0) {
                    entered.complete(Unit)
                    release.await()
                    return old
                }
                return repository.getAliasCandidates(creatorId)
            }
        }
        val editor = CreatorIdentityEditor(
            target.id,
            ManageCreatorIdentity(delayed),
            this,
        )
        try {
            val first = editor.openAliases()
            entered.await()
            editor.dismiss()
            val added = repository.upsertCreator("Later")
            editor.openAliases().join()
            editor.select(added.id)
            release.complete(Unit)
            first.join()
            editor.state.value.candidates!!.candidates.map { it.id }.toSet() shouldBe setOf(a.id, added.id)
            editor.state.value.selected shouldBe setOf(added.id)
        } finally {
            release.complete(Unit)
            editor.close()
        }
    }

    @Test
    fun `editor search keeps selection and stale refresh removes only absorbed candidates`() = runBlocking<Unit> {
        val target = repository.upsertCreator("Target")
        val a = repository.upsertCreator("A")
        val b = repository.upsertCreator("B")
        val editor = CreatorIdentityEditor(
            target.id,
            ManageCreatorIdentity(repository),
            this,
        )
        try {
            editor.openAliases().join()
            editor.select(a.id)
            editor.select(b.id)
            editor.search("A")
            editor.state.value.selected shouldBe setOf(a.id, b.id)
            editor.state.value.visibleCandidates.map { it.id } shouldBe listOf(a.id)
            repository.mergeCreatorIdentities(a.id, target.id)
            editor.submit().join()
            editor.state.value.open shouldBe true
            editor.state.value.stale shouldBe true
            editor.refreshCandidates().join()
            editor.state.value.selected shouldBe setOf(b.id)
            editor.submit().join()
            editor.state.value.open shouldBe false
            editor.state.value.identity!!.names.toSet() shouldBe setOf("Target", "A", "B")
        } finally {
            editor.close()
        }
    }

    @Test
    fun `editor cancel preserves database and display name confirmation owns a snapshot`() = runBlocking<Unit> {
        val creator = repository.upsertCreator("Main")
        repository.addManualCreatorAlias(creator.id, "Alias")
        val editor = CreatorIdentityEditor(
            creator.id,
            ManageCreatorIdentity(repository),
            this,
        )
        try {
            editor.openAliases().join()
            editor.dismiss()
            editor.state.value.open shouldBe false
            editor.chooseDisplayName("Alias")
            editor.state.value.pendingName shouldBe "Alias"
            editor.cancelDisplayName()
            repository.getIdentitySnapshot(creator.id).displayName shouldBe "Main"
            editor.chooseDisplayName("Alias")
            editor.saveDisplayName().join()
            editor.state.value.identity!!.displayName shouldBe "Alias"
            editor.state.value.focusTarget shouldBe "title"
        } finally {
            editor.close()
        }
    }

    @Test
    fun `add aliases atomically preserves target and all names with watch union and replay`() = runBlocking<Unit> {
        val target = repository.upsertCreator("Primary")
        val a = repository.upsertCreator("Alias A")
        val b = repository.upsertCreator("Alias B")
        repository.addManualCreatorAlias(a.id, "Variant")
        repository.followCreator(b.id, listOf(11), listOf("ja"))
        val choices = repository.getAliasCandidates(target.id)
        choices.candidates.map { it.id }.toSet() shouldBe setOf(a.id, b.id)
        val request = AddCreatorAliasesRequest(
            target.id,
            choices.target.revision,
            choices.candidates.associate { it.id to it.revision },
            "add-1",
        )
        val result = repository.addCreatorAliases(request)
        result.id shouldBe target.id
        result.displayName shouldBe "Primary"
        result.names.toSet() shouldBe setOf("Primary", "Alias A", "Alias B", "Variant")
        result.followed shouldBe true
        repository.addCreatorAliases(request) shouldBe result
        repository.getIdentitySnapshot(a.id).id shouldBe target.id
        shouldThrow<CreatorIdentityRequestConflict> {
            repository.addCreatorAliases(
                request.copy(
                    selectedRevisions = mapOf(
                        a.id to choices.candidates.first {
                            it.id == a.id
                        }.revision,
                    ),
                ),
            )
        }
    }

    @Test
    fun `one stale selection refuses whole group and permits refreshed retry`() = runBlocking<Unit> {
        val target = repository.upsertCreator("Target")
        val a = repository.upsertCreator("A")
        val b = repository.upsertCreator("B")
        val choices = repository.getAliasCandidates(target.id)
        repository.addManualCreatorAlias(b.id, "New B")
        val request = AddCreatorAliasesRequest(
            target.id,
            choices.target.revision,
            choices.candidates.associate { it.id to it.revision },
            "stale",
        )
        shouldThrow<StaleCreatorIdentityException> { repository.addCreatorAliases(request) }
        repository.getCreator(a.id)!!.id shouldBe a.id
        repository.getCreator(b.id)!!.id shouldBe b.id
        val refreshed = repository.getAliasCandidates(target.id)
        repository.addCreatorAliases(
            request.copy(
                selectedRevisions = refreshed.candidates.associate {
                    it.id to
                        it.revision
                },
            ),
        )
            .names.toSet() shouldBe setOf("Target", "A", "B", "New B")
    }

    @Test
    fun `display name only selects owned alias and leaves old name and identity intact`() = runBlocking<Unit> {
        val creator = repository.upsertCreator("Primary")
        repository.addManualCreatorAlias(creator.id, "Alias")
        val before = repository.getIdentitySnapshot(creator.id)
        shouldThrow<IllegalArgumentException> {
            repository.setCreatorDisplayName(
                SetCreatorDisplayNameRequest(creator.id, before.revision, "Unowned", "bad"),
            )
        }
        val request = SetCreatorDisplayNameRequest(creator.id, before.revision, "Alias", "rename")
        val result = repository.setCreatorDisplayName(request)
        result.displayName shouldBe "Alias"
        result.aliases shouldBe listOf("Primary")
        result.id shouldBe before.id
        repository.setCreatorDisplayName(request) shouldBe result
        shouldThrow<StaleCreatorIdentityException> {
            repository.setCreatorDisplayName(request.copy(name = "Primary", idempotencyKey = "stale-rename"))
        }
    }

    @Test
    fun `failed group merge rolls back receipt graph and every selected root`() = runBlocking<Unit> {
        val target = repository.upsertCreator("Target")
        val source = repository.upsertCreator("Source")
        val choices = repository.getAliasCandidates(target.id)
        val request = AddCreatorAliasesRequest(
            target.id,
            choices.target.revision,
            choices.candidates.associate { it.id to it.revision },
            "retry",
        )
        val failing = CreatorRepositoryImpl(handler, identityMutationHook = { error("injected") })
        shouldThrow<IllegalStateException> { failing.addCreatorAliases(request) }
        repository.getIdentitySnapshot(source.id).id shouldBe source.id
        repository.addCreatorAliases(request).id shouldBe target.id
    }
}
