package mihon.desktop.feedback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mihon.desktop.CrashHandler
import mihon.desktop.platform.DesktopPlatformPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.feedback.DiagnosticCategory
import tachiyomi.core.common.feedback.DiagnosticEvent
import tachiyomi.core.common.feedback.DiagnosticOperation
import tachiyomi.core.common.feedback.DiagnosticStage
import tachiyomi.core.common.feedback.SafeCrashReportCodec
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.feedback.NativeFeedbackException
import tachiyomi.domain.feedback.NativeFeedbackFailure
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences

class DesktopFeedbackDiagnosticsTest {
    private fun paths(root: File) = DesktopPlatformPaths.resolve(
        "Windows 11",
        root.path,
        mapOf("APPDATA" to root.resolve("roaming").path, "LOCALAPPDATA" to root.resolve("local").path),
        false,
    )

    @Test
    fun `actual desktop preferences restore mode and durable stop persists`(@TempDir root: File) = runBlocking {
        val node = Preferences.userRoot().node("/mihon/test/feedback-diagnostics-${UUID.randomUUID()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val preferences = DesktopPreferenceStore(node)
            val first = createDesktopDiagnosticStore(paths(root), preferences, scope)
            assertTrue(first.start(DiagnosticCategory.READER))
            first.record(
                DiagnosticEvent(
                    DiagnosticCategory.READER,
                    DiagnosticOperation.READER_PAGE,
                    DiagnosticStage.SUCCESS,
                    count = 2,
                ),
            )
            first.flush()
            val second = createDesktopDiagnosticStore(paths(root), DesktopPreferenceStore(node), scope)
            assertEquals(first.state.value.session, second.state.value.session)
            assertTrue(root.resolve("local/Mihon/logs/feedback-diagnostics/reader").listFiles()?.isNotEmpty() == true)
            assertTrue(second.stop())
            assertNull(createDesktopDiagnosticStore(paths(root), preferences, scope).state.value.session)
            assertFalse(node.node("feedback-diagnostics").keys().any { it.contains("description") })
        } finally {
            scope.cancel()
            node.removeNode()
        }
    }

    @Test
    fun `feedback reads safe crash history by embedded time and excludes legacy raw files`(
        @TempDir root: File,
    ) = runBlocking {
        val paths = paths(root)
        paths.logsDir.mkdirs()
        File(paths.logsDir, "mihon_crash.log").writeText("PRIVATE_OLD_RAW", Charsets.UTF_8)
        val error = IllegalStateException("PRIVATE_MESSAGE")
        error.stackTrace = arrayOf(StackTraceElement("mihon.Reader", "decode", "PRIVATE_PATH", 4))
        val recent = SafeCrashReportCodec.format(error, 200)
        val older = SafeCrashReportCodec.format(error, 100)
        CrashHandler.appendCrashReport(File(paths.logsDir, "mihon_crash_safe.log"), older)
        CrashHandler.appendCrashReport(File(paths.logsDir, "mihon_crash_safe.log.1"), recent)
        File(paths.logsDir, "mihon_crash_safe.log").setLastModified(9000)
        File(paths.logsDir, "mihon_crash_safe.log.1").setLastModified(1000)
        val content = desktopFeedbackCrashReader(paths).read()
        assertEquals(older + recent, content)
        assertFalse(content.contains("PRIVATE"))
    }

    @Test
    fun `legacy crashes alone are unavailable rather than auto-attached`(@TempDir root: File) = runBlocking {
        val paths = paths(root)
        paths.logsDir.mkdirs()
        File(paths.logsDir, "mihon_crash.log").writeText("PRIVATE_OLD_RAW", Charsets.UTF_8)
        val failure = try {
            desktopFeedbackCrashReader(paths).read()
            null
        } catch (
            error: NativeFeedbackException,
        ) {
            error.failure
        }
        assertEquals(NativeFeedbackFailure.LOG_UNAVAILABLE, failure)
    }
}
