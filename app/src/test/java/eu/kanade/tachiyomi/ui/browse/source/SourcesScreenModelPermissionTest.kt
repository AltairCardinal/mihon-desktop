package eu.kanade.tachiyomi.ui.browse.source

import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.tachiyomi.extension.permission.InstalledAppsPermissionController
import eu.kanade.tachiyomi.extension.permission.InstalledAppsPermissionState
import eu.kanade.tachiyomi.extension.permission.InstalledAppsPermissionStatus
import eu.kanade.tachiyomi.test.ScreenModelTestHost
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.Source

class SourcesScreenModelPermissionTest {
    @Test
    fun `screen observes real permission state flow without losing usable sources`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val host = ScreenModelTestHost()
        val permissionState = MutableStateFlow(InstalledAppsPermissionState(InstalledAppsPermissionStatus.DENIED))
        val controller = mockk<InstalledAppsPermissionController> { every { state } returns permissionState }
        val sources = mockk<GetEnabledSources> {
            every { subscribe() } returns flowOf(listOf(Source(7, "en", "Private fixture", true, false)))
        }
        try {
            val model = host.create {
                SourcesScreenModel(
                    sources,
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    installedAppsPermission = controller,
                )
            }
            suspend fun awaitState(expected: InstalledAppsPermissionState) = withContext(Dispatchers.Default) {
                withTimeout(2_000) {
                    model.state.first { it.installedAppsPermission == expected && it.items.size == 2 }
                }
            }
            val originalItems = awaitState(permissionState.value).items
            for (next in listOf(
                InstalledAppsPermissionState(InstalledAppsPermissionStatus.GRANTED, isRefreshing = true),
                InstalledAppsPermissionState(InstalledAppsPermissionStatus.GRANTED, scanFailed = true),
                InstalledAppsPermissionState(InstalledAppsPermissionStatus.GRANTED),
                InstalledAppsPermissionState(InstalledAppsPermissionStatus.DENIED),
            )) {
                permissionState.value = next
                assertEquals(originalItems, awaitState(next).items)
            }
        } finally {
            host.close()
            Dispatchers.resetMain()
        }
    }
}
