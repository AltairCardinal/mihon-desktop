package mihon.data.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.data.sync.auth.GitHubSyncSpaceClient
import mihon.data.sync.crypto.SyncSpaceCrypto
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncSetupStep
import mihon.data.sync.transport.GitHubSyncTransport
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.crypto.SyncSpaceMaterial
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncRepository
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import java.util.concurrent.atomic.AtomicInteger

/** Multiple real Git object fixtures share the application's original GitHub API origin and credential path. */
internal class SyncSpaceSwitchFixture(val storage: SyncRuntimeStorageContract.Storage) : AutoCloseable {
    val old = SyncOnboardingFixture(storage)
    val targets = linkedMapOf<Long, Target>()
    val unavailableTargets = mutableSetOf<Long>()
    var oldUnavailable = false
    var renamedOld: SyncRepository? = null
    val writes = AtomicInteger()
    val requests = AtomicInteger()
    val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val delegate = old.git.server.dispatcher

    data class Target(
        val id: Long,
        val repository: SyncRepository,
        val git: SyncGitSafetyContractTest.GitFixture,
        var material: SyncSpaceMaterial? = null,
    )

    init {
        old.git.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.incrementAndGet()
                val path = request.url.encodedPath
                paths += path
                if (request.method != "GET" && path.startsWith("/repos/")) writes.incrementAndGet()
                if (path == "/user/installations/1/repositories") {
                    val repositories = buildList {
                        val renamed = renamedOld
                        if (renamed != null) {
                            add(repositoryJson(99, renamed, old.git))
                        } else if (!oldUnavailable) {
                            add(repositoryJson(99, old.repository, old.git))
                        }
                        targets.values.filter { it.id !in unavailableTargets }.forEach {
                            add(repositoryJson(it.id, it.repository, it.git))
                        }
                    }.joinToString(",")
                    return MockResponse(code = 200, body = "{\"repositories\":[$repositories]}")
                }
                renamedOld?.let { repository ->
                    if (path == "/repos/${repository.fullName}") {
                        return MockResponse(code = 200, body = repositoryJson(99, repository, old.git))
                    }
                    if (path.startsWith("/repos/${repository.fullName}/")) return delegate.dispatch(request)
                }
                val target = targets.values.firstOrNull {
                    path == "/repos/${it.repository.fullName}" || path.startsWith("/repos/${it.repository.fullName}/")
                }
                if (target != null) {
                    if (target.id in unavailableTargets) return MockResponse(code = 404, body = "{}")
                    return if (path == "/repos/${target.repository.fullName}") {
                        MockResponse(code = 200, body = repositoryJson(target.id, target.repository, target.git))
                    } else {
                        target.git.server.dispatcher.dispatch(request)
                    }
                }
                if (oldUnavailable && path == "/repos/${old.repository.fullName}") {
                    return MockResponse(code = 404, body = "{}")
                }
                return delegate.dispatch(request)
            }
        }
    }

    suspend fun prepareOld() {
        storage.favorite("/old-preserved-local")
        old.runtime.preferences.importPaused.set(true)
        old.existing("")
        old.authorize()
        try {
            old.begin()
        } catch (error: Exception) {
            throw IllegalStateException(
                "old setup: step=${old.panel.state.value.setupStep} " +
                    "busy=${old.panel.state.value.setupBusy} " +
                    "problem=${old.panel.state.value.setupProblem} run=${old.panel.state.value.run?.state}",
                error,
            )
        }
        withTimeout(5_000) { old.panel.state.first { !it.setupBusy && it.setupStep == SyncSetupStep.MERGING } }
        oldUnavailable = true
        old.runtime.recheckSpace()
        old.panel.act(SyncPanelAction.Open)
    }

    suspend fun target(id: Long, name: String, spaceId: String?, password: String = ""): Target {
        val repository = SyncRepository("fixture-owner", name, GitHubSyncSpaceClient.BRANCH)
        val git = SyncGitSafetyContractTest().GitFixture(empty = true, repositoryOverride = repository)
        val target = Target(id, repository, git)
        targets[id] = target
        if (spaceId != null) {
            val material = SyncSpaceCrypto.create(spaceId, 1, password)
            val result = GitHubSyncTransport(
                old.client,
                { "synthetic-token" },
                old.git.baseUrl,
                spaceMaterial = material,
            ).initialize(repository, spaceId, 1)
            check(result is SyncInitializationResult.Initialized)
            target.material = material
        }
        return target
    }

    suspend fun pending(count: Int) {
        val inbox = SyncInboxStore(storage.handler)
        repeat(count) { number ->
            val key = SyncObjectKey(
                SyncObjectType.MANGA,
                sourceId = "1",
                originalUrl = "/remote-$number",
            )
            val add = SyncEventEnvelope(
                1, "space", 1, "remote-$number", 1, 1,
                SyncCategory.FAVORITE,
                listOf(
                    SyncEffect(
                        "membership",
                        key,
                        SyncField.FAVORITE,
                        SyncEffectKind.ADD,
                    ),
                ),
                SyncOrigin.USER, batchId = "add-$number",
            )
            check(
                inbox.ingest(
                    SyncBatch(
                        1,
                        "space",
                        1,
                        "add-$number",
                        listOf(add),
                        listOf(SyncObjectDescriptor(key, "Pending $number")),
                    ),
                ).accepted,
            )
            while (storage.projector.project("space", 1) == 50) Unit
            val remove = add.copy(
                seq = 2,
                batchId = "remove-$number",
                effects = listOf(
                    add.effects.single().copy(kind = SyncEffectKind.REMOVE, parents = listOf(add.ref("membership"))),
                ),
            )
            check(inbox.ingest(SyncBatch(1, "space", 1, "remove-$number", listOf(remove))).accepted)
            while (storage.projector.project("space", 1) == 50) Unit
        }
    }

    private fun repositoryJson(
        id: Long,
        repository: SyncRepository,
        git: SyncGitSafetyContractTest.GitFixture,
    ): String =
        buildJsonObject {
            put("id", id)
            put("name", repository.name)
            put("full_name", repository.fullName)
            put("private", true)
            put("archived", false)
            put("disabled", false)
            put("size", if (git.hasBranch("main")) 1 else 0)
            put("default_branch", "main")
            put("description", "")
            put("permissions", buildJsonObject { put("push", true) })
            put(
                "owner",
                buildJsonObject {
                    put("id", 1)
                    put("login", "fixture-owner")
                    put("type", "User")
                },
            )
        }.toString()

    override fun close() {
        targets.values.forEach { it.git.close() }
        old.close()
    }
}
