package mihon.desktop.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade

internal data class MangaCoverStateKey(
    val mangaId: Long,
    val thumbnailUrl: String?,
    val coverVersion: Long,
)

internal data class MangaCoverRequestState(
    val stateKey: MangaCoverStateKey,
    val request: ImageRequest,
)

internal fun mangaCoverStateKey(
    mangaId: Long,
    thumbnailUrl: String?,
    coverVersion: Long,
): MangaCoverStateKey {
    return MangaCoverStateKey(
        mangaId = mangaId,
        thumbnailUrl = thumbnailUrl,
        coverVersion = coverVersion,
    )
}

internal fun mangaCoverRequestKey(
    mangaId: Long,
    model: String?,
    coverVersion: Long,
): String {
    return "manga-cover:$mangaId:$coverVersion:${model.orEmpty()}"
}

/**
 * Builds the request for a cover.
 *
 * The library grid and the prefetch of the covers below the visible ones build the very
 * same request, so the grid finds the cover in the memory cache instead of loading it
 * a second time.
 */
internal fun buildMangaCoverRequest(
    platformContext: PlatformContext,
    mangaId: Long,
    sourceId: Long,
    coverModel: String?,
    coverVersion: Long,
): ImageRequest {
    val requestKey = mangaCoverRequestKey(mangaId, coverModel, coverVersion)
    return ImageRequest.Builder(platformContext)
        .data(mihon.desktop.image.desktopSourceImageModel(coverModel, sourceId))
        .memoryCacheKey(requestKey)
        .diskCacheKey(requestKey)
        .crossfade(false)
        .build()
}

@Composable
internal fun rememberMangaCoverRequestState(
    mangaId: Long,
    sourceId: Long,
    coverModel: String?,
    coverVersion: Long,
): MangaCoverRequestState {
    val stateKey = mangaCoverStateKey(mangaId, coverModel, coverVersion)
    val requestKey = mangaCoverRequestKey(mangaId, coverModel, coverVersion)
    val platformContext = LocalPlatformContext.current
    val request = remember(requestKey, sourceId, coverModel, platformContext) {
        buildMangaCoverRequest(platformContext, mangaId, sourceId, coverModel, coverVersion)
    }
    return MangaCoverRequestState(stateKey, request)
}
