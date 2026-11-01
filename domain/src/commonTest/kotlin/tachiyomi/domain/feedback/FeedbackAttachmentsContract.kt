package tachiyomi.domain.feedback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

abstract class FeedbackAttachmentsContract {
    @Test
    fun `new drafts include public device information and optional private log by default`() {
        val draft = NativeFeedbackDraft()
        assertTrue(draft.includeSummary)
        assertTrue(draft.logConsent)
        assertFalse(draft.publicConsent)
    }

    @Test
    fun `all twelve categories use stable IDs and reader alone adds position prompts`() {
        assertEquals(12, FeedbackCategory.entries.size)
        assertEquals(12, FeedbackCategory.entries.map { it.id }.toSet().size)
        for (language in FeedbackLanguage.entries) {
            for (category in FeedbackCategory.entries) {
                val template = category.template(language)
                assertTrue(template.isNotBlank())
                val marker = if (language == FeedbackLanguage.CHINESE) "【问题发生位置】" else "[Where it happened]"
                assertEquals(category == FeedbackCategory.READER, marker in template)
            }
        }
        assertTrue("【希望解决的困难】" in FeedbackCategory.FEATURE_REQUEST.template(FeedbackLanguage.CHINESE))
    }

    @Test
    fun `category switch protects edited description and requires explicit replacement`() = runTest {
        val service = object : NativeFeedbackService {
            override suspend fun createSession(contentHash: String): NativeFeedbackSession = error("No submission")
            override suspend fun pollSession(
                session: NativeFeedbackSession,
            ): NativeFeedbackVerification = error("No poll")
            override suspend fun submit(
                payload: NativeFeedbackPayload,
                session: NativeFeedbackSession,
            ): String = error("No send")
        }
        val c = NativeFeedbackController(service, FeedbackLogReader { "" }, "", this, {})
        c.category(FeedbackCategory.READER)
        assertEquals(FeedbackCategory.READER, c.state.value.draft.category)
        c.description("A fictional user draft that must survive category changes.")
        c.category(FeedbackCategory.FEATURE_REQUEST)
        assertNotNull(c.state.value.pendingCategory)
        assertEquals(FeedbackCategory.READER, c.state.value.draft.category)
        c.confirmCategory(false)
        assertNull(c.state.value.pendingCategory)
        assertTrue("fictional user" in c.state.value.draft.description)
        c.category(FeedbackCategory.FEATURE_REQUEST)
        c.confirmCategory(true)
        assertEquals(FeedbackCategory.FEATURE_REQUEST, c.state.value.draft.category)
        assertEquals(
            FeedbackCategory.FEATURE_REQUEST.template(FeedbackLanguage.ENGLISH),
            c.state.value.draft.description,
        )
    }

    @Test
    fun `processing publishes no final preview or 100 percent until the whole queue finishes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val processor = FeedbackScreenshotProcessor { _, progress ->
            progress(FeedbackImageStage.READ)
            progress(FeedbackImageStage.COMPLETE)
            gate.await()
            FeedbackScreenshot(1, 1, byteArrayOf(-1, -40, -1, -39))
        }
        val service = object : NativeFeedbackService {
            override suspend fun createSession(contentHash: String): NativeFeedbackSession = error("No session")
            override suspend fun pollSession(
                session: NativeFeedbackSession,
            ): NativeFeedbackVerification = error("No poll")
            override suspend fun submit(
                payload: NativeFeedbackPayload,
                session: NativeFeedbackSession,
            ): String = error("No send")
        }
        val c =
            NativeFeedbackController(service, FeedbackLogReader { "" }, "", this, {}, screenshotProcessor = processor)
        c.addScreenshots(listOf(FeedbackScreenshotInput(4) { error("handled by codec") }))
        runCurrent()
        assertTrue(c.state.value.busy)
        assertTrue(c.state.value.draft.screenshots.isEmpty())
        assertFalse(c.state.value.screenshotProgress?.stage == FeedbackImageStage.COMPLETE)
        c.description("Blocked change")
        assertEquals("", c.state.value.draft.description)
        c.cancel()
        gate.complete(Unit)
        runCurrent()
        assertTrue(c.state.value.draft.screenshots.isEmpty())
        assertEquals(NativeFeedbackFailure.CANCELLED, c.state.value.failure)
        assertNull(c.state.value.screenshotProgress)
        val token = c.beginScreenshotSelection()!!
        c.cancel()
        c.finishScreenshotSelection(token, listOf(FeedbackScreenshotInput(4) { error("Late picker") }))
        assertTrue(c.state.value.draft.screenshots.isEmpty())
        assertFalse(c.state.value.busy)
    }

    @Test
    fun `conversion failure preserves completed JPEG draft and retry identity`() = runTest {
        val bytes = javaClass.getResourceAsStream("/feedback/fictional-screenshot.jpg")!!.use { it.readBytes() }
        val existing = FeedbackScreenshot(16, 12, bytes)
        val original = NativeFeedbackSnapshot(
            NativeFeedbackDraft(
                description = "A fictional draft with a completed image.",
                screenshots = listOf(existing),
            ),
            "123e4567-e89b-42d3-a456-426614174000",
        )
        val controller = NativeFeedbackController(
            V2Service(),
            FeedbackLogReader { "" },
            "",
            this,
            {},
            screenshotProcessor = FeedbackScreenshotProcessor { _, progress ->
                progress(FeedbackImageStage.ENCODE)
                throw NativeFeedbackException(NativeFeedbackFailure.IMAGE_TOO_LARGE)
            },
            initialSnapshot = original,
        )
        controller.addScreenshots(listOf(FeedbackScreenshotInput(4) { error("Codec owns the input") }))
        runCurrent()
        assertEquals(NativeFeedbackFailure.IMAGE_TOO_LARGE, controller.state.value.failure)
        assertEquals(NativeFeedbackPhase.FAILED, controller.state.value.phase)
        assertEquals(original.draft, controller.state.value.draft)
        assertEquals(original.submissionId, controller.snapshot()!!.submissionId)
        assertNull(controller.state.value.screenshotProgress)
        controller.removeScreenshot(existing.id)
        assertTrue(controller.state.value.draft.screenshots.isEmpty())
        assertEquals(original.draft.description, controller.state.value.draft.description)
    }

    @Test
    fun `prompts alone cannot count as an actual bilingual problem`() {
        for (category in FeedbackCategory.entries) {
            for (language in FeedbackLanguage.entries) {
                val template = category.template(language)
                assertFalse(FeedbackCategory.hasActualDescription(template))
                assertFalse(FeedbackCategory.hasActualDescription(template.lines().first()))
                assertTrue(
                    FeedbackCategory.hasActualDescription(template + "\nFictional image stays blank after page turn."),
                )
            }
        }
    }

    @Test
    fun `JPEG metadata removal preserves encoded image and rejects malformed containers`() {
        val original = javaClass.getResourceAsStream("/feedback/fictional-screenshot.jpg")!!.use { it.readBytes() }
        val metadata = "ICC_PROFILE\u0000fictional-private-metadata".encodeToByteArray()
        val app2 = byteArrayOf(-1, -30, 0, (metadata.size + 2).toByte()) + metadata
        val source = original.copyOfRange(0, 2) + app2 + original.copyOfRange(2, original.size)
        val stripped = FeedbackJpegMetadata.strip(source)
        assertArrayEquals(FeedbackJpegMetadata.strip(original), stripped)
        assertArrayEquals(stripped, FeedbackJpegMetadata.strip(stripped))
        assertFalse(stripped.toString(Charsets.ISO_8859_1).contains("ICC_PROFILE"))
        for (bad in listOf(
            byteArrayOf(-1, -40, -1, -39),
            source + byteArrayOf(1),
            byteArrayOf(-1, -40, -1, -30, -1, -1, 1, 2),
        )) {
            assertThrows(NativeFeedbackException::class.java) { FeedbackJpegMetadata.strip(bad) }
        }
    }

    @Test
    fun `v2 Unicode and screenshot binding agrees with independently fixed Worker vector`() {
        val bytes = javaClass.getResourceAsStream("/feedback/fictional-screenshot.jpg")!!.use { it.readBytes() }
        val screenshot = FeedbackScreenshot(16, 12, bytes, "123e4567-e89b-42d3-a456-426614174111")
        assertEquals(826, screenshot.byteLength)
        assertEquals("8cd1a9db87366c05d67a4e44634845a4896482aaa7532432768cc93d8ed4b22d", screenshot.sha256)
        val payload = NativeFeedbackPayload(
            "123e4567-e89b-42d3-a456-426614174000",
            FeedbackCategory.READER.publicTitle,
            " \uFEFF报告😀问题\r\n ",
            "\r\n",
            " \r\n",
            FeedbackCategory.READER,
            listOf(screenshot),
        )
        assertEquals(
            "c8a4297b233a97ccfddac19e843ac68b217877584b524015eb0e150a35c3a07d",
            NativeFeedbackContent.hash(payload),
        )
        assertFalse(
            NativeFeedbackContent.hash(payload.copy(category = FeedbackCategory.OTHER)) ==
                NativeFeedbackContent.hash(payload),
        )
        assertFalse(
            NativeFeedbackContent.hash(payload.copy(screenshots = emptyList())) == NativeFeedbackContent.hash(payload),
        )
    }

    private class V2Service : NativeFeedbackService {
        var config = NativeFeedbackCapabilities(true, true, true)
        var capacityFailure = false
        val hashes = mutableListOf<String>()
        val payloads = mutableListOf<NativeFeedbackPayload>()
        override suspend fun capabilities() = config
        override suspend fun createSession(contentHash: String): NativeFeedbackSession {
            hashes += contentHash
            return NativeFeedbackSession(
                "123e4567-e89b-42d3-a456-426614174000",
                "s".repeat(43),
                600,
                "safe-verification",
            )
        }
        override suspend fun pollSession(session: NativeFeedbackSession) = NativeFeedbackVerification(true, 600)
        override suspend fun submit(payload: NativeFeedbackPayload, session: NativeFeedbackSession): String {
            payloads += payload
            if (capacityFailure) throw NativeFeedbackException(NativeFeedbackFailure.ATTACHMENT_CAPACITY)
            return "r".repeat(43)
        }
    }

    @Test
    fun `v2 controller respects config and only explicit public submit authorizes visible private log`() = runTest {
        val service = V2Service()
        val c = NativeFeedbackController(
            service,
            FeedbackLogReader { "" },
            "Application: Mihon",
            this,
            {},
            nowSeconds = { testScheduler.currentTime / 1000 },
        )
        c.refreshCapabilities()
        runCurrent()
        assertTrue(c.state.value.capabilities?.nativeV2 == true)
        c.category(FeedbackCategory.READER)
        c.description("A fictional page stays blank after turning to the next page.")
        c.log("Fictional private debug\n")
        c.submit()
        runCurrent()
        assertEquals(NativeFeedbackFailure.PUBLIC_CONSENT, c.state.value.failure)
        assertTrue(service.hashes.isEmpty())
        c.publicConsent(true)
        c.submit()
        runCurrent()
        c.category(FeedbackCategory.OTHER)
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(NativeFeedbackPhase.RECEIVED, c.state.value.phase)
        val payload = service.payloads.single()
        assertEquals(FeedbackCategory.READER, payload.category)
        assertEquals("Fictional private debug\n", payload.crashLog)
        assertEquals("Application: Mihon", payload.summary)
    }

    @Test
    fun `capacity failure retains attachments and retry UUID while removing included log starts new snapshot`() =
        runTest {
            val service = V2Service().apply { capacityFailure = true }
            val c = NativeFeedbackController(
                service,
                FeedbackLogReader { "" },
                "",
                this,
                {},
                nowSeconds = { testScheduler.currentTime / 1000 },
            )
            c.category(FeedbackCategory.OTHER)
            c.description("A fictional failure used only for the capacity recovery test.")
            c.publicConsent(true)
            c.log("Fictional private log\n")
            c.submit()
            runCurrent()
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(NativeFeedbackFailure.ATTACHMENT_CAPACITY, c.state.value.failure)
            val first = service.payloads.single().submissionId
            c.submit()
            runCurrent()
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(first, service.payloads.last().submissionId)
            c.logConsent(false)
            service.capacityFailure = false
            c.submit()
            runCurrent()
            advanceTimeBy(2000)
            runCurrent()
            assertEquals(NativeFeedbackPhase.RECEIVED, c.state.value.phase)
            assertFalse(first == service.payloads.last().submissionId)
            assertEquals("", service.payloads.last().crashLog)
            assertEquals("Fictional private log\n", c.state.value.draft.crashLog)
            val legacy = V2Service().apply { config = NativeFeedbackCapabilities(true, false, false) }
            val blocked = NativeFeedbackController(legacy, FeedbackLogReader { "" }, "", this, {})
            blocked.category(FeedbackCategory.OTHER)
            blocked.description("A fictional report that must not fall back to the old protocol.")
            blocked.publicConsent(true)
            blocked.submit()
            runCurrent()
            assertEquals(NativeFeedbackFailure.V2_UNAVAILABLE, blocked.state.value.failure)
            assertTrue(legacy.hashes.isEmpty())
        }

    @Test
    fun `real IO old progress callbacks after cancellation cannot revive busy state`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val published = CompletableDeferred<Unit>()
        val processor = FeedbackScreenshotProcessor { _, progress ->
            withContext(Dispatchers.IO + NonCancellable) {
                progress(FeedbackImageStage.READ)
                started.complete(Unit)
                release.await()
                repeat(2000) { progress(FeedbackImageStage.ENCODE) }
                published.complete(Unit)
                FeedbackScreenshot(1, 1, byteArrayOf(-1, -40, -1, -39))
            }
        }
        val c =
            NativeFeedbackController(
                V2Service(),
                FeedbackLogReader {
                    ""
                },
                "",
                this,
                {},
                screenshotProcessor = processor,
            )
        c.addScreenshots(listOf(FeedbackScreenshotInput(4) { error("codec handles input") }))
        runCurrent()
        started.await()
        c.cancel()
        release.complete(Unit)
        published.await()
        runCurrent()
        assertEquals(NativeFeedbackPhase.FAILED, c.state.value.phase)
        assertEquals(NativeFeedbackFailure.CANCELLED, c.state.value.failure)
        assertNull(c.state.value.screenshotProgress)
        assertTrue(c.state.value.draft.screenshots.isEmpty())
    }
}
