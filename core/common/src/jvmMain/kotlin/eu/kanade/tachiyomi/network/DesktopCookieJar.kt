package eu.kanade.tachiyomi.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [CookieJar] for the desktop OkHttp client.
 *
 * When [storageFile] is provided, cookies are persisted to disk as JSON and
 * restored on construction.  Session-only cookies (no explicit expiry) and
 * already-expired cookies are never persisted.
 *
 * Thread-safety: reads and mutations share one lock so a complete login
 * session cannot be observed or persisted partially.
 */
class DesktopCookieJar(
    private val storageFile: File? = null,
    private val persistenceReplace: (source: Path, target: Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    },
) : CookieJar {

    private val cookieStore = ConcurrentHashMap<String, MutableMap<String, Cookie>>()
    private val mutationLock = Any()

    init {
        if (storageFile != null) loadFromDisk()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(mutationLock) {
            val domain = url.host
            val domainCookies = cookieStore.getOrPut(domain) { mutableMapOf() }
            for (cookie in cookies) {
                domainCookies[cookie.name] = cookie
            }
            persistToDisk()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val domain = url.host
        return synchronized(mutationLock) { cookieStore[domain]?.values?.toList() ?: emptyList() }
    }

    fun get(url: HttpUrl): List<Cookie> = loadForRequest(url)

    fun remove(url: HttpUrl, cookieNames: List<String>) {
        synchronized(mutationLock) {
            val domain = url.host
            val domainCookies = cookieStore[domain] ?: return
            for (name in cookieNames) {
                domainCookies.remove(name)
            }
            persistToDisk()
        }
    }

    fun addManual(url: HttpUrl, name: String, value: String) {
        val cookie = Cookie.Builder()
            .name(name)
            .value(value)
            .domain(url.host)
            .path("/")
            .expiresAt(System.currentTimeMillis() + 365L * 24 * 3600 * 1000) // 1 year
            .build()
        synchronized(mutationLock) {
            val domainCookies = cookieStore.getOrPut(url.host) { mutableMapOf() }
            domainCookies[name] = cookie
            persistToDisk()
        }
    }

    fun clear() {
        synchronized(mutationLock) {
            cookieStore.clear()
            persistToDisk()
        }
    }

    /** Removes all cookies for the supplied hosts and their subdomains. */
    fun clearDomains(domains: Set<String>): Int {
        return synchronized(mutationLock) {
            val normalized = domains.map { it.lowercase().trimStart('.') }.filter { it.isNotBlank() }.toSet()
            val matching = cookieStore.keys.filter { stored ->
                normalized.any { domain -> stored.equals(domain, true) || stored.endsWith(".$domain", true) }
            }
            matching.forEach(cookieStore::remove)
            if (matching.isNotEmpty()) persistToDisk()
            matching.size
        }
    }

    /** Replaces a login target's complete cookie set and persists it as one transaction. */
    @Throws(Exception::class)
    fun commitAuthenticatedSession(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(mutationLock) {
            val domain = url.host
            val previous = cookieStore[domain]?.toMutableMap()
            if (cookies.isEmpty()) {
                cookieStore.remove(domain)
            } else {
                cookieStore[domain] = cookies.associateByTo(linkedMapOf()) { it.name }
            }
            try {
                persistToDiskOrThrow()
            } catch (error: Exception) {
                if (previous == null) cookieStore.remove(domain) else cookieStore[domain] = previous
                throw error
            }
        }
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
        try {
            persistToDiskOrThrow()
        } catch (_: Exception) {
            // Best-effort: ignore I/O failures silently
        }
    }

    private fun persistToDiskOrThrow() {
        val file = storageFile ?: return
        val parent = file.absoluteFile.parentFile
        parent?.mkdirs()
        val now = System.currentTimeMillis()
        val toSave = cookieStore.entries.associate { (domain, cookies) ->
            domain to cookies.values
                .filter { it.persistent && it.expiresAt > now }
                .map { PersistedCookie.from(it) }
        }.filterValues { it.isNotEmpty() }
        val temporary = File.createTempFile("${file.name}.", ".tmp", parent)
        try {
            temporary.writeText(json.encodeToString(toSave))
            persistenceReplace(temporary.toPath(), file.toPath())
        } finally {
            temporary.delete()
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
