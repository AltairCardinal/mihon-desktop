package mihon.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.domain.sync.transport.SyncRepository
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/** Real Git object storage with the GitHub repository management API around the same production origin. */
internal class SyncNativeRepositoryFixture(
    storage: SyncRuntimeStorageContract.Storage,
    name: String = "mihon-sync-recovered",
) : AutoCloseable {
    val app = SyncOnboardingFixture(storage)
    val repository = SyncRepository(app.repository.owner, name, app.repository.branch)
    var available = false
    var granted = true
    var archived = false
    var privateRepository = true
    var posts = 0
    var patches = 0
    var puts = 0
    var denyCreation = false
    private var description = ""

    init {
        app.created = false
        if (repository != app.repository) app.git.renameRepository(repository)
        val delegate = app.git.server.dispatcher
        app.git.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path == "/user/installations/1/repositories" -> MockResponse(
                        body = """{"repositories":[${if (available && granted) metadata() else ""}]}""",
                    )
                    path == "/user/repos" && request.method == "POST" -> {
                        if (denyCreation) {
                            posts++
                            return MockResponse(code = 403, body = "{}")
                        }
                        val body = Json.parseToJsonElement(requireNotNull(request.body).utf8()).jsonObject
                        check(body["name"]?.jsonPrimitive?.content == repository.name)
                        check(body["private"]?.jsonPrimitive?.content == "true")
                        check(body["auto_init"]?.jsonPrimitive?.content == "false")
                        description = body["description"]!!.jsonPrimitive.content
                        available = true
                        posts++
                        MockResponse(code = 201, body = metadata())
                    }
                    path == "/user/installations/1/repositories/99" && request.method == "PUT" -> {
                        puts++
                        if (granted) MockResponse(code = 204) else MockResponse(code = 403, body = "{}")
                    }
                    path == "/repos/${repository.fullName}" && request.method == "PATCH" -> {
                        val body = Json.parseToJsonElement(requireNotNull(request.body).utf8()).jsonObject
                        if (body["private"]?.jsonPrimitive?.content == "true") privateRepository = true
                        if (body["archived"]?.jsonPrimitive?.content == "false") archived = false
                        patches++
                        MockResponse(body = metadata())
                    }
                    path == "/repos/${repository.fullName}" || path == "/repositories/99" ->
                        if (available &&
                            granted
                        ) {
                            MockResponse(body = metadata())
                        } else {
                            MockResponse(code = 404, body = "{}")
                        }
                    else -> delegate.dispatch(request)
                }
            }
        }
    }

    private fun metadata() = """{"id":99,"name":"${repository.name}","full_name":"${repository.fullName}","owner":{"id":1,"login":"fixture-owner","type":"User"},"private":$privateRepository,"permissions":{"push":true,"admin":true},"description":${kotlinx.serialization.json.JsonPrimitive(
        description,
    )},"size":${if (app.git.hasBranch(
            "main",
        )
    ) {
        1
    } else {
        0
    }},"default_branch":"main","archived":$archived,"disabled":false}"""

    override fun close() = app.close()
}
