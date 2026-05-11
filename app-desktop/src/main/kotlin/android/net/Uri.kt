package android.net

import java.net.URI

/**
 * Desktop stub for android.net.Uri.
 * Wraps java.net.URI to provide the most commonly used subset of the Android API.
 */
class Uri private constructor(private val uri: URI) {

    val scheme: String? get() = uri.scheme
    val host: String? get() = uri.host
    val path: String? get() = uri.path
    val query: String? get() = uri.query
    val fragment: String? get() = uri.fragment
    val port: Int get() = uri.port
    val authority: String? get() = uri.authority

    fun getQueryParameter(key: String): String? {
        val q = uri.query ?: return null
        return q.split("&").firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    }

    fun getQueryParameters(key: String): List<String> {
        val q = uri.query ?: return emptyList()
        return q.split("&").filter { it.startsWith("$key=") }
            .map { java.net.URLDecoder.decode(it.substringAfter("="), "UTF-8") }
    }

    fun buildUpon(): Builder = Builder(uri.toString())

    override fun toString(): String = uri.toString()

    companion object {
        @JvmStatic
        fun parse(uriString: String): Uri = Uri(URI.create(uriString))

        @JvmStatic
        fun fromParts(scheme: String, ssp: String, fragment: String?): Uri =
            Uri(URI(scheme, ssp, fragment))

        @JvmStatic
        fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

        @JvmStatic
        fun decode(s: String): String = java.net.URLDecoder.decode(s, "UTF-8")

        @JvmStatic
        fun withAppendedPath(base: Uri, segment: String): Uri {
            val baseStr = base.toString().trimEnd('/')
            return parse("$baseStr/$segment")
        }
    }

    class Builder(private var uriStr: String) {
        private val queryParams = mutableListOf<Pair<String, String>>()

        fun scheme(scheme: String) = apply {
            uriStr = uriStr.replaceFirst(Regex("^[^:]+"), scheme)
        }

        fun appendPath(segment: String) = apply {
            uriStr = uriStr.trimEnd('/') + "/" + segment
        }

        fun appendQueryParameter(key: String, value: String) = apply {
            queryParams.add(key to value)
        }

        fun build(): Uri {
            val base = uriStr.substringBefore("?")
            return if (queryParams.isEmpty()) {
                parse(uriStr)
            } else {
                val existing = if ("?" in uriStr) uriStr.substringAfter("?") else ""
                val allParams = (
                    (if (existing.isNotEmpty()) listOf(existing) else emptyList()) +
                        queryParams.map { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
                    ).joinToString("&")
                parse("$base?$allParams")
            }
        }

        override fun toString(): String = build().toString()
    }
}
