package mihon.feedback

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.kanade.tachiyomi.crash.CrashActivity
import eu.kanade.tachiyomi.crash.GlobalExceptionHandler
import eu.kanade.tachiyomi.di.AppModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.core.common.feedback.DiagnosticCategory
import tachiyomi.core.common.feedback.DiagnosticEvent
import tachiyomi.core.common.feedback.DiagnosticOperation
import tachiyomi.core.common.feedback.DiagnosticStage
import tachiyomi.core.common.feedback.DiagnosticStore
import tachiyomi.core.common.feedback.SafeCrashReportCodec
import tachiyomi.domain.feedback.FeedbackCategory
import tachiyomi.domain.feedback.FeedbackDiagnosticController
import tachiyomi.domain.feedback.NativeFeedbackException
import tachiyomi.domain.feedback.NativeFeedbackFailure
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidFeedbackDiagnosticsTest {
    @Test
    fun `real private preferences restore selected category and durable stop survives recreation`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val previousInjekt = Injekt
        Injekt =
            InjektScope(DefaultRegistrar())
        try {
            Injekt.importModule(AppModule(context as Application))
            assertNotNull(Injekt.get<DiagnosticStore>())
            assertNotNull(Injekt.get<tachiyomi.domain.feedback.FeedbackDraftMemory>())
            val first = createAndroidDiagnosticStore(context, scope)
            assertTrue(first.start(DiagnosticCategory.READER))
            first.record(
                DiagnosticEvent(DiagnosticCategory.READER, DiagnosticOperation.READER_PAGE, DiagnosticStage.SUCCESS),
            )
            first.flush()
            val restored = createAndroidDiagnosticStore(context, scope)
            assertEquals(first.state.value.session, restored.state.value.session)
            assertTrue(restored.stop())
            assertNull(createAndroidDiagnosticStore(context, scope).state.value.session)
        } finally {
            scope.cancel()
            Injekt = previousInjekt
        }
    }

    @Test
    fun `invalid safe crash file reports read failure instead of no records`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(context.filesDir, "mihon_crash_safe.log")
        try {
            for (bytes in listOf("Fictional corrupt safe report".toByteArray(), byteArrayOf(0xC3.toByte(), 0x28))) {
                file.writeBytes(bytes)
                val reader = androidFeedbackCrashReader(context)
                val failure = try {
                    reader.read()
                    error("Invalid report accepted")
                } catch (error: NativeFeedbackException) {
                    error.failure
                }
                assertEquals(NativeFeedbackFailure.LOG_INVALID, failure)
                val controller = FeedbackDiagnosticController(
                    createAndroidDiagnosticStore(context, scope),
                    reader,
                    scope,
                )
                controller.select(FeedbackCategory.STARTUP_CRASH)
                val state = withTimeout(5000) { controller.state.first { !it.reading } }
                assertTrue(state.readFailed)
                assertEquals("", state.content)
            }
        } finally {
            file.delete()
            scope.cancel()
        }
    }

    @Test
    fun `actual crash capture excludes private message and old raw logs and preserves timestamp`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "mihon_crash.log").writeText("fictional old raw /private/path password=secret")
        val reader = androidFeedbackCrashReader(context)
        assertEquals("", reader.read())
        val error = IllegalStateException("fictional token=private /private/path")
        error.stackTrace = arrayOf(StackTraceElement("mihon.FictionalReader", "open", "private-file.kt", 7))
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        var delegated = false
        val before = System.currentTimeMillis()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated = true }
            GlobalExceptionHandler.initialize(
                context,
                CrashActivity::class.java,
            )
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
        assertTrue(delegated)
        val report = reader.read()
        val entry = SafeCrashReportCodec.read(report).single()
        assertTrue(entry.atMillis in before..System.currentTimeMillis())
        assertTrue(report.contains("mihon.FictionalReader.open:7"))
        assertFalse(report.contains("private"))
        assertFalse(report.contains("fictional token"))
        Unit
    }
}
