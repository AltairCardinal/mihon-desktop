package eu.kanade.tachiyomi.extension.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.installer.Installer
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.service.ExtensionInstallCoordinator
import mihon.domain.extension.service.ExtensionInstallFailure
import mihon.domain.extension.service.ExtensionInstallPort
import mihon.domain.extension.service.ExtensionInstallRequest
import mihon.domain.extension.service.ExtensionInstallRollbackToken
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.service.PreparedExtensionInstallToken
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Android adapter for downloading and installing extension APKs. */
internal class ExtensionInstaller(
    private val context: Context,
    private val runtimeReloader: suspend (String) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    installPort: ExtensionInstallPort? = null,
) {

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeTransactions = ConcurrentHashMap<String, ActiveTransaction>()
    private val activeSteps = ConcurrentHashMap<String, MutableStateFlow<InstallStep>>()
    private val platformResults = ConcurrentHashMap<String, CompletableDeferred<InstallStep>>()
    private val completedTransactions = ConcurrentHashMap.newKeySet<String>()
    private val extensionInstaller by lazy { Injekt.get<BasePreferences>().extensionInstaller() }
    private val httpClient: OkHttpClient by lazy { Injekt.get<NetworkHelper>().client }
    private val coordinator = ExtensionInstallCoordinator(installPort ?: AndroidInstallPort(), scope)

    fun downloadAndInstall(url: String, extension: Extension.Available): Flow<InstallStep> {
        cancelActiveInstall(extension.pkgName)

        val transactionId = UUID.randomUUID().toString()
        val step = MutableStateFlow(InstallStep.Pending)
        activeSteps[transactionId] = step
        activeTransactions[extension.pkgName] = ActiveTransaction(transactionId, step)
        val job = scope.launch {
            coordinator.install(ExtensionInstallRequest(extension.toArtifact(url))).collect { state ->
                step.value = state.toInstallStep()
            }
        }
        activeJobs[extension.pkgName] = job

        return step.asStateFlow().onCompletion {
            activeJobs.remove(extension.pkgName, job)
            activeTransactions.remove(extension.pkgName, ActiveTransaction(transactionId, step))
            activeSteps.remove(transactionId, step)
            job.cancel()
        }
    }

    private fun Extension.Available.toArtifact(url: String) = ExtensionArtifact(
        name = name,
        packageName = pkgName,
        versionName = versionName,
        versionCode = versionCode,
        language = lang,
        isNsfw = isNsfw,
        sources = sources.map { ExtensionSourceDescriptor(it.id, it.lang, it.name, it.baseUrl) },
        repository = RepositoryIdentity(repoUrl, repoUrl, ""),
        downloadUrl = url,
        iconUrl = iconUrl,
        declaredSha256 = null,
    )

    private fun ExtensionInstallState.toInstallStep(): InstallStep = when (this) {
        ExtensionInstallState.Preparing -> InstallStep.Downloading
        ExtensionInstallState.Validating,
        ExtensionInstallState.Committing,
        ExtensionInstallState.Reloading,
        ExtensionInstallState.RollingBack,
        ExtensionInstallState.RestoringRuntime,
        -> InstallStep.Installing
        is ExtensionInstallState.Installed -> InstallStep.Installed
        is ExtensionInstallState.Failed -> if (error == AppError.Cancelled) InstallStep.Idle else InstallStep.Error
    }

    private suspend fun installPrepared(transactionId: String, file: File) {
        val result = CompletableDeferred<InstallStep>()
        platformResults[transactionId] = result
        try {
            installApk(transactionId, file)
            when (awaitPlatformResult(result)) {
                InstallStep.Installed -> Unit
                InstallStep.Idle -> throw CancellationException("Extension install cancelled")
                else -> throw ExtensionInstallFailure(
                    AppError.Unknown(IllegalStateException("Android package installer failed")),
                )
            }
        } catch (error: TimeoutCancellationException) {
            Installer.cancelInstallQueue(context, transactionId)
            throw ExtensionInstallFailure(AppError.Unknown(IllegalStateException("Android package install timed out")))
        } finally {
            platformResults.remove(transactionId, result)
        }
    }

    private suspend fun awaitPlatformResult(result: CompletableDeferred<InstallStep>): InstallStep =
        withTimeout(INSTALL_TIMEOUT_MILLIS) { result.await() }

    private fun installApk(transactionId: String, tempFile: File) {
        when (val installer = extensionInstaller.get()) {
            BasePreferences.ExtensionInstaller.LEGACY -> {
                val intent = Intent(context, ExtensionInstallActivity::class.java)
                    .setDataAndType(tempFile.getUriCompat(context), APK_MIME)
                    .putExtra(EXTRA_TRANSACTION_ID, transactionId)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(intent)
            }
            BasePreferences.ExtensionInstaller.PRIVATE -> {
                val installed = runCatching { ExtensionLoader.installPrivateExtensionFile(context, tempFile) }
                    .onFailure { logcat(LogPriority.ERROR, it) { "Failed to read downloaded extension file." } }
                    .getOrDefault(false)
                updateInstallStep(transactionId, if (installed) InstallStep.Installed else InstallStep.Error)
                tempFile.delete()
            }
            else -> {
                val intent = ExtensionInstallService.getIntent(
                    context,
                    transactionId,
                    tempFile.getUriCompat(context),
                    installer,
                )
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    fun cancelInstall(pkgName: String) {
        val active = activeTransactions.remove(pkgName) ?: return
        active.step.value = InstallStep.Idle
        activeJobs.remove(pkgName)?.cancel()
        Installer.cancelInstallQueue(context, active.transactionId)
    }

    private fun cancelActiveInstall(pkgName: String) {
        val active = activeTransactions.remove(pkgName) ?: return
        active.step.value = InstallStep.Idle
        activeJobs.remove(pkgName)?.cancel()
        Installer.cancelInstallQueue(context, active.transactionId)
    }

    fun uninstallApk(pkgName: String) {
        if (context.isPackageInstalled(pkgName)) {
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:$pkgName".toUri())
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            ExtensionLoader.uninstallPrivateExtension(context, pkgName)
            ExtensionInstallReceiver.notifyRemoved(context, pkgName)
        }
    }

    fun updateInstallStep(transactionId: String, step: InstallStep) {
        if (step.isCompleted()) {
            if (!completedTransactions.add(transactionId)) return
            platformResults[transactionId]?.complete(step)
        } else if (!completedTransactions.contains(transactionId)) {
            activeSteps[transactionId]?.value = step
        }
    }

    private inner class AndroidInstallPort : ExtensionInstallPort {
        private val prepared = ConcurrentHashMap<String, AndroidPreparedInstall>()

        override suspend fun prepare(request: ExtensionInstallRequest): PreparedExtensionInstallToken {
            val id = activeTransactions[request.artifact.packageName]?.transactionId ?: UUID.randomUUID().toString()
            val file = File(context.cacheDir, "extension_${request.artifact.packageName}_$id.apk")
            try {
                val response = httpClient.newCall(Request.Builder().url(request.artifact.downloadUrl).build()).execute()
                response.use {
                    if (!it.isSuccessful) throw ExtensionInstallFailure(AppError.Network())
                    it.body.byteStream().use { input -> file.outputStream().use(input::copyTo) }
                }
                prepared[id] = AndroidPreparedInstall(request.artifact, file)
                return PreparedExtensionInstallToken(id)
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }

        override suspend fun validate(token: PreparedExtensionInstallToken): ExtensionInstallRollbackToken {
            val install = prepared[token.value] ?: failStorage("Unknown Android extension install")
            val candidate = packageInfo(install.download) ?: failMalformed("Downloaded file is not an APK")
            if (candidate.packageName != install.artifact.packageName ||
                candidate.versionName != install.artifact.versionName ||
                PackageInfoCompat.getLongVersionCode(candidate) != install.artifact.versionCode
            ) {
                failMalformed("Downloaded APK metadata does not match repository metadata")
            }
            if (candidate.reqFeatures.orEmpty().none { it.name == EXTENSION_FEATURE }) {
                failMalformed("Downloaded APK is not a Mihon extension")
            }
            val candidateSignatures = signatures(candidate)
            if (candidateSignatures.isEmpty()) failMalformed("Downloaded extension is unsigned")

            val current = ExtensionLoader.getExtensionPackageInfoFromPkgName(context, candidate.packageName)
            if (current != null && !candidateSignatures.containsAll(signatures(current))) {
                throw ExtensionInstallFailure(AppError.Authentication())
            }
            current?.applicationInfo?.sourceDir?.let { source ->
                install.rollback =
                    File(context.cacheDir, "extension_${candidate.packageName}_${token.value}.rollback.apk")
                        .also { File(source).copyTo(it, overwrite = true) }
            }
            return ExtensionInstallRollbackToken(token.value)
        }

        override suspend fun commit(token: PreparedExtensionInstallToken) {
            val install = prepared[token.value] ?: failStorage("Unknown Android extension install")
            installPrepared(token.value, install.download)
        }

        override suspend fun reload(packageName: String) {
            runtimeReloader(packageName)
        }

        override suspend fun rollback(token: ExtensionInstallRollbackToken) {
            val install = prepared[token.value] ?: failStorage("Unknown Android extension rollback")
            val snapshot = install.rollback
            if (snapshot != null) {
                val privateFile = privateExtensionFile(install.artifact.packageName)
                if (privateFile.isFile) {
                    try {
                        snapshot.copyTo(privateFile, overwrite = true)
                    } catch (error: Exception) {
                        throw ExtensionInstallFailure(AppError.Storage(error))
                    }
                } else {
                    installPrepared(UUID.randomUUID().toString(), snapshot)
                }
            } else {
                removeFreshInstall(install.artifact.packageName)
            }
        }

        override suspend fun cleanup(token: PreparedExtensionInstallToken) {
            prepared.remove(token.value)?.let {
                it.download.delete()
                it.rollback?.delete()
            }
        }

        @Suppress("DEPRECATION")
        private fun packageInfo(file: File): PackageInfo? = context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PACKAGE_FLAGS,
        )

        private fun signatures(info: PackageInfo): List<String> {
            val values = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.let { signing ->
                    if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
            return values.orEmpty().map { Hash.sha256(it.toByteArray()) }
        }

        private suspend fun removeFreshInstall(packageName: String) {
            val privateFile = privateExtensionFile(packageName)
            if (privateFile.isFile) {
                if (!privateFile.delete()) failStorage("Failed to remove rolled back private extension")
                return
            }
            if (!context.isPackageInstalled(packageName)) return

            val action = "${context.packageName}.EXTENSION_ROLLBACK.${UUID.randomUUID()}"
            val result = CompletableDeferred<Int>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    result.complete(
                        intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
                    )
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                val sender = PendingIntent.getBroadcast(
                    context,
                    packageName.hashCode(),
                    Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ).intentSender
                context.packageManager.packageInstaller.uninstall(packageName, sender)
                when (result.await()) {
                    PackageInstaller.STATUS_SUCCESS -> Unit
                    PackageInstaller.STATUS_FAILURE_ABORTED -> throw CancellationException(
                        "Rollback uninstall cancelled",
                    )
                    else -> throw ExtensionInstallFailure(
                        AppError.Unknown(IllegalStateException("Android package rollback uninstall failed")),
                    )
                }
            } finally {
                context.unregisterReceiver(receiver)
            }
        }

        private fun privateExtensionFile(packageName: String) = File(context.filesDir, "exts/$packageName.ext")
    }

    private data class AndroidPreparedInstall(
        val artifact: ExtensionArtifact,
        val download: File,
        var rollback: File? = null,
    )

    private data class ActiveTransaction(
        val transactionId: String,
        val step: MutableStateFlow<InstallStep>,
    )

    private fun failMalformed(message: String): Nothing =
        throw ExtensionInstallFailure(AppError.MalformedData(IllegalArgumentException(message)))

    private fun failStorage(message: String): Nothing =
        throw ExtensionInstallFailure(AppError.Storage(IllegalStateException(message)))

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val EXTRA_TRANSACTION_ID = "ExtensionInstaller.extra.TRANSACTION_ID"

        private const val EXTENSION_FEATURE = "tachiyomi.extension"
        private const val INSTALL_TIMEOUT_MILLIS = 2 * 60 * 1000L

        @Suppress("DEPRECATION")
        private val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
            PackageManager.GET_META_DATA or
            PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)
    }
}
