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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.InstalledExtensionTrustRecord
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.model.extractExtensionLibVersion
import mihon.domain.extension.service.ExtensionInstallCoordinator
import mihon.domain.extension.service.ExtensionInstallFailure
import mihon.domain.extension.service.ExtensionInstallPort
import mihon.domain.extension.service.ExtensionInstallRequest
import mihon.domain.extension.service.ExtensionInstallRollbackToken
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.service.ExtensionTrustDecision
import mihon.domain.extension.service.ExtensionTrustPolicy
import mihon.domain.extension.service.ExtensionTrustRequest
import mihon.domain.extension.service.ExtensionUpdatePolicy
import mihon.domain.extension.service.PreparedExtensionInstallToken
import mihon.domain.extension.service.SharedExtensionUpdatePolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Android adapter for downloading and installing extension APKs. */
internal class ExtensionInstaller private constructor(
    private val context: Context,
    private val runtimeReloader: suspend (String) -> Unit,
    private val scope: CoroutineScope,
    installPort: ExtensionInstallPort?,
    gateway: AndroidInstallGateway?,
    client: OkHttpClient?,
    private val installerProvider: (() -> BasePreferences.ExtensionInstaller)?,
) {

    internal constructor(
        context: Context,
        runtimeReloader: suspend (String) -> Unit = {},
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
        installPort: ExtensionInstallPort? = null,
    ) : this(context, runtimeReloader, scope, installPort, null, null, null)

    internal constructor(
        context: Context,
        runtimeReloader: suspend (String) -> Unit,
        scope: CoroutineScope,
        gateway: AndroidInstallGateway,
        client: OkHttpClient,
        installerProvider: () -> BasePreferences.ExtensionInstaller,
    ) : this(context, runtimeReloader, scope, null, gateway, client, installerProvider)

    private val activeJobs = ConcurrentHashMap<String, ActiveInstallJob>()
    private val activeTransactions = ConcurrentHashMap<String, ActiveTransaction>()
    private val activeSteps = ConcurrentHashMap<String, MutableStateFlow<InstallStep>>()
    private val platformResults = ConcurrentHashMap<String, CompletableDeferred<InstallStep>>()
    private val transactionLifecycles = ConcurrentHashMap<String, TransactionLifecycle>()
    private val completedTransactions = ConcurrentHashMap<String, Long>()
    private val cancelledTransactions = ConcurrentHashMap.newKeySet<String>()
    private val systemAttemptsByParent = ConcurrentHashMap<String, String>()
    private val extensionInstaller by lazy { Injekt.get<BasePreferences>().extensionInstaller() }
    private val httpClient: OkHttpClient by lazy { Injekt.get<NetworkHelper>().client }
    private val selectsAndroidInstaller = installPort == null
    private fun selectedInstaller() = installerProvider?.invoke() ?: extensionInstaller.get()
    private val coordinator = ExtensionInstallCoordinator(
        LifecycleInstallPort(
            installPort ?: AndroidInstallPort(
                gateway = gateway ?: DefaultAndroidInstallGateway(
                    context = context,
                    installSystem = ::installSystemAttempt,
                    commitPlanProvider = { packageName ->
                        val installer = activeTransactions[packageName]?.installer ?: selectedInstaller()
                        if (installer == BasePreferences.ExtensionInstaller.PRIVATE) {
                            AndroidCommitPlan(AndroidInstallLocation.PRIVATE)
                        } else {
                            AndroidCommitPlan(AndroidInstallLocation.SYSTEM, installer)
                        }
                    },
                ),
                client = client ?: httpClient,
                runtimeReloader = runtimeReloader,
                transactionIdProvider = { packageName ->
                    activeTransactions[packageName]?.transactionId
                },
            ),
        ),
        scope,
    )

    fun downloadAndInstall(url: String, extension: Extension.Available): Flow<InstallStep> {
        cancelActiveInstall(extension.pkgName)

        val transactionId = UUID.randomUUID().toString()
        val step = MutableStateFlow(InstallStep.Pending)
        val lifecycle = TransactionLifecycle()
        val activeTransaction = ActiveTransaction(
            transactionId,
            step,
            if (selectsAndroidInstaller) selectedInstaller() else null,
        )
        transactionLifecycles[transactionId] = lifecycle
        activeSteps[transactionId] = step
        activeTransactions[extension.pkgName] = activeTransaction
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                coordinator.install(ExtensionInstallRequest(extension.toArtifact(url))).collect { state ->
                    val installStep = state.toInstallStep()
                    if (installStep.isCompleted()) {
                        activeJobs.remove(
                            extension.pkgName,
                            ActiveInstallJob(transactionId, coroutineContext.job),
                        )
                        activeTransactions.remove(
                            extension.pkgName,
                            activeTransaction,
                        )
                        activeSteps.remove(transactionId, step)
                        cancelledTransactions -= transactionId
                    }
                    step.value = installStep
                }
            } finally {
                activeJobs.remove(
                    extension.pkgName,
                    ActiveInstallJob(transactionId, coroutineContext.job),
                )
                activeTransactions.remove(extension.pkgName, activeTransaction)
                activeSteps.remove(transactionId, step)
                completeLifecycle(transactionId, lifecycle)
            }
        }
        val activeJob = ActiveInstallJob(transactionId, job)
        activeJobs[extension.pkgName] = activeJob
        job.start()

        return step.asStateFlow().onCompletion {
            activeJobs.remove(extension.pkgName, activeJob)
            activeTransactions.remove(extension.pkgName, activeTransaction)
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
        repository = RepositoryIdentity(repoUrl, repoName, repoFingerprint),
        downloadUrl = url,
        iconUrl = iconUrl,
        declaredSha256 = declaredSha256,
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
        installPrepared(transactionId, file, extensionInstaller.get())
    }

    internal suspend fun installSystemAttempt(
        parentTransactionId: String,
        file: File,
        installer: BasePreferences.ExtensionInstaller,
    ) {
        check(installer != BasePreferences.ExtensionInstaller.PRIVATE)
        val attemptId = UUID.randomUUID().toString()
        val lifecycle = TransactionLifecycle()
        transactionLifecycles[attemptId] = lifecycle
        check(systemAttemptsByParent.putIfAbsent(parentTransactionId, attemptId) == null)
        try {
            installPrepared(attemptId, file, installer)
        } finally {
            try {
                systemAttemptsByParent.remove(parentTransactionId, attemptId)
            } finally {
                completeLifecycle(attemptId, lifecycle)
            }
        }
    }

    private suspend fun installPrepared(
        transactionId: String,
        file: File,
        installer: BasePreferences.ExtensionInstaller,
    ) {
        val lifecycle = transactionLifecycles.computeIfAbsent(transactionId) { TransactionLifecycle() }
        val result = CompletableDeferred<InstallStep>()
        try {
            synchronized(lifecycle) {
                if (cancelledTransactions.contains(transactionId)) {
                    throw CancellationException("Extension install cancelled")
                }
                platformResults[transactionId] = result
                installApk(transactionId, file, installer)
                lifecycle.markHandedOff()
            }
            val platformStep = try {
                awaitPlatformResult(result)
            } finally {
                lifecycle.markFinishing()
            }
            when (platformStep) {
                InstallStep.Installed -> Unit
                InstallStep.Idle -> throw CancellationException("Extension install cancelled")
                else -> throw ExtensionInstallFailure(
                    AppError.Unknown(IllegalStateException("Android package installer failed")),
                )
            }
        } catch (error: TimeoutCancellationException) {
            awaitPlatformCleanup(transactionId)
            throw ExtensionInstallFailure(AppError.Unknown(IllegalStateException("Android package install timed out")))
        } catch (error: CancellationException) {
            awaitPlatformCleanup(transactionId)
            throw error
        } finally {
            platformResults.remove(transactionId, result)
        }
    }

    private suspend fun awaitPlatformResult(result: CompletableDeferred<InstallStep>): InstallStep =
        withTimeout(INSTALL_TIMEOUT_MILLIS) { result.await() }

    private suspend fun awaitPlatformCleanup(transactionId: String) {
        val acknowledgement = Installer.cancelInstallQueue(context, transactionId)
        withContext(NonCancellable) {
            acknowledgement.await()
        }
    }

    private fun installApk(
        transactionId: String,
        tempFile: File,
        installer: BasePreferences.ExtensionInstaller,
    ) {
        when (installer) {
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
        val active = activeTransactions[pkgName] ?: return
        requestCancellation(pkgName, active)
    }

    internal fun isInstallTransactionActive(pkgName: String): Boolean = activeTransactions.containsKey(pkgName)

    private fun cancelActiveInstall(pkgName: String) {
        val active = activeTransactions[pkgName] ?: return
        requestCancellation(pkgName, active)
    }

    private fun requestCancellation(pkgName: String, active: ActiveTransaction) {
        if (!cancelledTransactions.add(active.transactionId)) return
        val activeJob = activeJobs[pkgName]?.takeIf { it.transactionId == active.transactionId }
        val systemAttempt = systemAttemptsByParent[active.transactionId]
        val cancellationId = systemAttempt ?: active.transactionId
        if (systemAttempt != null) cancelledTransactions += systemAttempt
        val lifecycle = transactionLifecycles[cancellationId] ?: run {
            cancelledTransactions -= active.transactionId
            systemAttempt?.let(cancelledTransactions::remove)
            return
        }
        val cancellationTarget = synchronized(lifecycle) {
            CancellationTarget(lifecycle.phase(), platformResults[cancellationId])
        }
        if (cancellationTarget.phase == TransactionPhase.COMPLETE) {
            cancelledTransactions -= active.transactionId
            return
        }
        if (systemAttempt != null) {
            val acknowledgement = Installer.cancelInstallQueue(context, systemAttempt)
            scope.launch {
                acknowledgement.await()
                cancellationTarget.platformResult?.complete(InstallStep.Idle)
            }
            return
        }
        if (cancellationTarget.phase == TransactionPhase.NEW) {
            activeJob?.job?.cancel()
            activeTransactions.remove(pkgName, active)
            activeSteps.remove(active.transactionId, active.step)
            active.step.value = InstallStep.Idle
            Installer.cancelInstallQueue(context, active.transactionId)
            if (activeJob == null) {
                completeLifecycle(active.transactionId, lifecycle)
            } else {
                scope.launch {
                    activeJob.job.join()
                    activeJobs.remove(pkgName, activeJob)
                    completeLifecycle(active.transactionId, lifecycle)
                }
            }
            return
        }
        val acknowledgement = Installer.cancelInstallQueue(context, active.transactionId)
        scope.launch {
            acknowledgement.await()
            cancellationTarget.platformResult?.complete(InstallStep.Idle)
        }
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
        pruneCompletedTransactions()
        if (step.isCompleted()) {
            if (completedTransactions.putIfAbsent(transactionId, System.nanoTime()) != null) return
            platformResults[transactionId]?.complete(step)
        } else if (!completedTransactions.containsKey(transactionId)) {
            activeSteps[transactionId]?.value = step
        }
    }

    private fun pruneCompletedTransactions() {
        val cutoff = System.nanoTime() - COMPLETED_TRANSACTION_TTL_NANOS
        completedTransactions.entries.removeIf { (transactionId, completedAtNanos) ->
            completedAtNanos < cutoff && !isTransactionActive(transactionId)
        }
    }

    private fun isTransactionActive(transactionId: String): Boolean =
        platformResults.containsKey(transactionId) ||
            activeSteps.containsKey(transactionId) ||
            activeTransactions.values.any { it.transactionId == transactionId } ||
            activeJobs.values.any { it.transactionId == transactionId } ||
            transactionLifecycles[transactionId]?.isActive() == true

    private fun completeLifecycle(transactionId: String, lifecycle: TransactionLifecycle) {
        cancelledTransactions -= transactionId
        if (lifecycle.complete()) {
            transactionLifecycles.remove(transactionId, lifecycle)
        }
    }

    private inner class LifecycleInstallPort(
        private val delegate: ExtensionInstallPort,
    ) : ExtensionInstallPort {
        private val transactionsByPackage = ConcurrentHashMap<String, String>()

        override suspend fun prepare(request: ExtensionInstallRequest): PreparedExtensionInstallToken =
            delegate.prepare(request).also { token ->
                transactionsByPackage[request.artifact.packageName] = token.value
            }

        override suspend fun validate(token: PreparedExtensionInstallToken): ExtensionInstallRollbackToken =
            delegate.validate(token).also { ensureNotCancelled(token.value) }

        override suspend fun commit(token: PreparedExtensionInstallToken) {
            delegate.commit(token)
            ensureNotCancelled(token.value)
        }

        override suspend fun reload(packageName: String) {
            delegate.reload(packageName)
            transactionsByPackage[packageName]?.let(::ensureNotCancelled)
        }

        override suspend fun rollback(token: ExtensionInstallRollbackToken) {
            delegate.rollback(token)
        }

        override suspend fun cleanup(token: PreparedExtensionInstallToken) {
            try {
                delegate.cleanup(token)
            } finally {
                transactionsByPackage.entries.removeIf { it.value == token.value }
            }
        }

        private fun ensureNotCancelled(transactionId: String) {
            if (cancelledTransactions.contains(transactionId)) {
                throw CancellationException("Extension install cancelled")
            }
        }
    }

    private data class ActiveTransaction(
        val transactionId: String,
        val step: MutableStateFlow<InstallStep>,
        val installer: BasePreferences.ExtensionInstaller?,
    )

    private data class ActiveInstallJob(
        val transactionId: String,
        val job: Job,
    )

    private data class CancellationTarget(
        val phase: TransactionPhase,
        val platformResult: CompletableDeferred<InstallStep>?,
    )

    private class TransactionLifecycle {
        private val phase = AtomicReference(TransactionPhase.NEW)

        fun phase(): TransactionPhase = phase.get()

        fun markHandedOff() {
            check(phase.compareAndSet(TransactionPhase.NEW, TransactionPhase.HANDED_OFF))
        }

        fun markFinishing() {
            phase.compareAndSet(TransactionPhase.HANDED_OFF, TransactionPhase.FINISHING)
        }

        fun complete(): Boolean {
            while (true) {
                val current = phase.get()
                if (current == TransactionPhase.COMPLETE) return false
                if (phase.compareAndSet(current, TransactionPhase.COMPLETE)) return true
            }
        }

        fun isActive(): Boolean = phase.get() != TransactionPhase.COMPLETE
    }

    private enum class TransactionPhase {
        NEW,
        HANDED_OFF,
        FINISHING,
        COMPLETE,
    }

    private fun failMalformed(message: String): Nothing =
        throw ExtensionInstallFailure(AppError.MalformedData(IllegalArgumentException(message)))

    private fun failStorage(message: String): Nothing =
        throw ExtensionInstallFailure(AppError.Storage(IllegalStateException(message)))

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val EXTRA_TRANSACTION_ID = "ExtensionInstaller.extra.TRANSACTION_ID"

        private const val EXTENSION_FEATURE = "tachiyomi.extension"
        private const val INSTALL_TIMEOUT_MILLIS = 2 * 60 * 1000L
        private const val COMPLETED_TRANSACTION_TTL_NANOS = 5L * 60L * 1_000_000_000L

        @Suppress("DEPRECATION")
        private val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
            PackageManager.GET_META_DATA or
            PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)
    }
}

internal enum class AndroidInstallLocation { PRIVATE, SYSTEM }

internal enum class AndroidLoaderOrigin { PRIVATE, SYSTEM, ABSENT }

internal data class AndroidCommitPlan(
    val location: AndroidInstallLocation,
    val systemInstaller: BasePreferences.ExtensionInstaller? = null,
)

internal data class AndroidApk(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signers: Set<String>,
    val isExtension: Boolean,
)

internal data class AndroidInstalledPackage(
    val apk: File,
    val versionName: String,
    val versionCode: Long,
    val signers: Set<String>,
    val trust: InstalledExtensionTrustRecord?,
)

internal data class AndroidInstallTopology(
    val privatePackage: AndroidInstalledPackage?,
    val systemPackage: AndroidInstalledPackage?,
    val loaderOrigin: AndroidLoaderOrigin,
)

internal interface AndroidInstallGateway {
    val transactionRoot: File

    fun commitPlan(packageName: String): AndroidCommitPlan
    fun canonical(file: File): File
    fun writeDownload(input: InputStream, destination: File)
    fun inspect(file: File): AndroidApk?
    fun topology(packageName: String): AndroidInstallTopology
    fun copy(source: File, destination: File): Boolean
    fun makeReadOnly(file: File): Boolean
    fun installPrivate(file: File, metadata: AndroidInstalledPackage): Boolean
    suspend fun installSystem(
        parentTransactionId: String,
        file: File,
        metadata: AndroidInstalledPackage,
        installer: BasePreferences.ExtensionInstaller,
    )
    fun removePrivate(packageName: String): Boolean
    suspend fun removeSystem(packageName: String)
    fun delete(file: File): Boolean
}

internal class AndroidInstallPort(
    private val gateway: AndroidInstallGateway,
    private val client: OkHttpClient,
    private val runtimeReloader: suspend (String) -> Unit = {},
    private val trustPolicy: ExtensionTrustPolicy = ExtensionTrustPolicy(),
    private val updatePolicy: ExtensionUpdatePolicy = SharedExtensionUpdatePolicy,
    private val transactionIdProvider: (String) -> String? = { null },
) : ExtensionInstallPort {
    private val prepared = ConcurrentHashMap<String, PreparedInstall>()
    private val failedPreparationCleanup = ConcurrentHashMap<String, List<File>>()
    private val expectedAbsentAfterRollback = ConcurrentHashMap.newKeySet<String>()

    override suspend fun prepare(request: ExtensionInstallRequest): PreparedExtensionInstallToken {
        retryFailedPreparationCleanup()
        val id = transactionIdProvider(request.artifact.packageName) ?: UUID.randomUUID().toString()
        val root = storage { gateway.transactionRoot.canonicalFile }
        val directory = storage { gateway.canonical(File(root, id)) }
        val download = storage { gateway.canonical(File(directory, "candidate.apk")) }
        ensureContained(root, directory)
        ensureContained(root, download)
        if (!directory.mkdirs() &&
            !directory.isDirectory
        ) {
            failStorage("Failed to create extension transaction directory")
        }
        try {
            val response = try {
                client.newCall(Request.Builder().url(request.artifact.downloadUrl).build()).execute()
            } catch (error: java.io.IOException) {
                throw ExtensionInstallFailure(AppError.Network(error))
            }
            response.use {
                if (!it.isSuccessful) throw ExtensionInstallFailure(it.toDownloadError())
                try {
                    it.body.byteStream().use { input -> gateway.writeDownload(input, download) }
                } catch (error: ExtensionInstallFailure) {
                    throw error
                } catch (error: Throwable) {
                    throw ExtensionInstallFailure(AppError.Storage(error))
                }
            }
            prepared[id] = PreparedInstall(
                artifact = request.artifact,
                directory = directory,
                download = download,
                commitPlan = storage { gateway.commitPlan(request.artifact.packageName) },
            )
            return PreparedExtensionInstallToken(id)
        } catch (failure: Throwable) {
            cleanupFailures(listOf(download, directory)).takeIf { it.isNotEmpty() }?.let { remaining ->
                failedPreparationCleanup[id] = remaining
            }
            throw failure
        }
    }

    override suspend fun validate(token: PreparedExtensionInstallToken): ExtensionInstallRollbackToken {
        val install = prepared[token.value] ?: failStorage("Unknown Android extension install")
        val candidate = storage { gateway.inspect(install.download) }
            ?: failMalformed("Downloaded file is not an APK")
        if (!candidate.isExtension || candidate.packageName != install.artifact.packageName ||
            candidate.versionName != install.artifact.versionName ||
            candidate.versionCode != install.artifact.versionCode
        ) {
            failMalformed("Downloaded APK metadata does not match repository metadata")
        }
        if (candidate.signers.isEmpty()) failMalformed("Downloaded extension is unsigned")

        val downloadedSha = digest(install.download)
        val topology = storage { gateway.topology(candidate.packageName) }
        val selected = topology.selected()
        val installedPackages = topology.allPackages()
        val trustContinuityPackages = if (installedPackages.isEmpty()) {
            listOf<AndroidInstalledPackage?>(null)
        } else {
            installedPackages
        }
        trustContinuityPackages.forEach { installed ->
            when (
                val trustDecision = trustPolicy.evaluate(
                    ExtensionTrustRequest(
                        incomingArtifact = install.artifact,
                        downloadedArtifactSha256 = downloadedSha,
                        installed = installed?.trust ?: installed?.let {
                            InstalledExtensionTrustRecord(repository = null, artifactSha256 = null)
                        },
                        installedArtifactSha256 = installed?.let { digest(it.apk) },
                    ),
                )
            ) {
                ExtensionTrustDecision.Trusted -> Unit
                is ExtensionTrustDecision.Rejected -> throw ExtensionInstallFailure(trustDecision.error)
                is ExtensionTrustDecision.ConfirmationRequired -> throw ExtensionInstallFailure(
                    AppError.Authentication(TrustConfirmationRequiredException(trustDecision)),
                )
            }
        }

        topology.allPackages().forEach { installed ->
            if (!candidate.signers.containsAll(installed.signers)) {
                throw ExtensionInstallFailure(AppError.Authentication())
            }
        }
        selected?.let { installed ->
            val installedLibVersion = extractExtensionLibVersion(installed.versionName) ?: 0.0
            val upgrade = updatePolicy.isUpdateAvailable(
                candidate.versionCode,
                install.artifact.libVersion,
                installed.versionCode,
                installedLibVersion,
            )
            val exactVersion = candidate.versionCode == installed.versionCode &&
                install.artifact.libVersion == installedLibVersion
            if (!upgrade && !exactVersion) {
                failMalformed("Extension downgrade is not allowed")
            }
        }

        val target = install.commitPlan.location
        fun snapshot(location: AndroidInstallLocation, current: AndroidInstalledPackage?): AndroidInstalledPackage? =
            current?.let {
                File(install.directory, "rollback-${location.name.lowercase()}.apk").also { destination ->
                    if (!storage { gateway.copy(it.apk, destination) }) {
                        failStorage("Failed to snapshot installed extension")
                    }
                    if (!storage { gateway.makeReadOnly(destination) }) {
                        failStorage("Failed to make extension snapshot read-only")
                    }
                }.let { destination -> it.copy(apk = destination) }
            }
        val privateSnapshot = snapshot(AndroidInstallLocation.PRIVATE, topology.privatePackage)
        val systemSnapshot = snapshot(AndroidInstallLocation.SYSTEM, topology.systemPackage)
        install.preState = InstallPreState(
            privatePackage = privateSnapshot,
            systemPackage = systemSnapshot,
            loaderOrigin = topology.loaderOrigin,
            commitTarget = target,
            systemInstaller = install.commitPlan.systemInstaller,
            expectedAbsent = topology.loaderOrigin == AndroidLoaderOrigin.ABSENT,
        )
        install.downloadedSha = downloadedSha
        return ExtensionInstallRollbackToken(token.value)
    }

    override suspend fun commit(token: PreparedExtensionInstallToken) {
        val install = prepared[token.value] ?: failStorage("Unknown Android extension install")
        val preState = install.preState ?: failStorage("Android extension was not validated")
        val candidate = storage { gateway.inspect(install.download) }
            ?: failMalformed("Downloaded file is not an APK")
        val metadata = AndroidInstalledPackage(
            apk = install.download,
            versionName = candidate.versionName,
            versionCode = candidate.versionCode,
            signers = candidate.signers,
            trust = InstalledExtensionTrustRecord(install.artifact.repository, install.downloadedSha),
        )
        when (preState.commitTarget) {
            AndroidInstallLocation.PRIVATE -> if (!gateway.installPrivate(install.download, metadata)) {
                failStorage("Failed to atomically replace private extension")
            }
            AndroidInstallLocation.SYSTEM -> gateway.installSystem(
                parentTransactionId = token.value,
                file = install.download,
                metadata = metadata,
                installer = checkNotNull(preState.systemInstaller),
            )
        }
    }

    override suspend fun reload(packageName: String) {
        if (expectedAbsentAfterRollback.contains(packageName)) {
            if (storage { gateway.topology(packageName) }.loaderOrigin == AndroidLoaderOrigin.ABSENT) return
            failStorage("Expected rolled back extension to be absent")
        }
        runtimeReloader(packageName)
    }

    override suspend fun rollback(token: ExtensionInstallRollbackToken) {
        val install = prepared[token.value] ?: failStorage("Unknown Android extension rollback")
        val state = install.preState ?: failStorage("Missing Android extension rollback state")
        when (state.commitTarget) {
            AndroidInstallLocation.PRIVATE -> {
                val snapshot = state.privatePackage
                if (snapshot == null) {
                    if (!gateway.removePrivate(
                            install.artifact.packageName,
                        )
                    ) {
                        failStorage("Failed to remove private extension")
                    }
                } else if (!gateway.installPrivate(snapshot.apk, snapshot)) {
                    failStorage("Failed to restore private extension")
                }
            }
            AndroidInstallLocation.SYSTEM -> {
                val snapshot = state.systemPackage
                if (snapshot == null) {
                    gateway.removeSystem(install.artifact.packageName)
                } else {
                    gateway.installSystem(
                        parentTransactionId = token.value,
                        file = snapshot.apk,
                        metadata = snapshot,
                        installer = checkNotNull(state.systemInstaller),
                    )
                }
            }
        }
        if (state.expectedAbsent) expectedAbsentAfterRollback += install.artifact.packageName
    }

    override suspend fun cleanup(token: PreparedExtensionInstallToken) {
        val install = prepared[token.value] ?: return
        val failures = listOf(
            install.download,
            install.preState?.privatePackage?.apk,
            install.preState?.systemPackage?.apk,
            install.directory,
        )
            .filterNotNull()
            .filterNot { storage { gateway.delete(it) } }
        if (failures.isNotEmpty()) failStorage("Failed to clean extension transaction files")
        prepared.remove(token.value, install)
        expectedAbsentAfterRollback -= install.artifact.packageName
    }

    private fun digest(file: File): String = try {
        Hash.sha256(file.readBytes())
    } catch (error: Throwable) {
        throw ExtensionInstallFailure(AppError.Storage(error))
    }

    private fun ensureContained(root: File, child: File) {
        if (!child.toPath().startsWith(root.toPath())) failStorage("Extension transaction escaped cache root")
    }

    private fun retryFailedPreparationCleanup() {
        failedPreparationCleanup.entries.forEach { (id, files) ->
            val remaining = cleanupFailures(files)
            if (remaining.isEmpty()) {
                failedPreparationCleanup.remove(id, files)
            } else {
                failedPreparationCleanup.replace(id, files, remaining)
                failStorage("Failed to clean previous extension transaction files")
            }
        }
    }

    private fun cleanupFailures(files: List<File>): List<File> = files.filter { file ->
        runCatching { gateway.delete(file) }.getOrDefault(false).not()
    }

    private inline fun <T> storage(block: () -> T): T = try {
        block()
    } catch (failure: ExtensionInstallFailure) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        throw ExtensionInstallFailure(AppError.Storage(failure))
    }

    private fun okhttp3.Response.toDownloadError(): AppError = when (code) {
        401, 403 -> AppError.Authentication()
        429 -> AppError.RateLimited(header("Retry-After")?.toLongOrNull())
        in 500..599 -> AppError.Server(code)
        else -> AppError.Network()
    }

    private data class PreparedInstall(
        val artifact: ExtensionArtifact,
        val directory: File,
        val download: File,
        val commitPlan: AndroidCommitPlan,
        var downloadedSha: String? = null,
        var preState: InstallPreState? = null,
    )
}

internal data class InstallPreState(
    val privatePackage: AndroidInstalledPackage?,
    val systemPackage: AndroidInstalledPackage?,
    val loaderOrigin: AndroidLoaderOrigin,
    val commitTarget: AndroidInstallLocation,
    val systemInstaller: BasePreferences.ExtensionInstaller?,
    val expectedAbsent: Boolean,
)

internal class TrustConfirmationRequiredException(val decision: ExtensionTrustDecision.ConfirmationRequired) :
    IllegalStateException("Extension trust confirmation is required")

private fun AndroidInstallTopology.selected(): AndroidInstalledPackage? = when (loaderOrigin) {
    AndroidLoaderOrigin.PRIVATE -> privatePackage
    AndroidLoaderOrigin.SYSTEM -> systemPackage
    AndroidLoaderOrigin.ABSENT -> null
}

private fun AndroidInstallTopology.allPackages(): List<AndroidInstalledPackage> =
    listOfNotNull(privatePackage, systemPackage)

internal class DefaultAndroidInstallGateway(
    private val context: Context,
    private val installSystem: suspend (String, File, BasePreferences.ExtensionInstaller) -> Unit,
    private val commitPlanProvider: (String) -> AndroidCommitPlan,
    private val apkInspector: ((File) -> AndroidApk?)? = null,
    private val atomicReplace: (File, File) -> Unit = { source, target ->
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    },
    private val deleteFile: (File) -> Boolean = { file -> !file.exists() || file.delete() },
    private val trustInput: (File) -> InputStream = File::inputStream,
) : AndroidInstallGateway {
    override val transactionRoot: File get() = File(context.cacheDir, "extension-installs")
    override fun commitPlan(packageName: String): AndroidCommitPlan = commitPlanProvider(packageName)

    override fun canonical(file: File): File = file.canonicalFile

    override fun writeDownload(input: InputStream, destination: File) {
        destination.outputStream().use { input.copyTo(it) }
    }

    override fun inspect(file: File): AndroidApk? = apkInspector?.invoke(file) ?: packageInfo(file)?.let { info ->
        AndroidApk(
            packageName = info.packageName,
            versionName = info.versionName.orEmpty(),
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            signers = signatures(info).toSet(),
            isExtension = info.reqFeatures.orEmpty().any { it.name == "tachiyomi.extension" },
        )
    }

    override fun topology(packageName: String): AndroidInstallTopology {
        val privateFile = File(context.filesDir, "exts/$packageName.ext")
        val privatePackage = privateFile.takeIf(File::isFile)?.let {
            installedPackage(it, packageName, AndroidInstallLocation.PRIVATE)
                ?: failMalformed("Installed private extension APK cannot be inspected")
        }
        val systemPackage = try {
            context.packageManager.getPackageInfo(packageName, PACKAGE_FLAGS)?.applicationInfo?.sourceDir
                ?.let(::File)?.let {
                    installedPackage(it, packageName, AndroidInstallLocation.SYSTEM)
                        ?: failMalformed("Installed system extension APK cannot be inspected")
                }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val origin = when {
            privatePackage == null && systemPackage == null -> AndroidLoaderOrigin.ABSENT
            privatePackage != null &&
                (systemPackage == null || privatePackage.versionCode > systemPackage.versionCode) ->
                AndroidLoaderOrigin.PRIVATE
            else -> AndroidLoaderOrigin.SYSTEM
        }
        return AndroidInstallTopology(privatePackage, systemPackage, origin)
    }

    override fun copy(source: File, destination: File): Boolean = runCatching {
        destination.parentFile?.mkdirs()
        source.copyTo(destination, overwrite = true)
    }.isSuccess

    override fun makeReadOnly(file: File): Boolean = file.setReadOnly()

    override fun installPrivate(file: File, metadata: AndroidInstalledPackage): Boolean = runCatching {
        val directory = File(context.filesDir, "exts").canonicalFile
        if (!directory.mkdirs() && !directory.isDirectory) error("Failed to create private extension directory")
        val packageName = metadataPackage(file)
        val target = File(directory, "$packageName.ext").canonicalFile
        if (!target.toPath().startsWith(directory.toPath())) error("Private extension path escaped root")
        val temporary = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
        file.copyTo(temporary, overwrite = true)
        if (!temporary.setReadOnly()) error("Failed to make private extension read-only")
        val targetWasReadOnly = target.isFile && !target.canWrite()
        if (targetWasReadOnly && !target.setWritable(true, true)) {
            error("Failed to unlock existing private extension")
        }
        try {
            atomicReplace(temporary, target)
        } catch (failure: Throwable) {
            if (targetWasReadOnly && target.exists()) target.setReadOnly()
            throw failure
        } finally {
            if (temporary.exists()) deleteFile(temporary)
        }
        if (!target.setReadOnly()) error("Failed to keep private extension read-only")
        writeTrust(packageName, AndroidInstallLocation.PRIVATE, metadata.trust)
        ExtensionInstallReceiver.notifyReplaced(context, packageName)
    }.isSuccess

    override suspend fun installSystem(
        parentTransactionId: String,
        file: File,
        metadata: AndroidInstalledPackage,
        installer: BasePreferences.ExtensionInstaller,
    ) {
        installSystem(parentTransactionId, file, installer)
        try {
            writeTrust(metadataPackage(file), AndroidInstallLocation.SYSTEM, metadata.trust)
        } catch (error: Throwable) {
            throw ExtensionInstallFailure(AppError.Storage(error))
        }
    }

    override fun removePrivate(packageName: String): Boolean {
        val file = File(context.filesDir, "exts/$packageName.ext")
        val removed = deleteFile(file)
        return removed && deleteTrust(packageName, AndroidInstallLocation.PRIVATE)
    }

    override suspend fun removeSystem(packageName: String) {
        if (context.isPackageInstalled(packageName)) {
            val action = "${context.packageName}.EXTENSION_ROLLBACK.${UUID.randomUUID()}"
            val result = CompletableDeferred<Int>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    result.complete(intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE))
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
                val status = try {
                    withTimeout(SYSTEM_UNINSTALL_TIMEOUT_MILLIS) { result.await() }
                } catch (_: TimeoutCancellationException) {
                    failStorage("Timed out removing system extension")
                }
                if (status != PackageInstaller.STATUS_SUCCESS) failStorage("Failed to remove system extension")
            } finally {
                context.unregisterReceiver(receiver)
            }
        }
        if (!deleteTrust(packageName, AndroidInstallLocation.SYSTEM)) {
            failStorage("Failed to remove system trust metadata")
        }
    }

    override fun delete(file: File): Boolean = deleteFile(file)

    private fun installedPackage(
        file: File,
        packageName: String,
        location: AndroidInstallLocation,
    ): AndroidInstalledPackage? = inspect(file)?.let {
        AndroidInstalledPackage(file, it.versionName, it.versionCode, it.signers, readTrust(packageName, location))
    }

    private fun metadataPackage(file: File): String = inspect(file)?.packageName ?: error("Invalid extension APK")

    private fun readTrust(packageName: String, location: AndroidInstallLocation): InstalledExtensionTrustRecord? {
        val file = trustFile(packageName, location)
        if (!file.exists()) return null
        if (!file.isFile) failMalformed("Extension trust metadata is not a regular file")
        val properties = try {
            Properties().also { values -> trustInput(file).use(values::load) }
        } catch (failure: IllegalArgumentException) {
            failMalformed("Extension trust metadata is malformed")
        } catch (failure: Throwable) {
            throw ExtensionInstallFailure(AppError.Storage(failure))
        }
        fun required(key: String): String = properties.getProperty(key)?.takeIf(String::isNotBlank)
            ?: failMalformed("Extension trust metadata is missing $key")
        return InstalledExtensionTrustRecord(
            repository = RepositoryIdentity(
                baseUrl = required("repository.baseUrl"),
                name = required("repository.name"),
                signingKeyFingerprint = required("repository.fingerprint"),
            ),
            artifactSha256 = required("artifact.sha256"),
        )
    }

    private fun writeTrust(
        packageName: String,
        location: AndroidInstallLocation,
        trust: InstalledExtensionTrustRecord?,
    ) {
        if (trust?.repository == null) {
            if (!deleteTrust(packageName, location)) error("Failed to clear extension trust metadata")
            return
        }
        val target = trustFile(packageName, location)
        val parent = requireNotNull(target.parentFile)
        if (!parent.mkdirs() && !parent.isDirectory) error("Failed to create trust metadata directory")
        val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
        val repository = requireNotNull(trust.repository)
        try {
            Properties().apply {
                setProperty("repository.baseUrl", repository.baseUrl)
                setProperty("repository.name", repository.name)
                setProperty("repository.fingerprint", repository.signingKeyFingerprint)
                setProperty("artifact.sha256", trust.artifactSha256.orEmpty())
            }.also { values -> temporary.outputStream().use { values.store(it, null) } }
            atomicReplace(temporary, target)
        } finally {
            if (temporary.exists()) deleteFile(temporary)
        }
    }

    private fun deleteTrust(packageName: String, location: AndroidInstallLocation): Boolean {
        val file = trustFile(packageName, location)
        return deleteFile(file)
    }

    private fun trustFile(packageName: String, location: AndroidInstallLocation): File = try {
        val root = File(context.filesDir, "extension-install-metadata").canonicalFile
        val target = File(root, "${location.name.lowercase()}-$packageName.properties").canonicalFile
        if (!target.toPath().startsWith(root.toPath())) failStorage("Extension trust metadata escaped root")
        target
    } catch (failure: ExtensionInstallFailure) {
        throw failure
    } catch (failure: Throwable) {
        throw ExtensionInstallFailure(AppError.Storage(failure))
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(file: File): PackageInfo? = context.packageManager.getPackageArchiveInfo(
        file.absolutePath,
        PACKAGE_FLAGS,
    )

    private fun signatures(info: PackageInfo): List<String> {
        val values = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return values.orEmpty().map { Hash.sha256(it.toByteArray()) }
    }

    private companion object {
        @Suppress("DEPRECATION")
        val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA or
            PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)
    }
}

private const val SYSTEM_UNINSTALL_TIMEOUT_MILLIS = 2 * 60 * 1000L

private fun failMalformed(message: String): Nothing =
    throw ExtensionInstallFailure(AppError.MalformedData(IllegalArgumentException(message)))

private fun failStorage(message: String): Nothing =
    throw ExtensionInstallFailure(AppError.Storage(IllegalStateException(message)))
