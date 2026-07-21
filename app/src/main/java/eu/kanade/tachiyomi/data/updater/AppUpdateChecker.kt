package eu.kanade.tachiyomi.data.updater

import android.content.Context
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.isFossBuildType
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AppUpdateChecker(
    private val getApplicationRelease: GetApplicationRelease = Injekt.get(),
    private val notifyUpdate: (Context, Release) -> Unit = { context, release ->
        AppUpdateNotifier(context).promptUpdate(release)
    },
) {

    suspend fun checkForUpdate(context: Context, forceCheck: Boolean = false): GetApplicationRelease.Result {
        // Disable app update checks for older Android versions that we're going to drop support for
        // if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        //     return GetApplicationRelease.Result.OsTooOld
        // }

        return withIOContext {
            val sharedResult = getApplicationRelease.await(
                GetApplicationRelease.Arguments(
                    isFossBuildType,
                    isPreviewBuildType,
                    BuildConfig.COMMIT_COUNT.toInt(),
                    BuildConfig.VERSION_NAME,
                    GITHUB_REPO,
                    forceCheck,
                ),
            )
            val result = when (sharedResult) {
                GetApplicationRelease.Result.NoCompatiblePackage -> GetApplicationRelease.Result.NoNewUpdate
                else -> sharedResult
            }

            when (result) {
                is GetApplicationRelease.Result.NewUpdate -> notifyUpdate(context, result.release)
                else -> {}
            }

            result
        }
    }
}

val GITHUB_REPO: String by lazy {
    if (isPreviewBuildType) {
        "mihonapp/mihon-preview"
    } else {
        "mihonapp/mihon"
    }
}

val RELEASE_TAG: String by lazy {
    if (isPreviewBuildType) {
        "r${BuildConfig.COMMIT_COUNT}"
    } else {
        "v${BuildConfig.VERSION_NAME}"
    }
}

val RELEASE_URL = "https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
