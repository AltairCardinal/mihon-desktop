package android.util

import java.io.Reader

/**
 * Desktop stub for android.util.JsonReader.
 * Implements a minimal streaming JSON parser using a hand-rolled state machine
 * over java.io.PushbackReader. Handles flat objects, arrays, and nested structures
 * sufficient for the typical use-cases in Android extensions.
 */
class JsonReader(reader: Reader) : AutoCloseable {

    private val input = java.io.PushbackReader(reader, 8)
    var isLenient: Boolean = false

    // --- Internal state ---

    private val stateStack = ArrayDeque<Int>()
    private val STATE_OBJECT = 0
    private val STATE_ARRAY  = 1

    // --- Public API ---

    fun beginObject() {
        skipWs()
        expect('{')
        stateStack.addLast(STATE_OBJECT)
    }

    fun endObject() {
        skipWs()
        expect('}')
        stateStack.removeLastOrNull()
    }

    fun beginArray() {
        skipWs()
        expect('[')
        stateStack.addLast(STATE_ARRAY)
    }

    fun endArray() {
        skipWs()
        expect(']')
        stateStack.removeLastOrNull()
    }

    fun hasNext(): Boolean {
        skipWs()
        val ch = peekChar()
        return ch != '}' && ch != ']' && ch.code != -1
    }

    fun nextName(): String {
        skipWs()
        consumeCommaIfPresent()
        skipWs()
        val name = readString()
        skipWs()
        expect(':')
        return name
    }

    fun nextString(): String {
        skipWs()
        consumeCommaIfPresent()
        skipWs()
        return when (peekChar()) {
            '"' -> readString()
            'n' -> { readLiteral("null"); "" }
            else -> readUnquotedValue()
        }
    }

    fun nextInt(): Int = nextString().trim().toInt()

    fun nextLong(): Long = nextString().trim().toLong()

    fun nextDouble(): Double = nextString().trim().toDouble()

    fun nextBoolean(): Boolean = nextString().trim().toBooleanStrict()

    fun nextNull() {
        skipWs()
        consumeCommaIfPresent()
        skipWs()
        readLiteral("null")
    }

    fun skipValue() {
        skipWs()
        consumeCommaIfPresent()
        skipWs()
        skipJsonValue()
    }

    fun peek(): JsonToken {
        skipWs()
        return when (peekChar()) {
            '{' -> JsonToken.BEGIN_OBJECT
            '}' -> JsonToken.END_OBJECT
            '[' -> JsonToken.BEGIN_ARRAY
            ']' -> JsonToken.END_ARRAY
            '"' -> JsonToken.STRING
            't', 'f' -> JsonToken.BOOLEAN
            'n' -> JsonToken.NULL
            else -> if (peekChar().code == -1) JsonToken.END_DOCUMENT else JsonToken.NUMBER
        }
    }

    override fun close() { input.close() }

    // --- Low-level helpers ---

    private fun readRaw(): Int = input.read()

    private fun peekChar(): Char {
        val ch = input.read()
        if (ch != -1) input.unread(ch)
        return ch.toChar()
    }

    private fun expect(expected: Char) {
        val ch = readRaw()
        if (ch.toChar() != expected) {
            throw IllegalStateException("Expected '$expected' but got '${ch.toChar()}'")
        }
    }

    private fun skipWs() {
        while (true) {
            val ch = readRaw()
            if (ch == -1) return
            if (ch.toChar() !in " \t\r\n") {
                input.unread(ch)
                return
            }
        }
    }

    private fun consumeCommaIfPresent() {
        if (peekChar() == ',') readRaw()
    }

    private fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val ch = readRaw()
            when {
                ch == -1 -> throw IllegalStateException("Unterminated string")
                ch.toChar() == '"' -> return sb.toString()
                ch.toChar() == '\\' -> {
                    val esc = readRaw()
                    when (esc.toChar()) {
                        '"'  -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/'  -> sb.append('/')
                        'b'  -> sb.append('\b')
                        'f'  -> sb.append('\u000C')
                        'n'  -> sb.append('\n')
                        'r'  -> sb.append('\r')
                        't'  -> sb.append('\t')
                        'u'  -> {
                            val hex = CharArray(4) { readRaw().toChar() }.concatToString()
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> sb.append(esc.toChar())
                    }
                }
                else -> sb.append(ch.toChar())
            }
        }
    }

    private fun readUnquotedValue(): String {
        val sb = StringBuilder()
        while (true) {
            val ch = peekChar()
            if (ch == ',' || ch == '}' || ch == ']' || ch.code == -1 || ch.isWhitespace()) break
            sb.append(readRaw().toChar())
        }
        return sb.toString()
    }

    private fun readLiteral(expected: String) {
        for (c in expected) {
            val ch = readRaw().toChar()
            if (ch != c) throw IllegalStateException("Expected literal '$expected'")
        }
    }

    private fun skipJsonValue() {
        when (peekChar()) {
            '"' -> readString()
            '{' -> {
                beginObject()
                while (hasNext()) { nextName(); skipValue() }
                endObject()
            }
            '[' -> {
                beginArray()
                while (hasNext()) skipValue()
                endArray()
            }
            't' -> readLiteral("true")
            'f' -> readLiteral("false")
            'n' -> readLiteral("null")
            else -> readUnquotedValue()
        }
    }

    enum class JsonToken {
        BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY,
        NAME, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT
    }
}
