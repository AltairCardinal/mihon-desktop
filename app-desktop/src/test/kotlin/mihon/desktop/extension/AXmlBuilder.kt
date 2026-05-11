package mihon.desktop.extension

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds minimal but structurally valid Android Binary XML (AXML) byte arrays
 * for use in unit tests.
 *
 * The format produced matches the Android resource compiler (aapt) output for
 * a Tachiyomi extension AndroidManifest.xml.
 */
class AXmlBuilder {

    /**
     * Builds an AXML binary representing a minimal Tachiyomi extension manifest:
     *
     * ```xml
     * <manifest package="[packageName]"
     *           xmlns:android="http://schemas.android.com/apk/res/android">
     *   <application>
     *     <meta-data android:name="tachiyomi.extension.class"
     *                android:value="[extensionClass]"/>  <!-- omitted if null -->
     *   </application>
     * </manifest>
     * ```
     */
    fun buildManifest(packageName: String, extensionClass: String?): ByteArray {
        // ── string pool ──────────────────────────────────────────────────────────
        // Build the string list deterministically — indices must match what the
        // parser looks up by name.
        val strings = mutableListOf<String>()
        fun addStr(s: String): Int { strings.add(s); return strings.size - 1 }

        val idxAndroid = addStr("android")          // ns prefix
        val idxNsUri   = addStr("http://schemas.android.com/apk/res/android")
        val idxManifest  = addStr("manifest")
        val idxPackage   = addStr("package")
        val idxApplication = addStr("application")
        val idxMetaData  = addStr("meta-data")
        val idxName      = addStr("name")
        val idxValue     = addStr("value")
        val idxExtKey    = addStr("tachiyomi.extension.class")
        val idxPkgValue  = addStr(packageName)
        val idxClassValue = if (extensionClass != null) addStr(extensionClass) else -1

        val stringPoolBytes = buildStringPool(strings, utf8 = true)

        // ── XML events ───────────────────────────────────────────────────────────
        val events = ByteArrayBuilder()

        // START_NAMESPACE: android → http://schemas.android.com/apk/res/android
        events.chunk(0x0100, 24) {
            putI(1)             // line
            putI(-1)            // comment
            putI(idxAndroid)    // prefix
            putI(idxNsUri)      // uri
        }

        // START_ELEMENT: <manifest package="...">
        val manifestAttrs = if (packageName.isNotEmpty()) listOf(
            Attr(ns = -1, name = idxPackage, rawValue = idxPkgValue,
                dataType = 0x03, dataValue = idxPkgValue),
        ) else emptyList()
        events.append(buildStartElement(line = 2, ns = -1, name = idxManifest, attrs = manifestAttrs))

        // START_ELEMENT: <application>
        events.append(buildStartElement(line = 3, ns = -1, name = idxApplication, attrs = emptyList()))

        if (extensionClass != null) {
            // START_ELEMENT: <meta-data android:name="tachiyomi.extension.class" android:value="...">
            val metaAttrs = listOf(
                Attr(ns = idxNsUri, name = idxName, rawValue = idxExtKey,
                    dataType = 0x03, dataValue = idxExtKey),
                Attr(ns = idxNsUri, name = idxValue, rawValue = idxClassValue,
                    dataType = 0x03, dataValue = idxClassValue),
            )
            events.append(buildStartElement(line = 4, ns = idxNsUri, name = idxMetaData, attrs = metaAttrs))
            // END_ELEMENT: </meta-data>
            events.chunk(0x0103, 24) {
                putI(4); putI(-1); putI(idxNsUri); putI(idxMetaData)
            }
        }

        // END_ELEMENT: </application>
        events.chunk(0x0103, 24) {
            putI(5); putI(-1); putI(-1); putI(idxApplication)
        }
        // END_ELEMENT: </manifest>
        events.chunk(0x0103, 24) {
            putI(6); putI(-1); putI(-1); putI(idxManifest)
        }
        // END_NAMESPACE
        events.chunk(0x0101, 24) {
            putI(7); putI(-1); putI(idxAndroid); putI(idxNsUri)
        }

        val eventsBytes = events.toByteArray()

        // ── assemble file ─────────────────────────────────────────────────────────
        val totalSize = 8 + stringPoolBytes.size + eventsBytes.size
        val file = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        file.putShort(0x0003)               // ResXMLTree type
        file.putShort(8)                    // header size
        file.putInt(totalSize)              // total file size
        file.put(stringPoolBytes)
        file.put(eventsBytes)
        return file.array()
    }

    // ──────────────────────────────────────────────────────────────
    // Builders
    // ──────────────────────────────────────────────────────────────

    private data class Attr(
        val ns: Int, val name: Int, val rawValue: Int,
        val dataType: Int, val dataValue: Int,
    )

    private fun buildStartElement(line: Int, ns: Int, name: Int, attrs: List<Attr>): ByteArray {
        // chunk header (8) + node (8) + attrExt (20) + attrs * 20
        val attrBlockSize = attrs.size * 20
        val chunkSize = 8 + 8 + 20 + attrBlockSize
        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        // chunk header
        buf.putShort(0x0102)         // START_ELEMENT
        buf.putShort(8)              // header size (just the chunk header itself)
        buf.putInt(chunkSize)
        // ResXMLTree_node
        buf.putInt(line)
        buf.putInt(-1)               // comment
        // ResXMLTree_attrExt
        buf.putInt(ns)               // namespace
        buf.putInt(name)             // element name
        buf.putShort(0x14)           // attributeStart (offset from here = 20)
        buf.putShort(0x14)           // attributeSize (20 bytes each)
        buf.putShort(attrs.size.toShort())
        buf.putShort(0)              // idAttributeIndex
        buf.putShort(0)              // classAttributeIndex
        buf.putShort(0)              // styleAttributeIndex
        // attributes
        for (attr in attrs) {
            buf.putInt(attr.ns)
            buf.putInt(attr.name)
            buf.putInt(attr.rawValue)
            buf.putShort(8)                      // Res_value.size
            buf.put(0)                           // Res_value.res0
            buf.put(attr.dataType.toByte())
            buf.putInt(attr.dataValue)
        }
        return buf.array()
    }

    /**
     * Builds a ResStringPool chunk in UTF-8 encoding.
     */
    private fun buildStringPool(strings: List<String>, utf8: Boolean = true): ByteArray {
        // Encode each string to UTF-8 bytes with Android's length prefix
        val encoded = strings.map { s ->
            val utf8Bytes = s.toByteArray(Charsets.UTF_8)
            val charLen = s.length
            // UTF-16 char count prefix (1 or 2 bytes)
            val charLenBytes = encodeUtf8Len(charLen)
            // UTF-8 byte count prefix (1 or 2 bytes)
            val byteLenBytes = encodeUtf8Len(utf8Bytes.size)
            charLenBytes + byteLenBytes + utf8Bytes + byteArrayOf(0) // null terminator
        }

        // String offsets (4 bytes each)
        val offsets = IntArray(strings.size)
        var pos = 0
        for (i in encoded.indices) {
            offsets[i] = pos
            pos += encoded[i].size
        }

        val stringDataSize = pos
        // Pad to 4-byte alignment
        val paddedStringDataSize = (stringDataSize + 3) and -4

        // header size = ResStringPool_header = 28 bytes
        val headerSize = 28
        val chunkSize = headerSize + strings.size * 4 + paddedStringDataSize
        val flags = if (utf8) 0x100 else 0
        val stringsStart = headerSize + strings.size * 4  // after offsets

        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        // ResChunk_header
        buf.putShort(0x0001)         // type = STRING_POOL
        buf.putShort(headerSize.toShort())
        buf.putInt(chunkSize)
        // ResStringPool_header fields
        buf.putInt(strings.size)     // stringCount
        buf.putInt(0)                // styleCount
        buf.putInt(flags)            // flags
        buf.putInt(stringsStart)     // stringsStart
        buf.putInt(0)                // stylesStart
        // offsets
        for (off in offsets) buf.putInt(off)
        // string data
        for (enc in encoded) buf.put(enc)
        // padding
        repeat(paddedStringDataSize - stringDataSize) { buf.put(0) }

        return buf.array()
    }

    private fun encodeUtf8Len(len: Int): ByteArray =
        if (len > 0x7F) byteArrayOf(((len shr 8) or 0x80).toByte(), (len and 0xFF).toByte())
        else byteArrayOf(len.toByte())

    // ──────────────────────────────────────────────────────────────
    // ByteArrayBuilder helper
    // ──────────────────────────────────────────────────────────────

    private class ByteArrayBuilder {
        private val parts = mutableListOf<ByteArray>()

        fun append(bytes: ByteArray) { parts.add(bytes) }

        /**
         * Writes a simple chunk (header + content produced by [body]).
         * [contentSize] is the size of [body]'s output (must be known in advance).
         */
        fun chunk(type: Int, totalSize: Int, body: ByteBuffer.() -> Unit) {
            val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
            buf.putShort(type.toShort())
            buf.putShort(8)              // header size
            buf.putInt(totalSize)
            body(buf)
            parts.add(buf.array())
        }

        fun toByteArray(): ByteArray {
            val total = parts.sumOf { it.size }
            val result = ByteArray(total)
            var pos = 0
            for (part in parts) {
                System.arraycopy(part, 0, result, pos, part.size)
                pos += part.size
            }
            return result
        }
    }

    private fun ByteBuffer.putI(v: Int) { putInt(v) }
}
