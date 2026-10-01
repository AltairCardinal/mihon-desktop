package eu.kanade.tachiyomi.ui.reader.viewer.pager

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

internal fun readerTestPng(w: Int, h: Int): ByteArray {
    // Encode transparent scanlines without allocating a full bitmap for every vector.
    val compressed = ByteArrayOutputStream()
    DeflaterOutputStream(compressed).use { output ->
        val row = ByteArray(1 + w * 4)
        repeat(h) { output.write(row) }
    }
    val image = ByteArrayOutputStream()
    DataOutputStream(image).use { output ->
        output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(name: String, payload: ByteArray) {
            val type = name.toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply {
                update(type)
                update(payload)
            }
            output.writeInt(payload.size)
            output.write(type)
            output.write(payload)
            output.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use {
            it.writeInt(w)
            it.writeInt(h)
            it.write(byteArrayOf(8, 6, 0, 0, 0))
        }
        chunk("IHDR", header.toByteArray())
        chunk("IDAT", compressed.toByteArray())
        chunk("IEND", byteArrayOf())
    }
    return image.toByteArray()
}
