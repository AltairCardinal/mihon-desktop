package tachiyomi.domain.feedback

import java.io.ByteArrayOutputStream

/** Drops encoder-added APP/COM metadata without decoding or changing entropy-coded pixels. */
object FeedbackJpegMetadata {
    fun strip(bytes: ByteArray): ByteArray {
        if (bytes.size !in 4..16 * 1024 * 1024 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte()) {
            FeedbackImageSource.invalid()
        }
        val output = ByteArrayOutputStream(bytes.size)
        output.write(bytes, 0, 2)
        var cursor = 2
        var scanSeen = false
        while (cursor < bytes.size) {
            val start = cursor
            if (bytes[cursor] != 0xff.toByte()) FeedbackImageSource.invalid()
            while (cursor < bytes.size && bytes[cursor] == 0xff.toByte()) cursor++
            if (cursor >= bytes.size) FeedbackImageSource.invalid()
            val marker = bytes[cursor++].toInt() and 255
            if (marker == 0xd9) {
                if (!scanSeen || cursor != bytes.size) FeedbackImageSource.invalid()
                output.write(255)
                output.write(217)
                return output.toByteArray()
            }
            if (marker !in 0xe0..0xef && marker !in setOf(0xfe, 0xdb, 0xc4, 0xc0, 0xc2, 0xdd, 0xda)) {
                FeedbackImageSource.invalid()
            }
            if (cursor + 2 > bytes.size) FeedbackImageSource.invalid()
            val length = ((bytes[cursor].toInt() and 255) shl 8) or (bytes[cursor + 1].toInt() and 255)
            if (length < 2 || cursor.toLong() + length > bytes.size) FeedbackImageSource.invalid()
            val end = cursor + length
            if (marker !in 0xe0..0xef && marker != 0xfe) output.write(bytes, start, end - start)
            cursor = end
            if (marker == 0xda) {
                scanSeen = true
                val entropyStart = cursor
                while (cursor < bytes.size) {
                    if (bytes[cursor] != 0xff.toByte()) {
                        cursor++
                        continue
                    }
                    var codePosition = cursor + 1
                    while (codePosition < bytes.size && bytes[codePosition] == 0xff.toByte()) codePosition++
                    if (codePosition >= bytes.size) FeedbackImageSource.invalid()
                    val code = bytes[codePosition].toInt() and 255
                    if (code == 0 || code in 0xd0..0xd7) cursor = codePosition + 1 else break
                }
                output.write(bytes, entropyStart, cursor - entropyStart)
            }
        }
        FeedbackImageSource.invalid()
    }
}
