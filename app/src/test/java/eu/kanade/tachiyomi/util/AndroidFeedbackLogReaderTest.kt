package eu.kanade.tachiyomi.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.domain.feedback.NativeFeedbackException
import tachiyomi.domain.feedback.NativeFeedbackFailure
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AndroidFeedbackLogReaderTest {
    private class FixtureProcess(private val input: InputStream, private val exit: Int = 0) : Process() {
        var destroyed = false
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun waitFor(): Int = exit
        override fun exitValue(): Int = exit
        override fun destroy() {
            destroyed = true
        }
    }

    @Test
    fun `explicit reading runs off UI and restricts logcat to app uid`() = runBlocking {
        var command = emptyList<String>()
        var readerThread = ""
        val fake = FixtureProcess(ByteArrayInputStream(" Fictional own-app error\r\n\t ".encodeToByteArray()))
        val currentThread = Thread.currentThread().name
        val text = CrashLogUtil.readFeedbackLogs {
            command = it
            readerThread = Thread.currentThread().name
            fake
        }
        assertEquals(" Fictional own-app error\n\t ", text)
        assertTrue(command.contains("--uid=${android.os.Process.myUid()}"))
        assertTrue(command.contains("*:E"))
        assertNotEquals(currentThread, readerThread)
        assertFalse(text.contains("Installation ID"))
        assertTrue(fake.destroyed)
    }

    @Test
    fun `reader rejects invalid encoding binary oversize and process failure`() = runBlocking {
        for ((bytes, expected) in listOf(
            byteArrayOf(0xc3.toByte(), 0x28) to NativeFeedbackFailure.LOG_INVALID,
            "Fictional\u0000binary".encodeToByteArray() to NativeFeedbackFailure.LOG_INVALID,
            ByteArray(4 * 1024 * 1024 + 1) { 'x'.code.toByte() } to NativeFeedbackFailure.LOG_READ_TOO_LARGE,
        )) {
            val fake = FixtureProcess(ByteArrayInputStream(bytes))
            val failure = try {
                CrashLogUtil.readFeedbackLogs { fake }
                null
            } catch (
                error: NativeFeedbackException,
            ) {
                error
            }
            assertEquals(expected, failure?.failure)
            assertTrue(fake.destroyed)
        }
        val failed = FixtureProcess(ByteArrayInputStream("Private command failure".encodeToByteArray()), 1)
        val failure = try {
            CrashLogUtil.readFeedbackLogs { failed }
            null
        } catch (
            error: NativeFeedbackException,
        ) {
            error
        }
        assertEquals(NativeFeedbackFailure.LOG_UNAVAILABLE, failure?.failure)
        assertFalse(failure.toString().contains("Private command"))
    }
}
