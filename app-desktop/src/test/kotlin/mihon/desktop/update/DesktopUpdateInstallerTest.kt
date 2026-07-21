package mihon.desktop.update

import kotlinx.coroutines.test.runTest
import mihon.desktop.platform.CommandResult
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.release.DesktopPlatformInfo
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ReleaseAsset
import tachiyomi.domain.release.model.ReleaseChecksum
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.ReleasePackageType
import tachiyomi.domain.release.model.ReleaseTarget
import tachiyomi.domain.release.model.ReleaseVariant
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

class DesktopUpdateInstallerTest {
    @TempDir lateinit var tempDir: Path
    @Test
    fun `target and canonical package must exactly match current platform`() = runTest {
        val runner = FakeRunner()
        val windows = target(ReleaseOs.WINDOWS, ReleasePackageType.MSI)
        val installer = installer(windows, runner)
        assertRejected(InstallFailure.TARGET_MISMATCH, installer.prepare(download(target(ReleaseOs.MACOS, ReleasePackageType.DMG)), TAG))
        val nonCanonical = target(ReleaseOs.WINDOWS, ReleasePackageType.DMG)
        assertRejected(InstallFailure.TARGET_MISMATCH, installer(nonCanonical, runner).prepare(download(nonCanonical), TAG))
        val wrongArch = WINDOWS.copy(arch = "arm64")
        assertRejected(InstallFailure.TARGET_MISMATCH, installer(wrongArch, runner).prepare(download(wrongArch), TAG))
        val foss = WINDOWS.copy(variant = ReleaseVariant.FOSS)
        assertRejected(InstallFailure.TARGET_MISMATCH, installer(foss, runner).prepare(download(foss), TAG))
        assertTrue(runner.calls.isEmpty())
    }
    @Test
    fun `only exact canonical asset names for release tag are accepted`() = runTest {
        val runner = FakeRunner()
        val installer = installer(WINDOWS, runner)
        val valid = download(WINDOWS)
        listOf(
            "update.msi", "Mihon Desktop-$TAG.msi",
            "mihon-desktop-windows-arm64-$TAG.msi", "mihon-desktop-windows-x86_64-v9.msi",
            "mihon-desktop-windows-x86_64-$TAG .msi", "${valid.asset.name}.bak",
        ).forEach { name ->
            assertRejected(InstallFailure.ASSET_NAME_MISMATCH, installer.prepare(valid.copy(asset = valid.asset.copy(name = name)), TAG))
        }
        assertTrue(installer.prepare(valid, TAG) is ReadyToInstall)
        val macArm = MAC.copy(arch = "arm64")
        assertTrue(installer(macArm, FakeRunner(0, 0)).prepare(download(macArm), TAG) is ReadyToInstall)
    }
    @Test
    fun `real downloader artifacts flow through verifier and confirmed handoff unchanged`() = runTest {
        MockWebServer().also { it.start() }.use { server ->
            listOf(WINDOWS, MAC).forEach { target ->
                val runner = FakeRunner(*(if (target.os == ReleaseOs.WINDOWS) intArrayOf(0, 0) else intArrayOf(0, 0, 0, 0)))
                val launches = mutableListOf<List<String>>()
                val installer = installer(target, runner) { launches += it; true }
                val verified = downloaded(server, target)
                assertTrue(verified.file.toString().endsWith(if (target.os == ReleaseOs.WINDOWS) ".msi" else ".dmg"))
                val ready = installer.prepare(verified, TAG) as ReadyToInstall
                assertTrue(installer.handoff(ready, true) is InstallHandedOff)
                val path = verified.file.toString()
                if (target.os == ReleaseOs.WINDOWS) assertEquals(listOf(path, path), runner.stdins.map { decode(it!!.lineSequence().first()) })
                else assertEquals(List(4) { path }, runner.calls.map { it.last() })
                assertEquals(if (target.os == ReleaseOs.WINDOWS) listOf("msiexec.exe", "/i", path) else listOf("/usr/bin/open", path), launches.single())
                assertTrue(Files.exists(verified.file))
            }
            assertEquals(2, server.requestCount)
        }
    }
    @Test
    fun `missing changed-size and same-size tampered files are rejected before verifier`() = runTest {
        val runner = FakeRunner()
        val installer = installer(WINDOWS, runner)
        val missing = download(WINDOWS, write = false)
        assertRejected(InstallFailure.FILE_MISSING, installer.prepare(missing, TAG))
        val resized = download(WINDOWS).also { Files.writeString(it.file, "longer-than-before") }
        assertRejected(InstallFailure.SIZE_MISMATCH, installer.prepare(resized, TAG))
        val tampered = download(WINDOWS).also { Files.writeString(it.file, "fedcba") }
        assertRejected(InstallFailure.HASH_MISMATCH, installer.prepare(tampered, TAG))
        assertTrue(runner.calls.isEmpty())
    }
    @Test
    fun `unsupported platforms and absent trust roots are manual only without commands`() = runTest {
        val runner = FakeRunner()
        listOf(LINUX, UNKNOWN).forEach { assertTrue(installer(it, runner).prepare(download(it), TAG) is InstallManualOnly) }
        assertTrue(DesktopUpdateInstaller(WINDOWS, InstallerTrust(), runner).prepare(download(WINDOWS), TAG) is InstallManualOnly)
        assertTrue(DesktopUpdateInstaller(MAC, InstallerTrust(macTeamId = "bad"), runner).prepare(download(MAC), TAG) is InstallManualOnly)
        assertTrue(runner.calls.isEmpty())
    }
    @Test
    fun `Windows requires valid signature and exact configured publisher`() = runTest {
        listOf(31 to InstallFailure.SIGNATURE_INVALID, 32 to InstallFailure.PUBLISHER_MISMATCH).forEach { (code, failure) ->
            val runner = FakeRunner(code)
            val download = download(WINDOWS)
            assertRejected(failure, installer(WINDOWS, runner).prepare(download, TAG))
            assertTrue(Files.exists(download.file))
            val call = runner.calls.single()
            assertEquals("powershell.exe", call.first())
            assertFalse(call.any { download.file.toString() in it || PUBLISHER in it })
            val input = runner.stdins.single()!!.lineSequence().toList()
            assertEquals(download.file.toString(), decode(input[0]))
            assertEquals(PUBLISHER, decode(input[1]))
        }
    }
    @Test
    fun `macOS requires exact team requirement and notarization policy`() = runTest {
        val wrongTeam = FakeRunner(1)
        assertRejected(InstallFailure.SIGNATURE_INVALID, installer(MAC, wrongTeam).prepare(download(MAC), TAG))
        assertEquals("anchor apple generic and certificate leaf[subject.OU] = \"$TEAM\"", wrongTeam.calls.single()[5])
        val notNotarized = FakeRunner(0, 1)
        val download = download(MAC)
        assertRejected(InstallFailure.NOTARIZATION_FAILED, installer(MAC, notNotarized).prepare(download, TAG))
        assertTrue(Files.exists(download.file))
        val launches = mutableListOf<List<String>>()
        val installer = installer(MAC, FakeRunner(0, 0, 0, 0)) { launches += it; true }
        val ready = installer.prepare(download(MAC), TAG) as ReadyToInstall
        assertTrue(installer.handoff(ready, true) is InstallHandedOff)
        assertEquals(listOf("/usr/bin/open", ready.download.file.toString()), launches.single())
    }
    @Test
    fun `confirmation gates handoff and launch result preserves artifact`() = runTest {
        val runner = FakeRunner(0, 0, 0, 0, 0)
        val launches = mutableListOf<List<String>>()
        val installer = installer(WINDOWS, runner) { args -> launches += args; args.none { it == "fail" } }
        val ready = installer.prepare(download(WINDOWS), TAG) as ReadyToInstall
        assertEquals(TAG, ready.releaseTag)
        assertTrue(installer.handoff(ready, confirmed = false) is InstallCancelled)
        assertTrue(launches.isEmpty())
        val failed = installer(WINDOWS, runner) { false }
        val failedReady = failed.prepare(download(WINDOWS), TAG) as ReadyToInstall
        assertEquals(InstallFailure.LAUNCH_FAILED, (failed.handoff(failedReady, true) as InstallHandoffFailed).reason)
        assertTrue(Files.exists(failedReady.download.file))
        assertTrue(installer.handoff(ready, true) is InstallHandedOff)
        assertEquals(listOf("msiexec.exe", "/i", ready.download.file.toString()), launches.single())
        assertTrue(Files.exists(ready.download.file))
    }
    @Test
    fun `handoff revalidates artifact after user confirmation`() = runTest {
        val installer = installer(WINDOWS, FakeRunner(0, 0))
        val ready = installer.prepare(download(WINDOWS), TAG) as ReadyToInstall
        Files.writeString(ready.download.file, "fedcba")
        assertEquals(InstallFailure.HASH_MISMATCH, (installer.handoff(ready, true) as InstallHandoffFailed).reason)
    }
    @Test
    fun `real Windows verifier fails closed for unsigned file`() = runTest {
        val actual = DesktopPlatformInfo().releaseTarget(isFoss = false)
        assumeTrue(actual.os == ReleaseOs.WINDOWS)
        val result = DesktopUpdateInstaller(actual, InstallerTrust(windowsPublisher = PUBLISHER)).prepare(download(actual), TAG)
        assertRejected(InstallFailure.SIGNATURE_INVALID, result)
    }
    private fun installer(target: ReleaseTarget, runner: DesktopUpdateCommandRunner, launcher: (List<String>) -> Boolean = { true }) = DesktopUpdateInstaller(target, InstallerTrust(PUBLISHER, TEAM), runner, launcher)
    private suspend fun downloaded(server: MockWebServer, target: ReleaseTarget): VerifiedDownload {
        val bytes = "abcdef".toByteArray()
        server.enqueue(MockResponse(body = bytes.decodeToString()))
        val release = Release(TAG, "info", "https://example.com/release", server.url("/update").toString(), ReleaseAsset(canonicalName(target), target, ReleaseChecksum("sha256", bytes.sha256())))
        return DesktopUpdateDownloader(OkHttpClient(), tempDir.resolve("safe root with spaces/${target.os}"), 1024, 3).download(release) as VerifiedDownload
    }
    private fun download(target: ReleaseTarget, write: Boolean = true): VerifiedDownload {
        val bytes = "abcdef".toByteArray()
        val file = downloadPath(target)
        Files.createDirectories(file.parent)
        if (write) Files.write(file, bytes)
        return VerifiedDownload(file, ReleaseAsset(canonicalName(target), target, ReleaseChecksum("sha256", bytes.sha256())), bytes.sha256(), bytes.size.toLong())
    }
    private fun downloadPath(target: ReleaseTarget) = tempDir.resolve("updates with spaces").resolve(canonicalName(target))
    private fun canonicalName(target: ReleaseTarget) = when (target.os) {
        ReleaseOs.WINDOWS -> "mihon-desktop-windows-${target.arch}-$TAG.msi"
        ReleaseOs.MACOS -> "mihon-desktop-macos-${target.arch}-$TAG.dmg"
        else -> "unsupported-$TAG.bin"
    }
    private fun decode(value: String) = Base64.getDecoder().decode(value).decodeToString()
    private fun assertRejected(expected: InstallFailure, actual: InstallPreparation) = assertEquals(expected, (actual as InstallRejected).reason)
    private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
    private class FakeRunner(vararg results: Int) : DesktopUpdateCommandRunner {
        val calls = mutableListOf<List<String>>()
        val stdins = mutableListOf<String?>()
        private val results = results.toMutableList()
        override suspend fun run(arguments: List<String>, stdin: CharArray?) =
            CommandResult(if (results.isEmpty()) 0 else results.removeAt(0), "ignored", "ignored")
                .also { calls += arguments; stdins += stdin?.concatToString() }
    }
    companion object {
        private const val PUBLISHER = "CN=Mihon Desktop, O=Mihon"
        private const val TEAM = "ABCDE12345"
        private const val TAG = "v1.2.3"
        private val WINDOWS = target(ReleaseOs.WINDOWS, ReleasePackageType.MSI)
        private val MAC = target(ReleaseOs.MACOS, ReleasePackageType.DMG)
        private val LINUX = target(ReleaseOs.LINUX, ReleasePackageType.UNKNOWN)
        private val UNKNOWN = target(ReleaseOs.UNKNOWN, ReleasePackageType.UNKNOWN)
        private fun target(os: ReleaseOs, type: ReleasePackageType) = ReleaseTarget(os, "x86_64", type, ReleaseVariant.STANDARD)
    }
}
