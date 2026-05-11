package mihon.desktop.extension

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Extracts the Tachiyomi extension class name from an Android APK or from raw
 * AndroidManifest.xml binary (AXML) bytes.
 *
 * Tachiyomi extensions declare their entry class via a <meta-data> element:
 *
 *   <meta-data
 *       android:name="tachiyomi.extension.class"
 *       android:value=".SourceClassName"/>
 *
 * The value may be:
 *  - Absolute: "eu.kanade.tachiyomi.extension.zh.xxx.ClassName"
 *  - Relative: ".ClassName"  →  resolved as "<package><value>"
 *
 * The manifest is stored in Android Binary XML format (AXML), a compact binary
 * encoding used by the Android resource system.
 */
object ManifestClassExtractor {

    private const val EXTENSION_CLASS_META_KEY = "tachiyomi.extension.class"

    // AXML chunk types
    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_XML_START_NS = 0x0100
    private const val CHUNK_XML_END_NS = 0x0101
    private const val CHUNK_XML_START_ELEMENT = 0x0102
    private const val CHUNK_XML_END_ELEMENT = 0x0103

    // Res_value data types
    private const val TYPE_STRING = 0x03

    // Sentinel for "no value"
    private const val NO_INDEX = -1

    /**
     * Extracts the extension class from an APK file by reading its
     * AndroidManifest.xml binary.
     *
     * @return the fully-qualified class name, or null on failure.
     */
    fun extractFromApk(apkFile: File): String? {
        return try {
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml") ?: return null
                extractExtensionClass(zip.getInputStream(entry).readBytes())
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts the extension class from raw AXML bytes.
     *
     * @return the fully-qualified class name, or null if not found or on parse error.
     */
    fun extractExtensionClass(axmlBytes: ByteArray): String? {
        if (axmlBytes.size < 8) return null
        return try {
            parseAxml(axmlBytes)
        } catch (_: Exception) {
            null
        }
    }

    // ──────────────────────────────────────────────────────────────
    // AXML binary parser
    // ──────────────────────────────────────────────────────────────

    private fun parseAxml(bytes: ByteArray): String? {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // AXML file starts with a ResChunk_header for the XML tree (type = 0x0003)
        val fileType = buf.short.toInt() and 0xFFFF
        if (fileType != 0x0003) return null
        buf.position(8) // skip headerSize + fileSize

        // First chunk is always the String Pool
        if (buf.remaining() < 8) return null
        val strings = parseStringPool(buf) ?: return null

        // Find the string indices we need
        val extClassKeyIdx = strings.indexOf(EXTENSION_CLASS_META_KEY)
        if (extClassKeyIdx == NO_INDEX) return null

        val nameAttrIdx = strings.indexOf("name")
        val valueAttrIdx = strings.indexOf("value")
        val packageAttrIdx = strings.indexOf("package")

        var packageName: String? = null
        var extensionClass: String? = null

        // Walk remaining XML event chunks
        while (buf.remaining() >= 8 && extensionClass == null) {
            val chunkStart = buf.position()
            val type = buf.short.toInt() and 0xFFFF
            val headerSize = buf.short.toInt() and 0xFFFF
            val chunkSize = buf.int

            if (chunkSize < 8 || chunkStart + chunkSize > buf.capacity()) break

            when (type) {
                CHUNK_XML_START_ELEMENT -> {
                    val (pkg, cls) = processStartElement(
                        buf, chunkStart, headerSize, chunkSize, strings,
                        extClassKeyIdx, nameAttrIdx, valueAttrIdx, packageAttrIdx,
                    )
                    if (pkg != null) packageName = pkg
                    if (cls != null) extensionClass = cls
                }
                else -> buf.position(chunkStart + chunkSize)
            }
        }

        return when {
            extensionClass == null -> null
            extensionClass.startsWith(".") -> "$packageName$extensionClass"
            else -> extensionClass
        }
    }

    /**
     * Parses a String Pool chunk. Advances [buf] to immediately after the chunk.
     * @return the list of decoded strings, or null on parse failure.
     */
    private fun parseStringPool(buf: ByteBuffer): List<String>? {
        val chunkStart = buf.position()
        if (buf.remaining() < 28) return null

        val type = buf.short.toInt() and 0xFFFF
        if (type != CHUNK_STRING_POOL) return null

        val headerSize = buf.short.toInt() and 0xFFFF
        val chunkSize = buf.int
        val stringCount = buf.int
        /* styleCount */ buf.int
        val flags = buf.int
        val stringsStart = buf.int
        /* stylesStart */ buf.int

        if (stringCount < 0 || stringCount > 65536) return null

        val isUtf8 = (flags and 0x100) != 0
        val offsets = IntArray(stringCount) { buf.int }

        val stringDataBase = chunkStart + stringsStart
        val strings = ArrayList<String>(stringCount)

        val savedPos = buf.position()
        for (offset in offsets) {
            val strPos = stringDataBase + offset
            if (strPos < 0 || strPos >= buf.capacity()) {
                strings.add("")
                continue
            }
            buf.position(strPos)
            strings.add(
                if (isUtf8) readUtf8String(buf) else readUtf16String(buf),
            )
        }
        buf.position(savedPos)
        buf.position(chunkStart + chunkSize)
        return strings
    }

    /** Reads a length-prefixed UTF-16LE string from the current buffer position. */
    private fun readUtf16String(buf: ByteBuffer): String {
        val len = buf.short.toInt() and 0xFFFF
        if (len == 0) return ""
        val chars = CharArray(len) { buf.char }
        return String(chars)
    }

    /**
     * Reads a length-prefixed UTF-8 string from the current buffer position.
     *
     * The Android string pool uses a 1- or 2-byte encoding for lengths:
     * - If high bit is set: 2-byte encoding (second byte holds the low 7 bits)
     * - Otherwise: 1-byte encoding
     */
    private fun readUtf8String(buf: ByteBuffer): String {
        // Skip UTF-16 character count (same encoding)
        readUtf8Len(buf)
        val byteLen = readUtf8Len(buf)
        if (byteLen == 0) return ""
        val bytes = ByteArray(byteLen) { buf.get() }
        return String(bytes, Charsets.UTF_8)
    }

    private fun readUtf8Len(buf: ByteBuffer): Int {
        val first = buf.get().toInt() and 0xFF
        return if (first and 0x80 != 0) {
            ((first and 0x7F) shl 8) or (buf.get().toInt() and 0xFF)
        } else {
            first
        }
    }

    /**
     * Processes a START_ELEMENT chunk.
     *
     * @return Pair(packageName, extensionClass) — either may be null if not found in this element.
     */
    private fun processStartElement(
        buf: ByteBuffer,
        chunkStart: Int,
        headerSize: Int,
        chunkSize: Int,
        strings: List<String>,
        extClassKeyIdx: Int,
        nameAttrIdx: Int,
        valueAttrIdx: Int,
        packageAttrIdx: Int,
    ): Pair<String?, String?> {
        // ResXMLTree_node: lineNumber (4) + comment (4) = 8 bytes after chunk header
        // ResXMLTree_attrExt: ns (4) + name (4) + attrStart (2) + attrSize (2) + attrCount (2) + 3×index (6) = 20
        val attrExtStart = chunkStart + 8 + 8 // node header (8) + ResXMLTree_node fields (8)
        if (attrExtStart + 20 > buf.capacity()) {
            buf.position(chunkStart + chunkSize)
            return Pair(null, null)
        }

        buf.position(attrExtStart)
        /* ns */ buf.int
        val elementName = readStringIndex(buf, strings)
        val attrStart = buf.short.toInt() and 0xFFFF
        val attrSize = buf.short.toInt() and 0xFFFF
        val attrCount = buf.short.toInt() and 0xFFFF
        buf.position(buf.position() + 6) // skip id/class/style indices

        val attrsBase = attrExtStart + attrStart
        if (attrSize < 20 || attrCount < 0 || attrsBase + attrCount.toLong() * attrSize > buf.capacity()) {
            buf.position(chunkStart + chunkSize)
            return Pair(null, null)
        }

        var packageName: String? = null
        var extensionClass: String? = null

        for (i in 0 until attrCount) {
            val attrPos = attrsBase + i * attrSize
            if (attrPos + 20 > buf.capacity()) break
            buf.position(attrPos)

            /* ns */ buf.int
            val attrName = buf.int   // raw string index (may include high byte for attr type)
            val rawAttrName = attrName and 0xFFFF
            /* rawValue */ buf.int
            /* size */ buf.short
            /* res0 */ buf.get()
            val dataType = buf.get().toInt() and 0xFF
            val dataValue = buf.int

            if (dataType != TYPE_STRING) continue

            when {
                // <manifest ... package="...">
                elementName == "manifest" && rawAttrName == packageAttrIdx -> {
                    packageName = strings.getOrNull(dataValue)
                }

                // <meta-data android:name="tachiyomi.extension.class" android:value="...">
                elementName == "meta-data" && rawAttrName == nameAttrIdx &&
                    dataValue == extClassKeyIdx -> {
                    // The value attribute for this element follows; find it in this attribute set
                    extensionClass = findAttributeValue(
                        buf, attrsBase, attrSize, attrCount, valueAttrIdx, strings,
                    )
                }
            }
        }

        buf.position(chunkStart + chunkSize)
        return Pair(packageName, extensionClass)
    }

    /** Reads a 4-byte raw string index and maps it to the string at that index. */
    private fun readStringIndex(buf: ByteBuffer, strings: List<String>): String {
        val idx = buf.int and 0xFFFF
        return strings.getOrElse(idx) { "" }
    }

    /**
     * Scans the attribute block for an attribute whose name index matches [nameIdx]
     * and returns its string value.
     */
    private fun findAttributeValue(
        buf: ByteBuffer,
        attrsBase: Int,
        attrSize: Int,
        attrCount: Int,
        nameIdx: Int,
        strings: List<String>,
    ): String? {
        val saved = buf.position()
        try {
            for (i in 0 until attrCount) {
                val pos = attrsBase + i * attrSize
                if (pos + 20 > buf.capacity()) break
                buf.position(pos)
                /* ns */ buf.int
                val attrName = buf.int and 0xFFFF
                /* rawValue */ buf.int
                /* size */ buf.short
                /* res0 */ buf.get()
                val dataType = buf.get().toInt() and 0xFF
                val dataValue = buf.int
                if (attrName == nameIdx && dataType == TYPE_STRING) {
                    return strings.getOrNull(dataValue)
                }
            }
        } finally {
            buf.position(saved)
        }
        return null
    }
}
