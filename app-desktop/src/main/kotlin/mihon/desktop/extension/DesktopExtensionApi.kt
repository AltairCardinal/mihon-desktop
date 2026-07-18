package mihon.desktop.extension

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.awaitSuccess
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.last
import kotlinx.serialization.json.Json
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.InstalledExtensionTrustRecord
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.model.toIdentity
import mihon.domain.extension.service.ExtensionCatalogService
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.service.ExtensionTrustDecision
import mihon.domain.extension.service.ExtensionTrustPolicy
import mihon.domain.extension.service.ExtensionTrustRequest
import mihon.domain.extension.service.RepositoryFetchResult
import mihon.domain.extension.service.TrustMismatch
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import mihon.domain.extensionrepo.service.ExtensionRepoIndexEntryDto
import mihon.domain.extensionrepo.service.toCatalogEntry
import okhttp3.OkHttpClient
import java.util.UUID

sealed interface DesktopExtensionInstallStart {
    data class Started(val states: Flow<ExtensionInstallState>) : DesktopExtensionInstallStart
    data class TrustRequired(
        val requestId: String,
        val existingFingerprint: String,
        val incomingFingerprint: String,
        val reasons: Set<TrustMismatch>,
        internal val request: ExtensionTrustRequest,
    ) : DesktopExtensionInstallStart
    data class Rejected(val error: AppError) : DesktopExtensionInstallStart
}

/** Fetches Desktop extension catalogs and provides downloaded artifacts to the install manager. */
class DesktopExtensionApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val extensionRepoRepository: ExtensionRepoRepository,
    private val catalogService: ExtensionCatalogService = ExtensionCatalogService(),
    private val trustPolicy: ExtensionTrustPolicy = ExtensionTrustPolicy(),
) {
    private val pendingTrust = mutableMapOf<String, ExtensionTrustRequest>()
    suspend fun loadExtensionIcon(iconUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        if (iconUrl.isBlank()) return@withContext null
        runCatching {
            client.newCall(GET(iconUrl)).awaitSuccess().use { response ->
                val contentType = response.header("Content-Type").orEmpty()
                if (!contentType.startsWith("image/", ignoreCase = true)) return@use null
                response.body.bytes().takeIf { it.isNotEmpty() && it.size <= MAX_ICON_BYTES }
            }
        }.getOrNull()
    }

    suspend fun findAvailableExtensions(): List<DesktopAvailableExtension> = availableExtensions(refreshCatalog())

    internal fun availableExtensions(catalog: ExtensionCatalogResult): List<DesktopAvailableExtension> = catalog.entries
        .filter { it.compatibility == ExtensionCompatibility.Compatible }
        .map { entry ->
            val artifact = entry.artifact
            DesktopAvailableExtension(
                name = artifact.name,
                pkgName = artifact.packageName,
                versionName = artifact.versionName,
                versionCode = artifact.versionCode,
                libVersion = artifact.libVersion,
                lang = artifact.language,
                isNsfw = artifact.isNsfw,
                jarUrl = artifact.downloadUrl,
                iconUrl = artifact.iconUrl,
                repoUrl = artifact.repository.baseUrl,
                repoName = artifact.repository.name,
                repoFingerprint = artifact.repository.signingKeyFingerprint,
                declaredSha256 = artifact.declaredSha256,
                sources = artifact.sources.map {
                    DesktopAvailableSource(it.id, it.language, it.name, it.baseUrl)
                },
            )
        }

    suspend fun refreshCatalog(): ExtensionCatalogResult = coroutineScope {
        catalogService.refresh(extensionRepoRepository.getAll(), ::fetchRepository)
    }

    private suspend fun fetchRepository(repo: ExtensionRepo): RepositoryFetchResult {
        val response = client.newCall(GET("${repo.baseUrl}/index.min.json")).awaitSuccess()
        val entries = json.decodeFromString<List<ExtensionRepoIndexEntryDto>>(response.body.string())
            .map { it.toCatalogEntry(repo) }
        return RepositoryFetchResult.Success(repo.toIdentity(), entries)
    }

    suspend fun installExtension(
        extension: DesktopAvailableExtension,
        manager: DesktopExtensionManager,
    ): InstallResult = withContext(Dispatchers.IO) {
        when (val start = beginInstall(extension, manager)) {
            is DesktopExtensionInstallStart.Started -> when (val terminal = start.states.last()) {
                is ExtensionInstallState.Installed -> InstallResult.Success(
                    extensionArtifactFile(manager.extensionsDirectory, extension.pkgName, "jar"),
                )
                is ExtensionInstallState.Failed -> terminal.error.toInstallResult()
                else -> InstallResult.Error("Extension install ended before reaching a terminal state")
            }
            is DesktopExtensionInstallStart.TrustRequired -> {
                discardTrust(start.requestId)
                InstallResult.TrustRequired(start.existingFingerprint, start.incomingFingerprint, start.reasons)
            }
            is DesktopExtensionInstallStart.Rejected -> start.error.toInstallResult()
        }
    }

    internal suspend fun beginInstall(
        extension: DesktopAvailableExtension,
        manager: DesktopExtensionManager,
    ): DesktopExtensionInstallStart = withContext(Dispatchers.IO) {
        try {
            val installedJar = extensionArtifactFile(manager.extensionsDirectory, extension.pkgName, "jar")
            val meta = readExtensionMeta(installedJar)
            val request = extension.trustRequest(installedJar, meta)
            when (val decision = trustPolicy.evaluate(request)) {
                ExtensionTrustDecision.Trusted -> DesktopExtensionInstallStart.Started(
                    manager.installExtensionStates(request.incomingArtifact),
                )
                is ExtensionTrustDecision.ConfirmationRequired -> {
                    val id = UUID.randomUUID().toString()
                    synchronized(pendingTrust) { pendingTrust[id] = request }
                    DesktopExtensionInstallStart.TrustRequired(
                        id, meta?.repoFingerprint.orEmpty(), extension.repoFingerprint, decision.reasons, request,
                    )
                }
                is ExtensionTrustDecision.Rejected -> DesktopExtensionInstallStart.Rejected(decision.error)
            }
        } catch (failure: mihon.domain.extension.service.ExtensionInstallFailure) {
            DesktopExtensionInstallStart.Rejected(failure.error)
        }
    }

    internal fun confirmTrust(
        requestId: String,
        manager: DesktopExtensionManager,
    ): Flow<ExtensionInstallState>? = synchronized(pendingTrust) { pendingTrust.remove(requestId) }
        ?.let { manager.installExtensionStates(it.incomingArtifact) }

    internal fun discardTrust(requestId: String): Boolean =
        synchronized(pendingTrust) { pendingTrust.remove(requestId) != null }

    internal val pendingTrustCount: Int get() = synchronized(pendingTrust) { pendingTrust.size }

    internal suspend fun downloadArtifact(artifact: ExtensionArtifact, destination: File) {
        val response = try {
            client.newCall(GET(artifact.downloadUrl)).awaitSuccess()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: HttpException) {
            throw mihon.domain.extension.service.ExtensionInstallFailure(failure.toDownloadError())
        } catch (failure: IOException) {
            throw mihon.domain.extension.service.ExtensionInstallFailure(AppError.Network(failure))
        }
        response.use {
            val input = networkIo { response.body.byteStream() }
            try {
                val output = storageIo { destination.outputStream() }
                try {
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = networkIo { input.read(buffer) }
                        if (read < 0) break
                        storageIo { output.write(buffer, 0, read) }
                    }
                    storageIo { output.flush() }
                } finally {
                    storageIo { output.close() }
                }
            } finally {
                networkIo { input.close() }
            }
        }
    }

    private fun AppError.toInstallResult(): InstallResult.Error {
        val message = when (this) {
            is AppError.Network -> cause?.message ?: "Network error while downloading extension"
            is AppError.Authentication -> "Extension download was rejected by the server"
            is AppError.Challenge -> "Extension download requires an unsupported challenge"
            is AppError.RateLimited -> "Extension download was rate limited"
            is AppError.Server -> "Extension server returned HTTP $statusCode"
            is AppError.Permission -> cause?.message ?: "Permission denied while installing extension"
            is AppError.MalformedData -> if (cause?.message == "Downloaded extension digest mismatch") {
                "Extension artifact integrity validation failed"
            } else {
                cause?.message ?: "Extension artifact is malformed"
            }
            is AppError.Storage -> cause?.message ?: "Unable to store extension artifact"
            AppError.Cancelled -> "Extension installation was cancelled"
            is AppError.PartialFailure -> failures.joinToString(
                prefix = "Extension installation partially failed: ",
                separator = "; ",
            ) { it.toInstallResult().message }
            is AppError.Unknown -> cause?.message ?: "Unknown extension installation error"
        }
        return InstallResult.Error(message, this)
    }

    private fun HttpException.toDownloadError(): AppError = when (code) {
        401, 403 -> AppError.Authentication(this)
        429 -> AppError.RateLimited(cause = this)
        else -> AppError.Server(code, this)
    }

    private fun File.sha256(): String = try {
        inputStream().use { input ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (failure: IOException) {
        throw mihon.domain.extension.service.ExtensionInstallFailure(AppError.Storage(failure))
    } catch (failure: SecurityException) {
        throw mihon.domain.extension.service.ExtensionInstallFailure(AppError.Storage(failure))
    }

    private fun DesktopAvailableExtension.trustRequest(
        installedJar: File,
        existingMeta: ExtensionMeta?,
    ): ExtensionTrustRequest {
        val installed = installedJar.takeIf(File::exists)?.let {
            InstalledExtensionTrustRecord(
                repository = existingMeta?.takeIf {
                    it.repoUrl.isNotBlank() && it.repoFingerprint.isNotBlank()
                }?.let {
                    RepositoryIdentity(it.repoUrl, it.repoName, it.repoFingerprint)
                },
                artifactSha256 = existingMeta?.artifactSha256,
            )
        }
        return ExtensionTrustRequest(
            incomingArtifact = ExtensionArtifact(
                name = name,
                packageName = pkgName,
                versionName = versionName,
                versionCode = versionCode,
                language = lang,
                isNsfw = isNsfw,
                sources = sources.map { ExtensionSourceDescriptor(it.id, it.lang, it.name, it.baseUrl) },
                repository = RepositoryIdentity(repoUrl, repoName, repoFingerprint),
                downloadUrl = jarUrl,
                iconUrl = iconUrl,
                declaredSha256 = declaredSha256,
            ),
            downloadedArtifactSha256 = null,
            installed = installed,
            installedArtifactSha256 = installedJar.takeIf(File::exists)?.sha256(),
        )
    }

    sealed interface InstallResult {
        data class Success(val file: File) : InstallResult
        data class Error(val message: String, val error: AppError? = null) : InstallResult
        data class TrustRequired(
            val existingFingerprint: String,
            val incomingFingerprint: String,
            val reasons: Set<TrustMismatch>,
        ) : InstallResult
    }

    private inline fun <T> networkIo(operation: () -> T): T = try {
        operation()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: mihon.domain.extension.service.ExtensionInstallFailure) {
        throw failure
    } catch (failure: IOException) {
        throw mihon.domain.extension.service.ExtensionInstallFailure(AppError.Network(failure))
    }

    private inline fun <T> storageIo(operation: () -> T): T = try {
        operation()
    } catch (failure: mihon.domain.extension.service.ExtensionInstallFailure) {
        throw failure
    } catch (failure: IOException) {
        throw mihon.domain.extension.service.ExtensionInstallFailure(AppError.Storage(failure))
    } catch (failure: SecurityException) {
        throw mihon.domain.extension.service.ExtensionInstallFailure(AppError.Storage(failure))
    }

    companion object {
        private const val MAX_ICON_BYTES = 2 * 1024 * 1024
    }
}
