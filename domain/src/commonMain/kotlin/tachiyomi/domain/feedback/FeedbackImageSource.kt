package tachiyomi.domain.feedback

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream

object FeedbackImageSource {
    const val MAX_INPUT_BYTES = 10 * 1024 * 1024

    suspend fun read(input: FeedbackScreenshotInput): ByteArray {
        if (input.size != null && (input.size < 0 || input.size > MAX_INPUT_BYTES)) tooLarge()
        val output = ByteArrayOutputStream()
        input.open().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer, 0, minOf(buffer.size, MAX_INPUT_BYTES + 1 - output.size()))
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                if (output.size() > MAX_INPUT_BYTES) tooLarge()
            }
        }
        return output.toByteArray().also(::checkFormat)
    }

    fun dimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0) invalid()
        if (width !in 1..16384 || height !in 1..16384 || width.toLong() * height > 16_000_000) tooLarge()
    }

    fun outputDimensions(width: Int, height: Int, orientation: Int): Pair<Int, Int> {
        val swap = orientation in 5..8
        val w = if (swap) height else width
        val h = if (swap) width else height
        val factor = minOf(1.0, 1920.0 / maxOf(w, h))
        return maxOf(1, (w * factor).toInt()) to maxOf(1, (h * factor).toInt())
    }

    private fun checkFormat(bytes: ByteArray) {
        if (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[2] == 0xff.toByte()
        ) {
            return
        }
        val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        if (bytes.size < 33 || !bytes.copyOfRange(0, 8).contentEquals(png)) invalid()
        var offset = 8
        while (offset + 12 <= bytes.size) {
            val length = (0..3).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 255) }
            if (length > bytes.size - offset - 12) invalid()
            val type = bytes.copyOfRange(offset + 4, offset + 8).decodeToString()
            if (type == "acTL" || type == "fcTL" || type == "fdAT") invalid()
            offset += length.toInt() + 12
            if (type == "IEND") {
                if (offset != bytes.size) invalid()
                return
            }
        }
        invalid()
    }

    // Read only the bounded TIFF orientation tag; encoded output never copies source metadata.
    fun orientation(bytes: ByteArray): Int {
        if (bytes.size < 4 || bytes[0] != 0xff.toByte()) return 1
        var offset = 2
        while (offset + 4 <= bytes.size && bytes[offset] == 0xff.toByte()) {
            val marker = bytes[offset + 1].toInt() and 255
            if (marker == 0xda || marker == 0xd9) break
            val length = ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
            if (length < 2 || offset.toLong() + 2 + length > bytes.size) return 1
            val start = offset + 4
            val end = offset + 2 + length
            if (marker == 0xe1 && start + 14 <= end &&
                bytes.copyOfRange(start, start + 6).contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0))
            ) {
                val tiff = start + 6
                val little = bytes[tiff] == 73.toByte() && bytes[tiff + 1] == 73.toByte()
                if (!little && !(bytes[tiff] == 77.toByte() && bytes[tiff + 1] == 77.toByte())) return 1
                fun number(pos: Int, count: Int): Long? {
                    if (pos < tiff || pos.toLong() + count > end) return null
                    return (0 until count).fold(0L) { value, index ->
                        val shift = if (little) index * 8 else (count - index - 1) * 8
                        value or ((bytes[pos + index].toLong() and 255) shl shift)
                    }
                }
                if (number(tiff + 2, 2) != 42L) return 1
                val directory = number(tiff + 4, 4)?.takeIf { it <= end - tiff - 2 }?.toInt() ?: return 1
                val position = tiff + directory
                val count = number(position, 2)?.takeIf { it <= (end - position - 2) / 12 }?.toInt() ?: return 1
                repeat(count) { index ->
                    val entry = position + 2 + index * 12
                    if (number(entry, 2) == 0x112L && number(entry + 2, 2) == 3L && number(entry + 4, 4) == 1L) {
                        return number(entry + 8, 2)?.toInt()?.takeIf { it in 1..8 } ?: 1
                    }
                }
                return 1
            }
            offset = end
        }
        return 1
    }

    fun invalid(): Nothing = throw NativeFeedbackException(NativeFeedbackFailure.IMAGE_INVALID)
    fun tooLarge(): Nothing = throw NativeFeedbackException(NativeFeedbackFailure.IMAGE_TOO_LARGE)
}
