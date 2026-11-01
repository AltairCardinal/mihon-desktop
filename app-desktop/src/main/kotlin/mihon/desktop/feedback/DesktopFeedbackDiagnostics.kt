package mihon.desktop.feedback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.desktop.platform.DesktopPlatformPaths
import tachiyomi.core.common.feedback.DiagnosticPersistence
import tachiyomi.core.common.feedback.DiagnosticSession
import tachiyomi.core.common.feedback.DiagnosticStore
import tachiyomi.core.common.feedback.SafeCrashReportCodec
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.feedback.FeedbackLogReader
import tachiyomi.domain.feedback.FeedbackLogText
import tachiyomi.domain.feedback.NativeFeedbackContent
import tachiyomi.domain.feedback.NativeFeedbackException
import tachiyomi.domain.feedback.NativeFeedbackFailure
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal fun createDesktopDiagnosticStore(
    paths: DesktopPlatformPaths,
    preferenceStore: tachiyomi.core.common.preference.PreferenceStore,
    scope: CoroutineScope,
): DiagnosticStore {
    if (preferenceStore !is DesktopPreferenceStore) {
        val preference = preferenceStore.getString(
            tachiyomi.core.common.preference.Preference.appStateKey("feedback-diagnostic-session"),
        )
        return DiagnosticStore(
            File(paths.logsDir, "feedback-diagnostics"),
            object : DiagnosticPersistence {
                override fun load(): DiagnosticSession? = preference.get().takeIf { it.isNotEmpty() }?.let {
                    require(it.length <= 2048)
                    Json.decodeFromString(it)
                }
                override fun save(session: DiagnosticSession?) {
                    if (session == null) preference.delete() else preference.set(Json.encodeToString(session))
                }
            },
            scope,
        )
    }
    val node by lazy { preferenceStore.childNode("feedback-diagnostics") }
    val persistence = object : DiagnosticPersistence {
        override fun load(): DiagnosticSession? {
            node.sync()
            val raw = node.get("session", null) ?: return null
            require(raw.length <= 2048)
            return Json.decodeFromString(raw)
        }

        override fun save(session: DiagnosticSession?) {
            val previous = node.get("session", null)
            try {
                if (session == null) node.remove("session") else node.put("session", Json.encodeToString(session))
                node.flush()
            } catch (error: Exception) {
                // Restore the cached value too: a failed flush must not make a
                // freshly created store believe recording started successfully.
                try {
                    if (previous == null) node.remove("session") else node.put("session", previous)
                    node.flush()
                } catch (rollback: Exception) {
                    error.addSuppressed(rollback)
                }
                throw error
            }
        }
    }
    return DiagnosticStore(File(paths.logsDir, "feedback-diagnostics"), persistence, scope)
}

internal fun desktopFeedbackCrashReader(paths: DesktopPlatformPaths): FeedbackLogReader = FeedbackLogReader {
    withContext(Dispatchers.IO) {
        try {
            val files = (0..3).map { index ->
                File(paths.logsDir, "mihon_crash_safe.log" + if (index == 0) "" else ".$index").toPath()
            }.filter { file ->
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return@filter false
                require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                require(file.toRealPath() == file.toAbsolutePath().normalize())
                Files.size(file) > 0
            }
            if (files.isEmpty()) throw NativeFeedbackException(NativeFeedbackFailure.LOG_UNAVAILABLE)
            if (files.sumOf { Files.size(it) } > NativeFeedbackContent.MAX_PREVIEW_BYTES) {
                throw NativeFeedbackException(NativeFeedbackFailure.LOG_READ_TOO_LARGE)
            }
            var readBytes = 0
            val entries = files.flatMap { file ->
                val content = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { FeedbackLogText.read(it) }
                readBytes += content.toByteArray(Charsets.UTF_8).size
                if (readBytes > NativeFeedbackContent.MAX_PREVIEW_BYTES) {
                    throw NativeFeedbackException(NativeFeedbackFailure.LOG_READ_TOO_LARGE)
                }
                require(file.toRealPath() == file.toAbsolutePath().normalize())
                SafeCrashReportCodec.read(content)
            }
            entries.sortedBy { it.atMillis }.joinToString("") { it.content }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: NativeFeedbackException) {
            throw error
        } catch (_: Exception) {
            throw NativeFeedbackException(NativeFeedbackFailure.LOG_INVALID)
        }
    }
}
