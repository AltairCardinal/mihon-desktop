package eu.kanade.tachiyomi.extension.permission

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstalledAppsPermissionControllerTest {
    @Test
    fun `denied is explicit and unknown is not denied`() = runTest {
        var permission = InstalledAppsPermissionStatus.DENIED
        val controller = InstalledAppsPermissionController({ permission })
        controller.refresh()
        assertEquals(InstalledAppsPermissionStatus.DENIED, controller.state.value.status)
        permission = InstalledAppsPermissionStatus.GRANTED
        controller.refresh()
        assertEquals(InstalledAppsPermissionStatus.GRANTED, controller.state.value.status)
        val broken = InstalledAppsPermissionController({ throw SecurityException() })
        broken.refresh()
        assertEquals(InstalledAppsPermissionStatus.UNKNOWN, broken.state.value.status)
    }

    @Test
    fun `scan failure preserves permission and retry scans again`() = runTest {
        val controller = InstalledAppsPermissionController({ InstalledAppsPermissionStatus.GRANTED })
        var scans = 0
        controller.scan = {
            scans++
            if (scans == 1) error("scan")
        }
        controller.refresh()
        assertTrue(controller.state.value.scanFailed)
        assertEquals(InstalledAppsPermissionStatus.GRANTED, controller.state.value.status)
        controller.refresh()
        assertFalse(controller.state.value.scanFailed)
        assertEquals(2, scans)
        controller.refresh()
        assertEquals(2, scans)
    }

    @Test
    fun `concurrent refresh does not replay scan and checks latest revocation`() = runTest {
        var permission = InstalledAppsPermissionStatus.GRANTED
        val controller = InstalledAppsPermissionController({ permission })
        val gate = CompletableDeferred<Unit>()
        var scans = 0
        controller.scan = {
            scans++
            if (scans == 1) gate.await()
        }
        val first = async { controller.refresh() }
        runCurrent()
        permission = InstalledAppsPermissionStatus.DENIED
        val second = async { controller.refresh() }
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(InstalledAppsPermissionStatus.DENIED, controller.state.value.status)
        assertEquals(2, scans)
    }

    @Test
    fun `package manager detects extra permission separately from standard visibility`() {
        val pm = mockk<PackageManager>()
        val context = mockk<Context>()
        every { context.packageManager } returns pm
        every { context.packageName } returns "app.test"
        every { pm.getPermissionInfo("com.android.permission.GET_INSTALLED_APPS", 0) } returns mockk<PermissionInfo>()
        every { pm.getPackageInfo("app.test", PackageManager.GET_PERMISSIONS) } returns mockk<PackageInfo>().apply {
            requestedPermissions = arrayOf("com.android.permission.GET_INSTALLED_APPS")
        }
        every { pm.checkPermission(any(), "app.test") } returns PackageManager.PERMISSION_DENIED
        assertEquals(InstalledAppsPermissionStatus.DENIED, AndroidInstalledAppsPermissionDetector(context).check())
        every { pm.checkPermission(any(), "app.test") } returns PackageManager.PERMISSION_GRANTED
        assertEquals(InstalledAppsPermissionStatus.GRANTED, AndroidInstalledAppsPermissionDetector(context).check())
        every { pm.getPackageInfo("app.test", PackageManager.GET_PERMISSIONS) } returns mockk<PackageInfo>().apply {
            requestedPermissions = arrayOf("android.permission.QUERY_ALL_PACKAGES")
        }
        assertEquals(
            InstalledAppsPermissionStatus.NOT_REQUIRED,
            AndroidInstalledAppsPermissionDetector(context).check(),
        )
        every { pm.getPermissionInfo(any(), 0) } throws mockk<PackageManager.NameNotFoundException>()
        assertEquals(
            InstalledAppsPermissionStatus.NOT_REQUIRED,
            AndroidInstalledAppsPermissionDetector(context).check(),
        )
        every { pm.getPermissionInfo(any(), 0) } throws SecurityException()
        assertEquals(InstalledAppsPermissionStatus.UNKNOWN, AndroidInstalledAppsPermissionDetector(context).check())
    }
}
