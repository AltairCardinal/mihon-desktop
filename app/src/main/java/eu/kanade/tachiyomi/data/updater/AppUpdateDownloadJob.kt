package eu.kanade.tachiyomi.data.updater

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.sync.AndroidRecoveryPackageInstaller
import eu.kanade.tachiyomi.data.sync.AndroidRecoveryPackageVerification
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.release.model.ForkUpdateRules
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

class AppUpdateDownloadJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = AppUpdateNotifier(context)
    private val network: NetworkHelper by injectLazy()

    override suspend fun doWork(): Result {
        val url = inputData.getString(EXTRA_DOWNLOAD_URL)
        val title = inputData.getString(EXTRA_DOWNLOAD_TITLE) ?: context.stringResource(MR.strings.app_name)

        val expected = runCatching {
            require(canInstallForkUpdate()) { "This build cannot install release updates" }
            requireNotNull(url)
            require(ForkUpdateRules.downloadPath(url).second.endsWith(".apk"))
            val hash = requireNotNull(inputData.getString(EXTRA_EXPECTED_SHA256))
            require(ForkUpdateRules.sha256.matches(hash))
            val size = inputData.getLong(EXTRA_EXPECTED_SIZE, -1)
            require(size in 1..ForkUpdateRules.MAX_ANDROID_BYTES)
            val versionCode = inputData.getLong(EXTRA_EXPECTED_VERSION_CODE, -1)
            require(versionCode in (BuildConfig.VERSION_CODE.toLong() + 1)..2_100_000_000L)
            ExpectedDownload(hash, size, versionCode)
        }.getOrElse { return Result.failure() }

        setForegroundSafely()

        return withIOContext {
            downloadApk(title, requireNotNull(url), expected)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_APP_UPDATER,
            notifier.onDownloadStarted().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    /**
     * Called to start downloading apk of new update
     *
     * @param url url location of file
     */
    private suspend fun downloadApk(title: String, url: String, expected: ExpectedDownload): Result {
        // Show notification download starting.
        notifier.onDownloadStarted(title)

        val progressListener = object : ProgressListener {
            // Progress of the download
            var savedProgress = 0

            // Keep track of the last notification sent to avoid posting too many.
            var lastTick = 0L

            override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                val progress = (100 * (bytesRead.toFloat() / expected.size)).toInt().coerceIn(0, 100)
                val currentTime = System.currentTimeMillis()
                if (progress > savedProgress && currentTime - 200 > lastTick) {
                    savedProgress = progress
                    lastTick = currentTime
                    notifier.onProgressChange(progress)
                }
            }
        }

        var temporary: File? = null
        return try {
            val directory = context.cacheDir.resolve("app-updates")
            require(directory.isDirectory || directory.mkdirs())
            val apkFile = File.createTempFile("candidate-", ".apk", directory).also { temporary = it }
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            // GitHub release assets redirect to its HTTPS CDN; never follow a TLS downgrade.
            val client = network.client.newBuilder().followSslRedirects(false).build()
            client.newCachelessCallWithProgress(GET(url), progressListener).await().use { response ->
                require(response.isSuccessful) { "Update download failed" }
                require(response.body.contentLength().let { it < 0 || it == expected.size }) { "Update size mismatch" }
                response.body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            require(downloaded <= expected.size) { "Update exceeds expected size" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            require(downloaded == expected.size) { "Update size mismatch" }
            require(
                digest.digest().joinToString("") {
                    "%02x".format(it)
                } == expected.sha256,
            ) { "Update checksum mismatch" }
            require(
                AndroidRecoveryPackageInstaller(
                    context,
                ).verify(apkFile, expected.versionCode) is AndroidRecoveryPackageVerification.Ready,
            ) {
                "Update package identity or signature mismatch"
            }
            currentCoroutineContext().ensureActive()
            notifier.cancel()
            notifier.promptInstall(apkFile.getUriCompat(context))
            temporary = null // Keep the verified private file alive for the system installer.
            Result.success()
        } catch (e: Exception) {
            val shouldCancel = e is CancellationException ||
                (e is StreamResetException && e.errorCode == ErrorCode.CANCEL)
            if (shouldCancel) {
                notifier.cancel()
                throw if (e is CancellationException) {
                    e
                } else {
                    CancellationException("Update download cancelled").apply {
                        initCause(e)
                    }
                }
            } else {
                notifier.onDownloadError(url, title, expected.sha256, expected.size, expected.versionCode)
                Result.failure()
            }
        } finally {
            temporary?.delete()
        }
    }

    private data class ExpectedDownload(val sha256: String, val size: Long, val versionCode: Long)

    companion object {
        private const val TAG = "AppUpdateDownload"

        const val EXTRA_DOWNLOAD_URL = "DOWNLOAD_URL"
        const val EXTRA_DOWNLOAD_TITLE = "DOWNLOAD_TITLE"
        const val EXTRA_EXPECTED_SHA256 = "EXPECTED_SHA256"
        const val EXTRA_EXPECTED_SIZE = "EXPECTED_SIZE"
        const val EXTRA_EXPECTED_VERSION_CODE = "EXPECTED_VERSION_CODE"

        fun start(
            context: Context,
            url: String,
            title: String? = null,
            expectedSha256: String? = null,
            expectedSizeBytes: Long? = null,
            expectedVersionCode: Long? = null,
        ) {
            val constraints = Constraints(
                requiredNetworkType = NetworkType.CONNECTED,
            )

            val request = OneTimeWorkRequestBuilder<AppUpdateDownloadJob>()
                .setConstraints(constraints)
                .addTag(TAG)
                .setInputData(
                    workDataOf(
                        EXTRA_DOWNLOAD_URL to url,
                        EXTRA_DOWNLOAD_TITLE to title,
                        EXTRA_EXPECTED_SHA256 to expectedSha256,
                        EXTRA_EXPECTED_SIZE to expectedSizeBytes,
                        EXTRA_EXPECTED_VERSION_CODE to expectedVersionCode,
                    ),
                )
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }
    }
}
