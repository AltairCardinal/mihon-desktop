package tachiyomi.domain.track.service

import kotlinx.coroutines.CancellationException
import tachiyomi.domain.track.model.Track

enum class TrackerProviderOperation {
    EDIT,
    DELETE,
}

enum class TrackerProviderErrorKind {
    AUTHENTICATION,
    NOT_FOUND,
    RATE_LIMITED,
    SERVER,
    NETWORK,
    INVALID_REQUEST,
    TITLE_NOT_APPROVED,
    UNSUPPORTED,
    NOT_CONFIGURED,
    UNKNOWN,
}

data class TrackerProviderError(
    val operation: TrackerProviderOperation,
    val kind: TrackerProviderErrorKind,
    val statusCode: Int? = null,
    val message: String? = null,
    val retryAfterSeconds: Long? = null,
)

class TrackerProviderException(
    val kind: TrackerProviderErrorKind,
    val statusCode: Int? = null,
    message: String? = null,
    val retryAfterSeconds: Long? = null,
) : RuntimeException(message)

sealed interface TrackerProviderRequest {
    val track: Track

    data class Edit(
        override val track: Track,
        val edit: TrackEdit,
    ) : TrackerProviderRequest

    data class Delete(override val track: Track) : TrackerProviderRequest
}

sealed interface TrackerProviderResult {
    data class Success(val track: Track? = null) : TrackerProviderResult
    data class Failure(val error: TrackerProviderError) : TrackerProviderResult
}

class TrackerProviderResultException(
    val error: TrackerProviderError,
) : RuntimeException(
    error.message?.takeIf(String::isNotBlank)
        ?: "${error.operation} failed: ${error.kind}${error.statusCode?.let { " (HTTP $it)" }.orEmpty()}",
)

fun TrackerProviderResult.trackOrThrow(): Track? = when (this) {
    is TrackerProviderResult.Success -> track
    is TrackerProviderResult.Failure -> throw TrackerProviderResultException(error)
}

data class EnhancedTrackerManga(
    val mangaId: Long,
    val sourceId: Long,
    val url: String,
    val title: String,
)

interface EnhancedTrackerService : TrackerProviderService {
    fun accept(manga: EnhancedTrackerManga): Boolean
    suspend fun match(manga: EnhancedTrackerManga): TrackSearchResult?
}

class EnhancedTrackerWorkflow {
    suspend fun bindIfMatched(
        service: EnhancedTrackerService,
        manga: EnhancedTrackerManga,
    ): Track? {
        if (!service.accept(manga)) return null
        val match = service.match(manga) ?: return null
        return service.bind(manga.mangaId, match)
    }
}

interface TrackerProviderService : TrackerService {
    val configuration: TrackerProviderConfiguration
    val session: TrackerProviderSession
    suspend fun bind(
        mangaId: Long,
        result: TrackSearchResult,
        hasReadChapters: Boolean,
    ): Track = bind(mangaId, result)
    suspend fun execute(request: TrackerProviderRequest): TrackerProviderResult
}

interface TrackerProviderPort {
    val configuration: TrackerProviderConfiguration
    val session: TrackerProviderSession
    suspend fun refresh(track: Track): Track
    suspend fun update(track: Track): Track
    suspend fun delete(track: Track)
}

class TrackerProviderWorkflow(
    private val clock: () -> Long = System::currentTimeMillis,
    private val classify: (Throwable) -> Pair<TrackerProviderErrorKind, Int?> = ::classifyProviderError,
) {
    suspend fun execute(port: TrackerProviderPort, request: TrackerProviderRequest): TrackerProviderResult {
        val operation = when (request) {
            is TrackerProviderRequest.Edit -> TrackerProviderOperation.EDIT
            is TrackerProviderRequest.Delete -> TrackerProviderOperation.DELETE
        }
        return try {
            require(
                request.track.trackerId == port.configuration.id &&
                    port.session.trackerId == port.configuration.id,
            ) { "Tracker configuration mismatch" }
            if (!port.session.loggedIn) {
                throw TrackerProviderException(TrackerProviderErrorKind.AUTHENTICATION)
            }
            when (request) {
                is TrackerProviderRequest.Edit -> TrackerProviderResult.Success(
                    port.update(port.refresh(request.track).apply(request.edit, port.configuration, clock())),
                )
                is TrackerProviderRequest.Delete -> {
                    if (!port.configuration.supportsDelete) {
                        throw UnsupportedOperationException("Tracker does not support remote deletion")
                    }
                    port.delete(request.track)
                    TrackerProviderResult.Success()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val (kind, statusCode) = classify(error)
            TrackerProviderResult.Failure(
                TrackerProviderError(
                    operation = operation,
                    kind = kind,
                    statusCode = statusCode,
                    message = error.message,
                    retryAfterSeconds = (error as? TrackerProviderException)?.retryAfterSeconds,
                ),
            )
        }
    }
}

object TrackerProviderCatalog {
    val publicProviderIds = setOf(1L, 2L, 3L, 4L, 5L, 7L)

    fun configuration(id: Long): TrackerProviderConfiguration {
        require(id in TrackerProviderContracts.androidProviderIds) { "Unknown Android tracker: $id" }
        return TrackerProviderConfiguration(
            id = id,
            authentication = TrackerProviderContracts.authentication(id),
            readingStatus = when (id) {
                5L -> 3L
                6L, 8L, 9L -> 2L
                7L -> 0L
                else -> 1L
            },
            completionStatus = if (id in setOf(6L, 8L, 9L)) 3L else 2L,
            rereadingStatus = when (id) {
                1L -> 7L
                2L, 4L -> 6L
                else -> null
            },
            supportsReadingDates = id in setOf(1L, 2L, 3L),
            supportsPrivateTracking = id in setOf(2L, 3L, 5L),
            supportsDelete = id in setOf(1L, 2L, 3L, 4L, 7L),
            chapterReadPolicy = if (id == 7L) {
                TrackerChapterReadPolicy.ALWAYS_READING
            } else {
                TrackerChapterReadPolicy.AUTO_COMPLETE
            },
        )
    }
}

fun trackerProviderHttpError(
    statusCode: Int,
    message: String? = null,
    retryAfterSeconds: Long? = null,
): TrackerProviderException = TrackerProviderException(
    kind = when (statusCode) {
        401, 403 -> TrackerProviderErrorKind.AUTHENTICATION
        404 -> TrackerProviderErrorKind.NOT_FOUND
        429 -> TrackerProviderErrorKind.RATE_LIMITED
        in 500..599 -> TrackerProviderErrorKind.SERVER
        else -> TrackerProviderErrorKind.UNKNOWN
    },
    statusCode = statusCode,
    message = message,
    retryAfterSeconds = retryAfterSeconds,
)

private fun Track.apply(edit: TrackEdit, configuration: TrackerProviderConfiguration, now: Long): Track {
    val edited = copy(
        status = edit.status ?: status,
        score = edit.score ?: score,
        lastChapterRead = if (edit.status == configuration.completionStatus && totalChapters > 0) {
            totalChapters.toDouble()
        } else {
            edit.lastChapterRead ?: lastChapterRead
        },
        startDate = edit.startDate ?: startDate,
        finishDate = edit.finishDate ?: finishDate,
        private = edit.private ?: private,
    )
    if (edit.lastChapterRead == null) return edited
    val policy = if (edit.didReadChapter) configuration.chapterReadPolicy else TrackerChapterReadPolicy.INITIAL_ONLY
    val isLast = edited.totalChapters > 0 && edited.lastChapterRead.toLong() == edited.totalChapters
    val startDate = if (configuration.supportsReadingDates && edited.lastChapterRead == 1.0) now else edited.startDate
    val finishDate = if (configuration.supportsReadingDates) now else edited.finishDate
    return when (policy) {
        TrackerChapterReadPolicy.INITIAL_ONLY -> when {
            isLast -> edited.copy(status = configuration.completionStatus, finishDate = now)
            lastChapterRead == 0.0 && edited.lastChapterRead > lastChapterRead &&
                status != configuration.rereadingStatus ->
                edited.copy(status = configuration.readingStatus)
            else -> edited
        }
        TrackerChapterReadPolicy.AUTO_COMPLETE -> when {
            status == configuration.completionStatus -> edited
            isLast -> edited.copy(status = configuration.completionStatus, finishDate = finishDate)
            status == configuration.rereadingStatus -> edited
            else -> edited.copy(status = configuration.readingStatus, startDate = startDate)
        }
        TrackerChapterReadPolicy.ALWAYS_READING ->
            if (status == configuration.completionStatus) edited else edited.copy(status = configuration.readingStatus)
    }
}

private fun classifyProviderError(error: Throwable): Pair<TrackerProviderErrorKind, Int?> = when (error) {
    is TrackerProviderException -> error.kind to error.statusCode
    is IllegalArgumentException -> TrackerProviderErrorKind.INVALID_REQUEST to null
    is UnsupportedOperationException -> TrackerProviderErrorKind.UNSUPPORTED to null
    else -> TrackerProviderErrorKind.UNKNOWN to null
}

data class ProviderAuthorizationRequest(
    val parameters: Map<String, String>,
)

class OAuthProviderProtocol(
    private val responseType: String,
    val supportsAuthorizationCodeExchange: Boolean,
) {
    fun authorization(
        clientId: String,
        redirectUri: String? = null,
        state: String? = null,
    ) = ProviderAuthorizationRequest(
        buildMap {
            put("client_id", clientId)
            put("response_type", responseType)
            redirectUri?.let { put("redirect_uri", it) }
            state?.let { put("state", it) }
        },
    )

    fun authorizationCodeToken(
        clientId: String,
        clientSecret: String,
        code: String,
        redirectUri: String,
    ) = mapOf(
        "grant_type" to "authorization_code",
        "client_id" to clientId,
        "client_secret" to clientSecret,
        "code" to code,
        "redirect_uri" to redirectUri,
    )

    fun refreshToken(
        clientId: String,
        clientSecret: String,
        refreshToken: String,
        redirectUri: String? = null,
    ) = buildMap {
        put("grant_type", "refresh_token")
        put("client_id", clientId)
        put("client_secret", clientSecret)
        put("refresh_token", refreshToken)
        redirectUri?.let { put("redirect_uri", it) }
    }
}

data class AniListUpdateRequest(
    val query: String,
    val libraryId: Long,
    val progress: Int,
    val status: String,
    val scoreRaw: Int,
    val private: Boolean,
    val startedAt: ProviderFuzzyDate?,
    val completedAt: ProviderFuzzyDate?,
)

data class ProviderFuzzyDate(val year: Int?, val month: Int?, val day: Int?)

data class AniListBindRequest(
    val query: String,
    val mediaId: Long,
    val progress: Int,
    val status: String,
    val private: Boolean,
)

object AniListProviderProtocol {
    private val oauth = OAuthProviderProtocol(responseType = "token", supportsAuthorizationCodeExchange = false)
    val supportsAuthorizationCodeExchange: Boolean get() = oauth.supportsAuthorizationCodeExchange

    fun authorization(clientId: String, redirectUri: String? = null, state: String? = null) = oauth.authorization(
        clientId,
        redirectUri,
        state,
    )

    fun bind(mediaId: Long, progress: Int, status: String, private: Boolean) = AniListBindRequest(
        query = """
            mutation AddManga(${'$'}mediaId: Int, ${'$'}progress: Int, ${'$'}status: MediaListStatus, ${'$'}private: Boolean) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, progress: ${'$'}progress, status: ${'$'}status, private: ${'$'}private) {
                id status progress
              }
            }
        """.trimIndent(),
        mediaId = mediaId,
        progress = progress,
        status = status,
        private = private,
    )

    fun update(
        libraryId: Long,
        progress: Int,
        status: String,
        scoreRaw: Int,
        private: Boolean,
        startedAt: ProviderFuzzyDate? = null,
        completedAt: ProviderFuzzyDate? = null,
    ) = AniListUpdateRequest(
        query = """
            mutation UpdateManga(
              ${'$'}listId: Int, ${'$'}progress: Int, ${'$'}status: MediaListStatus, ${'$'}scoreRaw: Int,
              ${'$'}private: Boolean, ${'$'}startedAt: FuzzyDateInput, ${'$'}completedAt: FuzzyDateInput
            ) {
              SaveMediaListEntry(
                id: ${'$'}listId, progress: ${'$'}progress, status: ${'$'}status, scoreRaw: ${'$'}scoreRaw,
                private: ${'$'}private, startedAt: ${'$'}startedAt, completedAt: ${'$'}completedAt
              ) {
                id status progress
              }
            }
        """.trimIndent(),
        libraryId = libraryId,
        progress = progress,
        status = status,
        scoreRaw = scoreRaw,
        private = private,
        startedAt = startedAt,
        completedAt = completedAt,
    )
}

data class KitsuBindRequest(
    val mediaId: Long,
    val userId: String,
    val status: String,
    val progress: Int,
    val private: Boolean,
)

data class KitsuUpdateRequest(
    val libraryId: Long,
    val status: String,
    val progress: Int,
    val ratingTwenty: Int?,
    val private: Boolean,
)

object KitsuProviderProtocol {
    fun passwordToken(clientId: String, clientSecret: String, username: String, password: String) = mapOf(
        "username" to username,
        "password" to password,
        "grant_type" to "password",
        "client_id" to clientId,
        "client_secret" to clientSecret,
    )

    fun refreshToken(clientId: String, clientSecret: String, refreshToken: String) = mapOf(
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
        "client_id" to clientId,
        "client_secret" to clientSecret,
    )

    fun bind(mediaId: Long, userId: String, status: String, progress: Int, private: Boolean) =
        KitsuBindRequest(mediaId, userId, status, progress, private)

    fun update(
        libraryId: Long,
        status: String,
        progress: Int,
        ratingTwenty: Int?,
        private: Boolean,
    ): KitsuUpdateRequest {
        require(libraryId > 0) { "Kitsu update requires the saved library entry id" }
        return KitsuUpdateRequest(libraryId, status, progress, ratingTwenty, private)
    }
}

object TrackerProviderProtocols {
    val aniList = AniListProviderProtocol
    val kitsu = KitsuProviderProtocol
    val shikimori = OAuthProviderProtocol(responseType = "code", supportsAuthorizationCodeExchange = true)
    val bangumi = OAuthProviderProtocol(responseType = "code", supportsAuthorizationCodeExchange = true)
}
