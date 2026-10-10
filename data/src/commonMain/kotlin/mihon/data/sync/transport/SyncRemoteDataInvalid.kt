package mihon.data.sync.transport

import kotlinx.coroutines.CancellationException

/** Only validated remote tree structure or Git-OID-checked payload decoding enters this boundary. */
internal class SyncRemoteDataInvalid(message: String = "sync remote data validation failed") :
    IllegalStateException(message)

internal inline fun requireRemoteData(condition: Boolean, message: () -> String = { "sync remote data is invalid" }) {
    if (!condition) throw SyncRemoteDataInvalid(message())
}

internal inline fun <T : Any> requireRemoteValue(
    value: T?,
    message: () -> String = { "sync remote value is missing" },
): T =
    value ?: throw SyncRemoteDataInvalid(message())

internal inline fun <T> decodeRemoteData(block: () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    throw SyncRemoteDataInvalid()
}
