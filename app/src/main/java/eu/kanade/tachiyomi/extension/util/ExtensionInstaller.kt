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
internal class ExtensionInstaller(
    private val context: Context,
    private val runtimeReloader: suspend (String) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    installPort: ExtensionInstallPort? = null,
) {

    private val activeJobs = ConcurrentHashMap<String, ActiveInstallJob>()
    private val activeTransactions = ConcurrentHashMap<String, ActiveTransaction>()
    private val activeSteps = ConcurrentHashMap<String, MutableStateFlow<InstallStep>>()
    private val platformResults = ConcurrentHashMap<String, CompletableDeferred<InstallStep>>()
    private val transactionLifecycles = ConcurrentHashMap<String, TransactionLifecycle>()
    private val completedTransactions = ConcurrentHashMap<String, Long>()
    private val cancelledTransactions = ConcurrentHashMap.newKeySet<String>()
    private val extensionInstaller by lazy { Injekt.get<BasePreferences>().extensionInstaller() }
    private val httpClient: OkHttpClient by lazy { Injekt.get<NetworkHelper>().client }
    private val coordinator = ExtensionInstallCoordinator(
        LifecycleInstallPort(
            installPort ?: AndroidInstallPort(
                gateway = DefaultAndroidInstallGateway(
                    context = context,
                    installSystem = ::installPrepared,
                    commitTargetProvider = {
                        if (extensionInstaller.get() == BasePreferences.ExtensionInstaller.PRIVATE) {
                            AndroidInstallLocation.PRIVATE
                        } else {
                            AndroidInstallLocation.SYSTEM
                        }
                    },
                ),
                client = httpClient,
                runtimeReloader = runtimeReloader,
            ),
        ),
        scope,
    )

    fun downloadAndInstall(url: String, extension: Extension.Available): Flow<InstallStep> {
        cancelActiveInstall(extension.pkgName)

        val transactionId = UUID.randomUUID().toString()
        val step = MutableStateFlow(InstallStep.Pending)
        val lifecycle = TransactionLifecycle()
        transactionLifecycles[transactionId] = lifecycle
        activeSteps[transactionId] = step
        activeTransactions[extension.pkgName] = ActiveTransaction(transactionId, step)
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
                            ActiveTransaction(transactionId, step),
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
                activeTransactions.remove(extension.pkgName, ActiveTransaction(transactionId, step))
                activeSteps.remove(transactionId, step)
                completeLifecycle(transactionId, lifecycle)
            }
        }
        val activeJob = ActiveInstallJob(transactionId, job)
        activeJobs[extension.pkgName] = activeJob
        job.start()

        return step.asStateFlow().onCompletion {
            activeJobs.remove(extension.pkgName, activeJob)
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
        val lifecycle = transactionLifecycles.computeIfAbsent(transactionId) { TransactionLifecycle() }
        val result = CompletableDeferred<InstallStep>()
        try {
            synchronized(lifecycle) {
                if (cancelledTransactions.contains(transactionId)) {
                    throw CancellationException("Extension install cancelled")
                }
                platformResults[transactionId] = result
                installApk(transactionId, file)
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
        val lifecycle = transactionLifecycles[active.transactionId] ?: run {
            cancelledTransactions -= active.transactionId
            return
        }
        val cancellationTarget = synchronized(lifecycle) {
            CancellationTarget(lifecycle.phase(), platformResults[active.transactionId])
        }
        if (cancellationTarget.phase == TransactionPhase.COMPLETE) {
            cancelledTransactions -= active.transactionId
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
    val commitTarget: AndroidInstallLocation

    fun canonical(file: File): File
    fun writeDownload(input: InputStream, destination: File)
    fun inspect(file: File): AndroidApk?
    fun topology(packageName: String): AndroidInstallTopology
    fun copy(source: File, destination: File): Boolean
    fun makeReadOnly(file: File): Boolean
    fun installPrivate(file: File, metadata: AndroidInstalledPackage): Boolean
    suspend fun installSystem(transactionId: String, file: File, metadata: AndroidInstalledPackage)
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
) : ExtensionInstallPort {
    private val prepared = ConcurrentHashMap<String, PreparedInstall>()
    private val expectedAbsentAfterRollback = ConcurrentHashMap.newKeySet<String>()

    override suspend fun prepare(request: ExtensionInstallRequest): PreparedExtensionInstallToken {
        val id = UUID.randomUUID().toString()
        val root = gateway.transactionRoot.canonicalFile
        val directory = gateway.canonical(File(root, id))
        val download = gateway.canonical(File(directory, "candidate.apk"))
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
            prepared[id] = PreparedInstall(request.artifact, directory, download)
            return PreparedExtensionInstallToken(id)
        } catch (failure: Throwable) {
            runCatching { gateway.delete(download) }
            runCatching { gateway.delete(directory) }
            throw failure
        }
    }

    override suspend fun validate(token: PreparedExtensionInstallToken): ExtensionInstallRollbackToken {
        val install = prepared[token.value] ?: failStorage("Unknown Android extension install")
        val candidate = gateway.inspect(install.download) ?: failMalformed("Downloaded file is not an APK")
        if (!candidate.isExtension || candidate.packageName != install.artifact.packageName ||
            candidate.versionName != install.artifact.versionName ||
            candidate.versionCode != install.artifact.versionCode
        ) {
            failMalformed("Downloaded APK metadata does not match repository metadata")
        }
        if (candidate.signers.isEmpty()) failMalformed("Downloaded extension is unsigned")

        val downloadedSha = digest(install.download)
        val topology = gateway.topology(candidate.packageName)
        val selected = topology.selected()
        val trustDecision = trustPolicy.evaluate(
            ExtensionTrustRequest(
                incomingArtifact = install.artifact,
                downloadedArtifactSha256 = downloadedSha,
                installed = selected?.trust,
                installedArtifactSha256 = selected?.let { digest(it.apk) },
            ),
        )
        when (trustDecision) {
            ExtensionTrustDecision.Trusted -> Unit
            is ExtensionTrustDecision.Rejected -> throw ExtensionInstallFailure(trustDecision.error)
            is ExtensionTrustDecision.ConfirmationRequired -> throw ExtensionInstallFailure(
                AppError.Authentication(TrustConfirmationRequiredException(trustDecision)),
            )
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

        val target = gateway.commitTarget
        fun snapshot(location: AndroidInstallLocation, current: AndroidInstalledPackage?): AndroidInstalledPackage? =
            current?.let {
                File(install.directory, "rollback-${location.name.lowercase()}.apk").also { destination ->
                    if (!gateway.copy(it.apk, destination)) failStorage("Failed to snapshot installed extension")
                    if (!gateway.makeReadOnly(destination)) failStorage("Failed to make extension snapshot read-only")
                }.let { destination -> it.copy(apk = destination) }
            }
        val privateSnapshot = snapshot(AndroidInstallLocation.PRIVATE, topology.privatePackage)
        val systemSnapshot = snapshot(AndroidInstallLocation.SYSTEM, topology.systemPackage)
        install.preState = InstallPreState(
            privatePackage = privateSnapshot,
            systemPackage = systemSnapshot,
            loaderOrigin = topology.loaderOrigin,
            commitTarget = target,
            expectedAbsent = topology.loaderOrigin == AndroidLoaderOrigin.ABSENT,
        )
        install.downloadedSha = downloadedSha
        return ExtensionInstallRollbackToken(token.value)
    }

    override suspend fun commit(token: PreparedExtensionInstallToken) {
        val install = prepared[token.value] ?: failStorage("Unknown Android extension install")
        val preState = install.preState ?: failStorage("Android extension was not validated")
        val candidate = gateway.inspect(install.download) ?: failMalformed("Downloaded file is not an APK")
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
            AndroidInstallLocation.SYSTEM -> gateway.installSystem(token.value, install.download, metadata)
        }
    }

    override suspend fun reload(packageName: String) {
        if (expectedAbsentAfterRollback.remove(packageName)) {
            if (gateway.topology(packageName).loaderOrigin == AndroidLoaderOrigin.ABSENT) return
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
                    gateway.installSystem(token.value, snapshot.apk, snapshot)
                }
            }
        }
        if (state.expectedAbsent) expectedAbsentAfterRollback += install.artifact.packageName
    }

    override suspend fun cleanup(token: PreparedExtensionInstallToken) {
        val install = prepared.remove(token.value) ?: return
        val failures = listOf(
            install.download,
            install.preState?.privatePackage?.apk,
            install.preState?.systemPackage?.apk,
            install.directory,
        )
            .filterNotNull()
            .filterNot(gateway::delete)
        if (failures.isNotEmpty()) failStorage("Failed to clean extension transaction files")
    }

    private fun digest(file: File): String = try {
        Hash.sha256(file.readBytes())
    } catch (error: Throwable) {
        throw ExtensionInstallFailure(AppError.Storage(error))
    }

    private fun ensureContained(root: File, child: File) {
        if (!child.toPath().startsWith(root.toPath())) failStorage("Extension transaction escaped cache root")
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
        var downloadedSha: String? = null,
        var preState: InstallPreState? = null,
    )
}

internal data class InstallPreState(
    val privatePackage: AndroidInstalledPackage?,
    val systemPackage: AndroidInstalledPackage?,
    val loaderOrigin: AndroidLoaderOrigin,
    val commitTarget: AndroidInstallLocation,
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

private class DefaultAndroidInstallGateway(
    private val context: Context,
    private val installSystem: suspend (String, File) -> Unit,
    private val commitTargetProvider: () -> AndroidInstallLocation,
) : AndroidInstallGateway {
    override val transactionRoot: File get() = File(context.cacheDir, "extension-installs")
    override val commitTarget: AndroidInstallLocation get() = commitTargetProvider()

    override fun canonical(file: File): File = file.canonicalFile

    override fun writeDownload(input: InputStream, destination: File) {
        destination.outputStream().use { input.copyTo(it) }
    }

    override fun inspect(file: File): AndroidApk? = packageInfo(file)?.let { info ->
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
        }
        val systemPackage = try {
            context.packageManager.getPackageInfo(packageName, PACKAGE_FLAGS)?.applicationInfo?.sourceDir
                ?.let(::File)?.let { installedPackage(it, packageName, AndroidInstallLocation.SYSTEM) }
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
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        writeTrust(packageName, AndroidInstallLocation.PRIVATE, metadata.trust)
        ExtensionInstallReceiver.notifyReplaced(context, packageName)
    }.isSuccess

    override suspend fun installSystem(transactionId: String, file: File, metadata: AndroidInstalledPackage) {
        installSystem(transactionId, file)
        try {
            writeTrust(metadataPackage(file), AndroidInstallLocation.SYSTEM, metadata.trust)
        } catch (error: Throwable) {
            throw ExtensionInstallFailure(AppError.Storage(error))
        }
    }

    override fun removePrivate(packageName: String): Boolean {
        val file = File(context.filesDir, "exts/$packageName.ext")
        val removed = !file.exists() || file.delete()
        return removed && deleteTrust(packageName, AndroidInstallLocation.PRIVATE)
    }

    override suspend fun removeSystem(packageName: String) {
        if (!context.isPackageInstalled(packageName)) return
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
            if (result.await() != PackageInstaller.STATUS_SUCCESS) failStorage("Failed to remove system extension")
            if (!deleteTrust(
                    packageName,
                    AndroidInstallLocation.SYSTEM,
                )
            ) {
                failStorage("Failed to remove system trust metadata")
            }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    override fun delete(file: File): Boolean = !file.exists() || file.delete()

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
        if (!file.isFile) return null
        return runCatching {
            val properties = Properties().also { values -> file.inputStream().use(values::load) }
            val repository = RepositoryIdentity(
                baseUrl = properties.getProperty("repository.baseUrl") ?: return null,
                name = properties.getProperty("repository.name") ?: return null,
                signingKeyFingerprint = properties.getProperty("repository.fingerprint") ?: return null,
            )
            InstalledExtensionTrustRecord(repository, properties.getProperty("artifact.sha256"))
        }.getOrNull()
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
        Properties().apply {
            setProperty("repository.baseUrl", repository.baseUrl)
            setProperty("repository.name", repository.name)
            setProperty("repository.fingerprint", repository.signingKeyFingerprint)
            setProperty("artifact.sha256", trust.artifactSha256.orEmpty())
        }.also { values -> temporary.outputStream().use { values.store(it, null) } }
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun deleteTrust(packageName: String, location: AndroidInstallLocation): Boolean {
        val file = trustFile(packageName, location)
        return !file.exists() || file.delete()
    }

    private fun trustFile(packageName: String, location: AndroidInstallLocation) =
        File(context.filesDir, "extension-install-metadata/${location.name.lowercase()}-$packageName.properties")

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

private fun failMalformed(message: String): Nothing =
    throw ExtensionInstallFailure(AppError.MalformedData(IllegalArgumentException(message)))

private fun failStorage(message: String): Nothing =
    throw ExtensionInstallFailure(AppError.Storage(IllegalStateException(message)))
