package mihon.desktop.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

@Composable
internal fun rememberMangaCoverRequestState(
    mangaId: Long,
    coverModel: String?,
    coverVersion: Long,
): MangaCoverRequestState {
    val stateKey = mangaCoverStateKey(mangaId, coverModel, coverVersion)
    val requestKey = mangaCoverRequestKey(mangaId, coverModel, coverVersion)
    val platformContext = LocalPlatformContext.current
    val request = remember(requestKey, coverModel, platformContext) {
        ImageRequest.Builder(platformContext)
            .data(coverModel)
            .memoryCacheKey(requestKey)
            .diskCacheKey(requestKey)
            .crossfade(false)
            .build()
    }
    return MangaCoverRequestState(stateKey, request)
}
