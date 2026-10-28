package mihon.desktop.extension

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Response
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Shared by online reading and queued downloads: keep the source's complete image operation. */
internal suspend fun resolveSourceImageResponse(source: CatalogueSource, page: Page): Response? {
    if (source is HttpSource) return source.getImage(page)
    val method = source.javaClass.methods.firstOrNull { candidate ->
        candidate.name == "getImage" && candidate.parameterCount == 2 &&
            candidate.parameterTypes.first().isAssignableFrom(page.javaClass)
    }?.apply { trySetAccessible() } ?: return null
    return try {
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val result = method.invoke(source, page, continuation)
            if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else result as Response
        }
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}
