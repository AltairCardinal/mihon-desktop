package mihon.domain.network

import mihon.domain.error.AppError

class AppErrorException(val error: AppError) : RuntimeException(error::class.simpleName, error.cause)

fun requireSuccessfulHttpResponse(
    statusCode: Int,
    body: String,
    retryAfter: String? = null,
): String = when (statusCode) {
    401, 403 -> throw AppErrorException(AppError.Authentication())
    429 -> throw AppErrorException(AppError.RateLimited(retryAfter?.toLongOrNull()))
    in 500..599 -> throw AppErrorException(AppError.Server(statusCode))
    in 200..299 -> if (body.isBlank()) throw AppErrorException(AppError.MalformedData()) else body
    else -> throw AppErrorException(AppError.Unknown())
}

inline fun <T> parseNetworkPayload(block: () -> T): T = try {
    block()
} catch (error: AppErrorException) {
    throw error
} catch (error: Exception) {
    throw AppErrorException(AppError.MalformedData(error))
}
