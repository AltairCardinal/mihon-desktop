package mihon.desktop.tracking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mihon.desktop.platform.DesktopCredentialStore
import mihon.desktop.tracking.api.TrackerHttpException
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackSearchResult
import tachiyomi.domain.track.service.ProviderFuzzyDate
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerProfile
import tachiyomi.domain.track.service.TrackerProviderContracts
import tachiyomi.domain.track.service.TrackerProviderProtocols
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.domain.track.service.TrackerServiceRegistry
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

class DesktopTrackerServiceRegistry(
    override val services: List<TrackerService> = emptyList(),
    private val enhancedContextProvider: tachiyomi.domain.track.service.EnhancedTrackerContextProvider? = null,
) : TrackerServiceRegistry {
    companion object {
        fun production(
            client: OkHttpClient,
            json: Json,
            credentialStore: DesktopCredentialStore,
            account: String = "default",
            endpoints: DesktopTrackerEndpoints = DesktopTrackerEndpoints(),
            enhancedContextProvider: tachiyomi.domain.track.service.EnhancedTrackerContextProvider? = null,
            sourceClient: (Long) -> OkHttpClient? = { null },
            clientConfig: DesktopTrackerClientConfig = DesktopTrackerClientConfig(),
        ): DesktopTrackerServiceRegistry = DesktopTrackerServiceRegistry(
            ProviderDefinition.publicProviders(endpoints, clientConfig).map { definition ->
                DesktopProviderTrackerService(client, json, credentialStore, definition, account)
            } + enhancedContextProvider?.let { enhancedTrackerServices(client, json, it, sourceClient) }.orEmpty(),
            enhancedContextProvider,
        )
    }

    override fun get(id: Long): TrackerService? {
        refresh()
        return services.firstOrNull { it.profile.value.id == id && it.profile.value.unavailableReason == null }
    }

    override fun refresh() {
        enhancedContextProvider?.refresh()
    }
}

data class DesktopTrackerClientConfig(
    val loopbackRedirectProviderIds: Set<Long> = emptySet(),
    val implicitTokenCallbackProviderIds: Set<Long> = emptySet(),
    val clientSecrets: Map<Long, String> = emptyMap(),
) {
    companion object {
        fun forTesting() = DesktopTrackerClientConfig(
            loopbackRedirectProviderIds = setOf(1, 4, 5),
            implicitTokenCallbackProviderIds = setOf(2),
            clientSecrets = mapOf(
                3L to "test-kitsu-secret",
                4L to "test-shikimori-secret",
                5L to "test-bangumi-secret",
            ),
        )
    }
}

interface DesktopAuthenticatingTrackerService : TrackerService {
    fun authorizationUrl(redirectUri: String, state: String): String
    suspend fun finishOAuth(code: String, redirectUri: String)
    suspend fun login(username: String, password: String)
    suspend fun loginWithApiKey(apiKey: String)
}

data class DesktopTrackerEndpoints(
    val myAnimeList: String = "https://api.myanimelist.net/",
    val myAnimeListOAuth: String = "https://myanimelist.net/",
    val aniList: String = "https://graphql.anilist.co/",
    val aniListOAuth: String = "https://anilist.co/",
    val kitsu: String = "https://kitsu.app/",
    val shikimori: String = "https://shikimori.one/",
    val bangumi: String = "https://api.bgm.tv/",
    val bangumiOAuth: String = "https://bgm.tv/",
    val mangaUpdates: String = "https://api.mangaupdates.com/",
) {
    companion object {
        fun all(baseUrl: String) = DesktopTrackerEndpoints(
            myAnimeList = baseUrl,
            myAnimeListOAuth = baseUrl,
            aniList = baseUrl,
            aniListOAuth = baseUrl,
            kitsu = baseUrl,
            shikimori = baseUrl,
            bangumi = baseUrl,
            bangumiOAuth = baseUrl,
            mangaUpdates = baseUrl,
        )
    }
}

private enum class ProviderKind { MAL, ANILIST, KITSU, SHIKIMORI, BANGUMI, MANGA_UPDATES }

private data class ProviderDefinition(
    val id: Long,
    val name: String,
    val authentication: TrackerAuthentication,
    val kind: ProviderKind,
    val apiBase: String,
    val oauthBase: String = apiBase,
    val clientId: String = "",
    val clientSecret: String? = null,
    val unavailableReason: String? = null,
    val statuses: List<Pair<Long, String>>,
    val initialStatus: Long,
    val scores: List<Double>,
) {
    companion object {
        private val standardStatuses = listOf(
            1L to "Reading",
            2L to "Completed",
            3L to "On hold",
            4L to "Dropped",
            5L to "Plan to read",
        )

        fun publicProviders(e: DesktopTrackerEndpoints, config: DesktopTrackerClientConfig) = listOf(
            ProviderDefinition(
                1, "MyAnimeList", TrackerAuthentication.OAUTH, ProviderKind.MAL,
                e.myAnimeList, e.myAnimeListOAuth, "c46c9e24640a64dad5be5ca7a1a53a0f",
                clientSecret = null,
                unavailableReason = "MyAnimeList desktop OAuth client has no registered loopback redirect.".takeUnless { 1L in config.loopbackRedirectProviderIds },
                statuses = standardStatuses.dropLast(1) + (6L to "Plan to read") + (7L to "Rereading"),
                initialStatus = 6,
                (0..10).map(Int::toDouble),
            ),
            ProviderDefinition(
                2, "AniList", TrackerAuthentication.OAUTH, ProviderKind.ANILIST,
                e.aniList, e.aniListOAuth, "16329",
                clientSecret = null,
                unavailableReason = "AniList uses an implicit token callback that the loopback server cannot receive.".takeUnless { 2L in config.implicitTokenCallbackProviderIds },
                statuses = standardStatuses + (6L to "Rereading"),
                initialStatus = 5,
                (0..100).map(Int::toDouble),
            ),
            ProviderDefinition(
                3, "Kitsu", TrackerAuthentication.USERNAME_PASSWORD, ProviderKind.KITSU,
                e.kitsu, clientId = "dd031b32d2f56c990b1425efe6c42ad847e7fe3ab46bf1299f05ecd856bdb7dd",
                clientSecret = config.clientSecrets[3],
                unavailableReason = "Kitsu desktop client secret is not configured.".takeIf { config.clientSecrets[3].isNullOrBlank() },
                statuses = standardStatuses,
                initialStatus = 5,
                scores = listOf(0.0) + (2..20).map { it / 2.0 },
            ),
            ProviderDefinition(
                4, "Shikimori", TrackerAuthentication.OAUTH, ProviderKind.SHIKIMORI,
                e.shikimori, clientId = "PB9dq8DzI405s7wdtwTdirYqHiyVMh--djnP7lBUqSA",
                clientSecret = config.clientSecrets[4],
                unavailableReason = providerConfigurationReason(4, "Shikimori", config),
                statuses = standardStatuses + (6L to "Rereading"),
                initialStatus = 5,
                scores = (0..10).map(Int::toDouble),
            ),
            ProviderDefinition(
                5, "Bangumi", TrackerAuthentication.OAUTH, ProviderKind.BANGUMI,
                e.bangumi, e.bangumiOAuth, "bgm291665acbd06a4c28",
                clientSecret = config.clientSecrets[5],
                unavailableReason = providerConfigurationReason(5, "Bangumi", config),
                statuses = listOf(3L to "Reading", 2L to "Completed", 4L to "On hold", 5L to "Dropped", 1L to "Plan to read"),
                initialStatus = 1,
                scores = (0..10).map(Int::toDouble),
            ),
            ProviderDefinition(
                7, "MangaUpdates", TrackerAuthentication.USERNAME_PASSWORD, ProviderKind.MANGA_UPDATES,
                e.mangaUpdates,
                statuses = listOf(0L to "Reading list", 2L to "Complete list", 4L to "On hold list", 3L to "Unfinished list", 1L to "Wish list"),
                initialStatus = 1,
                scores = listOf(0.0) + (1..100).map { it / 10.0 },
            ),
        )

        private fun providerConfigurationReason(id: Long, name: String, config: DesktopTrackerClientConfig): String? = when {
            config.clientSecrets[id].isNullOrBlank() -> "$name desktop client secret is not configured."
            id !in config.loopbackRedirectProviderIds -> "$name desktop OAuth client has no registered loopback redirect."
            else -> null
        }
    }
}

private class DesktopProviderTrackerService(
    private val client: OkHttpClient,
    private val json: Json,
    private val credentialStore: DesktopCredentialStore,
    private val definition: ProviderDefinition,
    account: String,
) : DesktopAuthenticatingTrackerService {
    private val credentialKey = "tracker.${definition.id}.account.$account.session.v1"
    private var session = credentialStore.load(credentialKey)?.let {
        runCatching { json.decodeFromString<StoredSession>(it) }.getOrNull()
    }
    private var pkceVerifier: String? = null
    private val mutableProfile = MutableStateFlow(profileFor(session))

    override val profile: StateFlow<TrackerProfile> = mutableProfile
    override val statuses = definition.statuses
    override val scores = definition.scores

    override fun authorizationUrl(redirectUri: String, state: String): String {
        requireAvailable()
        check(definition.authentication == TrackerAuthentication.OAUTH) { "${definition.name} does not use OAuth" }
        val (path, challenge) = when (definition.kind) {
            ProviderKind.MAL -> {
                val verifier = randomVerifier().also { pkceVerifier = it }
                "v1/oauth2/authorize" to verifier
            }
            ProviderKind.ANILIST -> "api/v2/oauth/authorize" to null
            ProviderKind.SHIKIMORI -> "oauth/authorize" to null
            ProviderKind.BANGUMI -> "oauth/authorize" to null
            else -> error("${definition.name} does not use OAuth")
        }
        val parameters = when (definition.kind) {
            ProviderKind.ANILIST -> TrackerProviderProtocols.aniList.authorization(definition.clientId, redirectUri, state).parameters
            ProviderKind.SHIKIMORI -> TrackerProviderProtocols.shikimori.authorization(definition.clientId, redirectUri, state).parameters
            ProviderKind.BANGUMI -> TrackerProviderProtocols.bangumi.authorization(definition.clientId, redirectUri, state).parameters
            else -> mapOf("client_id" to definition.clientId, "redirect_uri" to redirectUri, "response_type" to "code", "state" to state)
        }
        return url(definition.oauthBase, path).newBuilder()
            .apply { parameters.forEach { (name, value) -> addQueryParameter(name, value) } }
            .apply {
                challenge?.let {
                    addQueryParameter("code_challenge", it)
                    addQueryParameter("code_challenge_method", "plain")
                }
            }
            .build().toString()
    }

    override suspend fun finishOAuth(code: String, redirectUri: String) {
        requireAvailable()
        check(definition.authentication == TrackerAuthentication.OAUTH)
        if (definition.kind == ProviderKind.ANILIST) {
            saveSession(StoredSession(accessToken = code))
            return
        }
        val path = when (definition.kind) {
            ProviderKind.MAL -> "v1/oauth2/token"
            ProviderKind.ANILIST -> "api/v2/oauth/token"
            ProviderKind.SHIKIMORI -> "oauth/token"
            ProviderKind.BANGUMI -> "oauth/access_token"
            else -> error("${definition.name} does not use OAuth")
        }
        val fields = when (definition.kind) {
            ProviderKind.SHIKIMORI -> TrackerProviderProtocols.shikimori.authorizationCodeToken(
                definition.clientId, requireNotNull(definition.clientSecret), code, redirectUri,
            )
            ProviderKind.BANGUMI -> TrackerProviderProtocols.bangumi.authorizationCodeToken(
                definition.clientId, requireNotNull(definition.clientSecret), code, redirectUri,
            )
            else -> buildMap {
                put("grant_type", "authorization_code"); put("client_id", definition.clientId); put("code", code); put("redirect_uri", redirectUri)
                pkceVerifier?.let { put("code_verifier", it) }
            }
        }
        val body = fields.toFormBody()
        saveSession(parseToken(execute(Request.Builder().url(url(definition.oauthBase, path)).post(body).build())))
        pkceVerifier = null
    }

    override suspend fun login(username: String, password: String) {
        requireAvailable()
        check(definition.authentication == TrackerAuthentication.USERNAME_PASSWORD)
        val request = when (definition.kind) {
            ProviderKind.KITSU -> Request.Builder()
                .url(url(definition.apiBase, "api/oauth/token"))
                .post(
                    TrackerProviderProtocols.kitsu.passwordToken(
                        definition.clientId, requireNotNull(definition.clientSecret), username, password,
                    ).toFormBody(),
                ).build()
            ProviderKind.MANGA_UPDATES -> Request.Builder()
                .url(url(definition.apiBase, "v1/account/login"))
                .put(
                    buildJsonObject { put("username", username); put("password", password) }
                        .toString().toRequestBody(JSON),
                ).build()
            else -> error("${definition.name} does not support password login")
        }
        val root = parse(execute(request)).jsonObject
        val authenticated = if (definition.kind == ProviderKind.MANGA_UPDATES) {
            val context = root.requiredObject("context")
            StoredSession(
                accessToken = context.requiredString("session_token"),
                username = context["uid"]?.jsonPrimitive?.contentOrNull ?: username,
            )
        } else {
            parseToken(root, username)
        }
        saveSession(authenticated)
    }

    override suspend fun loginWithApiKey(apiKey: String) {
        throw UnsupportedOperationException("${definition.name} does not use an API key")
    }

    override suspend fun search(query: String): List<TrackSearchResult> {
        val request = searchRequest(query)
        val root = parse(executeAuthenticated(request))
        return parseSearch(root)
    }

    override suspend fun bind(mangaId: Long, result: TrackSearchResult): Track {
        requireAvailable()
        var track = Track(
        id = 0,
        mangaId = mangaId,
        trackerId = definition.id,
        remoteId = result.remoteId,
        libraryId = null,
        title = result.title,
        lastChapterRead = 0.0,
        totalChapters = result.totalChapters,
        status = definition.initialStatus,
        score = 0.0,
        remoteUrl = result.remoteUrl,
        startDate = 0,
        finishDate = 0,
            private = false,
        )
        if (definition.kind == ProviderKind.ANILIST) {
            val bind = TrackerProviderProtocols.aniList.bind(
                mediaId = result.remoteId,
                progress = track.lastChapterRead.toInt(),
                status = TrackerProviderContracts.aniList.statusToWire(track.status),
                private = track.private,
            )
            val payload = buildJsonObject {
                put("query", bind.query)
                putJsonObject("variables") {
                    put("mediaId", bind.mediaId)
                    put("progress", bind.progress)
                    put("status", bind.status)
                    put("private", bind.private)
                }
            }
            val created = parse(
                executeAuthenticated(
                    Request.Builder()
                        .url(definition.apiBase.toHttpUrl())
                        .post(payload.toString().toRequestBody(JSON))
                        .build(),
                ),
            )
            track = track.copy(
                libraryId = created.jsonObject
                    .requiredObject("data")
                    .requiredObject("SaveMediaListEntry")
                    .requiredLong("id"),
            )
        } else if (definition.kind == ProviderKind.KITSU) {
            val user = parse(executeAuthenticated(Request.Builder().url(url(definition.apiBase, "api/edge/users")).get().build()))
                .jsonObject.requiredArray("data").first().jsonObject.requiredString("id")
            val bind = TrackerProviderProtocols.kitsu.bind(result.remoteId, user, kitsuStatus(track.status), 0, false)
            val payload = buildJsonObject {
                putJsonObject("data") {
                    put("type", "libraryEntries")
                    putJsonObject("attributes") { put("status", bind.status); put("progress", bind.progress); put("private", bind.private) }
                    putJsonObject("relationships") {
                        putJsonObject("user") { putJsonObject("data") { put("id", bind.userId); put("type", "users") } }
                        putJsonObject("media") { putJsonObject("data") { put("id", bind.mediaId); put("type", "manga") } }
                    }
                }
            }
            val created = parse(executeAuthenticated(Request.Builder().url(url(definition.apiBase, "api/edge/library-entries")).post(payload.toString().toRequestBody(JSON_API)).build()))
            track = track.copy(libraryId = created.jsonObject.requiredObject("data").requiredString("id").toLong())
        }
        return track
    }

    override suspend fun update(track: Track, edit: TrackEdit): Track {
        requireAvailable()
        val updated = track.copy(
            status = edit.status ?: track.status,
            score = edit.score ?: track.score,
            lastChapterRead = edit.lastChapterRead ?: track.lastChapterRead,
            startDate = track.startDate,
            finishDate = track.finishDate,
        )
        val request = updateRequest(updated)
        val response = parse(executeAuthenticated(request))
        return parseUpdated(updated, response)
    }

    override suspend fun logout() {
        session = null
        credentialStore.delete(credentialKey)
        mutableProfile.value = profileFor(null)
    }

    private fun searchRequest(query: String): Request = when (definition.kind) {
        ProviderKind.MAL -> Request.Builder().url(
            url(definition.apiBase, "v2/manga").newBuilder()
                .addQueryParameter("q", query.take(64))
                .addQueryParameter("nsfw", "true")
                .addQueryParameter("fields", "id,title,synopsis,num_chapters,main_picture,media_type")
                .build(),
        ).get().build()
        ProviderKind.ANILIST -> Request.Builder().url(definition.apiBase.toHttpUrl()).post(
            buildJsonObject {
                put("query", "query Search(\u0024query: String) { Page(perPage: 50) { media(search: \u0024query, type: MANGA, format_not_in: [NOVEL]) { id title { userPreferred } coverImage { large } chapters description } } }")
                putJsonObject("variables") { put("query", query) }
            }.toString().toRequestBody(JSON),
        ).build()
        ProviderKind.KITSU -> Request.Builder().url(
            url(definition.apiBase, "api/edge/manga").newBuilder().addQueryParameter("filter[text]", query).build(),
        ).get().build()
        ProviderKind.SHIKIMORI -> Request.Builder().url(
            url(definition.apiBase, "api/mangas").newBuilder().addQueryParameter("search", query).addQueryParameter("limit", "20").build(),
        ).get().build()
        ProviderKind.BANGUMI -> Request.Builder().url(
            url(definition.apiBase, "v0/search/subjects").newBuilder().addQueryParameter("limit", "20").build(),
        ).post(buildJsonObject { put("keyword", query) }.toString().toRequestBody(JSON)).build()
        ProviderKind.MANGA_UPDATES -> Request.Builder().url(url(definition.apiBase, "v1/series/search")).post(
            buildJsonObject { put("search", query) }.toString().toRequestBody(JSON_API),
        ).build()
    }

    private fun updateRequest(track: Track): Request = when (definition.kind) {
        ProviderKind.MAL -> Request.Builder().url(url(definition.apiBase, "v2/manga/${track.remoteId}/my_list_status"))
            .put(
                FormBody.Builder()
                    .add("status", malStatus(track.status))
                    .add("is_rereading", (track.status == 7L).toString())
                    .add("score", track.score.toInt().toString())
                    .add("num_chapters_read", track.lastChapterRead.toInt().toString())
                    .build(),
            ).build()
        ProviderKind.ANILIST -> Request.Builder().url(definition.apiBase.toHttpUrl()).post(
            TrackerProviderProtocols.aniList.update(
                libraryId = requireNotNull(track.libraryId) { "AniList update requires a bound library entry" },
                progress = track.lastChapterRead.toInt(), status = TrackerProviderContracts.aniList.statusToWire(track.status),
                scoreRaw = track.score.toInt(), private = track.private,
                startedAt = track.startDate.toProviderFuzzyDate(),
                completedAt = track.finishDate.toProviderFuzzyDate(),
            ).let { update ->
                val startedAt = update.startedAt
                val completedAt = update.completedAt
                buildJsonObject {
                put("query", update.query)
                putJsonObject("variables") {
                    put("listId", update.libraryId); put("progress", update.progress); put("status", update.status)
                    put("scoreRaw", update.scoreRaw); put("private", update.private)
                    putJsonObject("startedAt") {
                        if (startedAt?.year == null) put("year", JsonNull) else put("year", startedAt.year)
                        if (startedAt?.month == null) put("month", JsonNull) else put("month", startedAt.month)
                        if (startedAt?.day == null) put("day", JsonNull) else put("day", startedAt.day)
                    }
                    putJsonObject("completedAt") {
                        if (completedAt?.year == null) put("year", JsonNull) else put("year", completedAt.year)
                        if (completedAt?.month == null) put("month", JsonNull) else put("month", completedAt.month)
                        if (completedAt?.day == null) put("day", JsonNull) else put("day", completedAt.day)
                    }
                }
            } }.toString().toRequestBody(JSON),
        ).build()
        ProviderKind.KITSU -> TrackerProviderProtocols.kitsu.update(
            requireNotNull(track.libraryId), kitsuStatus(track.status), track.lastChapterRead.toInt(), (track.score * 2).toInt(), track.private,
        ).let { update -> Request.Builder().url(url(definition.apiBase, "api/edge/library-entries/${update.libraryId}"))
            .patch(
                buildJsonObject {
                    putJsonObject("data") {
                        put("type", "libraryEntries"); put("id", update.libraryId.toString())
                        putJsonObject("attributes") {
                            put("status", update.status); put("progress", update.progress); put("ratingTwenty", update.ratingTwenty); put("private", update.private)
                        }
                    }
                }.toString().toRequestBody(JSON_API),
            ).build() }
        ProviderKind.SHIKIMORI -> Request.Builder().url(url(definition.apiBase, "api/v2/user_rates")).post(
            buildJsonObject {
                putJsonObject("user_rate") {
                    put("target_id", track.remoteId); put("target_type", "Manga"); put("status", shikimoriStatus(track.status))
                    put("chapters", track.lastChapterRead.toInt()); put("score", track.score.toInt())
                }
            }.toString().toRequestBody(JSON),
        ).build()
        ProviderKind.BANGUMI -> Request.Builder().url(url(definition.apiBase, "v0/users/-/collections/${track.remoteId}"))
            .post(
                buildJsonObject {
                    put("type", TrackerProviderContracts.bangumi.statusToWire(track.status).toInt())
                    put("rate", track.score.toInt())
                }.toString().toRequestBody(JSON),
            ).build()
        ProviderKind.MANGA_UPDATES -> Request.Builder().url(url(definition.apiBase, "v1/lists/series/update"))
            .post(
                buildJsonObject {
                    put("series_id", track.remoteId); put("list_id", track.status); put("chapter", track.lastChapterRead)
                }.toString().toRequestBody(JSON_API),
            ).build()
    }

    private fun parseSearch(root: JsonElement): List<TrackSearchResult> = when (definition.kind) {
        ProviderKind.MAL -> root.jsonObject.requiredArray("data").map { entry ->
            val node = entry.jsonObject.requiredObject("node")
            TrackSearchResult(
                node.requiredLong("id"), node.requiredString("title"), node.long("num_chapters"),
                "https://myanimelist.net/manga/${node.requiredLong("id")}",
                node.obj("main_picture")?.string("large").orEmpty(), node.string("synopsis").orEmpty(),
            )
        }
        ProviderKind.ANILIST -> root.jsonObject.requiredObject("data").requiredObject("Page").requiredArray("media").map { value ->
            val item = value.jsonObject
            TrackSearchResult(
                item.requiredLong("id"), item.requiredObject("title").requiredString("userPreferred"), item.long("chapters"),
                "https://anilist.co/manga/${item.requiredLong("id")}", item.obj("coverImage")?.string("large").orEmpty(), item.string("description").orEmpty(),
            )
        }
        ProviderKind.KITSU -> root.jsonObject.requiredArray("data").map { value ->
            val item = value.jsonObject
            val attrs = item.requiredObject("attributes")
            val id = item.requiredString("id").toLong()
            TrackSearchResult(
                id, attrs.requiredString("canonicalTitle"), attrs.long("chapterCount"),
                "https://kitsu.app/manga/${attrs.string("slug") ?: id}", attrs.obj("posterImage")?.string("original").orEmpty(), attrs.string("synopsis").orEmpty(),
            )
        }
        ProviderKind.SHIKIMORI -> root.jsonArray.map { value ->
            val item = value.jsonObject
            TrackSearchResult(
                item.requiredLong("id"), item.string("name") ?: item.requiredString("russian"), item.long("chapters"),
                "https://shikimori.one${item.string("url").orEmpty()}", "https://shikimori.one${item.obj("image")?.string("preview").orEmpty()}", item.string("description").orEmpty(),
            )
        }
        ProviderKind.BANGUMI -> root.jsonObject.requiredArray("data").map { value ->
            val item = value.jsonObject
            val id = item.requiredLong("id")
            TrackSearchResult(
                id, item.string("name_cn")?.takeIf(String::isNotBlank) ?: item.requiredString("name"), item.long("volumes"),
                "https://bgm.tv/subject/$id", item.obj("images")?.string("large").orEmpty(), item.string("summary").orEmpty(),
            )
        }
        ProviderKind.MANGA_UPDATES -> root.jsonObject.requiredArray("results").map { value ->
            val item = value.jsonObject.requiredObject("record")
            TrackSearchResult(
                item.requiredLong("series_id"), item.requiredString("title"), 0,
                item.string("url").orEmpty(), item.obj("image")?.obj("url")?.string("original").orEmpty(), item.string("description").orEmpty(),
            )
        }
    }

    private fun parseUpdated(track: Track, response: JsonElement): Track {
        if (definition.kind != ProviderKind.MAL) return track
        val item = response.jsonObject
        return track.copy(
            status = item.string("status")?.let(::malStatus) ?: track.status,
            score = item["score"]?.jsonPrimitive?.doubleOrNull ?: track.score,
            lastChapterRead = item["num_chapters_read"]?.jsonPrimitive?.doubleOrNull ?: track.lastChapterRead,
        )
    }

    private suspend fun executeAuthenticated(request: Request): String {
        val current = requireNotNull(session?.takeIf { it.accessToken.isNotBlank() }) { "${definition.name} is not authenticated" }
        if (
            current.expiresAtEpochSeconds != null &&
            current.expiresAtEpochSeconds <= System.currentTimeMillis() / 1000 + 30 &&
            !current.refreshToken.isNullOrBlank()
        ) {
            refreshSession(current)
        }
        val auth = requireNotNull(session?.accessToken?.takeIf(String::isNotBlank)) { "${definition.name} is not authenticated" }
        val authenticated = request.newBuilder().header("Authorization", "Bearer $auth").build()
        return execute(authenticated)
    }

    private suspend fun refreshSession(current: StoredSession) {
        val path = when (definition.kind) {
            ProviderKind.MAL -> "v1/oauth2/token"
            ProviderKind.ANILIST -> "api/v2/oauth/token"
            ProviderKind.KITSU -> "api/oauth/token"
            ProviderKind.SHIKIMORI -> "oauth/token"
            ProviderKind.BANGUMI -> "oauth/access_token"
            ProviderKind.MANGA_UPDATES -> error("MangaUpdates sessions do not use OAuth refresh tokens")
        }
        val fields = when (definition.kind) {
            ProviderKind.KITSU -> TrackerProviderProtocols.kitsu.refreshToken(
                definition.clientId, requireNotNull(definition.clientSecret), requireNotNull(current.refreshToken),
            )
            ProviderKind.SHIKIMORI -> TrackerProviderProtocols.shikimori.refreshToken(
                definition.clientId, requireNotNull(definition.clientSecret), requireNotNull(current.refreshToken),
            )
            ProviderKind.BANGUMI -> TrackerProviderProtocols.bangumi.refreshToken(
                definition.clientId, requireNotNull(definition.clientSecret), requireNotNull(current.refreshToken),
            )
            else -> mapOf("grant_type" to "refresh_token", "client_id" to definition.clientId, "refresh_token" to requireNotNull(current.refreshToken))
        }
        val refreshed = parseToken(
            execute(
                Request.Builder().url(url(definition.oauthBase, path)).post(
                    fields.toFormBody(),
                ).build(),
            ),
        )
        saveSession(
            refreshed.copy(
                refreshToken = refreshed.refreshToken ?: current.refreshToken,
                username = current.username,
            ),
        )
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw TrackerHttpException(
                    response.code,
                    response.header("Retry-After")?.toLongOrNull(),
                    "${definition.name} request failed with HTTP ${response.code}",
                )
            }
            body
        }
    }

    private fun parseToken(body: String) = parseToken(parse(body).jsonObject)

    private fun parseToken(root: JsonObject, username: String? = null): StoredSession {
        val expires = root["expires_in"]?.jsonPrimitive?.longOrNull
        return StoredSession(
            accessToken = root.requiredString("access_token"),
            refreshToken = root.string("refresh_token"),
            username = username,
            expiresAtEpochSeconds = expires?.let { System.currentTimeMillis() / 1000 + it },
        )
    }

    private fun saveSession(value: StoredSession) {
        session = value
        credentialStore.save(credentialKey, json.encodeToString(value))
        mutableProfile.value = profileFor(value)
    }

    private fun profileFor(value: StoredSession?) = TrackerProfile(
        definition.id,
        definition.name,
        definition.authentication,
        loggedIn = !value?.accessToken.isNullOrBlank(),
        username = value?.username,
        unavailableReason = definition.unavailableReason,
    )

    private fun requireAvailable() {
        check(definition.unavailableReason == null) { definition.unavailableReason ?: "Tracker is unavailable" }
    }

    private fun parse(body: String): JsonElement = json.parseToJsonElement(body)

    override fun toString() = "DesktopProviderTrackerService(id=${definition.id}, name=${definition.name}, loggedIn=${profile.value.loggedIn})"

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val JSON_API = "application/vnd.api+json".toMediaType()
    }
}

@Serializable
private data class StoredSession(
    val accessToken: String,
    val refreshToken: String? = null,
    val username: String? = null,
    val expiresAtEpochSeconds: Long? = null,
)

private fun url(base: String, path: String) = base.toHttpUrl().newBuilder().addPathSegments(path).build()

private fun randomVerifier(): String {
    val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun Map<String, String>.toFormBody(): FormBody = FormBody.Builder().apply {
    forEach { (name, value) -> add(name, value) }
}.build()

private fun JsonObject.requiredArray(name: String) = this[name]?.jsonArray
    ?: throw IllegalArgumentException("Tracker response is missing $name")
private fun JsonObject.requiredObject(name: String) = this[name]?.jsonObject
    ?: throw IllegalArgumentException("Tracker response is missing $name")
private fun JsonObject.requiredString(name: String) = string(name)
    ?: throw IllegalArgumentException("Tracker response is missing $name")
private fun JsonObject.requiredLong(name: String) = this[name]?.jsonPrimitive?.longOrNull
    ?: throw IllegalArgumentException("Tracker response is missing $name")
private fun JsonObject.string(name: String) = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.long(name: String) = this[name]?.jsonPrimitive?.longOrNull ?: 0
private fun JsonObject.obj(name: String) = this[name] as? JsonObject

private fun malStatus(status: Long) = when (status) {
    1L, 2L, 3L, 4L, 6L, 7L -> TrackerProviderContracts.myAnimeList.statusToWire(status)
    else -> TrackerProviderContracts.myAnimeList.statusToWire(1L)
}

private fun malStatus(status: String) = TrackerProviderContracts.myAnimeList.wireToStatus(status)

private fun kitsuStatus(status: Long) = TrackerProviderContracts.kitsu.statusToWire(status)

private fun shikimoriStatus(status: Long) = TrackerProviderContracts.shikimori.statusToWire(status)

private fun Long.toProviderFuzzyDate(): ProviderFuzzyDate {
    if (this == 0L) return ProviderFuzzyDate(null, null, null)
    val date = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
    return ProviderFuzzyDate(date.year, date.monthValue, date.dayOfMonth)
}
