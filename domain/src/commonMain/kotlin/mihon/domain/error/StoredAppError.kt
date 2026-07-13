package mihon.domain.error

import kotlinx.serialization.Serializable

@Serializable
data class StoredAppError(
    val type: String,
    val statusCode: Int? = null,
    val retryAfterSeconds: Long? = null,
    val message: String? = null,
    val failures: List<StoredAppError> = emptyList(),
    val failedUnits: List<StoredFailedUnit> = emptyList(),
) {
    fun toAppError(): AppError = when (type) {
        "Network" -> AppError.Network(message?.let(::IllegalStateException))
        "Authentication" -> AppError.Authentication(message?.let(::IllegalStateException))
        "Challenge" -> AppError.Challenge(message?.let(::IllegalStateException))
        "RateLimited" -> AppError.RateLimited(retryAfterSeconds, message?.let(::IllegalStateException))
        "Server" -> AppError.Server(requireNotNull(statusCode), message?.let(::IllegalStateException))
        "Permission" -> AppError.Permission(message?.let(::IllegalStateException))
        "MalformedData" -> AppError.MalformedData(message?.let(::IllegalStateException))
        "Storage" -> AppError.Storage(message?.let(::IllegalStateException))
        "Cancelled" -> AppError.Cancelled
        "PartialFailure" -> AppError.PartialFailure(
            failures.map(StoredAppError::toAppError),
            failedUnits.map { AppError.FailedUnit(it.unitId, it.error.toAppError()) },
            message?.let(::IllegalStateException),
        )
        else -> AppError.Unknown(message?.let(::IllegalStateException))
    }
}

@Serializable
data class StoredFailedUnit(val unitId: String, val error: StoredAppError)

fun AppError.toStoredAppError(): StoredAppError = when (this) {
    is AppError.RateLimited -> StoredAppError(
        "RateLimited",
        retryAfterSeconds = retryAfterSeconds,
        message = cause?.message,
    )
    is AppError.Server -> StoredAppError("Server", statusCode = statusCode, message = cause?.message)
    is AppError.PartialFailure -> StoredAppError(
        "PartialFailure",
        message = cause?.message,
        failures = failures.map(AppError::toStoredAppError),
        failedUnits = failedUnits.map { StoredFailedUnit(it.unitId, it.error.toStoredAppError()) },
    )
    else -> StoredAppError(this::class.simpleName ?: "Unknown", message = cause?.message)
}
