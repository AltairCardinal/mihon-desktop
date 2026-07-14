package tachiyomi.domain.track.service

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
