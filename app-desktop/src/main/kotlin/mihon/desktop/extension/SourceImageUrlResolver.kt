package mihon.desktop.extension

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Resolves a source image URL across both the app and extension child classloaders. */
suspend fun resolveSourceImageUrl(source: CatalogueSource, page: Page): String? = when (source) {
    is HttpSource -> source.getImageUrl(page)
    else -> invokeReflectiveImageUrl(source, page)
}

private suspend fun invokeReflectiveImageUrl(source: CatalogueSource, page: Page): String? {
    val method = source.javaClass.methods.firstOrNull { candidate ->
        candidate.name == "getImageUrl" &&
            candidate.parameterCount == 2 &&
            candidate.parameterTypes.first().isAssignableFrom(page.javaClass)
    }?.apply { trySetAccessible() } ?: return null
    return try {
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val result = method.invoke(source, page, continuation)
            if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else result as? String
        }
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}
