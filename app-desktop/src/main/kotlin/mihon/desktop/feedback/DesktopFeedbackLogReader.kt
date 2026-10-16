package mihon.desktop.feedback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.desktop.CrashHandler
import tachiyomi.domain.feedback.FeedbackLogReader
import tachiyomi.domain.feedback.FeedbackLogText
import tachiyomi.domain.feedback.NativeFeedbackContent
import tachiyomi.domain.feedback.NativeFeedbackException
import tachiyomi.domain.feedback.NativeFeedbackFailure
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

class DesktopFeedbackLogReader(private val directory: File = CrashHandler.defaultCrashLogDir()) : FeedbackLogReader {
    override suspend fun read(): String = withContext(Dispatchers.IO) {
        val file = (0..3).map { index -> File(directory, "mihon_crash.log" + if (index == 0) "" else ".$index") }
            .filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) && it.length() > 0 }
            .maxByOrNull(File::lastModified)
            ?: throw NativeFeedbackException(NativeFeedbackFailure.LOG_UNAVAILABLE)
        if (file.length() >
            NativeFeedbackContent.MAX_PREVIEW_BYTES
        ) {
            throw NativeFeedbackException(NativeFeedbackFailure.LOG_READ_TOO_LARGE)
        }
        file.inputStream().use { FeedbackLogText.read(it) }
    }
}
