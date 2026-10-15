package tachiyomi.domain.release.interactor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.model.ForkUpdateRequest
import tachiyomi.domain.release.model.ForkUpdateRules
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.service.ReleaseService
import java.time.Instant
import java.time.temporal.ChronoUnit

class GetApplicationRelease(
    private val service: ReleaseService,
    private val preferenceStore: PreferenceStore,
    private val now: () -> Instant = Instant::now,
) {

    private val forkCheckMutex = Mutex()
    private val forkCache = mutableMapOf<String, Preference<String>>()

    private val lastChecked: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_app_check"), 0)
    }

    suspend fun await(arguments: Arguments): Result {
        if (arguments.forkUpdate != null) return forkCheckMutex.withLock { awaitFork(arguments) }
        val now = now()

        // Limit checks to once every 3 days at most
        val nextCheckTime = Instant.ofEpochMilli(lastChecked.get()).plus(3, ChronoUnit.DAYS)
        if (!arguments.forceCheck && now.isBefore(nextCheckTime)) {
            return Result.NoNewUpdate
        }

        val release = service.latest(arguments) ?: return Result.NoCompatiblePackage

        lastChecked.set(now.toEpochMilli())

        // Check if latest version is different from current version
        val isNewVersion = isNewVersion(
            arguments.isPreview,
            arguments.commitCount,
            arguments.versionName,
            release.version,
        )
        return when {
            isNewVersion -> Result.NewUpdate(release)
            else -> Result.NoNewUpdate
        }
    }

    private suspend fun awaitFork(arguments: Arguments): Result {
        val request = requireNotNull(arguments.forkUpdate)
        val currentIdentity = if (request.platform == ReleaseOs.ANDROID) {
            requireNotNull(request.installedVersionCode).also { require(it in 1..2_100_000_000L) }.toString()
        } else {
            ForkUpdateRules.desktopVersion(arguments.versionName)
            arguments.versionName
        }
        val key = Preference.appStateKey("fork_update_${request.platform}_${request.channel}")
        val preference = forkCache.getOrPut(key) { preferenceStore.getString(key) }
        val checkedAt = now().toEpochMilli()
        val cached = runCatching { Json.decodeFromString<CachedForkRelease>(preference.get()) }.getOrNull()
        if (!arguments.forceCheck && cached != null &&
            cached.installedIdentity == currentIdentity && cached.architecture == request.architecture &&
            cached.applicationId == request.expectedApplicationId &&
            cached.certificateSha256 == request.expectedCertificateSha256 &&
            checkedAt - cached.checkedAt in 0 until FORK_CHECK_INTERVAL_MILLIS
        ) {
            runCatching {
                validateCachedRelease(cached.release, request)
                forkResult(arguments, cached.release)
            }.getOrNull()?.let { return it }
        }
        val release = service.latest(arguments)
        val result = forkResult(arguments, release)
        val encoded = Json.encodeToString(
            CachedForkRelease(
                checkedAt,
                currentIdentity,
                request.architecture,
                request.expectedApplicationId,
                request.expectedCertificateSha256,
                release?.forCache(),
            ),
        )
        require(encoded.length <= MAX_CACHE_VALUE_LENGTH) { "Update cache exceeds native preference limit" }
        preference.set(encoded)
        return result
    }

    private fun validateCachedRelease(release: Release?, request: ForkUpdateRequest) {
        release ?: return
        require(
            ForkUpdateRules.releaseTag(release.releaseLink) == ForkUpdateRules.downloadPath(release.downloadLink).first,
        )
        if (request.platform == ReleaseOs.ANDROID) {
            require(
                release.applicationId == request.expectedApplicationId &&
                    release.certificateSha256 == request.expectedCertificateSha256,
            )
        }
    }

    private fun Release.forCache(): Release {
        val count = info.codePointCount(0, info.length)
        return if (count > MAX_CACHED_NOTES_CODEPOINTS) {
            copy(
                info = info.substring(0, info.offsetByCodePoints(0, MAX_CACHED_NOTES_CODEPOINTS)),
                infoTruncated = true,
            )
        } else {
            this
        }
    }

    private fun forkResult(arguments: Arguments, release: Release?): Result {
        release ?: return Result.NoCompatiblePackage
        val request = requireNotNull(arguments.forkUpdate)
        val newer = if (request.platform == ReleaseOs.ANDROID) {
            val available = requireNotNull(release.versionCode).also { require(it in 1..2_100_000_000L) }
            available > requireNotNull(request.installedVersionCode)
        } else {
            ForkUpdateRules.compareDesktopVersions(release.version, arguments.versionName) > 0
        }
        return if (newer) Result.NewUpdate(release) else Result.NoNewUpdate
    }

    @Serializable
    private data class CachedForkRelease(
        val checkedAt: Long,
        val installedIdentity: String,
        val architecture: String?,
        val applicationId: String?,
        val certificateSha256: String?,
        val release: Release?,
    )

    private companion object {
        const val FORK_CHECK_INTERVAL_MILLIS = 12L * 60 * 60 * 1000
        const val MAX_CACHE_VALUE_LENGTH = 8192
        const val MAX_CACHED_NOTES_CODEPOINTS = 512
    }

    private fun isNewVersion(
        isPreview: Boolean,
        commitCount: Int,
        versionName: String,
        versionTag: String,
    ): Boolean {
        // Removes prefixes like "r" or "v"
        val newVersion = versionTag.replace("[^\\d.]".toRegex(), "")
        return if (isPreview) {
            // Preview builds: based on releases in "mihonapp/mihon-preview" repo
            // tagged as something like "r1234"
            newVersion.toInt() > commitCount
        } else {
            // Release builds: based on releases in "mihonapp/mihon" repo
            // tagged as something like "v0.1.2"
            val oldVersion = versionName.replace("[^\\d.]".toRegex(), "")

            val newSemVer = newVersion.split(".").map { it.toInt() }
            val oldSemVer = oldVersion.split(".").map { it.toInt() }

            oldSemVer.mapIndexed { index, i ->
                if (newSemVer[index] > i) {
                    return true
                }
            }

            false
        }
    }

    data class Arguments(
        val isFoss: Boolean,
        val isPreview: Boolean,
        val commitCount: Int,
        val versionName: String,
        val repository: String,
        val forceCheck: Boolean = false,
        val forkUpdate: ForkUpdateRequest? = null,
    )

    sealed interface Result {
        data class NewUpdate(val release: Release) : Result
        data object NoNewUpdate : Result
        data object NoCompatiblePackage : Result
        data object OsTooOld : Result
    }
}
