package eu.kanade.tachiyomi.network

import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import mihon.domain.network.parseNetworkPayload
import mihon.domain.network.requireSuccessfulHttpResponse
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.IOException

internal class AndroidNetworkResponseAdapter {

    fun install(client: OkHttpClient, allowEmptyPayload: Boolean = false): OkHttpClient = client.newBuilder()
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val bytes = response.body.bytes()
            try {
                if (!(allowEmptyPayload && response.isSuccessful && bytes.isEmpty())) {
                    requireSuccessfulHttpResponse(
                        statusCode = response.code,
                        body = bytes.decodeToString(),
                        retryAfter = response.header("Retry-After"),
                    )
                }
                response.newBuilder()
                    .body(bytes.toResponseBody(response.body.contentType()))
                    .build()
            } catch (error: AppErrorException) {
                response.close()
                throw MappedNetworkIOException(error.error)
            }
        }
        .build()

    suspend fun awaitSuccess(call: Call): Response = try {
        call.awaitSuccess()
    } catch (error: IOException) {
        val mapped = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<MappedNetworkIOException>()
            .firstOrNull()
        if (mapped != null) throw AppErrorException(mapped.error)
        throw error
    }

    fun <T> parsePayload(block: () -> T): T = parseNetworkPayload(block)

    private class MappedNetworkIOException(val error: AppError) : IOException()
}
