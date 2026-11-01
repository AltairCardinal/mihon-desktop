package mihon.feedback

import android.content.Context
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.feedback.DiagnosticPersistence
import tachiyomi.core.common.feedback.DiagnosticSession
import tachiyomi.core.common.feedback.DiagnosticStore
import tachiyomi.core.common.feedback.SafeCrashReportCodec
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.feedback.FeedbackLogReader
import tachiyomi.domain.feedback.NativeFeedbackContent
import tachiyomi.domain.feedback.NativeFeedbackException
import tachiyomi.domain.feedback.NativeFeedbackFailure
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

internal fun createAndroidDiagnosticStore(context: Context, scope: CoroutineScope): DiagnosticStore {
    val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    val key = Preference.appStateKey("feedback_diagnostic_session")
    val persistence = object : DiagnosticPersistence {
        override fun load(): DiagnosticSession? = preferences.getString(key, null)?.let { Json.decodeFromString(it) }
        override fun save(session: DiagnosticSession?) {
            val editor = preferences.edit()
            if (session == null) editor.remove(key) else editor.putString(key, Json.encodeToString(session))
            if (!editor.commit()) throw IOException("Diagnostic preference save failed")
        }
    }
    return DiagnosticStore(File(context.filesDir, "feedback-diagnostics"), persistence, scope)
}

private fun safeCrashFile(context: Context): File = File(context.filesDir, "mihon_crash_safe.log")
private fun checkCrashPath(file: File) {
    var path: java.nio.file.Path? = file.toPath().toAbsolutePath().normalize()
    while (path != null) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath() != path.toAbsolutePath().normalize()) {
            throw IOException("Unsafe crash location")
        }
        path = path.parent
    }
}

internal fun androidFeedbackCrashReader(context: Context): FeedbackLogReader = FeedbackLogReader {
    withContext(Dispatchers.IO) {
        try {
            val file = safeCrashFile(context)
            val entries = (0..3).flatMap { index ->
                val item = if (index == 0) file else File("${file.path}.$index")
                checkCrashPath(item)
                if (!item.exists()) return@flatMap emptyList()
                if (item.length() > NativeFeedbackContent.MAX_PREVIEW_BYTES) {
                    throw NativeFeedbackException(NativeFeedbackFailure.LOG_READ_TOO_LARGE)
                }
                val bytes = item.inputStream().use { it.readNBytes(NativeFeedbackContent.MAX_PREVIEW_BYTES + 1) }
                if (bytes.size > NativeFeedbackContent.MAX_PREVIEW_BYTES) {
                    throw NativeFeedbackException(NativeFeedbackFailure.LOG_READ_TOO_LARGE)
                }
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                SafeCrashReportCodec.read(text)
            }.sortedBy { it.atMillis }
            val report = entries.joinToString("") { it.content }
            if (NativeFeedbackContent.logBytes(report) > NativeFeedbackContent.MAX_PREVIEW_BYTES) {
                throw NativeFeedbackException(NativeFeedbackFailure.LOG_READ_TOO_LARGE)
            }
            report
        } catch (failure: NativeFeedbackException) {
            throw failure
        } catch (_: Exception) {
            throw NativeFeedbackException(NativeFeedbackFailure.LOG_INVALID)
        }
    }
}

@Synchronized
internal fun writeAndroidFeedbackCrash(
    context: Context,
    error: Throwable,
    atMillis: Long = System.currentTimeMillis(),
) {
    val file = safeCrashFile(context)
    checkCrashPath(file)
    val report = SafeCrashReportCodec.format(error, atMillis)
    if (file.exists() && file.length() + report.toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024) {
        for (index in 3 downTo 1) {
            val target = File("${file.path}.$index")
            val source = if (index == 1) file else File("${file.path}.${index - 1}")
            checkCrashPath(target)
            checkCrashPath(source)
            if (source.exists()) Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    java.io.FileOutputStream(file, true).use { output ->
        output.write(report.toByteArray(Charsets.UTF_8))
        output.fd.sync()
    }
}
