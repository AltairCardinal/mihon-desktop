package mihon.desktop.tracking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.service.EnhancedTrackerContext
import tachiyomi.domain.track.service.EnhancedTrackerContextProvider
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackSearchResult
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerProfile
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.domain.track.service.trackerProviderHttpError
import java.io.IOException
import kotlin.math.max

internal fun enhancedTrackerServices(
    client: OkHttpClient,
    json: Json,
    contexts: EnhancedTrackerContextProvider,
    sourceClient: (Long) -> OkHttpClient? = { null },
): List<TrackerService> = listOf(
    DesktopEnhancedTrackerService(6, "Komga", client, json, contexts, sourceClient),
    DesktopEnhancedTrackerService(8, "Kavita", client, json, contexts, sourceClient),
    DesktopEnhancedTrackerService(9, "Suwayomi", client, json, contexts, sourceClient),
)

private class DesktopEnhancedTrackerService(
    private val trackerId: Long,
    private val trackerName: String,
    private val fallbackClient: OkHttpClient,
    private val json: Json,
    private val contextProvider: EnhancedTrackerContextProvider,
    private val sourceClient: (Long) -> OkHttpClient?,
) : TrackerService {
    private val mutableProfile = MutableStateFlow(profile(contextProvider.contexts.value))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            contextProvider.contexts.collect { mutableProfile.value = profile(it) }
        }
    }

    override val profile: StateFlow<TrackerProfile> = mutableProfile
    override val statuses = listOf(1L to "Unread", 2L to "Reading", 3L to "Completed")
    override val scores = emptyList<Double>()

    override suspend fun search(query: String): List<TrackSearchResult> {
        val context = contextFor(query)
        return listOf(
            when (trackerId) {
                6L -> komgaSearch(context, query)
                8L -> kavitaSearch(context, query)
                9L -> suwayomiSearch(context, mangaId(query))
                else -> error("Unsupported enhanced tracker $trackerId")
            },
        )
    }

    override suspend fun bind(mangaId: Long, result: TrackSearchResult): Track = Track(
        id = 0,
        mangaId = mangaId,
        trackerId = trackerId,
        remoteId = result.remoteId,
        libraryId = null,
        title = result.title,
        lastChapterRead = result.lastChapterRead,
        totalChapters = result.totalChapters,
        status = result.status ?: 1L,
        score = 0.0,
        remoteUrl = result.remoteUrl,
        startDate = 0,
        finishDate = 0,
        private = false,
    )

    override suspend fun update(track: Track, edit: TrackEdit): Track {
        val desired = track.copy(
            status = edit.status ?: track.status,
            score = edit.score ?: track.score,
            lastChapterRead = edit.lastChapterRead ?: track.lastChapterRead,
        )
        val context = contextFor(track.remoteUrl)
        return when (trackerId) {
            6L -> {
                val progressUrl = komgaProgressUrl(track.remoteUrl)
                val key = if (track.remoteUrl.contains("/api/v1/series/")) "lastBookNumberSortRead" else "lastBookRead"
                execute(context, Request.Builder().url(progressUrl).put(buildJsonObject { put(key, desired.lastChapterRead) }.toString().toRequestBody(JSON)).build())
                bind(track.mangaId, komgaSearch(context, track.remoteUrl)).copy(id = track.id)
            }
            8L -> {
                val token = kavitaToken(context)
                val seriesId = track.remoteUrl.substringAfterLast('/').toLong()
                val api = context.baseUrl.trimEnd('/')
                execute(
                    context,
                    Request.Builder().url("$api/Tachiyomi/mark-chapter-until-as-read?seriesId=$seriesId&chapterNumber=${desired.lastChapterRead}")
                        .header("Authorization", "Bearer $token").post(EMPTY_JSON).build(),
                )
                bind(track.mangaId, kavitaSearch(context, track.remoteUrl)).copy(id = track.id)
            }
            9L -> {
                suwayomiUpdate(context, desired)
                bind(track.mangaId, suwayomiSearch(context, track.remoteId)).copy(id = track.id)
            }
            else -> error("Unsupported enhanced tracker $trackerId")
        }
    }

    override suspend fun logout() = Unit

    private fun profile(contexts: List<EnhancedTrackerContext>): TrackerProfile {
        val configured = contexts.any { it.trackerId == trackerId && it.configured && (trackerId != 8L || !it.apiKey.isNullOrBlank()) }
        return TrackerProfile(
            id = trackerId,
            name = trackerName,
            authentication = if (trackerId == 8L) TrackerAuthentication.API_KEY else TrackerAuthentication.API_KEY,
            loggedIn = configured,
            unavailableReason = if (configured) null else "$trackerName requires an installed and fully configured matching source.",
        )
    }

    private fun contextFor(value: String): EnhancedTrackerContext {
        contextProvider.refresh()
        val candidates = contextProvider.contexts.value.filter {
            it.trackerId == trackerId && it.configured && (trackerId != 8L || !it.apiKey.isNullOrBlank())
        }
        return candidates.maxByOrNull { context -> if (value.startsWith(context.baseUrl.trimEnd('/'))) context.baseUrl.length else -1 }
            ?: error(profile.value.unavailableReason ?: "$trackerName source configuration is unavailable")
    }

    private suspend fun komgaSearch(context: EnhancedTrackerContext, url: String): TrackSearchResult {
        require(url.startsWith(context.baseUrl.trimEnd('/'))) { "Komga manga URL does not belong to the configured source" }
        val item = parse(execute(context, Request.Builder().url(url).get().build())).jsonObject
        val progress = parse(execute(context, Request.Builder().url(komgaProgressUrl(url)).get().build())).jsonObject
        val title = item["metadata"]?.jsonObject?.string("title") ?: item.string("name")
            ?: throw IllegalArgumentException("Komga response is missing title")
        val total = progress.long("maxNumberSort").takeIf { it > 0 } ?: progress.long("booksCount")
        val read = progress.double("lastReadContinuousNumberSort") ?: progress.double("lastReadContinuousIndex") ?: 0.0
        val count = progress.long("booksCount")
        val unread = progress.long("booksUnreadCount")
        val readCount = progress.long("booksReadCount")
        return TrackSearchResult(
            remoteId = stableRemoteId(url),
            title = title,
            totalChapters = total,
            remoteUrl = url,
            coverUrl = "$url/thumbnail",
            summary = item["metadata"]?.jsonObject?.string("summary").orEmpty(),
            status = when (count) { unread -> 1L; readCount -> 3L; else -> 2L },
            lastChapterRead = read,
        )
    }

    private suspend fun kavitaSearch(context: EnhancedTrackerContext, url: String): TrackSearchResult {
        require(url.startsWith(context.baseUrl.substringBefore("/api").trimEnd('/'))) { "Kavita manga URL does not belong to the configured source" }
        val token = kavitaToken(context)
        val auth = { request: Request -> request.newBuilder().header("Authorization", "Bearer $token").build() }
        val series = parse(execute(context, auth(Request.Builder().url(url).get().build()))).jsonObject
        val seriesId = url.substringAfterLast('/').toLong()
        val api = context.baseUrl.substringBeforeLast("/api", context.baseUrl).trimEnd('/') + "/api"
        val volumes = parse(execute(context, auth(Request.Builder().url("$api/Series/volumes?seriesId=$seriesId").get().build()))).jsonArray
        var volumeCount = 0L
        var maxChapter = 0L
        volumes.forEach { volume ->
            val numbers = volume.jsonObject["chapters"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject.string("number")?.replace(',', '.')?.toDoubleOrNull() }
            if (numbers.maxOrNull() == 0.0) volumeCount++ else maxChapter = max(maxChapter, numbers.maxOrNull()?.toLong() ?: 0)
        }
        val latestResponse = execute(context, auth(Request.Builder().url("$api/Tachiyomi/latest-chapter?seriesId=$seriesId").get().build()), allowNoContent = true)
        val latest = latestResponse.takeIf(String::isNotBlank)?.let { parse(it).jsonObject.string("number")?.replace(',', '.')?.toDoubleOrNull() } ?: 0.0
        val pages = series.long("pages")
        val pagesRead = series.long("pagesRead")
        return TrackSearchResult(
            remoteId = series.long("id").takeIf { it > 0 } ?: seriesId,
            title = series.string("name") ?: throw IllegalArgumentException("Kavita response is missing name"),
            totalChapters = max(volumeCount, maxChapter),
            remoteUrl = url,
            coverUrl = series.string("thumbnail_url").orEmpty(),
            status = when (pagesRead) { pages -> 3L; 0L -> 1L; else -> 2L },
            lastChapterRead = latest,
        )
    }

    private suspend fun kavitaToken(context: EnhancedTrackerContext): String {
        val apiKey = requireNotNull(context.apiKey?.takeIf(String::isNotBlank)) { "Kavita API key is missing" }
        val api = context.baseUrl.trimEnd('/')
        val response = parse(
            execute(
                context,
                Request.Builder().url("$api/Plugin/authenticate?apiKey=$apiKey&pluginName=Tachiyomi-Kavita").post(EMPTY_JSON).build(),
            ),
        ).jsonObject
        return response.string("token") ?: throw IllegalArgumentException("Kavita authentication response is missing token")
    }

    private suspend fun suwayomiSearch(context: EnhancedTrackerContext, mangaId: Long): TrackSearchResult {
        val root = graphql(
            context,
            "query GetManga(\u0024mangaId: Int!) { manga(id: \u0024mangaId) { id title thumbnailUrl description status chapters { totalCount } latestReadChapter { chapterNumber } unreadCount } }",
            buildJsonObject { put("mangaId", mangaId) },
        )
        val manga = root.requiredObject("data").requiredObject("manga")
        val total = manga.requiredObject("chapters").long("totalCount")
        val unread = manga.long("unreadCount")
        return TrackSearchResult(
            remoteId = manga.long("id").takeIf { it > 0 } ?: mangaId,
            title = manga.string("title") ?: throw IllegalArgumentException("Suwayomi response is missing title"),
            totalChapters = total,
            remoteUrl = "${context.baseUrl.trimEnd('/')}/manga/$mangaId",
            coverUrl = manga.string("thumbnailUrl")?.let { "${context.baseUrl.trimEnd('/')}/${it.trimStart('/')}" }.orEmpty(),
            summary = manga.string("description").orEmpty(),
            status = when (unread) { total -> 1L; 0L -> 3L; else -> 2L },
            lastChapterRead = manga["latestReadChapter"]?.jsonObject?.double("chapterNumber") ?: 0.0,
        )
    }

    private suspend fun suwayomiUpdate(context: EnhancedTrackerContext, track: Track) {
        val chapters = graphql(
            context,
            "query GetMangaUnreadChapters(\u0024mangaId: Int!) { chapters(condition: {mangaId: \u0024mangaId, isRead: false}) { nodes { id chapterNumber } } }",
            buildJsonObject { put("mangaId", track.remoteId) },
        ).requiredObject("data").requiredObject("chapters").requiredArray("nodes")
            .mapNotNull { it.jsonObject.long("id").takeIf { _ -> (it.jsonObject.double("chapterNumber") ?: Double.MAX_VALUE) <= track.lastChapterRead + .001 } }
        graphql(
            context,
            "mutation MarkChaptersRead(\u0024chapters: [Int!]!) { updateChapters(input: {ids: \u0024chapters, patch: {isRead: true}}) { __typename } }",
            buildJsonObject { putJsonArray("chapters") { chapters.forEach { add(JsonPrimitive(it)) } } },
        )
        graphql(
            context,
            "mutation TrackManga(\u0024mangaId: Int!) { trackProgress(input: {mangaId: \u0024mangaId}) { __typename } }",
            buildJsonObject { put("mangaId", track.remoteId) },
        )
    }

    private suspend fun graphql(context: EnhancedTrackerContext, query: String, variables: JsonObject): JsonObject {
        val payload = buildJsonObject { put("query", query); put("variables", variables) }
        val root = parse(
            execute(
                context,
                Request.Builder().url("${context.baseUrl.trimEnd('/')}/api/graphql").post(payload.toString().toRequestBody(JSON)).build(),
            ),
        ).jsonObject
        root["errors"]?.jsonArray?.firstOrNull()?.jsonObject?.string("message")?.let { throw IllegalStateException("Suwayomi GraphQL error: $it") }
        return root
    }

    private suspend fun execute(context: EnhancedTrackerContext, request: Request, allowNoContent: Boolean = false): String = withContext(Dispatchers.IO) {
        try {
            (sourceClient(context.sourceId) ?: fallbackClient).newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful && !(allowNoContent && response.code == 204)) {
                    throw trackerProviderHttpError(
                        response.code,
                        "$trackerName request failed with HTTP ${response.code}",
                        response.header("Retry-After")
                            ?.trim()
                            ?.toLongOrNull()
                            ?.takeIf { it >= 0 },
                    )
                }
                body
            }
        } catch (error: IOException) {
            throw tachiyomi.domain.track.service.TrackerProviderException(
                tachiyomi.domain.track.service.TrackerProviderErrorKind.NETWORK,
                message = error.message,
            )
        }
    }

    private fun parse(body: String): JsonElement = json.parseToJsonElement(body)
    private fun mangaId(value: String): Long = value.substringAfterLast('/').toLongOrNull()
        ?: throw IllegalArgumentException("Suwayomi requires a manga ID or manga URL")
}

private fun komgaProgressUrl(url: String) =
    "${url.replace("/api/v1/series/", "/api/v2/series/")}/read-progress/tachiyomi"

private fun stableRemoteId(value: String): Long = value.hashCode().toLong() and Long.MAX_VALUE
private fun JsonObject.string(name: String) = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.long(name: String) = this[name]?.jsonPrimitive?.longOrNull ?: 0L
private fun JsonObject.double(name: String) = this[name]?.jsonPrimitive?.doubleOrNull
private fun JsonObject.requiredObject(name: String) = this[name]?.jsonObject
    ?: throw IllegalArgumentException("Tracker response is missing $name")
private fun JsonObject.requiredArray(name: String) = this[name]?.jsonArray
    ?: throw IllegalArgumentException("Tracker response is missing $name")

private val JSON = "application/json; charset=utf-8".toMediaType()
private val EMPTY_JSON = "{}".toRequestBody(JSON)
