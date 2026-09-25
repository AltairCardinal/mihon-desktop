package mihon.data.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mihon.data.sync.auth.GitHubSyncSpaceClient
import mihon.data.sync.crypto.SyncSpaceCrypto
import mihon.data.sync.http.NoopSyncMetrics
import mihon.data.sync.http.SyncMetrics
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelController
import mihon.data.sync.runtime.SyncRuntime
import mihon.data.sync.runtime.SyncSetupStep
import mihon.data.sync.transport.GitHubSyncTransport
import mihon.domain.sync.auth.GitHubAccessToken
import mihon.domain.sync.auth.GitHubAuthEndpoints
import mihon.domain.sync.crypto.SyncSpaceMaterial
import mihon.domain.sync.security.SyncSecureStore
import mihon.domain.sync.transport.SyncRepository
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Path
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.util.concurrent.ConcurrentHashMap

/** Real Git object server plus the GitHub identity/install/create HTTP contract, without copying sync logic. */
internal class SyncOnboardingFixture(
    val storage: SyncRuntimeStorageContract.Storage,
    preferenceStore: PreferenceStore? = null,
    val client: OkHttpClient = OkHttpClient(),
    tokenUrl: String? = null,
) : AutoCloseable {
    val repository = SyncRepository("fixture-owner", "mihon-sync", GitHubSyncSpaceClient.BRANCH)
    val git = SyncGitSafetyContractTest().GitFixture(empty = true, repositoryOverride = repository)
    val secure = MemorySyncSecureStore()
    private val defaults = InMemoryPreferenceStore()
    private val strings = ConcurrentHashMap<String, Preference<String>>()
    private val booleans = ConcurrentHashMap<String, Preference<Boolean>>()
    private val longs = ConcurrentHashMap<String, Preference<Long>>()
    private val ints = ConcurrentHashMap<String, Preference<Int>>()
    val preferences = preferenceStore ?: object : PreferenceStore by defaults {
        override fun getBoolean(key: String, defaultValue: Boolean) =
            booleans.computeIfAbsent(key) { defaults.getBoolean(key, defaultValue) }
        override fun getLong(key: String, defaultValue: Long) =
            longs.computeIfAbsent(key) { defaults.getLong(key, defaultValue) }
        override fun getInt(key: String, defaultValue: Int) =
            ints.computeIfAbsent(key) { defaults.getInt(key, defaultValue) }
        override fun getString(key: String, defaultValue: String) =
            strings.computeIfAbsent(key) { defaults.getString(key, defaultValue) }
    }
    var accountId = 1L
    var accountLogin = "fixture-owner"
    var created = false
    var repositoryPrivate = true
    var repositoryWrites = 0
    var now = 1_000L
    var description = ""
    var creationPosts = 0
    var rejectCreation = false
    var loseCreationResponse = false
    var creationEntered: java.util.concurrent.CountDownLatch? = null
    var creationRelease: java.util.concurrent.CountDownLatch? = null
    val repositoryTokens = java.util.Collections.synchronizedList(mutableListOf<String>())
    val endpoints = GitHubAuthEndpoints(
        git.server.url("/device").toString(),
        tokenUrl ?: git.server.url("/token").toString(),
        git.baseUrl,
    )
    val runtime = runtime()
    val panel = runtime.panel as SyncPanelController

    init {
        val delegate = git.server.dispatcher
        git.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                if (path.startsWith("/repos/")) {
                    repositoryTokens += request.headers["Authorization"].orEmpty()
                    if (request.method != "GET") repositoryWrites++
                }
                return when {
                    path == "/user" -> response("""{"id":$accountId,"login":"$accountLogin","type":"User"}""")
                    path == "/user/installations" -> response(
                        """
                        {"installations":[{"id":1,"app_slug":"mihon-desktop",
                        "account":{"id":$accountId,"type":"User"},"suspended_at":null,
                        "permissions":{"administration":"write","contents":"write"},"repository_selection":"selected"}]}
                        """.trimIndent(),
                    )
                    path == "/user/installations/1/repositories" -> response(
                        """{"repositories":[${if (created) repositoryJson() else ""}]}""",
                    )
                    path == "/user/repos" && request.method == "POST" -> {
                        creationPosts++
                        creationEntered?.countDown()
                        check(creationRelease?.await(5, java.util.concurrent.TimeUnit.SECONDS) != false)
                        if (rejectCreation) return MockResponse(code = 500, body = "{}")
                        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                        check(body.getValue("name").jsonPrimitive.content == "mihon-sync")
                        check(body.getValue("private").jsonPrimitive.content == "true")
                        description = body.getValue("description").jsonPrimitive.content
                        created = true
                        if (loseCreationResponse) return MockResponse(code = 500, body = "{}")
                        MockResponse(code = 201, body = repositoryJson())
                    }
                    path == "/repos/${repository.fullName}" ->
                        if (created) response(repositoryJson()) else MockResponse(code = 404, body = "{}")
                    path == "/device" -> response(
                        """
                        {"device_code":"synthetic-code","user_code":"SYNTHETIC",
                         "verification_uri":"https://github.com/login/device","expires_in":600,"interval":1}
                        """.trimIndent(),
                    )
                    path == "/token" -> response(
                        """{"access_token":"synthetic-token","token_type":"bearer","scope":""}""",
                    )
                    else -> delegate.dispatch(request)
                }
            }
        }
    }

    fun runtime(
        metrics: SyncMetrics = NoopSyncMetrics,
        persistentObjectCacheDirectory: Path? = null,
        progressTelemetryEnabled: Boolean = true,
    ): SyncRuntime = SyncRuntime(
        storage.handler, storage.bootstrap, storage.creators, storage.creators, { true }, secure,
        preferences, client, endpoints, clock = { now },
        persistentObjectCacheDirectory = persistentObjectCacheDirectory,
        syncMetrics = metrics,
        progressTelemetryEnabled = progressTelemetryEnabled,
    )

    suspend fun authorize(token: String = "synthetic-token") {
        runtime.credentials.replace(
            runtime.credentials.read()?.revision,
            GitHubAccessToken(token, null, "bearer", emptySet(), null, null),
        )
    }

    suspend fun existing(password: String): SyncSpaceMaterial {
        created = true
        val material = SyncSpaceCrypto.create("space", 1, password)
        GitHubSyncTransport(client, { "synthetic-token" }, git.baseUrl, spaceMaterial = material)
            .initialize(repository, "space", 1)
        return material
    }

    suspend fun begin() {
        panel.act(SyncPanelAction.Open)
        panel.act(SyncPanelAction.BeginSetup)
        withTimeout(5_000) { panel.state.first { !it.setupBusy && it.setupStep != SyncSetupStep.SIGN_IN } }
    }

    private fun repositoryJson(): String = buildJsonObject {
        put("id", 99)
        put("name", repository.name)
        put("full_name", repository.fullName)
        put("private", repositoryPrivate)
        put("size", if (runCatching { git.head("main") }.isSuccess) 1 else 0)
        put("default_branch", "main")
        put("description", description)
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

    private fun response(body: String) = MockResponse(body = body)

    override fun close() {
        runBlocking { runtime.stopPanel() }
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        git.close()
    }
}

internal suspend fun SyncPanelController.act(action: SyncPanelAction) {
    dispatch(action)
    awaitIdle()
}

internal class MemorySyncSecureStore : SyncSecureStore {
    val values = ConcurrentHashMap<String, String>()
    var fail = false
    var rejectConnectedSetup = false
    override suspend fun read(key: String): String? {
        if (fail) throw mihon.domain.sync.security.SyncSecureStoreException()
        return values[key]
    }
    override suspend fun compareAndSet(key: String, expected: String?, value: String?): Boolean = synchronized(values) {
        if (fail || (
                rejectConnectedSetup && key.startsWith("sync-setup-v2-") && value != null &&
                    Json.parseToJsonElement(value).jsonObject["connected"]?.jsonPrimitive?.content == "true"
                )
        ) {
            throw mihon.domain.sync.security.SyncSecureStoreException()
        }
        if (values[key] != expected) return@synchronized false
        if (value == null) values.remove(key) else values[key] = value
        true
    }
}
