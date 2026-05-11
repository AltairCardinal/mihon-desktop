package android.util

import java.util.Base64 as JavaBase64

/**
 * Desktop stub for android.util.Base64.
 * Delegates to java.util.Base64 — same RFC 4648 encoding.
 */
object Base64 {
    const val DEFAULT = 0
    const val NO_WRAP = 2
    const val URL_SAFE = 8

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        val encoded = if (flags and URL_SAFE != 0) {
            JavaBase64.getUrlEncoder().encodeToString(input)
        } else {
            JavaBase64.getEncoder().encodeToString(input)
        }
        return if (flags and NO_WRAP != 0) encoded.replace("\n", "") else encoded
    }

    @JvmStatic
    fun encode(input: ByteArray, flags: Int): ByteArray =
        encodeToString(input, flags).toByteArray(Charsets.US_ASCII)

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray = decodeInternal(str.trim(), flags)

    @JvmStatic
    fun decode(input: ByteArray, flags: Int): ByteArray = decode(String(input, Charsets.US_ASCII), flags)

    private fun decodeInternal(str: String, flags: Int): ByteArray =
        if (flags and URL_SAFE != 0) {
            JavaBase64.getUrlDecoder().decode(str)
        } else {
            JavaBase64.getDecoder().decode(str)
        }
}
