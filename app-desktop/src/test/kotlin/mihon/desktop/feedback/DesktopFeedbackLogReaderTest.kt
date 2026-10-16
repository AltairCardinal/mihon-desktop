package mihon.desktop.feedback

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.feedback.NativeFeedbackException
import tachiyomi.domain.feedback.NativeFeedbackFailure
import java.io.File

class DesktopFeedbackLogReaderTest {
    @Test
    fun `reads latest crash file`(@TempDir root: File) = runBlocking {
        File(root, "unrelated-secret.txt").writeText("Never read this fictional file")
        File(root, "mihon_crash.log").writeText("")
        File(root, "mihon_crash.log.1").apply {
            writeText("Older fictional crash")
            setLastModified(1000)
        }
        File(root, "mihon_crash.log.2").apply {
            val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
            writeBytes(bom + " Fictional crash\r\n\t ".encodeToByteArray())
            setLastModified(2000)
        }
        assertEquals(" Fictional crash\n\t ", DesktopFeedbackLogReader(root).read())
    }

    @Test
    fun `missing oversized malformed and binary logs fail with safe typed errors`(@TempDir root: File) = runBlocking {
        suspend fun expect(failure: NativeFeedbackFailure) {
            val error = try {
                DesktopFeedbackLogReader(root).read()
                null
            } catch (
                error: NativeFeedbackException,
            ) {
                error
            }
            assertEquals(failure, error?.failure)
            assertFalse(error.toString().contains(root.path))
        }
        expect(NativeFeedbackFailure.LOG_UNAVAILABLE)
        val log = File(root, "mihon_crash.log")
        log.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        expect(NativeFeedbackFailure.LOG_INVALID)
        log.writeText("Fictional\u0000binary")
        expect(NativeFeedbackFailure.LOG_INVALID)
        java.io.RandomAccessFile(log, "rw").use { it.setLength(4L * 1024 * 1024 + 1) }
        expect(NativeFeedbackFailure.LOG_READ_TOO_LARGE)
    }
}
