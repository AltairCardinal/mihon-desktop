package mihon.desktop.extension

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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

/** Fetches Desktop extension catalogs and provides downloaded artifacts to the install manager. */
class DesktopExtensionApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val extensionRepoRepository: ExtensionRepoRepository,
    private val apkConverter: ApkToJarConverter = ApkToJarConverter(),
    private val catalogService: ExtensionCatalogService = ExtensionCatalogService(),
    private val trustPolicy: ExtensionTrustPolicy = ExtensionTrustPolicy(),
) {
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

    suspend fun findAvailableExtensions(): List<DesktopAvailableExtension> = refreshCatalog().entries
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
        targetDir: File,
    ): InstallResult = withContext(Dispatchers.IO) {
        targetDir.mkdirs()
        val installedJar = File(targetDir, "${extension.pkgName}.jar")
        val existingMeta = readExtensionMeta(installedJar)
        val trustRequest = extension.trustRequest(installedJar, existingMeta)
        trustFailure(trustPolicy.evaluate(trustRequest), existingMeta, extension)?.let { return@withContext it }

        val manager = DesktopExtensionManager(DesktopExtensionLoader(targetDir)).also { it.loadAll() }
        try {
            when (
                val terminal = manager.installExtension(
                    artifact = trustRequest.incomingArtifact,
                    artifactProvider = ::downloadArtifact,
                    apkConverter = apkConverter,
                )
            ) {
                is ExtensionInstallState.Installed -> InstallResult.Success(installedJar)
                is ExtensionInstallState.Failed -> terminal.error.toInstallResult()
                else -> InstallResult.Error("Extension install ended before reaching a terminal state")
            }
        } finally {
            manager.close()
        }
    }

    private suspend fun downloadArtifact(artifact: ExtensionArtifact, destination: File) {
        client.newCall(GET(artifact.downloadUrl)).awaitSuccess().use { response ->
            response.body.byteStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun AppError.toInstallResult(): InstallResult.Error {
        val detail = cause?.message ?: "Unknown error"
        val message = if (detail == "Downloaded extension digest mismatch") {
            "Extension artifact integrity validation failed"
        } else {
            detail
        }
        return InstallResult.Error(message, this)
    }

    private fun File.sha256(): String = inputStream().use { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
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

    private fun trustFailure(
        decision: ExtensionTrustDecision,
        existingMeta: ExtensionMeta?,
        extension: DesktopAvailableExtension,
    ): InstallResult? = when (decision) {
        ExtensionTrustDecision.Trusted -> null
        is ExtensionTrustDecision.ConfirmationRequired -> InstallResult.TrustRequired(
            existingFingerprint = existingMeta?.repoFingerprint.orEmpty(),
            incomingFingerprint = extension.repoFingerprint,
            reasons = decision.reasons,
        )
        is ExtensionTrustDecision.Rejected -> InstallResult.Error(
            message = "Extension artifact integrity validation failed",
            error = decision.error,
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

    companion object {
        private const val MAX_ICON_BYTES = 2 * 1024 * 1024
    }
}

internal fun replaceExtensionArtifact(candidate: File, destination: File) {
    val backup = File(destination.parentFile, "${destination.name}.backup")
    backup.delete()
    if (destination.exists()) {
        Files.move(destination.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    try {
        Files.move(
            candidate.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        backup.delete()
    } catch (error: Exception) {
        if (backup.exists()) {
            Files.move(backup.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        throw error
    }
}
