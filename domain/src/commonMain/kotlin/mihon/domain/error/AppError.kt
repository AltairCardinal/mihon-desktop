package mihon.domain.error

sealed interface AppError {
    val cause: Throwable?

    data class Network(override val cause: Throwable? = null) : AppError
    data class Authentication(override val cause: Throwable? = null) : AppError
    data class Challenge(override val cause: Throwable? = null) : AppError
    data class RateLimited(val retryAfterSeconds: Long? = null, override val cause: Throwable? = null) : AppError
    data class Server(val statusCode: Int, override val cause: Throwable? = null) : AppError
    data class Permission(override val cause: Throwable? = null) : AppError
    data class MalformedData(override val cause: Throwable? = null) : AppError
    data object NoResults : AppError {
        override val cause: Throwable? = null
    }
    data class Storage(override val cause: Throwable? = null) : AppError
    data object Cancelled : AppError {
        override val cause: Throwable? = null
    }
    data class PartialFailure(
        val failures: List<AppError>,
        val failedUnits: List<FailedUnit> = emptyList(),
        override val cause: Throwable? = null,
    ) : AppError
    data class FailedUnit(val unitId: String, val error: AppError)
    data class Unknown(override val cause: Throwable? = null) : AppError
}
