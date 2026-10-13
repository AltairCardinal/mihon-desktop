package tachiyomi.domain.release

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.ForkUpdateRequest
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.UpdateChannel
import tachiyomi.domain.release.service.ReleaseService
import java.time.Instant

class ForkApplicationReleaseContractTest {
    @Test
    fun `Android upgrade identity is versionCode regardless of display version`() = runTest {
        val checker = checker(release("0.1.0", 91))
        assertInstanceOf(GetApplicationRelease.Result.NewUpdate::class.java, checker.await(android(90)))
        assertEquals(GetApplicationRelease.Result.NoNewUpdate, checker.await(android(92, force = true)))
    }

    @Test
    fun `Desktop compares all four numeric parts lexicographically and ignores hash identity`() = runTest {
        listOf(
            "0.11.19.92.abcdef0" to true,
            "0.11.19.91.123abc0" to false,
            "0.11.18.999.abcdef0" to false,
            "0.10.99.999.abcdef0" to false,
            "0.12.0.1.abcdef0" to true,
        ).forEach { (version, newer) ->
            val result = checker(release(version)).await(desktop())
            assertEquals(newer, result is GetApplicationRelease.Result.NewUpdate, version)
        }
    }

    @Test
    fun `twelve hour cache persists results and isolates channels and installed versions`() = runTest {
        val store = TestPreferences()
        var calls = 0
        var now = Instant.parse("2026-10-13T00:00:00Z")
        val service = object : ReleaseService {
            override suspend fun latest(arguments: GetApplicationRelease.Arguments): Release {
                calls++
                return release("0.11.19.92.abcdef0")
            }
        }
        fun instance() = GetApplicationRelease(service, store, now = { now })
        val first = instance().await(desktop())
        assertInstanceOf(GetApplicationRelease.Result.NewUpdate::class.java, first)
        now = now.plusSeconds(11 * 3600)
        assertEquals(first, instance().await(desktop()))
        assertEquals(1, calls)
        instance().await(desktop(channel = UpdateChannel.STABLE))
        instance().await(desktop(version = "0.11.19.90.abcdef0"))
        assertEquals(3, calls)
        now = now.plusSeconds(3601)
        instance().await(desktop())
        assertEquals(4, calls)
        instance().await(desktop(force = true))
        assertEquals(5, calls)
    }

    @Test
    fun `no compatible package is cached but failures neither overwrite success nor throttle retry`() = runTest {
        val store = TestPreferences()
        var calls = 0
        val service = object : ReleaseService {
            override suspend fun latest(arguments: GetApplicationRelease.Arguments): Release? {
                calls++
                if (calls == 1) error("offline")
                return null
            }
        }
        val checker = GetApplicationRelease(service, store)
        assertTrue(runCatching { checker.await(desktop()) }.isFailure)
        assertEquals(GetApplicationRelease.Result.NoCompatiblePackage, checker.await(desktop()))
        assertEquals(GetApplicationRelease.Result.NoCompatiblePackage, checker.await(desktop()))
        assertEquals(2, calls)
    }

    private fun checker(release: Release) = GetApplicationRelease(
        object : ReleaseService {
            override suspend fun latest(arguments: GetApplicationRelease.Arguments) = release
        },
        TestPreferences(),
    )

    private fun release(version: String, code: Long? = null) = Release(
        version,
        "notes",
        "https://github.com/AltairCardinal/mihon-desktop/releases/tag/v1",
        "https://github.com/AltairCardinal/mihon-desktop/releases/download/v1/app.apk",
        versionCode = code,
    )

    private fun android(code: Long, force: Boolean = false) = GetApplicationRelease.Arguments(
        false,
        false,
        0,
        "0.19.4-aex.72",
        "AltairCardinal/mihon-desktop",
        force,
        ForkUpdateRequest(UpdateChannel.TESTING, ReleaseOs.ANDROID, installedVersionCode = code),
    )

    private fun desktop(
        version: String = "0.11.19.91.abcdef0",
        channel: UpdateChannel = UpdateChannel.TESTING,
        force: Boolean = false,
    ) = GetApplicationRelease.Arguments(
        false,
        false,
        0,
        version,
        "AltairCardinal/mihon-desktop",
        force,
        ForkUpdateRequest(channel, ReleaseOs.WINDOWS),
    )

    private class TestPreferences : PreferenceStore by InMemoryPreferenceStore() {
        private val values = mutableMapOf<String, Preference<*>>()

        @Suppress("UNCHECKED_CAST")
        override fun getString(key: String, defaultValue: String): Preference<String> =
            values.getOrPut(key) {
                InMemoryPreferenceStore.InMemoryPreference(key, null, defaultValue)
            } as Preference<String>

        @Suppress("UNCHECKED_CAST")
        override fun getLong(key: String, defaultValue: Long): Preference<Long> =
            values.getOrPut(key) {
                InMemoryPreferenceStore.InMemoryPreference(key, null, defaultValue)
            } as Preference<Long>
    }
}
