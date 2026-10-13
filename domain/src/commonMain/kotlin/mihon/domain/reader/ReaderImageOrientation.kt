package mihon.domain.reader

/** Encoded-source orientation. Mirroring happens horizontally after rotation. */
enum class ReaderImageOrientation(val exifValue: Int, val rotation: PageRotation, val mirrorHorizontally: Boolean) {
    NORMAL(1, PageRotation.NONE, false),
    MIRROR_HORIZONTAL(2, PageRotation.NONE, true),
    HALF_TURN(3, PageRotation.HALF_TURN, false),
    MIRROR_VERTICAL(4, PageRotation.HALF_TURN, true),
    TRANSPOSE(5, PageRotation.CLOCKWISE_90, true),
    CLOCKWISE(6, PageRotation.CLOCKWISE_90, false),
    TRANSVERSE(7, PageRotation.COUNTER_CLOCKWISE_90, true),
    COUNTER_CLOCKWISE(8, PageRotation.COUNTER_CLOCKWISE_90, false),
    ;

    fun orientedSize(width: Int, height: Int): ReaderPageSize = when (rotation) {
        PageRotation.CLOCKWISE_90, PageRotation.COUNTER_CLOCKWISE_90 -> ReaderPageSize(height, width)
        else -> ReaderPageSize(width, height)
    }

    /** Additional viewport rotation follows normalization; reflection reverses its source rotation. */
    fun rotatedAfter(after: PageRotation): ReaderImageOrientation {
        val turns = (rotation.ordinal + (if (mirrorHorizontally) -after.ordinal else after.ordinal) + 4) % 4
        return entries.first { it.mirrorHorizontally == mirrorHorizontally && it.rotation.ordinal == turns }
    }

    /** A normalized pixel's original encoded-source coordinate, also used by bounded samplers. */
    fun sourcePixel(x: Int, y: Int, rawWidth: Int, rawHeight: Int): Pair<Int, Int> {
        val size = orientedSize(rawWidth, rawHeight)
        require(x in 0 until size.width && y in 0 until size.height)
        val normalizedX = if (mirrorHorizontally) size.width - 1 - x else x
        return when (rotation) {
            PageRotation.NONE -> normalizedX to y
            PageRotation.HALF_TURN -> rawWidth - 1 - normalizedX to rawHeight - 1 - y
            PageRotation.CLOCKWISE_90 -> y to rawHeight - 1 - normalizedX
            PageRotation.COUNTER_CLOCKWISE_90 -> rawWidth - 1 - y to normalizedX
        }
    }

    fun splitBounds(width: Int, height: Int, half: PageSplitHalf): PixelBounds? {
        if (width <= 0 || height <= 0) return null
        val size = orientedSize(width, height)
        val normalized = splitPageBounds(size.width, size.height, half) ?: return null
        if (normalized.width == 0) return PixelBounds(0, 0, 0, height)
        val corners = listOf(
            normalized.x to normalized.y,
            normalized.x + normalized.width - 1 to normalized.y,
            normalized.x to normalized.y + normalized.height - 1,
            normalized.x + normalized.width - 1 to normalized.y + normalized.height - 1,
        )
            .map { (x, y) -> sourcePixel(x, y, width, height) }
        val minX = corners.minOf { it.first }
        val minY = corners.minOf { it.second }
        return PixelBounds(minX, minY, corners.maxOf { it.first } - minX + 1, corners.maxOf { it.second } - minY + 1)
    }

    companion object {
        fun fromExif(value: Int): ReaderImageOrientation = entries.firstOrNull { it.exifValue == value } ?: NORMAL
    }
}

/** A truncated metadata prefix cannot prove that a later EXIF orientation is absent. */
fun hasCompleteImageOrientationMetadata(header: ByteArray): Boolean {
    fun byte(at: Int) = header[at].toInt() and 255
    fun label(at: Int, value: String) = at >= 0 && at + value.length <= header.size &&
        value.indices.all { byte(at + it) == value[it].code }
    if (header.size >= 2 && byte(0) == 255 && byte(1) == 216) {
        var at = 2
        while (at < header.size && byte(at) == 255) {
            while (at < header.size && byte(at) == 255) at++
            if (at >= header.size) return false
            val marker = byte(at++)
            if (marker == 0xda || marker == 0xd9) return true
            if (marker in 0xd0..0xd7 || marker == 1) continue
            if (at + 2 > header.size) return false
            val length = (byte(at) shl 8) or byte(at + 1)
            if (length < 2 || at.toLong() + length > header.size) return false
            at += length
        }
        return false
    }
    if (header.size >= 8 && byte(0) == 137 && label(1, "PNG\r\n\u001a\n")) {
        var at = 8
        while (at + 12 <= header.size) {
            val length = (0 until 4).fold(0L) { value, index -> (value shl 8) or byte(at + index).toLong() }
            if (length > header.size - at - 12L) return false
            if (label(at + 4, "IEND")) return true
            at += length.toInt() + 12
        }
        return false
    }
    if (header.size >= 12 && label(0, "RIFF") && label(8, "WEBP")) {
        val length = (3 downTo 0).fold(0L) { value, index -> (value shl 8) or byte(4 + index).toLong() }
        return length + 8 <= header.size
    }
    return true
}

/** Caller supplies at most its existing header bound; no image pixels are decoded. */
fun readImageOrientation(encodedHeader: ByteArray): ReaderImageOrientation {
    fun byte(at: Int): Int = encodedHeader[at].toInt() and 255
    fun label(at: Int, value: String): Boolean = at >= 0 && at + value.length <= encodedHeader.size &&
        value.indices.all { byte(at + it) == value[it].code }
    fun tiff(start: Int, end: Int): ReaderImageOrientation? {
        if (start < 0 || end > encodedHeader.size || end - start < 8) return null
        val little = label(start, "II")
        if (!little && !label(start, "MM")) return null
        fun number(at: Int, count: Int): Long? {
            if (at < start || at.toLong() + count > end) return null
            var result = 0L
            repeat(count) { index ->
                val position = if (little) at + count - 1 - index else at + index
                result = (result shl 8) or byte(position).toLong()
            }
            return result
        }
        if (number(start + 2, 2) != 42L) return null
        val offset = number(start + 4, 4) ?: return null
        if (offset < 8 || offset > end - start - 2L) return null
        val directory = start + offset.toInt()
        val count = number(directory, 2)?.toInt() ?: return null
        if (count.toLong() * 12 + directory + 2 > end) return null
        repeat(count) { index ->
            val entry = directory + 2 + index * 12
            if (number(entry, 2) == 0x112L && number(entry + 2, 2) == 3L && number(entry + 4, 4) == 1L) {
                return ReaderImageOrientation.fromExif(number(entry + 8, 2)?.toInt() ?: 1)
            }
        }
        return null
    }
    if (encodedHeader.size >= 2 && byte(0) == 255 && byte(1) == 216) {
        var cursor = 2
        while (cursor + 4 <= encodedHeader.size && byte(cursor) == 255) {
            while (cursor < encodedHeader.size && byte(cursor) == 255) cursor++
            if (cursor >= encodedHeader.size) break
            val marker = byte(cursor++)
            if (marker == 0xDA || marker == 0xD9) break
            if (marker in 0xD0..0xD7 || marker == 0x01) continue
            if (cursor + 2 > encodedHeader.size) break
            val length = (byte(cursor) shl 8) or byte(cursor + 1)
            if (length < 2 || cursor.toLong() + length > encodedHeader.size) break
            if (marker == 0xE1 && label(cursor + 2, "Exif\u0000\u0000")) {
                tiff(cursor + 8, cursor + length)?.let { return it }
            }
            cursor += length
        }
    } else if (encodedHeader.size >= 8 && byte(0) == 137 && label(1, "PNG\r\n\u001a\n")) {
        var cursor = 8
        while (cursor + 12 <= encodedHeader.size) {
            val length = (0 until 4).fold(0L) { value, index -> (value shl 8) or byte(cursor + index).toLong() }
            if (length > encodedHeader.size - cursor - 12L) break
            if (label(cursor + 4, "eXIf")) tiff(cursor + 8, cursor + 8 + length.toInt())?.let { return it }
            cursor += 12 + length.toInt()
        }
    } else if (label(0, "RIFF") && label(8, "WEBP")) {
        var cursor = 12
        while (cursor + 8 <= encodedHeader.size) {
            val length = (3 downTo 0).fold(0L) { value, index -> (value shl 8) or byte(cursor + 4 + index).toLong() }
            if (length > encodedHeader.size - cursor - 8L) break
            if (label(cursor, "EXIF")) {
                val start = cursor + 8 + if (label(cursor + 8, "Exif\u0000\u0000")) 6 else 0
                tiff(start, cursor + 8 + length.toInt())?.let { return it }
            }
            cursor += 8 + length.toInt() + (length.toInt() and 1)
        }
    }
    return ReaderImageOrientation.NORMAL
}
