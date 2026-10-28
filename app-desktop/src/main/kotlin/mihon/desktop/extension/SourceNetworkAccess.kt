package mihon.desktop.extension

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.lang.reflect.InvocationTargetException

/** Only sources without a network accessor may use a caller's fallback network policy. */
internal fun Source.sourceClientOrNull(): OkHttpClient? = when (this) {
    is HttpSource -> client
    else -> invokeNetworkAccessor("getClient")?.let { it as OkHttpClient }
}

internal fun Source.sourceHeadersOrNull(): Headers? = when (this) {
    is HttpSource -> headers
    else -> invokeNetworkAccessor("getHeaders")?.let { it as Headers }
}

private fun Source.invokeNetworkAccessor(name: String): Any? {
    val method = javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 } ?: return null
    check(method.trySetAccessible()) { "Cannot access source network accessor: $name" }
    return try {
        checkNotNull(method.invoke(this)) { "Source network accessor returned null: $name" }
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}
