package eu.kanade.tachiyomi.extension.model

sealed interface LoadResult {
    data class Success(val extension: Extension.Installed) : LoadResult
    data class Untrusted(val extension: Extension.Untrusted) : LoadResult
    data class Failure(val failure: ExtensionLoadFailure) : LoadResult
    data object Error : LoadResult
}

/** Identity comes from the installed package snapshot, never from a failing Source getter. */
data class ExtensionLoadFailure(
    val name: String,
    val pkgName: String,
    val versionName: String,
    val versionCode: Long,
    val isShared: Boolean,
    val phase: eu.kanade.tachiyomi.network.SourceOperationPhase,
    val error: mihon.domain.error.AppError,
)
