package mihon.desktop.sync

import kotlinx.coroutines.test.runTest
import mihon.desktop.update.DesktopUpdateInstaller
import mihon.desktop.update.InstallManualOnly
import mihon.desktop.update.InstallRejected
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.ReleasePackageType
import tachiyomi.domain.release.model.ReleaseTarget
import tachiyomi.domain.release.model.ReleaseVariant
import java.nio.file.Files
import java.nio.file.Path

class DesktopRecoveryPackageInstallerTest {
    @TempDir lateinit var directory: Path

    @Test fun `local recovery installer keeps existing publisher trust boundary`() = runTest {
        val target = ReleaseTarget(ReleaseOs.WINDOWS, "x86_64", ReleasePackageType.MSI, ReleaseVariant.STANDARD)
        val installer = DesktopRecoveryPackageInstaller(target, DesktopUpdateInstaller(target))
        val file = directory.resolve("mihon-desktop-windows-x86_64-v9.0.0.msi")
        Files.write(file, byteArrayOf(1, 2, 3))
        assertEquals(InstallManualOnly, installer.prepare(file.toFile()))
        val wrong = directory.resolve("untrusted.exe")
        Files.write(wrong, byteArrayOf(1))
        assertTrue(installer.prepare(wrong.toFile()) is InstallRejected)
        assertTrue(installer.prepare(directory.resolve("missing.msi").toFile()) is InstallRejected)
    }

    @Test fun `local recovery package cannot prepare an older Desktop version`() = runTest {
        val target = ReleaseTarget(ReleaseOs.WINDOWS, "x86_64", ReleasePackageType.MSI, ReleaseVariant.STANDARD)
        val installer = DesktopRecoveryPackageInstaller(target, DesktopUpdateInstaller(target))
        val old = directory.resolve("mihon-desktop-windows-x86_64-v0.0.0.msi")
        Files.write(old, byteArrayOf(1, 2, 3))
        assertTrue(installer.prepare(old.toFile()) is InstallRejected)
    }
}
