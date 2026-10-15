package eu.kanade.tachiyomi.data.updater

import android.content.Context
import android.os.Build
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.isFossBuildType
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.ForkUpdateRequest
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.UpdateChannel
import tachiyomi.domain.release.service.ApplicationUpdatePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AppUpdateChecker(
    private val getApplicationRelease: GetApplicationRelease = Injekt.get(),
    private val notifyUpdate: (Context, Release) -> Boolean = { context, release ->
        AppUpdateNotifier(context).promptUpdate(release)
    },
    private val updatePreferences: ApplicationUpdatePreferences? = null,
) {

    private val preferences by lazy { updatePreferences ?: Injekt.get<ApplicationUpdatePreferences>() }
    private var pendingPresentation: Pair<Long, UpdateChannel>? = null

    fun markPresented(release: Release) {
        val identity = requireNotNull(release.versionCode)
        val presentedChannel = pendingPresentation?.takeIf { it.first == identity }?.second ?: channel()
        preferences.lastPromptedVersion(
            ReleaseOs.ANDROID,
            presentedChannel,
        ).set(identity.toString())
    }

    private fun channel() = if (preferences.testingChannel().get()) UpdateChannel.TESTING else UpdateChannel.STABLE

    suspend fun checkForUpdate(context: Context, forceCheck: Boolean = false): GetApplicationRelease.Result {
        return withIOContext {
            if (!forceCheck &&
                !preferences.enabled().get()
            ) {
                return@withIOContext GetApplicationRelease.Result.NoNewUpdate
            }
            val selectedChannel = channel()
            val result = getApplicationRelease.await(
                GetApplicationRelease.Arguments(
                    isFoss = isFossBuildType,
                    isPreview = false,
                    commitCount = BuildConfig.COMMIT_COUNT.toInt(),
                    versionName = BuildConfig.VERSION_NAME,
                    repository = GITHUB_REPO,
                    forceCheck = forceCheck,
                    forkUpdate = ForkUpdateRequest(
                        channel = selectedChannel,
                        platform = ReleaseOs.ANDROID,
                        installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
                        expectedApplicationId = BuildConfig.FORK_RELEASE_PACKAGE,
                        expectedCertificateSha256 = BuildConfig.FORK_RELEASE_CERTIFICATE_SHA256,
                        architecture = Build.SUPPORTED_ABIS?.firstOrNull(),
                    ),
                ),
            )
            if (!forceCheck && (!preferences.enabled().get() || channel() != selectedChannel)) {
                return@withIOContext GetApplicationRelease.Result.NoNewUpdate
            }
            if (result is GetApplicationRelease.Result.NewUpdate) {
                pendingPresentation = requireNotNull(result.release.versionCode) to selectedChannel
            }
            if (!forceCheck && result is GetApplicationRelease.Result.NewUpdate) {
                val identity = requireNotNull(result.release.versionCode).toString()
                val prompted = preferences.lastPromptedVersion(ReleaseOs.ANDROID, selectedChannel)
                if (prompted.get() == identity) return@withIOContext GetApplicationRelease.Result.NoNewUpdate
                if (notifyUpdate(context, result.release)) prompted.set(identity)
            }

            result
        }
    }
}

const val GITHUB_REPO = ForkUpdateRequest.REPOSITORY
const val RELEASE_URL = "https://github.com/$GITHUB_REPO/releases"

internal fun canInstallForkUpdate(
    applicationId: String = BuildConfig.APPLICATION_ID,
    isDebug: Boolean = BuildConfig.DEBUG,
): Boolean = applicationId == BuildConfig.FORK_RELEASE_PACKAGE && !isDebug
