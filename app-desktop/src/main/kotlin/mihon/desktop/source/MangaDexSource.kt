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
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Built-in MangaDex source using the public MangaDex API v5.
 * No external extension JARs required.
 */
class MangaDexSource(
    private val client: OkHttpClient = Injekt.get(),
    private val json: Json = Injekt.get(),
    internal val baseUrl: String = "https://api.mangadex.org",
) : CatalogueSource {

    override val id: Long = 2499283573021220255L
    override val name: String = "MangaDex"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true

    private val coverBaseUrl = "https://uploads.mangadex.org/covers"

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
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        val body = response.body.string()
        val data = json.parseToJsonElement(body).jsonObject["data"]!!.jsonObject
        parseMangaObject(data)
    }

    // ── Chapters ──────────────────────────────────────────────────────────────

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        val mangaId = manga.url.removePrefix("/manga/")
        val chapters = mutableListOf<SChapter>()
        var offset = 0
        val limit = 96

        while (true) {
            val url = "$baseUrl/manga/$mangaId/feed" +
                "?limit=$limit&offset=$offset&translatedLanguage[]=en" +
                "&order[chapter]=desc&order[volume]=desc"
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            val obj = json.parseToJsonElement(response.body.string()).jsonObject
            val data = obj["data"]?.jsonArray ?: break

            for (item in data) {
                val ch = item.jsonObject
                val attrs = ch["attributes"]!!.jsonObject

                // Skip chapters that are external-only (e.g. MangaPlus links)
                val extEl = attrs["externalUrl"]
                if (extEl != null && extEl !is JsonNull && extEl.jsonPrimitive.content.isNotBlank()) continue

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
                sChapter.url = "/chapter/$chapterId"
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
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        val obj = json.parseToJsonElement(response.body.string()).jsonObject
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
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        val obj = json.parseToJsonElement(response.body.string()).jsonObject
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
}
