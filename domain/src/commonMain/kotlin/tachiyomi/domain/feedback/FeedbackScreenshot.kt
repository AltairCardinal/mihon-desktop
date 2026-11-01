package tachiyomi.domain.feedback

import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

class FeedbackScreenshotInput(val size: Long?, val open: () -> InputStream) {
    override fun toString(): String = "FeedbackScreenshotInput([private])"
}

enum class FeedbackImageStage { READ, DECODE, SCALE, ENCODE, CHECK, COMPLETE }
data class FeedbackScreenshotProgress(val index: Int, val total: Int, val stage: FeedbackImageStage)

fun interface FeedbackScreenshotProcessor {
    suspend fun process(input: FeedbackScreenshotInput, progress: (FeedbackImageStage) -> Unit): FeedbackScreenshot
}

class FeedbackScreenshot(
    val width: Int,
    val height: Int,
    bytes: ByteArray,
    val id: String = UUID.randomUUID().toString(),
) {
    private val encoded = bytes.copyOf()
    val mime: String get() = "image/jpeg"
    val byteLength: Int get() = encoded.size
    val sha256: String = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
    init {
        require(width in 1..1920 && height in 1..1920 && encoded.size in 4..524288)
        require(encoded[0] == 0xff.toByte() && encoded[1] == 0xd8.toByte())
        require(encoded[encoded.lastIndex - 1] == 0xff.toByte() && encoded.last() == 0xd9.toByte())
        require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
    }
    fun bytes(): ByteArray = encoded.copyOf()
    override fun toString(): String = "FeedbackScreenshot([private])"
}
