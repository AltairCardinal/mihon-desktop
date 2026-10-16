package tachiyomi.domain.feedback

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object FeedbackLogText {
    fun read(input: InputStream, maximumBytes: Int = NativeFeedbackContent.MAX_PREVIEW_BYTES): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (bytes.size() <= maximumBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size, maximumBytes + 1 - bytes.size()))
            if (read < 0) break
            bytes.write(buffer, 0, read)
        }
        if (bytes.size() > maximumBytes) throw NativeFeedbackException(NativeFeedbackFailure.LOG_READ_TOO_LARGE)
        val decoded = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(
                CodingErrorAction.REPORT,
            ).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString().removePrefix("\uFEFF")
        } catch (error: Exception) {
            throw NativeFeedbackException(NativeFeedbackFailure.LOG_INVALID)
        }
        if (!NativeFeedbackContent.validLog(decoded)) throw NativeFeedbackException(NativeFeedbackFailure.LOG_INVALID)
        return NativeFeedbackContent.normalizeLog(decoded)
    }
}
