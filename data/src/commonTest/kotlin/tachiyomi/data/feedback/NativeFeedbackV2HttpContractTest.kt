package tachiyomi.data.feedback

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.feedback.FeedbackCategory
import tachiyomi.domain.feedback.FeedbackScreenshot
import tachiyomi.domain.feedback.NativeFeedbackPayload
import tachiyomi.domain.feedback.NativeFeedbackSession
import java.util.Base64

class NativeFeedbackV2HttpContractTest {
    @Test
    fun `actual public config enables only supported v2 limits and legacy service is closed`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            val api = NativeFeedbackHttpService(OkHttpClient(), server.url("/"))
            server.enqueue(MockResponse(body = CONFIG))
            val capabilities = api.capabilities()
            assertTrue(capabilities.accepting && capabilities.nativeV2 && capabilities.screenshots)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/config", request.url.encodedPath)
            assertEquals(null, request.headers["Authorization"])
            server.enqueue(MockResponse(body = """{"accepting":true,"siteKey":"public"}"""))
            assertFalse(api.capabilities().nativeV2)
            server.enqueue(MockResponse(body = CONFIG.replace("\"screenshots\":true", "\"screenshots\":false")))
            assertFalse(api.capabilities().screenshots)
        }
    }

    @Test
    fun `final v2 request contains derived category and immutable JPEG bytes but no source identity`() = runBlocking {
        MockWebServer().also { it.start() }.use { server ->
            val bytes = javaClass.getResourceAsStream("/feedback/fictional-screenshot.jpg")!!.use { it.readBytes() }
            val category = FeedbackCategory.READER
            val image = FeedbackScreenshot(16, 12, bytes, "123e4567-e89b-42d3-a456-426614174111")
            val payload = NativeFeedbackPayload(
                "123e4567-e89b-42d3-a456-426614174000",
                category.publicTitle,
                "A fictional reader issue with a private screenshot.",
                "",
                "Fictional private debug\n",
                category,
                listOf(image),
            )
            val session = NativeFeedbackSession("123e4567-e89b-42d3-a456-426614174000", "s".repeat(43), 600, "unused")
            server.enqueue(
                MockResponse(code = 202, body = """{"status":"received_pending","receipt":"${"r".repeat(43)}"}"""),
            )
            val api = NativeFeedbackHttpService(OkHttpClient(), server.url("/"))
            assertEquals("r".repeat(43), api.submit(payload, session))
            val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals("2", body["protocolVersion"]?.jsonPrimitive?.content)
            assertEquals("reader", body["categoryId"]?.jsonPrimitive?.content)
            assertEquals(category.publicTitle, body["title"]?.jsonPrimitive?.content)
            assertEquals("true", body["screenshotConsent"]?.jsonPrimitive?.content)
            assertEquals("true", body["crashLogConsent"]?.jsonPrimitive?.content)
            val attachment = body["screenshots"]!!.jsonArray.single().jsonObject
            assertEquals(setOf("id", "mime", "width", "height", "byteLength", "sha256", "dataBase64"), attachment.keys)
            assertTrue(
                bytes.contentEquals(Base64.getDecoder().decode(attachment["dataBase64"]!!.jsonPrimitive.content)),
            )
            assertEquals(image.sha256, attachment["sha256"]!!.jsonPrimitive.content)
            assertFalse("turnstileToken" in body)
        }
    }

    companion object {
        const val CONFIG = """{"accepting":true,"siteKey":"public","capabilities":{"nativeV2":true,"screenshots":true},
          "limits":{"maxScreenshots":2,"maxScreenshotBytes":524288,"maxTotalScreenshotBytes":1048576,
          "maxScreenshotSide":1920,"maxBodyBytes":2097152,"maxLogBytes":262144}}"""
    }
}
