package eu.kanade.tachiyomi.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [CookieJar] for the desktop OkHttp client.
 *
 * When [storageFile] is provided, cookies are persisted to disk as JSON and
 * restored on construction.  Session-only cookies (no explicit expiry) and
 * already-expired cookies are never persisted.
 *
 * Thread-safety: mutations are synchronised on [cookieStore] before the
 * optional disk flush; reads use the concurrent map directly.
 */
class DesktopCookieJar(
    private val storageFile: File? = null,
) : CookieJar {

    private val cookieStore = ConcurrentHashMap<String, MutableMap<String, Cookie>>()

    init {
        if (storageFile != null) loadFromDisk()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val domain = url.host
        val domainCookies = cookieStore.getOrPut(domain) { mutableMapOf() }
        for (cookie in cookies) {
            domainCookies[cookie.name] = cookie
        }
        persistToDisk()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val domain = url.host
        return cookieStore[domain]?.values?.toList() ?: emptyList()
    }

    fun get(url: HttpUrl): List<Cookie> = loadForRequest(url)

    fun remove(url: HttpUrl, cookieNames: List<String>) {
        val domain = url.host
        val domainCookies = cookieStore[domain] ?: return
        for (name in cookieNames) {
            domainCookies.remove(name)
        }
        persistToDisk()
    }

    fun addManual(url: HttpUrl, name: String, value: String) {
        val cookie = Cookie.Builder()
            .name(name)
            .value(value)
            .domain(url.host)
            .path("/")
            .expiresAt(System.currentTimeMillis() + 365L * 24 * 3600 * 1000) // 1 year
            .build()
        val domainCookies = cookieStore.getOrPut(url.host) { mutableMapOf() }
        domainCookies[name] = cookie
        persistToDisk()
    }

    fun clear() {
        cookieStore.clear()
        persistToDisk()
    }

    /** Removes all cookies for the supplied hosts and their subdomains. */
    fun clearDomains(domains: Set<String>): Int {
        val normalized = domains.map { it.lowercase().trimStart('.') }.filter { it.isNotBlank() }.toSet()
        val matching = cookieStore.keys.filter { stored ->
            normalized.any { domain -> stored.equals(domain, true) || stored.endsWith(".$domain", true) }
        }
        matching.forEach(cookieStore::remove)
        if (matching.isNotEmpty()) persistToDisk()
        return matching.size
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    private fun loadFromDisk() {
        val file = storageFile ?: return
        if (!file.exists()) return
        try {
            val persisted = json.decodeFromString<Map<String, List<PersistedCookie>>>(file.readText())
            val now = System.currentTimeMillis()
            for ((domain, cookies) in persisted) {
                val domainMap = mutableMapOf<String, Cookie>()
                for (pc in cookies) {
                    if (pc.expiresAt > now) { // skip expired
                        domainMap[pc.name] = pc.toCookie()
                    }
                }
                if (domainMap.isNotEmpty()) {
                    cookieStore[domain] = domainMap
                }
            }
        } catch (_: Exception) {
            // Corrupt or unreadable file — start fresh
        }
    }

    private fun persistToDisk() {
        val file = storageFile ?: return
        try {
            file.parentFile?.mkdirs()
            val now = System.currentTimeMillis()
            val toSave = cookieStore.entries.associate { (domain, cookies) ->
                domain to cookies.values
                    .filter { it.persistent && it.expiresAt > now }
                    .map { PersistedCookie.from(it) }
            }.filterValues { it.isNotEmpty() }
            file.writeText(json.encodeToString(toSave))
        } catch (_: Exception) {
            // Best-effort: ignore I/O failures silently
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class PersistedCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
) {
    fun toCookie(): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .domain(domain)
        .path(path)
        .expiresAt(expiresAt)
        .apply { if (secure) secure() }
        .apply { if (httpOnly) httpOnly() }
        .build()

    companion object {
        fun from(cookie: Cookie) = PersistedCookie(
            name = cookie.name,
            value = cookie.value,
            domain = cookie.domain,
            path = cookie.path,
            expiresAt = cookie.expiresAt,
            secure = cookie.secure,
            httpOnly = cookie.httpOnly,
        )
    }
}
