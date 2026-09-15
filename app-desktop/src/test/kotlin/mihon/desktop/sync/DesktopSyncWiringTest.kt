package mihon.desktop.sync

import kotlinx.coroutines.runBlocking
import mihon.data.sync.runtime.SyncRuntime
import mihon.desktop.DesktopAppRuntime
import mihon.desktop.di.initDesktopDIForTest
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.security.SyncSecureStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences

@Isolated
class DesktopSyncWiringTest {
    @Test
    fun `default graph exposes one sync runtime and lifecycle reaches the same coordinator`(
        @TempDir folder: File,
    ) = runBlocking {
        val node = Preferences.userRoot().node("mihon-sync-di-test-" + UUID.randomUUID())
        val context = initDesktopDIForTest(folder, DesktopPreferenceStore(node))
        try {
            val runtime = Injekt.get<SyncRuntime>()
            assertSame(runtime, Injekt.get<SyncRuntime>())
            assertTrue(Injekt.get<SyncSecureStore>() is DesktopSyncSecureStore)
            assertSame(runtime.coordinator, Injekt.get<DesktopSyncScheduler>().coordinator)
            assertEquals(SyncRunStatus.SKIPPED, runtime.coordinator.synchronize(SyncTrigger.MANUAL).status)
            val before = runtime.coordinator.activity.value.completion
            Injekt.get<DesktopAppRuntime>().start()
            kotlinx.coroutines.withTimeout(5000) {
                while (runtime.coordinator.activity.value.completion == before) kotlinx.coroutines.delay(10)
            }
            assertEquals(SyncRunStatus.SKIPPED, runtime.coordinator.activity.value.result?.status)
        } finally {
            context.closeAndJoin()
            node.removeNode()
        }
    }
}
