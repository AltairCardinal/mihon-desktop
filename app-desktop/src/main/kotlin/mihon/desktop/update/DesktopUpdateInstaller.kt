package mihon.desktop.update

import kotlinx.coroutines.CancellationException
import mihon.desktop.platform.CommandRunner
import mihon.desktop.platform.ProcessCommandRunner
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.ReleasePackageType
import tachiyomi.domain.release.model.ReleaseTarget
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Base64

data class InstallerTrust(val windowsPublisher: String? = null, val macTeamId: String? = null)
sealed interface InstallPreparation
class ReadyToInstall internal constructor(val download: VerifiedDownload, val releaseTag: String, internal val launchArguments: List<String>) : InstallPreparation
data object InstallManualOnly : InstallPreparation
data class InstallRejected(val reason: InstallFailure) : InstallPreparation
enum class InstallFailure {
    TARGET_MISMATCH,
    ASSET_NAME_MISMATCH,
    FILE_MISSING,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    SIGNATURE_INVALID,
    PUBLISHER_MISMATCH,
    NOTARIZATION_FAILED,
    VERIFIER_UNAVAILABLE,
    LAUNCH_FAILED,
}
sealed interface InstallHandoffResult
data object InstallCancelled : InstallHandoffResult
data object InstallHandedOff : InstallHandoffResult
data class InstallHandoffFailed(val reason: InstallFailure) : InstallHandoffResult
class DesktopUpdateInstaller(
    private val currentTarget: ReleaseTarget, private val trust: InstallerTrust = InstallerTrust(),
    private val runner: CommandRunner = ProcessCommandRunner(), private val launcher: (List<String>) -> Boolean = { ProcessBuilder(it).start(); true },
) {
    fun prepare(download: VerifiedDownload, releaseTag: String): InstallPreparation {
        val os = currentTarget.os
        if (os != ReleaseOs.WINDOWS && os != ReleaseOs.MACOS) return InstallManualOnly
        val assetPattern = canonicalAssetPattern(currentTarget, releaseTag) ?: return rejected(InstallFailure.TARGET_MISMATCH)
        if (download.asset.target != currentTarget) return rejected(InstallFailure.TARGET_MISMATCH)
        if (!assetPattern.matches(download.asset.name)) return rejected(InstallFailure.ASSET_NAME_MISMATCH)
        val identity = if (os == ReleaseOs.WINDOWS) trust.windowsPublisher?.takeIf(String::isNotBlank)
        else trust.macTeamId?.takeIf(TEAM_ID::matches)
        identity ?: return InstallManualOnly
        integrityFailure(download)?.let { return rejected(it) }
        val verification = try {
            if (os == ReleaseOs.WINDOWS) verifyWindows(download, identity) else verifyMac(download, identity)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            InstallFailure.VERIFIER_UNAVAILABLE
        }
        return verification?.let(::rejected) ?: ReadyToInstall(download, releaseTag, launchArguments(os, download))
    }
    fun handoff(ready: ReadyToInstall, confirmed: Boolean): InstallHandoffResult {
        if (!confirmed) return InstallCancelled
        val refreshed = prepare(ready.download, ready.releaseTag)
        if (refreshed !is ReadyToInstall) {
            val failure = (refreshed as? InstallRejected)?.reason ?: InstallFailure.VERIFIER_UNAVAILABLE
            return InstallHandoffFailed(failure)
        }
        val launched = try {
            launcher(refreshed.launchArguments)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            false
        }
        return if (launched) InstallHandedOff else InstallHandoffFailed(InstallFailure.LAUNCH_FAILED)
    }
    private fun integrityFailure(download: VerifiedDownload): InstallFailure? {
        val file = download.file
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return InstallFailure.FILE_MISSING
        return try {
            if (Files.size(file) != download.sizeBytes) return InstallFailure.SIZE_MISMATCH
            val checksum = download.asset.checksum
            if (!SHA_256.matches(download.sha256) || checksum?.algorithm != "sha256" || checksum.value.lowercase() != download.sha256) {
                return InstallFailure.HASH_MISMATCH
            }
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
                .takeUnless(download.sha256::equals)
                ?.let { InstallFailure.HASH_MISMATCH }
        } catch (error: IOException) {
            InstallFailure.FILE_MISSING
        }
    }
    private fun verifyWindows(download: VerifiedDownload, publisher: String): InstallFailure? {
        val input = listOf(download.file.toString(), publisher)
            .joinToString("\n") { Base64.getEncoder().encodeToString(it.toByteArray(StandardCharsets.UTF_8)) }
            .toCharArray()
        val result = try {
            runner.run(WINDOWS_ARGUMENTS, input)
        } finally {
            input.fill('\u0000')
        }
        return when (result.exitCode) {
            0 -> null
            31 -> InstallFailure.SIGNATURE_INVALID
            32 -> InstallFailure.PUBLISHER_MISMATCH
            34 -> InstallFailure.FILE_MISSING
            else -> InstallFailure.VERIFIER_UNAVAILABLE
        }
    }
    private fun verifyMac(download: VerifiedDownload, teamId: String): InstallFailure? {
        val requirement = "anchor apple generic and certificate leaf[subject.OU] = \"$teamId\""
        val signed = runner.run(
            listOf("/usr/bin/codesign", "--verify", "--strict", "--deep", "--test-requirement", requirement, download.file.toString()),
        )
        if (signed.exitCode != 0) return InstallFailure.SIGNATURE_INVALID
        val notarized = runner.run(
            listOf("/usr/sbin/spctl", "--assess", "--type", "open", "--context", "context:primary-signature", download.file.toString()),
        )
        return InstallFailure.NOTARIZATION_FAILED.takeIf { notarized.exitCode != 0 }
    }
    private fun launchArguments(os: ReleaseOs, download: VerifiedDownload) = when (os) {
        ReleaseOs.WINDOWS -> listOf("msiexec.exe", "/i", download.file.toString())
        ReleaseOs.MACOS -> listOf("/usr/bin/open", download.file.toString())
        else -> emptyList()
    }
    private fun rejected(failure: InstallFailure) = InstallRejected(failure)
    private fun canonicalAssetPattern(target: ReleaseTarget, tag: String): Regex? {
        if (target.variant != tachiyomi.domain.release.model.ReleaseVariant.STANDARD || tag.isBlank() || tag.any(Char::isWhitespace)) return null
        val escapedTag = Regex.escape(tag)
        return when {
            target.os == ReleaseOs.WINDOWS && target.packageType == ReleasePackageType.MSI && target.arch == "x86_64" ->
                Regex("^mihon-desktop-windows-x86_64-$escapedTag\\.msi$")
            target.os == ReleaseOs.MACOS && target.packageType == ReleasePackageType.DMG && target.arch in setOf("x86_64", "arm64") ->
                Regex("^mihon-desktop-macos-${target.arch}-$escapedTag\\.dmg$")
            else -> null
        }
    }
    companion object {
        private val TEAM_ID = Regex("[A-Z0-9]{10}")
        private val SHA_256 = Regex("[0-9a-f]{64}")
        private val WINDOWS_ARGUMENTS = listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", WINDOWS_VERIFY)
        private const val WINDOWS_VERIFY = "& { try { \$Path=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String([Console]::In.ReadLine())); \$Publisher=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String([Console]::In.ReadLine())) } catch { exit 33 }; if (-not [IO.File]::Exists(\$Path)) { exit 34 }; \$s=Get-AuthenticodeSignature -LiteralPath \$Path; if (\$s.Status -ne [System.Management.Automation.SignatureStatus]::Valid) { exit 31 }; if (-not [string]::Equals(\$s.SignerCertificate.Subject,\$Publisher,[System.StringComparison]::Ordinal)) { exit 32 }; exit 0 }"
    }
}
