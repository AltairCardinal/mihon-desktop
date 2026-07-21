package mihon.domain.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlatformParityContractTest {

    private val fixture by lazy {
        requireNotNull(
            javaClass.getResourceAsStream("/parity/task5a/fixed-main-platform-fixtures.json"),
        ).use { input ->
            Json.parseToJsonElement(input.bufferedReader().use { it.readText() }).jsonObject
        }
    }

    @Test
    fun `fixture records fixed-main provenance and backup priority`() {
        val fixedMain = fixture.getValue("fixedMain").jsonObject
        assertEquals("6fbf6dfca203d99d6dd32137f2df97ced40c81b8", fixedMain["ref"]?.jsonPrimitive?.content)

        val sources = fixedMain.getValue("sources").jsonArray
        assertEquals(6, sources.size)
        assertEquals(
            "DeepLinkScreenModel.first ResolvableSource",
            sources[1].jsonObject["symbol"]?.jsonPrimitive?.content,
        )
        assertEquals("DeepLinkScreenModel.NoResults", sources[2].jsonObject["symbol"]?.jsonPrimitive?.content)
    }

    @Test
    fun `fixture action vectors preserve fixed-main behavior and reject unsafe input`() {
        listOf("fixedMainActions", "hardeningActions").forEach { group ->
            fixture.getValue(group).jsonArray.forEach { element ->
                val vector = element.jsonArray
                val actual = ExternalActionParser.resolve(inputFor(vector))
                if (resultTag(vector) == "rejected") {
                    assertTrue(actual is ExternalAction.Rejected, vector[1].jsonPrimitive.content)
                } else {
                    assertEquals(expectedAction(vector), actual, vector[1].jsonPrimitive.content)
                }
            }
        }
    }

    @Test
    fun `share payload preserves supported schemes`() {
        assertEquals(SharePayload.Text("https://example.com"), ExternalShare.fromUri("https://example.com"))
        assertEquals(SharePayload.Text("http://example.com"), ExternalShare.fromUri("http://example.com"))
        assertEquals(
            SharePayload.Stream(uri = "content://book/1", mimeType = "image/*", message = null),
            ExternalShare.fromUri("content://book/1"),
        )
        assertEquals(null, ExternalShare.fromUri("file:///book/1"))
    }

    private fun inputFor(vector: JsonArray): ExternalActionInput = when (vector[0].jsonPrimitive.content) {
        "view" -> ExternalActionInput.ViewUri(vector[1].jsonPrimitive.content)
        "search" -> ExternalActionInput.Search(
            primaryQuery = vector[1].jsonPrimitive.contentOrNull,
            fallbackText = vector[2].jsonPrimitive.contentOrNull,
        )
        "send" -> ExternalActionInput.SendText(vector[1].jsonPrimitive.contentOrNull)
        else -> error("Unknown action vector: ${vector[0]}")
    }
    private fun expectedAction(vector: JsonArray): ExternalAction = when (resultTag(vector)) {
        "backup" -> ExternalAction.RestoreBackup(resultPayload(vector))
        "repository" -> ExternalAction.AddRepository(resultPayload(vector))
        "search" -> ExternalAction.Search(resultPayload(vector))
        "noop" -> ExternalAction.NoOp
        else -> error("Unknown expected action: ${resultTag(vector)}")
    }
    private fun resultTag(vector: JsonArray): String = vector[resultTagIndex(vector)].jsonPrimitive.content

    private fun resultPayload(vector: JsonArray): String = vector[resultTagIndex(vector) + 1].jsonPrimitive.content

    private fun resultTagIndex(vector: JsonArray): Int = if (vector[0].jsonPrimitive.content == "search") 3 else 2
}
