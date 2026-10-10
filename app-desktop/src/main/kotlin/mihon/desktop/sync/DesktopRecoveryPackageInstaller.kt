package mihon.desktop.sync

import mihon.desktop.update.DesktopUpdateInstaller
import mihon.desktop.update.InstallManualOnly
import mihon.desktop.update.InstallPreparation
import mihon.desktop.update.InstallFailure
import mihon.desktop.update.InstallRejected
import mihon.desktop.update.VerifiedDownload
import tachiyomi.domain.release.model.ReleaseAsset
import tachiyomi.domain.release.model.ReleaseChecksum
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.ReleaseTarget
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class DesktopRecoveryPackageInstaller(
    private val target: ReleaseTarget,
    private val installer: DesktopUpdateInstaller,
) {
    suspend fun install(ready: mihon.desktop.update.ReadyToInstall): mihon.desktop.update.InstallHandoffResult =
        installer.handoff(ready, confirmed = true)

    suspend fun prepare(file: File): InstallPreparation {
        if (!file.isFile || file.length() !in 1..512L * 1024 * 1024) return InstallRejected(InstallFailure.FILE_MISSING)
        val prefix = when (target.os) {
            ReleaseOs.WINDOWS -> "mihon-desktop-windows-${target.arch}-"
            ReleaseOs.MACOS -> "mihon-desktop-macos-${target.arch}-"
            else -> return InstallManualOnly
        }
        val suffix = if (target.os == ReleaseOs.WINDOWS) ".msi" else ".dmg"
        if (!file.name.startsWith(prefix) || !file.name.endsWith(suffix)) return InstallRejected(InstallFailure.ASSET_NAME_MISMATCH)
        val version = file.name.removePrefix(prefix).removeSuffix(suffix)
        if (version.isBlank() || version.any(Char::isWhitespace)) return InstallRejected(InstallFailure.ASSET_NAME_MISMATCH)
        val nextVersion = numericVersion(version) ?: return InstallRejected(InstallFailure.ASSET_NAME_MISMATCH)
        val currentVersion = numericVersion(mihon.desktop.APP_VERSION) ?: return InstallManualOnly
        val length = maxOf(nextVersion.size, currentVersion.size)
        val comparison = (0 until length).firstNotNullOfOrNull { index ->
            (nextVersion.getOrElse(index) { 0 }.compareTo(currentVersion.getOrElse(index) { 0 })).takeIf { it != 0 }
        } ?: 0
        if (comparison <= 0) return InstallRejected(InstallFailure.TARGET_MISMATCH)
        val publisher = if (target.os == ReleaseOs.WINDOWS) installer.trust.windowsPublisher else installer.trust.macTeamId
        if (publisher.isNullOrBlank()) return InstallManualOnly
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        // This hash only detects later changes. Source trust remains the existing publisher/team signature gate.
        return installer.prepare(VerifiedDownload(file.toPath(), ReleaseAsset(file.name, target, ReleaseChecksum("sha256", hash)), hash, file.length()), version)
    }

    private fun numericVersion(value: String): List<Long>? = Regex("^v?(\\d+(?:\\.\\d+){2,3})(?:[.-][A-Za-z0-9][A-Za-z0-9.-]*)?$")
        .matchEntire(value)?.groupValues?.get(1)?.split('.')?.map { it.toLongOrNull() ?: return null }
}
