package tachiyomi.domain.release

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.ForkUpdateRequest
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.UpdateChannel
import tachiyomi.domain.release.service.ReleaseService
import java.util.prefs.Preferences

class ForkUpdateDesktopPersistenceTest {
    private val node = Preferences.userRoot().node("/mihon/test/fork-update-${System.nanoTime()}")
    private val preferences = DesktopPreferenceStore(node)
    private var calls = 0
    private var nextRelease = release()
    private val service = object : ReleaseService {
        override suspend fun latest(arguments: GetApplicationRelease.Arguments): Release {
            calls++
            return nextRelease
        }
    }
    private fun checker() = GetApplicationRelease(service, preferences)

    @AfterEach
    fun cleanup() = node.removeNode()

    @Test
    fun `forty character identity uses bounded keys and replaces obsolete cache`() = runTest {
        val request = arguments(version = "0.11.19.91.${"a".repeat(40)}")
        checker().await(request)
        assertTrue(node.keys().all { it.length <= Preferences.MAX_KEY_LENGTH })
        checker().await(arguments(version = "0.11.19.90.${"b".repeat(40)}"))
        assertEquals(1, node.keys().size)
        assertEquals(2, calls)
    }

    @Test
    fun `maximum notes remain complete live and cache explicitly marks its bounded excerpt`() = runTest {
        nextRelease = release().copy(info = "x".repeat(32768))
        val live = checker().await(arguments()) as GetApplicationRelease.Result.NewUpdate
        assertEquals(32768, live.release.info.length)
        assertFalse(live.release.infoTruncated)
        val cached = checker().await(arguments()) as GetApplicationRelease.Result.NewUpdate
        assertEquals(1, calls)
        assertTrue(cached.release.info.length <= 1024)
        assertTrue(cached.release.infoTruncated)
        assertTrue(node.keys().all { node.get(it, "").length <= Preferences.MAX_VALUE_LENGTH })
    }

    @Test
    fun `architecture and trusted signer changes require fresh discovery`() = runTest {
        checker().await(arguments(architecture = "x86_64"))
        checker().await(arguments(architecture = "arm64"))
        assertEquals(2, calls)
        val original = arguments().copy(
            forkUpdate = ForkUpdateRequest(
                UpdateChannel.TESTING,
                ReleaseOs.ANDROID,
                90,
                "app.mihon.desktop.fork",
                "a".repeat(64),
                "arm64-v8a",
            ),
        )
        nextRelease =
            release().copy(
                versionCode = 91,
                applicationId = "app.mihon.desktop.fork",
                certificateSha256 = "a".repeat(64),
            )
        checker().await(original)
        nextRelease = nextRelease.copy(certificateSha256 = "b".repeat(64))
        checker().await(
            original.copy(forkUpdate = original.forkUpdate!!.copy(expectedCertificateSha256 = "b".repeat(64))),
        )
        assertEquals(4, calls)
    }

    @Test
    fun `cached foreign URL is discarded and cannot be handed to the UI`() = runTest {
        checker().await(arguments())
        node.keys().single().let { key ->
            node.put(
                key,
                node.get(key, "").replace("github.com/AltairCardinal/mihon-desktop", "example.org/other/project"),
            )
        }
        val result = checker().await(arguments()) as GetApplicationRelease.Result.NewUpdate
        assertEquals(2, calls)
        assertTrue(result.release.releaseLink.startsWith("https://github.com/AltairCardinal/mihon-desktop/"))
    }

    private fun arguments(version: String = "0.11.19.91.abcdef0", architecture: String = "x86_64") =
        GetApplicationRelease.Arguments(
            false,
            false,
            0,
            version,
            ForkUpdateRequest.REPOSITORY,
            forkUpdate = ForkUpdateRequest(UpdateChannel.TESTING, ReleaseOs.WINDOWS, architecture = architecture),
        )

    private fun release() = Release(
        "0.11.19.92.abcdef0",
        "notes",
        "https://github.com/AltairCardinal/mihon-desktop/releases/tag/v1",
        "https://github.com/AltairCardinal/mihon-desktop/releases/download/v1/app.zip",
    )
}
