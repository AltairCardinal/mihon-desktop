package mihon.desktop.release

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.desktop.DesktopAppRuntime
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.di.initDesktopDI
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.service.ExtensionInstallFailure
import mihon.domain.extension.service.ExtensionInstallState
import tachiyomi.core.common.preference.DesktopPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal data class DesktopExtensionRuntimeAcceptanceRequest(
    val profileDirectory: Path,
    val resultFile: Path,
    val artifactUrl: String,
    val packageName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val repositoryFingerprint: String,
    val artifactSha256: String,
    val expectedSourceId: Long?,
)

@Serializable
internal data class DesktopExtensionRuntimeAcceptanceResult(
    val success: Boolean,
    val appVersion: String,
    val packageName: String,
    val sourceIds: List<Long> = emptyList(),
    val error: String? = null,
)

internal fun desktopExtensionRuntimeAcceptanceRequest(
    args: Array<String>,
): DesktopExtensionRuntimeAcceptanceRequest? {
    if (RUNTIME_ACCEPTANCE_MARKER !in args) return null
    require("--test-mode" in args && "--headless" in args) {
        "Extension runtime acceptance requires --test-mode and --headless"
    }
    val values = args.mapNotNull { argument ->
        argument.takeIf { it.startsWith(RUNTIME_ACCEPTANCE_PREFIX) && '=' in it }
            ?.let { it.substringBefore('=') to it.substringAfter('=') }
    }.toMap()
    fun required(name: String): String = values[name]?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Missing extension runtime acceptance argument: $name")

    val profile = Path.of(required("--test-extension-runtime-profile")).toAbsolutePath().normalize()
    val result = Path.of(required("--test-extension-runtime-result")).toAbsolutePath().normalize()
    require(result.startsWith(profile) && result != profile) {
        "Extension runtime acceptance result must stay inside its isolated profile"
    }
    val artifactUrl = required("--test-extension-runtime-url")
    val artifactUri = URI(artifactUrl)
    require(artifactUri.scheme == "http" && artifactUri.host.isLoopbackHost()) {
        "Extension runtime acceptance only permits a loopback HTTP artifact"
    }
    val packageName = required("--test-extension-runtime-package")
    require(packageName.matches(PACKAGE_NAME_PATTERN)) { "Invalid extension package name" }
    val fingerprint = required("--test-extension-runtime-fingerprint").normalizedDigest()
    val sha256 = required("--test-extension-runtime-sha256").normalizedDigest()
    require(fingerprint.matches(SHA256_PATTERN)) { "Invalid repository fingerprint" }
    require(sha256.matches(SHA256_PATTERN)) { "Invalid artifact SHA-256" }

    return DesktopExtensionRuntimeAcceptanceRequest(
        profileDirectory = profile,
        resultFile = result,
        artifactUrl = artifactUrl,
        packageName = packageName,
        name = required("--test-extension-runtime-name"),
        versionName = required("--test-extension-runtime-version"),
        versionCode = required("--test-extension-runtime-code").toLong(),
        repositoryFingerprint = fingerprint,
        artifactSha256 = sha256,
        expectedSourceId = values["--test-extension-runtime-source-id"]?.takeIf(String::isNotBlank)?.toLong(),
    )
}

internal suspend fun executeDesktopExtensionRuntimeAcceptance(
    request: DesktopExtensionRuntimeAcceptanceRequest,
    appVersion: String,
    installer: suspend (DesktopExtensionRuntimeAcceptanceRequest) -> List<Long> =
        ::installWithProductionDesktopRuntime,
): DesktopExtensionRuntimeAcceptanceResult {
    val result = try {
        DesktopExtensionRuntimeAcceptanceResult(
            success = true,
            appVersion = appVersion,
            packageName = request.packageName,
            sourceIds = installer(request),
        )
    } catch (failure: Throwable) {
        DesktopExtensionRuntimeAcceptanceResult(
            success = false,
            appVersion = appVersion,
            packageName = request.packageName,
            error = failure.acceptanceFailureChain(),
        )
    }
    Files.createDirectories(request.resultFile.parent)
    Files.writeString(
        request.resultFile,
        Json { prettyPrint = true }.encodeToString(result),
        StandardCharsets.UTF_8,
    )
    return result
}

private suspend fun installWithProductionDesktopRuntime(
    request: DesktopExtensionRuntimeAcceptanceRequest,
): List<Long> {
    Files.createDirectories(request.profileDirectory)
    val preferenceRoot = Preferences.userRoot()
    val preferenceNode = preferenceRoot.node("/mihon/runtime-acceptance/${UUID.randomUUID()}")
    val preferenceStore = DesktopPreferenceStore(preferenceNode)
    var manager: DesktopExtensionManager? = null
    var networkHelper: DesktopNetworkHelper? = null
    var runtime: DesktopAppRuntime? = null
    try {
        initDesktopDI(request.profileDirectory.desktopPaths(), preferenceStore)
        manager = Injekt.get()
        networkHelper = Injekt.get()
        runtime = Injekt.get()
        runtime.start()
        val terminal = manager.installExtension(request.toArtifact())
        if (terminal is ExtensionInstallState.Failed) throw ExtensionInstallFailure(terminal.error)
        check(terminal is ExtensionInstallState.Installed) {
            "Extension install did not reach Installed: ${terminal::class.simpleName}"
        }
        val installed = manager.installedExtensions.value.singleOrNull { it.pkgName == request.packageName }
            ?: error("Installed extension was not published by the production manager")
        check(installed.jarFile.isFile && installed.jarFile.length() > 0L) {
            "Installed extension JAR is missing or empty"
        }
        val sourceIds = installed.sources.map { it.id }
        check(sourceIds.isNotEmpty()) { "Installed extension exposed no sources" }
        request.expectedSourceId?.let { expected ->
            check(expected in sourceIds) { "Expected source $expected was not loaded; actual=$sourceIds" }
        }
        return sourceIds
    } finally {
        runCatching { runtime?.closeAndJoin() }
        runCatching { manager?.close() }
        runCatching { networkHelper?.close() }
        runCatching { preferenceStore.clearAndFlush() }
        runCatching { preferenceNode.removeNode() }
        runCatching { preferenceRoot.flush() }
    }
}

private fun DesktopExtensionRuntimeAcceptanceRequest.toArtifact(): ExtensionArtifact {
    val uri = URI(artifactUrl)
    val repositoryUrl = "${uri.scheme}://${uri.authority}"
    return ExtensionArtifact(
        name = name,
        packageName = packageName,
        versionName = versionName,
        versionCode = versionCode,
        language = "zh",
        isNsfw = true,
        sources = expectedSourceId?.let {
            listOf(ExtensionSourceDescriptor(it, "zh", name, repositoryUrl))
        }.orEmpty(),
        repository = RepositoryIdentity(repositoryUrl, "Runtime acceptance", repositoryFingerprint),
        downloadUrl = artifactUrl,
        iconUrl = "",
        declaredSha256 = artifactSha256,
    )
}

private fun Path.desktopPaths(): DesktopPlatformPaths {
    val root = toFile()
    return DesktopPlatformPaths(
        configDir = root.resolve("config"),
        databaseFile = root.resolve("config/mihon.db"),
        networkCacheDir = root.resolve("local/cache/network"),
        cookiesFile = root.resolve("config/cookies.json"),
        downloadsDir = root.resolve("local/downloads"),
        extensionsDir = root.resolve("local/extensions"),
        coversDir = root.resolve("local/covers"),
        logsDir = root.resolve("local/logs"),
        backupsDir = root.resolve("local/backups"),
    )
}

private fun Throwable.acceptanceFailureChain(): String = generateSequence(this) { it.cause }
    .joinToString(" -> ") { failure ->
        val type = failure::class.qualifiedName ?: failure::class.simpleName ?: "Throwable"
        failure.message?.let { "$type: $it" } ?: type
    }

private fun String.normalizedDigest() = replace(":", "").trim().lowercase()
private fun String?.isLoopbackHost() = this == "127.0.0.1" || this == "localhost" || this == "::1"

private const val RUNTIME_ACCEPTANCE_MARKER = "--test-extension-runtime"
private const val RUNTIME_ACCEPTANCE_PREFIX = "--test-extension-runtime-"
private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_.]*")
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
