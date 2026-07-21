package tachiyomi.domain.release.interactor

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService
import java.time.Instant

class GetApplicationReleaseParityTest {
    @Test
    fun `fixed-main three day throttle performs no service call`() = runTest {
        val service = FakeReleaseService(release("v2.0.0"))
        val checker = checker(service, Instant.now().toEpochMilli())

        assertEquals(GetApplicationRelease.Result.NoNewUpdate, checker.await(arguments()))
        assertEquals(0, service.calls)
    }

    @Test
    fun `force bypasses fixed-main throttle`() = runTest {
        val service = FakeReleaseService(release("v2.0.0"))
        val result = checker(service, Instant.now().toEpochMilli()).await(arguments(force = true))

        assertInstanceOf(GetApplicationRelease.Result.NewUpdate::class.java, result)
        assertEquals(1, service.calls)
    }

    @Test
    fun `fixed-main preview and release comparisons cover same newer and older`() = runTest {
        val cases = listOf(
            Case(true, 100, "", "r100", false),
            Case(true, 100, "", "r101", true),
            Case(true, 100, "", "r99", false),
            Case(false, 0, "v1.2.3", "v1.2.3", false),
            Case(false, 0, "v1.2.3", "v2.3.4", true),
            Case(false, 0, "v1.2.3", "v0.1.2", false),
        )

        cases.forEach { case ->
            val result = checker(FakeReleaseService(release(case.tag))).await(
                arguments(case.preview, case.commitCount, case.current),
            )
            assertEquals(case.newer, result is GetApplicationRelease.Result.NewUpdate, case.toString())
        }
    }

    private fun checker(service: ReleaseService, lastChecked: Long = 0) = GetApplicationRelease(
        service,
        InMemoryPreferenceStore(
            sequenceOf(InMemoryPreference(Preference.appStateKey("last_app_check"), lastChecked, 0L)),
        ),
    )

    private fun arguments(
        preview: Boolean = false,
        commits: Int = 0,
        version: String = "v1.0.0",
        force: Boolean = false,
    ) = GetApplicationRelease.Arguments(false, preview, commits, version, "mihonapp/mihon", force)

    private fun release(version: String) = Release(
        version,
        "info",
        "https://example/release",
        "https://example/app.apk",
    )

    private data class Case(
        val preview: Boolean,
        val commitCount: Int,
        val current: String,
        val tag: String,
        val newer: Boolean,
    )

    private class FakeReleaseService(private val release: Release?) : ReleaseService {
        var calls = 0
        override suspend fun latest(arguments: GetApplicationRelease.Arguments): Release? {
            calls++
            return release
        }
    }
}
