package mihon.data.sync.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Parses the single-batch GraphQL shape without enabling GraphQL in the
 * default transport. A response is usable only when HTTP and GraphQL data are
 * both complete; clientMutationId is request correlation only.
 */
internal object SyncGraphQlSingleBatchAdapter {
    data class Variables(val repository: String, val expectedHead: String, val clientMutationId: String)
    data class Mutation(val variables: Variables, val body: JsonObject)

    fun mutation(repository: String, expectedHead: String, clientMutationId: String): Mutation {
        val variables = Variables(repository, expectedHead, clientMutationId)
        return Mutation(
            variables,
            buildJsonObject {
                put(
                    "query",
                    "mutation CreateSyncCommit(${'$'}repository: String!, ${'$'}expectedHead: GitObjectID!, ${'$'}clientMutationId: String!) { createCommit(input: { repositoryNameWithOwner: ${'$'}repository, expectedHeadOid: ${'$'}expectedHead, clientMutationId: ${'$'}clientMutationId }) { oid } }",
                )
                put(
                    "variables",
                    buildJsonObject {
                        put("repository", repository)
                        put("expectedHead", expectedHead)
                        put("clientMutationId", clientMutationId)
                    },
                )
            },
        )
    }

    fun parse(httpCode: Int, body: String): Result<String> = runCatching {
        require(httpCode in 200..299) { "GraphQL HTTP status $httpCode" }
        val json = Json.parseToJsonElement(body).jsonObject
        val errors = json["errors"]?.jsonArray.orEmpty()
        require(errors.isEmpty()) {
            errors.joinToString {
                it.jsonObject["message"]?.jsonPrimitive?.content
                    ?: "GraphQL error"
            }
        }
        val oid = json["data"]?.jsonObject?.get("createCommit")?.jsonObject?.get("oid")?.jsonPrimitive?.content
        require(!oid.isNullOrBlank()) { "GraphQL commit data is incomplete" }
        oid
    }
}
