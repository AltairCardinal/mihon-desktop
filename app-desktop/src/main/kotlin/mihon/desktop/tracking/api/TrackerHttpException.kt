package mihon.desktop.tracking.api

class TrackerHttpException(
    val statusCode: Int,
    val retryAfterSeconds: Long?,
    message: String,
) : RuntimeException(message)
