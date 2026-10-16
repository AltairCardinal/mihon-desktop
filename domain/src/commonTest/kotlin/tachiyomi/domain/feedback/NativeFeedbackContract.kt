package tachiyomi.domain.feedback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
abstract class NativeFeedbackContract {
    private class Service : NativeFeedbackService {
        val hashes = mutableListOf<String>()
        val payloads = mutableListOf<NativeFeedbackPayload>()
        var verified = false
        var fail = false
        var polls = 0
        override suspend fun createSession(contentHash: String): NativeFeedbackSession {
            hashes += contentHash
            return NativeFeedbackSession(
                "123e4567-e89b-42d3-a456-426614174000",
                "s".repeat(43),
                600,
                "https://mihon-feedback.mihon-altaircardinal.workers.dev/verify.html#123e4567-e89b-42d3-a456-426614174000",
            )
        }
        override suspend fun pollSession(session: NativeFeedbackSession): NativeFeedbackVerification {
            polls++
            return NativeFeedbackVerification(verified, 600)
        }
        override suspend fun submit(payload: NativeFeedbackPayload, session: NativeFeedbackSession): String {
            payloads += payload
            if (fail) throw NativeFeedbackException(NativeFeedbackFailure.NETWORK)
            return "r".repeat(43)
        }
    }
    private fun TestScope.controller(
        service: Service,
        reader: FeedbackLogReader = FeedbackLogReader { "Fictional crash\r\n" },
        opened: MutableList<String> = mutableListOf(),
    ): NativeFeedbackController =
        NativeFeedbackController(service, reader, "Application: Mihon\nVersion: 0.19.4", this, opened::add, {
            testScheduler.currentTime /
                1000
        })
    private fun fill(controller: NativeFeedbackController) {
        controller.title("  Fictional crash report  ")
        controller.description("  A fictional page failed while opening in this test.  ")
        controller.publicConsent(true)
    }

    @Test
    fun `native hash uses length-prefixed canonical UTF8 fields and distinguishes separators`() {
        // Independent published protocol vector: 3 bytes for Chinese and 4 for emoji.
        assertEquals(
            "3e3b0f36e7413c053b28fd633c3d898796f82cb5be5ff1f0c842f1527264a811",
            NativeFeedbackContent.hash(" 问😀 ", " d ", "\r\n", " \r\n"),
        )
        assertNotEquals(
            NativeFeedbackContent.hash("abcde", "f", "", ""),
            NativeFeedbackContent.hash("abcde", "", "f", ""),
        )
    }

    @Test
    fun `canonical trim agrees with ECMAScript FEFF and preserves non-trim C1 character`() {
        assertEquals("Fictional title", NativeFeedbackContent.trimText("\uFEFFFictional title\uFEFF"))
        assertEquals("\u0085value\u0085", NativeFeedbackContent.trimText("\u0085value\u0085"))
        assertEquals(
            NativeFeedbackContent.hash("Fictional title", "Fictional description", "Application: Mihon", " \n"),
            NativeFeedbackContent.hash(
                "\uFEFFFictional title\uFEFF",
                "\uFEFFFictional description\uFEFF",
                "\uFEFFApplication: Mihon\uFEFF",
                " \r\n",
            ),
        )
        for (part in 0..3) {
            val values =
                mutableListOf("Fictional title", "Fictional description", "Application: Mihon", "Fictional log")
            values[part] = "\ud800"
            assertThrows(IllegalArgumentException::class.java) {
                NativeFeedbackContent.hash(values[0], values[1], values[2], values[3])
            }
        }
    }

    @Test
    fun `controller payload and fixed Unicode binding agree after public trim`() = runTest {
        val service = Service().apply { verified = true }
        val c = NativeFeedbackController(
            service,
            FeedbackLogReader { "unused" },
            "\uFEFFApplication: Mihon\uFEFF",
            this,
            {},
            { testScheduler.currentTime / 1000 },
        )
        c.title("\uFEFFFictional title\uFEFF")
        c.description("\uFEFFA fictional description with Unicode 问😀.\uFEFF")
        c.includeSummary(true)
        c.publicConsent(true)
        c.log("\uFEFF log\r\n")
        c.logConsent(true)
        c.submit()
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        assertEquals("a255cf0da63a136126244f200c4617676808fe81ef335c7b08ffa4209df91e32", service.hashes.single())
        val payload = service.payloads.single()
        assertEquals("Fictional title", payload.title)
        assertEquals("A fictional description with Unicode 问😀.", payload.description)
        assertEquals("Application: Mihon", payload.summary)
        assertEquals("\uFEFF log\n", payload.crashLog)
    }

    @Test
    fun `public consent and separate private consent block every external action until explicit submit`() = runTest {
        val service = Service()
        val opened = mutableListOf<String>()
        var reads = 0
        val c = controller(
            service,
            FeedbackLogReader {
                reads++
                "Fictional private crash\n"
            },
            opened,
        )
        assertFalse(c.state.value.draft.includeSummary)
        assertEquals(0, reads)
        c.title("Fictional report")
        c.description("A fictional problem detailed only inside this test.")
        c.submit()
        runCurrent()
        assertEquals(NativeFeedbackFailure.PUBLIC_CONSENT, c.state.value.failure)
        c.publicConsent(true)
        c.readLog()
        runCurrent()
        assertEquals(1, reads)
        assertFalse(c.state.value.draft.logConsent)
        c.submit()
        runCurrent()
        assertEquals(NativeFeedbackFailure.LOG_CONSENT, c.state.value.failure)
        assertTrue(service.hashes.isEmpty())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `verified browser session submits frozen snapshot once and receives in-app receipt`() = runTest {
        val service = Service()
        val opened = mutableListOf<String>()
        val c = controller(service, opened = opened)
        fill(c)
        c.includeSummary(true)
        c.readLog()
        runCurrent()
        c.logConsent(true)
        c.submit()
        c.submit()
        runCurrent()
        assertEquals(NativeFeedbackPhase.WAITING_VERIFICATION, c.state.value.phase)
        assertEquals(1, opened.size)
        assertFalse(opened.single().contains("Fictional"))
        c.title("Changed during request")
        c.log("Changed during request")
        assertEquals("  Fictional crash report  ", c.state.value.draft.title)
        service.verified = true
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(NativeFeedbackPhase.RECEIVED, c.state.value.phase)
        assertEquals("r".repeat(43), c.state.value.receipt)
        val payload = service.payloads.single()
        assertEquals("Fictional crash report", payload.title)
        assertEquals("Fictional crash\n", payload.crashLog)
        assertEquals("Application: Mihon\nVersion: 0.19.4", payload.summary)
        c.submit()
        runCurrent()
        assertEquals(1, service.payloads.size)
    }

    @Test
    fun `cancelling browser wait prevents submission and keeps draft`() = runTest {
        val service = Service()
        val c = controller(service)
        fill(c)
        c.submit()
        runCurrent()
        c.cancel()
        runCurrent()
        service.verified = true
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(service.payloads.isEmpty())
        assertEquals(NativeFeedbackFailure.CANCELLED, c.state.value.failure)
        assertEquals("  Fictional crash report  ", c.state.value.draft.title)
        assertFalse(c.state.value.busy)
    }

    @Test
    fun `failure retries with new verification but same UUID until contents are edited`() = runTest {
        val service = Service().apply {
            verified = true
            fail = true
        }
        val c = controller(service)
        fill(c)
        c.submit()
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(NativeFeedbackFailure.NETWORK, c.state.value.failure)
        c.submit()
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(2, service.hashes.size)
        assertEquals(service.payloads[0].submissionId, service.payloads[1].submissionId)
        c.title("A different fictional title")
        c.submit()
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        assertNotEquals(service.payloads[0].submissionId, service.payloads[2].submissionId)
    }

    @Test
    fun `log edits reset permission without trimming and removal permits no-log submission`() = runTest {
        val service = Service().apply { verified = true }
        val c = controller(service)
        fill(c)
        c.log(" \t\r\n ")
        c.logConsent(true)
        c.log(" \t\r\n more ")
        assertFalse(c.state.value.draft.logConsent)
        assertEquals(" \t\n more ", c.state.value.draft.crashLog)
        c.removeLog()
        assertEquals("", c.state.value.draft.crashLog)
        assertFalse(c.state.value.draft.logConsent)
        c.submit()
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        assertEquals("", service.payloads.single().crashLog)
    }

    @Test
    fun `log text and UTF8 byte limits reject unsafe and oversized inputs without truncation`() = runTest {
        assertTrue(NativeFeedbackContent.validLog(" \t\r\n😀 "))
        for (bad in listOf(
            "\u0000",
            "\u001b",
            "\u0085",
            "\ud800",
            "\udc00",
            "\ufffd",
        )) {
            assertFalse(NativeFeedbackContent.validLog(bad))
        }
        val service = Service()
        val c = controller(service)
        fill(c)
        val large = "x".repeat(256 * 1024 + 1)
        c.log(large)
        c.logConsent(true)
        c.submit()
        runCurrent()
        assertEquals(large, c.state.value.draft.crashLog)
        assertEquals(NativeFeedbackFailure.LOG_TOO_LARGE, c.state.value.failure)
        assertTrue(service.hashes.isEmpty())
        assertEquals(262144, NativeFeedbackContent.logBytes("中".repeat(87381) + "x"))
    }

    @Test
    fun `expired verification fails without submitting and unavailable log preserves report draft`() = runTest {
        val service = Service()
        val c = controller(service, FeedbackLogReader { throw IllegalStateException("private path") })
        fill(c)
        c.readLog()
        runCurrent()
        assertEquals(NativeFeedbackFailure.LOG_UNAVAILABLE, c.state.value.failure)
        assertEquals("  Fictional crash report  ", c.state.value.draft.title)
        c.submit()
        runCurrent()
        advanceTimeBy(600_001)
        runCurrent()
        assertTrue(service.payloads.isEmpty())
        assertEquals(NativeFeedbackFailure.EXPIRED, c.state.value.failure)
    }
}
