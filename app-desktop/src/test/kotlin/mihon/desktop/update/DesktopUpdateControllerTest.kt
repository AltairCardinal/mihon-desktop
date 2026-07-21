package mihon.desktop.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ReleaseAsset
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.ReleasePackageType
import tachiyomi.domain.release.model.ReleaseTarget
import tachiyomi.domain.release.model.ReleaseVariant
import java.nio.file.Path
class DesktopUpdateControllerTest {
    @Test
    fun `check maps every release result and retries only retryable failures`() = runTest {
        listOf(
            GetApplicationRelease.Result.NoNewUpdate to DesktopUpdateState.UpToDate,
            GetApplicationRelease.Result.NoCompatiblePackage to DesktopUpdateState.NoCompatiblePackage,
            GetApplicationRelease.Result.OsTooOld to DesktopUpdateState.CheckFailed(CheckFailure.OS_TOO_OLD, false),
            GetApplicationRelease.Result.NewUpdate(RELEASE) to DesktopUpdateState.UpdateAvailable(RELEASE),
        ).forEach { (result, expected) ->
            lateinit var controller: DesktopUpdateController
            controller = controller(check = { assertSame(DesktopUpdateState.Checking, controller.state.value); result })
            assertTrue(controller.check(ARGUMENTS)); assertEquals(expected, controller.state.value)
            if (result == GetApplicationRelease.Result.OsTooOld) assertFalse(controller.retry())
        }
        var calls = 0
        val controller = controller(check = { if (calls++ == 0) error("offline"); GetApplicationRelease.Result.NoNewUpdate })
        assertTrue(controller.check(ARGUMENTS)); assertEquals(CheckFailure.REQUEST_FAILED, (controller.state.value as DesktopUpdateState.CheckFailed).reason)
        assertTrue(controller.retry()); assertSame(DesktopUpdateState.UpToDate, controller.state.value)
        assertEquals(2, calls)
    }
    @Test
    fun `successful flow publishes progress and invokes every delegate in order`() = runTest {
        val calls = mutableListOf<String>()
        lateinit var controller: DesktopUpdateController
        controller = controller(
            check = { calls += "release"; GetApplicationRelease.Result.NewUpdate(RELEASE) },
            download = { release, progress ->
                calls += "download"; assertEquals(RELEASE, release); assertTrue(controller.state.value is DesktopUpdateState.Downloading)
                progress(DownloadProgress(3, 10)); assertEquals(DownloadProgress(3, 10), (controller.state.value as DesktopUpdateState.Downloading).progress)
                DOWNLOAD
            },
            prepare = { download, version ->
                calls += "verify"; assertEquals(DOWNLOAD, download); assertEquals(TAG, version)
                assertTrue(controller.state.value is DesktopUpdateState.Verifying)
                READY
            },
            handoff = { ready, confirmed ->
                calls += "handoff"; assertSame(READY, ready); assertTrue(confirmed)
                assertTrue(controller.state.value is DesktopUpdateState.HandingOff)
                InstallHandedOff
            },
        )
        assertTrue(controller.check(ARGUMENTS)); assertTrue(controller.download())
        assertTrue(controller.state.value is DesktopUpdateState.ReadyToInstall)
        assertTrue(controller.handoff(confirmed = true)); assertEquals(DesktopUpdateState.HandedOff(RELEASE.releaseLink), controller.state.value)
        assertEquals(listOf("release", "download", "verify", "handoff"), calls)
    }
    @Test
    fun `download failure retries download while manual fallback preserves release page`() = runTest {
        var downloads = 0
        var verifies = 0
        val controller = controller(
            download = { _, _ -> if (downloads++ == 0) DownloadFailed(DownloadFailure.HTTP) else DOWNLOAD },
            prepare = { _, _ -> verifies++; READY },
        )
        controller.check(ARGUMENTS); controller.download()
        val failed = controller.state.value as DesktopUpdateState.RetryableFailure
        assertEquals(UpdateOperation.DOWNLOAD, failed.operation); assertEquals(RELEASE.releaseLink, failed.releasePage)
        assertTrue(controller.retry()); assertTrue(controller.state.value is DesktopUpdateState.ReadyToInstall)
        assertEquals(2, downloads); assertEquals(1, verifies)
        val direct = controller(download = { _, _ -> ManualOnly(RELEASE.releaseLink) })
        direct.check(ARGUMENTS); direct.download()
        assertEquals(DesktopUpdateState.ManualOnly(RELEASE.releaseLink), direct.state.value)
        val verifier = controller(prepare = { _, _ -> InstallManualOnly })
        verifier.check(ARGUMENTS); verifier.download()
        assertEquals(DesktopUpdateState.ManualOnly(RELEASE.releaseLink), verifier.state.value)
    }
    @Test
    fun `verification failure retries verification without redownloading`() = runTest {
        var downloads = 0
        var verifies = 0
        val controller = controller(
            download = { _, _ -> downloads++; DOWNLOAD },
            prepare = { _, _ -> if (verifies++ == 0) InstallRejected(InstallFailure.HASH_MISMATCH) else READY },
        )
        controller.check(ARGUMENTS); controller.download()
        val failed = controller.state.value as DesktopUpdateState.InstallFailed
        assertEquals(InstallStage.VERIFY, failed.stage); assertEquals(InstallFailure.HASH_MISMATCH, failed.reason)
        assertTrue(controller.retry()); assertTrue(controller.state.value is DesktopUpdateState.ReadyToInstall)
        assertEquals(1, downloads); assertEquals(2, verifies)
    }
    @Test
    fun `handoff failure retries handoff and confirmation refusal cancels`() = runTest {
        val confirmations = mutableListOf<Boolean>()
        val controller = controller(handoff = { _, confirmed ->
            confirmations += confirmed
            if (confirmations.size == 1) InstallHandoffFailed(InstallFailure.LAUNCH_FAILED) else InstallHandedOff
        })
        ready(controller)
        controller.handoff(true)
        val failed = controller.state.value as DesktopUpdateState.InstallFailed
        assertEquals(InstallStage.HANDOFF, failed.stage); assertTrue(controller.retry())
        assertTrue(controller.state.value is DesktopUpdateState.HandedOff)
        assertEquals(listOf(true, true), confirmations)
        val refused = controller(handoff = { _, confirmed -> confirmations += confirmed; InstallCancelled })
        ready(refused)
        assertTrue(refused.handoff(false)); assertTrue(refused.state.value is DesktopUpdateState.Cancelled)
        assertEquals(false, confirmations.last())
    }
    @Test
    fun `delegate cancellation is propagated and stale progress is ignored`() = runTest {
        val stopped = CancellationException("stop")
        suspend fun verify(controller: DesktopUpdateController, page: String?, prime: suspend (DesktopUpdateController) -> Unit) {
            prime(controller)
            assertSame(stopped, runCatching { controller.retry() }.exceptionOrNull())
            assertEquals(DesktopUpdateState.Cancelled(page), controller.state.value); assertFalse(controller.retry())
        }
        var calls = 0
        verify(controller(check = { if (calls++ == 0) error("offline") else throw stopped }), null) { it.check(ARGUMENTS) }
        calls = 0
        verify(controller(download = { _, _ -> if (calls++ == 0) DownloadFailed(DownloadFailure.HTTP) else throw stopped }), RELEASE.releaseLink) { it.check(ARGUMENTS); it.download() }
        calls = 0
        verify(controller(prepare = { _, _ -> if (calls++ == 0) InstallRejected(InstallFailure.HASH_MISMATCH) else throw stopped }), RELEASE.releaseLink) { ready(it) }
        calls = 0
        verify(controller(handoff = { _, _ -> if (calls++ == 0) InstallHandoffFailed(InstallFailure.LAUNCH_FAILED) else throw stopped }), RELEASE.releaseLink) { ready(it); it.handoff(true) }

        lateinit var late: (DownloadProgress) -> Unit
        val cancelled = controller(download = { _, progress -> late = progress; throw CancellationException("stop") })
        cancelled.check(ARGUMENTS); runCatching { cancelled.download() }
        val cancelledState = cancelled.state.value; late(DownloadProgress(9, 10)); assertEquals(cancelledState, cancelled.state.value)

        val successful = controller(download = { _, progress -> late = progress; DOWNLOAD })
        ready(successful)
        val readyState = successful.state.value; late(DownloadProgress(9, 10)); assertEquals(readyState, successful.state.value)

        lateinit var old: (DownloadProgress) -> Unit
        var downloads = 0
        lateinit var retrying: DesktopUpdateController
        retrying = controller(download = { _, progress ->
            if (downloads++ == 0) { old = progress; DownloadFailed(DownloadFailure.HTTP) }
            else { old(DownloadProgress(7, 10)); assertEquals(null, (retrying.state.value as DesktopUpdateState.Downloading).progress); DOWNLOAD }
        })
        retrying.check(ARGUMENTS); retrying.download(); assertTrue(retrying.retry())
        assertTrue(retrying.state.value is DesktopUpdateState.ReadyToInstall)
    }
    @Test
    fun `late verifier result cannot replace Cancelled`() = runTest {
        listOf(false, true).forEach { fail ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val controller = controller(prepare = { _, _ ->
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                if (fail) error("late verifier failure") else READY
            })
            controller.check(ARGUMENTS)
            val completion = CompletableDeferred<Throwable?>()
            val running = launch { controller.download() }.also { job ->
                job.invokeOnCompletion { completion.complete(it) }
            }
            entered.await()
            val cancellation = CancellationException("late verifier")
            running.cancel(cancellation)
            release.complete(Unit)
            running.join()
            assertSame(cancellation, completion.await())
            assertEquals(DesktopUpdateState.Cancelled(RELEASE.releaseLink), controller.state.value)
        }
    }
    @Test
    fun `wrong-state calls and delegate reentry are rejected`() = runTest {
        lateinit var controller: DesktopUpdateController
        controller = controller(
            check = { assertFalse(controller.check(ARGUMENTS)); assertFalse(controller.download()); GetApplicationRelease.Result.NewUpdate(RELEASE) },
            download = { _, _ ->
                assertFalse(controller.download()); assertFalse(controller.handoff(true))
                DOWNLOAD
            },
        )
        assertFalse(controller.download()); assertFalse(controller.handoff(true)); assertFalse(controller.retry())
        assertTrue(controller.check(ARGUMENTS))
        assertTrue(controller.download())
    }
    private suspend fun ready(controller: DesktopUpdateController) { controller.check(ARGUMENTS); controller.download() }
    private fun controller(
        check: suspend (GetApplicationRelease.Arguments) -> GetApplicationRelease.Result = { GetApplicationRelease.Result.NewUpdate(RELEASE) },
        download: suspend (Release, (DownloadProgress) -> Unit) -> DesktopUpdateDownloadResult = { _, _ -> DOWNLOAD },
        prepare: suspend (VerifiedDownload, String) -> InstallPreparation = { _, _ -> READY },
        handoff: suspend (ReadyToInstall, Boolean) -> InstallHandoffResult = { _, _ -> InstallHandedOff },
    ) = DesktopUpdateController(check, download, prepare, handoff)

    companion object {
        private const val TAG = "v1.2.3"
        private val TARGET = ReleaseTarget(ReleaseOs.WINDOWS, "x86_64", ReleasePackageType.MSI, ReleaseVariant.STANDARD)
        private val ASSET = ReleaseAsset("mihon-desktop-windows-x86_64-$TAG.msi", TARGET, null)
        private val RELEASE = Release(TAG, "notes", "https://example/release", "https://example/update.msi", ASSET)
        private val DOWNLOAD = VerifiedDownload(Path.of("update.msi"), ASSET, "0".repeat(64), 1)
        private val READY = ReadyToInstall(DOWNLOAD, TAG, listOf("installer", DOWNLOAD.file.toString()))
        private val ARGUMENTS = GetApplicationRelease.Arguments(false, false, 0, "v1.0.0", "mihonapp/mihon", true)
    }
}
