package mihon.desktop.extension

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import mihon.domain.extensionrepo.model.ExtensionRepo
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * Fetches and parses available extensions from all registered repositories,
 * and handles JAR download/installation.
 *
 * lib version range accepted: 1.2 – 1.5  (mirrors Android ExtensionLoader constants)
 */
class DesktopExtensionApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val extensionRepoRepository: ExtensionRepoRepository,
    private val apkConverter: ApkToJarConverter = ApkToJarConverter(),
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

    suspend fun findAvailableExtensions(): List<DesktopAvailableExtension> = coroutineScope {
        extensionRepoRepository.getAll()
            .map { repo -> async { fetchExtensionsFromRepo(repo) } }
            .awaitAll()
            .flatten()
    }

    private suspend fun fetchExtensionsFromRepo(repo: ExtensionRepo): List<DesktopAvailableExtension> {
        return try {
            val response = client
                .newCall(GET("${repo.baseUrl}/index.min.json"))
                .awaitSuccess()
            val body = response.body.string()
            json.decodeFromString<List<ExtensionJsonObject>>(body)
                .toDesktopExtensions(repo)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to fetch extensions from ${repo.baseUrl}" }
            emptyList()
        }
    }

    /**
     * Downloads and installs an extension JAR into [targetDir].
     * The JAR URL is derived from the [DesktopAvailableExtension.jarUrl] field.
     */
    suspend fun installExtension(
        extension: DesktopAvailableExtension,
        targetDir: File,
    ): InstallResult = withContext(Dispatchers.IO) {
        return@withContext try {
            targetDir.mkdirs()
            val installedJar = File(targetDir, "${extension.pkgName}.jar")
            val existingMeta = readExtensionMeta(installedJar)
            if (repositoryIdentityConflicts(existingMeta?.repoFingerprint.orEmpty(), extension.repoFingerprint)) {
                return@withContext InstallResult.TrustRequired(
                    existingFingerprint = existingMeta?.repoFingerprint.orEmpty(),
                    incomingFingerprint = extension.repoFingerprint,
                )
            }
            // Download to a .tmp file first so we can inspect the content type
            val downloadedFile = File.createTempFile("${extension.pkgName}.", ".download", targetDir)
            val response = client.newCall(GET(extension.jarUrl)).awaitSuccess()
            response.body.byteStream().use { input ->
                downloadedFile.outputStream().use { output -> input.copyTo(output) }
            }

            // Determine content type by scanning ZIP entries
            val (hasJvmClasses, hasDex) = try {
                ZipFile(downloadedFile).use { zip ->
                    var classes = false
                    var dex = false
                    zip.entries().asSequence().forEach { entry ->
                        if (entry.name.endsWith(".class")) classes = true
                        if (entry.name.matches(Regex("classes\\d*\\.dex"))) dex = true
                    }
                    Pair(classes, dex)
                }
            } catch (_: Exception) {
                Pair(false, false)
            }

            when {
                hasJvmClasses -> {
                    // Pre-compiled JVM JAR — rename and install directly
                    val destFile = File(targetDir, "${extension.pkgName}.jar")
                    replaceExtensionArtifact(downloadedFile, destFile)
                    writeExtensionMeta(
                        destFile,
                        ExtensionMeta(
                            pkgName = extension.pkgName,
                            versionCode = extension.versionCode,
                            versionName = extension.versionName,
                            iconUrl = extension.iconUrl,
                            repoUrl = extension.repoUrl,
                            repoName = extension.repoName,
                            repoFingerprint = extension.repoFingerprint,
                            installedAt = System.currentTimeMillis(),
                            artifactSha256 = destFile.sha256(),
                            source = ExtensionOrigin.COMPILED_JAR,
                        ),
                    )
                    InstallResult.Success(destFile)
                }
                hasDex -> {
                    // Android APK — attempt DEX→JAR conversion via dex2jar
                    val apkFile = File(targetDir, "${extension.pkgName}.apk")
                    downloadedFile.renameTo(apkFile)
                    // Extract extension class from manifest BEFORE deleting the APK
                    val manifestClass = ManifestClassExtractor.extractFromApk(apkFile)
                    val convertedJar = apkConverter.convert(apkFile, targetDir)
                    apkFile.delete()
                    if (convertedJar == null) {
                        InstallResult.Error(
                            "APK convert failed: could not translate DEX bytecode to JVM. " +
                                "This extension may reference APIs not yet supported on desktop.",
                        )
                    } else {
                        // Ensure final JAR is named by package name
                        val finalJar = File(targetDir, "${extension.pkgName}.jar")
                        if (convertedJar.canonicalPath != finalJar.canonicalPath) {
                            replaceExtensionArtifact(convertedJar, finalJar)
                        }
                        writeExtensionMeta(
                            finalJar,
                            ExtensionMeta(
                                pkgName = extension.pkgName,
                                versionCode = extension.versionCode,
                                versionName = extension.versionName,
                                iconUrl = extension.iconUrl,
                                repoUrl = extension.repoUrl,
                                repoName = extension.repoName,
                                repoFingerprint = extension.repoFingerprint,
                                installedAt = System.currentTimeMillis(),
                                artifactSha256 = finalJar.sha256(),
                                source = ExtensionOrigin.CONVERTED_APK,
                                extensionClass = manifestClass,
                            ),
                        )
                        InstallResult.Success(finalJar)
                    }
                }
                else -> {
                    downloadedFile.delete()
                    InstallResult.Error(
                        "Android-only extension: this extension is compiled for Android (DEX) " +
                            "and cannot run on the desktop JVM. " +
                            "Only JVM-compatible extension JARs can be installed.",
                    )
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to install extension ${extension.pkgName}" }
            InstallResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun List<ExtensionJsonObject>.toDesktopExtensions(
        repo: ExtensionRepo,
    ): List<DesktopAvailableExtension> = this
        .filter { it.extractLibVersion() in LIB_VERSION_MIN..LIB_VERSION_MAX }
        .map { obj ->
            DesktopAvailableExtension(
                name = obj.name.substringAfter("Tachiyomi: "),
                pkgName = obj.pkg,
                versionName = obj.version,
                versionCode = obj.code,
                lang = obj.lang,
                isNsfw = obj.nsfw == 1,
                jarUrl = "${repo.baseUrl}/apk/${obj.apk}",
                iconUrl = "${repo.baseUrl}/icon/${obj.pkg}.png",
                repoUrl = repo.baseUrl,
                repoName = repo.name,
                repoFingerprint = repo.signingKeyFingerprint,
                sources = obj.sources.orEmpty().map {
                    DesktopAvailableSource(it.id, it.lang, it.name, it.baseUrl)
                },
            )
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

    private fun ExtensionJsonObject.extractLibVersion(): Double =
        version.substringBeforeLast('.').toDoubleOrNull() ?: 0.0

    sealed interface InstallResult {
        data class Success(val file: File) : InstallResult
        data class Error(val message: String) : InstallResult
        data class TrustRequired(
            val existingFingerprint: String,
            val incomingFingerprint: String,
        ) : InstallResult
    }

    companion object {
        private const val LIB_VERSION_MIN = 1.2
        private const val LIB_VERSION_MAX = 1.5
        private const val MAX_ICON_BYTES = 2 * 1024 * 1024
    }
}

@Serializable
private data class ExtensionJsonObject(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<ExtensionSourceJsonObject>? = null,
)

@Serializable
private data class ExtensionSourceJsonObject(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

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
