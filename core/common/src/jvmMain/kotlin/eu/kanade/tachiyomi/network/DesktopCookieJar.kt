package eu.kanade.tachiyomi.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
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

    private val cookieStore = ConcurrentHashMap<String, MutableMap<CookieIdentity, Cookie>>()
    private val mutationLock = Any()

    init {
        if (storageFile != null) loadFromDisk()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(mutationLock) {
            for (cookie in cookies) {
                putCookie(url.host, cookie)
            }
            persistToDisk()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return synchronized(mutationLock) {
            cookieStore.values
                .asSequence()
                .flatMap { it.values.asSequence() }
                .filter { it.expiresAt > now && it.matches(url) }
                .sortedWith(
                    compareByDescending<Cookie> { it.path.length }
                        .thenBy { it.name }
                        .thenBy { it.domain }
                        .thenBy { it.path },
                )
                .toList()
        }
    }

    fun get(url: HttpUrl): List<Cookie> = loadForRequest(url)

    fun remove(url: HttpUrl, cookieNames: List<String>) {
        synchronized(mutationLock) {
            val domain = url.host
            val domainCookies = cookieStore[domain] ?: return
            for (name in cookieNames) {
                domainCookies.keys.removeAll { it.name == name }
            }
            if (domainCookies.isEmpty()) cookieStore.remove(domain)
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
            putCookie(url.host, cookie)
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
            val previousStore = snapshotCookieStore()
            val previousFile = snapshotStorageFile()
            removeCookiesDeliverableToHost(domain)
            cookies.forEach { putCookie(domain, it) }
            try {
                persistToDiskOrThrow()
            } catch (error: Exception) {
                restoreCookieStore(previousStore)
                try {
                    restoreStorageFile(previousFile)
                } catch (rollbackError: Exception) {
                    error.addSuppressed(rollbackError)
                }
                throw error
            }
        }
    }

    private fun putCookie(bucket: String, cookie: Cookie) {
        val identity = CookieIdentity.from(cookie)
        cookieStore.values.forEach { it.remove(identity) }
        cookieStore.entries.removeIf { it.value.isEmpty() }
        cookieStore.getOrPut(bucket) { linkedMapOf() }[identity] = cookie
    }

    private fun removeCookiesDeliverableToHost(targetHost: String) {
        val normalizedTarget = targetHost.lowercase().trimStart('.')
        cookieStore.values.forEach { cookies ->
            cookies.values.removeAll { cookie ->
                val cookieDomain = cookie.domain.lowercase().trimStart('.')
                if (cookie.hostOnly) {
                    normalizedTarget == cookieDomain
                } else {
                    normalizedTarget == cookieDomain || normalizedTarget.endsWith(".$cookieDomain")
                }
            }
        }
        cookieStore.entries.removeIf { it.value.isEmpty() }
    }

    private fun snapshotCookieStore(): Map<String, MutableMap<CookieIdentity, Cookie>> =
        cookieStore.mapValues { (_, cookies) -> cookies.toMutableMap() }

    private fun restoreCookieStore(snapshot: Map<String, MutableMap<CookieIdentity, Cookie>>) {
        cookieStore.clear()
        snapshot.forEach { (domain, cookies) -> cookieStore[domain] = cookies.toMutableMap() }
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    private fun loadFromDisk() {
        val file = storageFile ?: return
        if (!file.exists()) return
        try {
            val persisted = json.decodeFromString<Map<String, List<PersistedCookie>>>(file.readText())
            val now = System.currentTimeMillis()
            for ((domain, cookies) in persisted) {
                for (pc in cookies) {
                    if (pc.expiresAt > now) { // skip expired
                        putCookie(domain, pc.toCookie())
                    }
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

    private fun snapshotStorageFile(): StorageFileSnapshot? {
        val file = storageFile ?: return null
        val target = file.toPath()
        return if (Files.exists(target)) {
            StorageFileSnapshot(existed = true, bytes = Files.readAllBytes(target))
        } else {
            StorageFileSnapshot(existed = false)
        }
    }

    private fun restoreStorageFile(snapshot: StorageFileSnapshot?) {
        val file = storageFile ?: return
        val state = snapshot ?: return
        val target = file.toPath()
        if (!state.existed) {
            Files.deleteIfExists(target)
            return
        }

        val parent = file.absoluteFile.parentFile
        parent?.mkdirs()
        val temporary = File.createTempFile("${file.name}.rollback.", ".tmp", parent)
        try {
            Files.write(temporary.toPath(), checkNotNull(state.bytes))
            try {
                Files.move(
                    temporary.toPath(),
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}

private data class CookieIdentity(
    val name: String,
    val domain: String,
    val path: String,
) {
    companion object {
        fun from(cookie: Cookie) = CookieIdentity(
            name = cookie.name,
            domain = cookie.domain.lowercase().trimStart('.'),
            path = cookie.path,
        )
    }
}

private data class StorageFileSnapshot(
    val existed: Boolean,
    val bytes: ByteArray? = null,
)

@Serializable
private data class PersistedCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean = false,
) {
    fun toCookie(): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .apply { if (hostOnly) hostOnlyDomain(domain) else domain(domain) }
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
            hostOnly = cookie.hostOnly,
        )
    }
}
