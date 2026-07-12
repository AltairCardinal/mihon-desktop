package mihon.desktop.source

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.reader.externalChapterUrl
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Built-in MangaDex source using the public MangaDex API v5.
 * No external extension JARs required.
 */
class MangaDexSource(
    private val client: OkHttpClient = Injekt.get(),
    private val json: Json = Injekt.get(),
    internal val baseUrl: String = "https://api.mangadex.org",
    private val browserJsonFetcher: MangaDexBrowserJsonFetcher? = DesktopMangaDexBrowserJsonFetcher(),
) : CatalogueSource {

    override val id: Long = 2499283573021220255L
    override val name: String = "MangaDex"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true

    private val coverBaseUrl = "https://uploads.mangadex.org/covers"
    private val requestHeaders = Headers.Builder()
        .set("User-Agent", "Mozilla/5.0 MihonDesktop")
        .set("Referer", "https://mangadex.org/")
        .build()

    fun getHeaders(): Headers = requestHeaders

    // ── Catalogue ────────────────────────────────────────────────────────────

    override suspend fun getPopularManga(page: Int): MangasPage {
        val offset = (page - 1) * 20
        val url = "$baseUrl/manga?limit=20&offset=$offset" +
            "&order[followedCount]=desc&includes[]=cover_art&contentRating[]=safe" +
            "&contentRating[]=suggestive"
        return withContext(Dispatchers.IO) { fetchMangaList(url) }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val offset = (page - 1) * 20
        val url = "$baseUrl/manga?limit=20&offset=$offset" +
            "&order[latestUploadedChapter]=desc&includes[]=cover_art&contentRating[]=safe" +
            "&contentRating[]=suggestive"
        return withContext(Dispatchers.IO) { fetchMangaList(url) }
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val offset = (page - 1) * 20
        val url = buildString {
            append("$baseUrl/manga?limit=20&offset=$offset")
            append("&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive")
            if (query.isNotBlank()) append("&title=${query.trim()}")
        }
        return withContext(Dispatchers.IO) { fetchMangaList(url) }
    }

    override fun getFilterList(): FilterList = FilterList()

    // ── Details ───────────────────────────────────────────────────────────────

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        val id = manga.url.removePrefix("/manga/")
        val url = "$baseUrl/manga/$id?includes[]=cover_art&includes[]=author&includes[]=artist"
        val body = fetchMangaDexJson(url)
        val data = json.parseToJsonElement(body).jsonObject["data"]!!.jsonObject
        parseMangaObject(data)
    }

    // ── Chapters ──────────────────────────────────────────────────────────────

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        val mangaId = manga.url.removePrefix("/manga/")
        val chapters = mutableListOf<SChapter>()
        var offset = 0
        val limit = 500

        while (true) {
            val url = "$baseUrl/manga/$mangaId/feed" +
                "?limit=$limit&offset=$offset" +
                "&order[chapter]=desc&order[volume]=desc"
            val obj = json.parseToJsonElement(fetchMangaDexJson(url)).jsonObject
            val data = obj["data"]?.jsonArray ?: break

            for (item in data) {
                val ch = item.jsonObject
                val attrs = ch["attributes"]!!.jsonObject

                val externalUrl = attrs["externalUrl"]
                    ?.takeUnless { it is JsonNull }
                    ?.jsonPrimitive
                    ?.content
                    ?.takeIf { it.isNotBlank() }

                val chapterId = ch["id"]!!.jsonPrimitive.content
                val vol = attrs["volume"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                val num = attrs["chapter"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                val chTitle = attrs["title"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                val scanlatorName = ch["relationships"]?.jsonArray
                    ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "scanlation_group" }
                    ?.jsonObject?.get("attributes")?.jsonObject?.get("name")?.jsonPrimitive?.content
                val chapterName = buildString {
                    if (vol != null) append("Vol.$vol ")
                    if (num != null) append("Ch.$num")
                    if (chTitle != null) append(": $chTitle")
                }.trim().ifEmpty { "Oneshot" }
                val sChapter = SChapter.create()
                sChapter.url = externalUrl?.let(::externalChapterUrl) ?: "/chapter/$chapterId"
                sChapter.name = chapterName
                sChapter.chapter_number = num?.toFloatOrNull() ?: -1f
                sChapter.date_upload = 0L
                sChapter.scanlator = scanlatorName
                chapters.add(sChapter)
            }

            val total = obj["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: break
            offset += limit
            if (offset >= total) break
        }
        chapters
    }

    // ── Pages ─────────────────────────────────────────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val chapterId = chapter.url.removePrefix("/chapter/")
        val url = "$baseUrl/at-home/server/$chapterId"
        val obj = json.parseToJsonElement(fetchMangaDexJson(url)).jsonObject
        val baseServerUrl = obj["baseUrl"]!!.jsonPrimitive.content
        val chapterObj = obj["chapter"]!!.jsonObject
        val hash = chapterObj["hash"]!!.jsonPrimitive.content

        // Prefer full-quality data; fall back to dataSaver if empty.
        val dataPages = chapterObj["data"]!!.jsonArray
        if (dataPages.isNotEmpty()) {
            dataPages.mapIndexed { index, pageEl ->
                val filename = pageEl.jsonPrimitive.content
                Page(index, imageUrl = "$baseServerUrl/data/$hash/$filename")
            }
        } else {
            val saverPages = chapterObj["dataSaver"]?.jsonArray ?: return@withContext emptyList()
            saverPages.mapIndexed { index, pageEl ->
                val filename = pageEl.jsonPrimitive.content
                Page(index, imageUrl = "$baseServerUrl/data-saver/$hash/$filename")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fetchMangaList(url: String): MangasPage {
        val obj = json.parseToJsonElement(fetchMangaDexJsonBlocking(url)).jsonObject
        val data = obj["data"]?.jsonArray ?: return MangasPage(emptyList(), false)
        val total = obj["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val offset = url.substringAfter("offset=").substringBefore("&").toIntOrNull() ?: 0

        val mangas = data.map { parseMangaObject(it.jsonObject) }
        return MangasPage(mangas, hasNextPage = offset + 20 < total)
    }

    private fun parseMangaObject(data: JsonObject): SManga {
        val id = data["id"]!!.jsonPrimitive.content
        val attrs = data["attributes"]!!.jsonObject

        // Title (prefer English, fall back to first available)
        val titleMap = attrs["title"]?.jsonObject
        val title = titleMap?.get("en")?.jsonPrimitive?.content
            ?: titleMap?.values?.firstOrNull()?.jsonPrimitive?.content
            ?: "Unknown"

        // Description
        val descMap = attrs["description"]?.jsonObject
        val description = descMap?.get("en")?.jsonPrimitive?.content
            ?: descMap?.values?.firstOrNull()?.jsonPrimitive?.content

        // Cover image
        val coverRel = data["relationships"]?.jsonArray
            ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "cover_art" }
            ?.jsonObject
        val coverFile = coverRel?.get("attributes")?.jsonObject?.get("fileName")?.jsonPrimitive?.content
        val thumbnailUrl = if (coverFile != null) "$coverBaseUrl/$id/$coverFile.256.jpg" else null

        // Authors
        val authors = data["relationships"]?.jsonArray
            ?.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "author" }
            ?.mapNotNull { it.jsonObject["attributes"]?.jsonObject?.get("name")?.jsonPrimitive?.content }
        val artists = data["relationships"]?.jsonArray
            ?.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "artist" }
            ?.mapNotNull { it.jsonObject["attributes"]?.jsonObject?.get("name")?.jsonPrimitive?.content }

        // Status
        val statusStr = attrs["status"]?.jsonPrimitive?.content
        val status = when (statusStr) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        // Genres from tags
        val genres = attrs["tags"]?.jsonArray
            ?.mapNotNull { it.jsonObject["attributes"]?.jsonObject?.get("name")?.jsonObject?.get("en")?.jsonPrimitive?.content }

        return SManga.create().apply {
            url = "/manga/$id"
            this.title = title
            this.description = description
            this.thumbnail_url = thumbnailUrl
            this.author = authors?.joinToString(", ")
            this.artist = artists?.joinToString(", ")
            this.status = status
            this.genre = genres?.joinToString(", ")
            this.initialized = true
        }
    }

    private fun mangaDexRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .headers(requestHeaders)
            .build()

    private suspend fun fetchMangaDexJson(url: String): String = withContext(Dispatchers.IO) {
        fetchMangaDexJsonBlocking(url)
    }

    private fun fetchMangaDexJsonBlocking(url: String): String {
        val responseBody = client.newCall(mangaDexRequest(url)).execute().use { response ->
            val body = response.body.string()
            if (response.isSuccessful && !body.isMangaDexUnsupportedBrowserPage()) {
                return body
            }
            body
        }
        if (responseBody.isMangaDexUnsupportedBrowserPage()) {
            browserJsonFetcher?.fetchBlocking(url)?.let { return it }
        }
        return responseBody
    }
}

fun interface MangaDexBrowserJsonFetcher {
    fun fetchBlocking(url: String): String?
}

class DesktopMangaDexBrowserJsonFetcher(
    private val executableCandidates: List<String> = defaultBrowserExecutableCandidates(),
) : MangaDexBrowserJsonFetcher {

    private val userDataDir by lazy {
        Files.createTempDirectory("mihon-mangadex-browser-profile-").also { directory ->
            Runtime.getRuntime().addShutdownHook(
                Thread { runCatching { directory.toFile().deleteRecursively() } },
            )
        }
    }

    @Synchronized
    override fun fetchBlocking(url: String): String? {
        val executable = executableCandidates
            .map(::File)
            .firstOrNull { it.isFile && it.canExecute() }
            ?: return null
        val outputFile = Files.createTempFile("mihon-mangadex-browser-", ".html")
        return try {
            val process = ProcessBuilder(
                executable.absolutePath,
                "--headless=new",
                "--disable-gpu",
                "--disable-background-networking",
                "--no-first-run",
                "--user-data-dir=${userDataDir.toAbsolutePath()}",
                "--dump-dom",
                url,
            )
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start()
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            val output = Files.readString(outputFile, StandardCharsets.UTF_8)
            output.extractJsonFromChromeDump()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { Files.deleteIfExists(outputFile) }
        }
    }
}

private fun String.isMangaDexUnsupportedBrowserPage(): Boolean =
    contains("Unsupported Browser", ignoreCase = true) &&
        contains("<html", ignoreCase = true)

private fun String.extractJsonFromChromeDump(): String? {
    val preBody = Regex("""(?is)<pre[^>]*>(.*?)</pre>""")
        .find(this)
        ?.groupValues
        ?.get(1)
    val candidate = (preBody ?: this).htmlEntityDecode().trim()
    return candidate.takeIf { it.startsWith("{") || it.startsWith("[") }
}

private fun String.htmlEntityDecode(): String =
    replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

private fun defaultBrowserExecutableCandidates(): List<String> {
    val envChrome = System.getenv("MIHON_CHROME_PATH")
    val programFiles = System.getenv("ProgramFiles")
    val programFilesX86 = System.getenv("ProgramFiles(x86)")
    val localAppData = System.getenv("LOCALAPPDATA")
    return listOfNotNull(
        envChrome,
        programFiles?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
        programFilesX86?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
        localAppData?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
        programFiles?.let { "$it\\Microsoft\\Edge\\Application\\msedge.exe" },
        programFilesX86?.let { "$it\\Microsoft\\Edge\\Application\\msedge.exe" },
        localAppData?.let { "$it\\Microsoft\\Edge\\Application\\msedge.exe" },
        rootPath("Applications", "Google Chrome.app", "Contents", "MacOS", "Google Chrome"),
        rootPath("Applications", "Microsoft Edge.app", "Contents", "MacOS", "Microsoft Edge"),
        rootPath("usr", "bin", "google-chrome"),
        rootPath("usr", "bin", "google-chrome-stable"),
        rootPath("usr", "bin", "chromium"),
        rootPath("usr", "bin", "chromium-browser"),
        rootPath("usr", "bin", "microsoft-edge"),
    ).distinct()
}

private fun rootPath(vararg parts: String): String =
    File(File.separator + parts.joinToString(File.separator)).path
