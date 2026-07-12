package mihon.desktop.network

import mihon.domain.error.AppError
import okhttp3.Response

object NetworkErrorMapper {
    fun fromResponse(response: Response, validate: (String) -> Unit): AppError? = response.use {
        val body = it.body.string()
        when (it.code) {
            401, 403 -> AppError.Authentication()
            429 -> AppError.RateLimited(it.header("Retry-After")?.toLongOrNull())
            in 500..599 -> AppError.Server(it.code)
            in 200..299 -> if (body.isBlank()) {
                AppError.MalformedData()
            } else {
                runCatching { validate(body) }.exceptionOrNull()?.let(AppError::MalformedData)
            }
            else -> AppError.Unknown()
        }
    }
}
