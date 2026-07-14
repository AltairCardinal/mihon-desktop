package eu.kanade.tachiyomi.data.track.bangumi

import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMCollectionResponse
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMOAuth
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMSearchResult
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMUser
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers.Companion.headersOf
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.track.service.TrackerProviderProtocols
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class BangumiApi(
    private val trackId: Long,
    private val client: OkHttpClient,
    interceptor: Interceptor,
    private val json: Json = Injekt.get(),
    private val apiUrl: String = API_URL,
    private val oauthUrl: String = OAUTH_URL,
) {

    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    suspend fun addLibManga(track: Track): Track {
        return withIOContext {
            val url = "$apiUrl/v0/users/-/collections/${track.remote_id}"
            val body = buildJsonObject {
                put("type", track.toApiStatus())
                put("rate", track.score.toInt().coerceIn(0, 10))
                put("ep_status", track.last_chapter_read.toInt())
                put("private", track.private)
            }
                .toString()
                .toRequestBody()
            // Returns with 202 Accepted on success with no body
            authClient.newCall(POST(url, body = body, headers = headersOf("Content-Type", APP_JSON)))
                .awaitSuccess()
            track
        }
    }

    suspend fun updateLibManga(track: Track): Track {
        return withIOContext {
            val url = "$apiUrl/v0/users/-/collections/${track.remote_id}"
            val body = buildJsonObject {
                put("type", track.toApiStatus())
                put("rate", track.score.toInt().coerceIn(0, 10))
                put("ep_status", track.last_chapter_read.toInt())
                put("private", track.private)
            }
                .toString()
                .toRequestBody()

            val request = Request.Builder()
                .url(url)
                .patch(body)
                .headers(headersOf("Content-Type", APP_JSON))
                .build()
            // Returns with 204 No Content
            authClient.newCall(request)
                .awaitSuccess()

            track
        }
    }

    suspend fun search(search: String): List<TrackSearch> {
        // This API is marked as experimental in the documentation
        // but that has been the case since 2022 with few significant
        // changes to the schema for this endpoint since
        // "实验性 API， 本 schema 和实际的 API 行为都可能随时发生改动"
        return withIOContext {
            val url = "$apiUrl/v0/search/subjects?limit=20"
            val body = buildJsonObject {
                put("keyword", search)
                put("sort", "match")
                putJsonObject("filter") {
                    putJsonArray("type") {
                        add(1) // "Book" (书籍) type
                    }
                }
            }
                .toString()
                .toRequestBody()
            with(json) {
                authClient.newCall(POST(url, body = body, headers = headersOf("Content-Type", APP_JSON)))
                    .awaitSuccess()
                    .parseAs<BGMSearchResult>()
                    .data
                    .filter { it.platform == null || it.platform == "漫画" }
                    .map { it.toTrackSearch(trackId) }
            }
        }
    }

    suspend fun statusLibManga(track: Track, username: String): Track? {
        return withIOContext {
            val url = "$apiUrl/v0/users/$username/collections/${track.remote_id}"
            with(json) {
                try {
                    authClient.newCall(GET(url, cache = CacheControl.FORCE_NETWORK))
                        .awaitSuccess()
                        .parseAs<BGMCollectionResponse>()
                        .let {
                            track.status = it.getStatus()
                            track.last_chapter_read = it.epStatus?.toDouble() ?: 0.0
                            track.score = it.rate?.toDouble() ?: 0.0
                            track.total_chapters = it.subject?.eps?.toLong() ?: 0L
                            track
                        }
                } catch (e: HttpException) {
                    if (e.code == 404) { // "subject is not collected by user"
                        null
                    } else {
                        throw e
                    }
                }
            }
        }
    }

    suspend fun accessToken(code: String): BGMOAuth {
        return withIOContext {
            val body = TrackerProviderProtocols.bangumi
                .authorizationCodeToken(CLIENT_ID, CLIENT_SECRET, code, REDIRECT_URL)
                .toFormBody()
            with(json) {
                client.newCall(POST(oauthUrl, body = body))
                    .awaitSuccess()
                    .parseAs<BGMOAuth>()
            }
        }
    }

    suspend fun getUsername(): String {
        return withIOContext {
            with(json) {
                authClient.newCall(GET("$apiUrl/v0/me"))
                    .awaitSuccess()
                    .parseAs<BGMUser>()
                    .username
            }
        }
    }

    companion object {
        private const val CLIENT_ID = "bgm291665acbd06a4c28"
        private const val CLIENT_SECRET = "43e5ce36b207de16e5d3cfd3e79118db"

        private const val API_URL = "https://api.bgm.tv"
        private const val OAUTH_URL = "https://bgm.tv/oauth/access_token"
        private const val LOGIN_URL = "https://bgm.tv/oauth/authorize"

        private const val REDIRECT_URL = "mihon://bangumi-auth"

        private const val APP_JSON = "application/json"

        fun authUrl(): Uri =
            LOGIN_URL.toUri().buildUpon().apply {
                TrackerProviderProtocols.bangumi.authorization(CLIENT_ID, REDIRECT_URL).parameters.forEach {
                    appendQueryParameter(it.key, it.value)
                }
            }.build()

        fun refreshTokenRequest(token: String, oauthUrl: String = OAUTH_URL) = POST(
            oauthUrl,
            body = TrackerProviderProtocols.bangumi.refreshToken(
                CLIENT_ID,
                CLIENT_SECRET,
                token,
                REDIRECT_URL,
            ).toFormBody(),
        )
    }
}

private fun Map<String, String>.toFormBody() = FormBody.Builder().apply {
    forEach { (key, value) -> add(key, value) }
}.build()
