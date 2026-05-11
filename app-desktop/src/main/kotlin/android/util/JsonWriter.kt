package android.util

import java.io.Writer

/**
 * Desktop stub for android.util.JsonWriter.
 * Implements a minimal streaming JSON writer backed by java.io.Writer.
 * Produces compact JSON output compatible with Android's JsonWriter API.
 */
class JsonWriter(private val writer: Writer) : AutoCloseable {

    private var needsComma: Boolean = false
    private var indentLevel: Int = 0
    private var htmlSafe: Boolean = false
    private var lenient: Boolean = false

    fun setIndent(indent: String): JsonWriter { return this }
    fun setHtmlSafe(safe: Boolean): JsonWriter { htmlSafe = safe; return this }
    fun setLenient(lenient: Boolean): JsonWriter { this.lenient = lenient; return this }
    fun isHtmlSafe(): Boolean = htmlSafe
    fun isLenient(): Boolean = lenient

    fun beginObject(): JsonWriter {
        beforeValue()
        writer.write("{")
        needsComma = false
        return this
    }

    fun endObject(): JsonWriter {
        writer.write("}")
        needsComma = true
        return this
    }

    fun beginArray(): JsonWriter {
        beforeValue()
        writer.write("[")
        needsComma = false
        return this
    }

    fun endArray(): JsonWriter {
        writer.write("]")
        needsComma = true
        return this
    }

    fun name(name: String): JsonWriter {
        if (needsComma) writer.write(",")
        needsComma = false
        writer.write(escapeString(name, quoted = true))
        writer.write(":")
        return this
    }

    fun value(value: String?): JsonWriter {
        if (value == null) return nullValue()
        beforeValue()
        writer.write(escapeString(value, quoted = true))
        return this
    }

    fun value(value: Boolean): JsonWriter {
        beforeValue()
        writer.write(if (value) "true" else "false")
        return this
    }

    fun value(value: Boolean?): JsonWriter {
        if (value == null) return nullValue()
        return value(value.booleanValue())
    }

    fun value(value: Double): JsonWriter {
        beforeValue()
        writer.write(value.toString())
        return this
    }

    fun value(value: Long): JsonWriter {
        beforeValue()
        writer.write(value.toString())
        return this
    }

    fun value(value: Number?): JsonWriter {
        if (value == null) return nullValue()
        beforeValue()
        writer.write(value.toString())
        return this
    }

    fun nullValue(): JsonWriter {
        beforeValue()
        writer.write("null")
        return this
    }

    fun jsonValue(value: String?): JsonWriter {
        if (value == null) return nullValue()
        beforeValue()
        writer.write(value)
        return this
    }

    fun flush() { writer.flush() }

    override fun close() {
        writer.flush()
        writer.close()
    }

    // --- Internal helpers ---

    private fun beforeValue() {
        if (needsComma) writer.write(",")
        needsComma = true
    }

    private fun escapeString(value: String, quoted: Boolean): String {
        val sb = StringBuilder()
        if (quoted) sb.append('"')
        for (ch in value) {
            when (ch) {
                '"'  -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        if (quoted) sb.append('"')
        return sb.toString()
    }
}

private fun Boolean.booleanValue(): Boolean = this
