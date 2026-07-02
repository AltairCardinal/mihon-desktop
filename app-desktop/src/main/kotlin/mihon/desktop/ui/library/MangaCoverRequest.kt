package mihon.desktop.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import mihon.desktop.LocalDesktopUiDependencies

internal data class MangaCoverStateKey(
    val mangaId: Long,
    val thumbnailUrl: String?,
    val coverVersion: Int,
)

internal data class MangaCoverRequestState(
    val stateKey: MangaCoverStateKey,
    val request: ImageRequest,
)

internal fun mangaCoverStateKey(
    mangaId: Long,
    thumbnailUrl: String?,
    coverVersion: Int,
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
    coverVersion: Int,
): String {
    return "manga-cover:$mangaId:$coverVersion:${model.orEmpty()}"
}

@Composable
internal fun rememberMangaCoverRequestState(
    mangaId: Long,
    thumbnailUrl: String?,
    coverVersion: Int,
): MangaCoverRequestState {
    val coverManager = LocalDesktopUiDependencies.current.mangaCoverManager
    val stateKey = mangaCoverStateKey(mangaId, thumbnailUrl, coverVersion)
    val coverModel by produceState<String?>(initialValue = null, stateKey) {
        value = coverManager.resolveModel(mangaId, thumbnailUrl)
    }
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
