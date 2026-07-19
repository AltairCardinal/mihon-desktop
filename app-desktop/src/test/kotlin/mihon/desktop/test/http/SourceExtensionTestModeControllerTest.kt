package mihon.desktop.test.http

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.extension.DesktopAvailableExtension
import mihon.desktop.extension.DesktopAvailableSource
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionInstallStart
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.InstalledExtension
import mihon.desktop.ui.extension.DesktopExtensionPresentationPort
import mihon.desktop.ui.extension.ExtensionsScreenModel
import mihon.domain.error.AppError
import mihon.domain.error.toStoredAppError
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.presentation.ExtensionPresentationInstallStep
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.service.ExtensionInstallState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SourceExtensionTestModeControllerTest {
    @Test
    fun `controller bridges production state intents failures and trust`() = runBlocking {
        val update = available("pkg.update", "Update Extension", 2)
        val fresh = available("pkg.new", "New Extension", 1)
        val installed = InstalledExtension(File("pkg.update.jar"), emptyList(), versionCode = 1, displayName = update.name)
        val stable = InstalledExtension(File("pkg.stable.jar"), emptyList(), versionCode = 1, displayName = "Stable Extension")
        val installedFlow = MutableStateFlow(listOf(installed, stable))
        val failure = AppError.Network(IllegalStateException("offline-raw"))
        val newStarts = ArrayDeque<DesktopExtensionInstallStart>(
            listOf(
                DesktopExtensionInstallStart.Started(flowOf(ExtensionInstallState.Failed(failure))),
                DesktopExtensionInstallStart.Started(flow { emit(ExtensionInstallState.Preparing); awaitCancellation() }),
                DesktopExtensionInstallStart.TrustRequired("confirm", "old-fingerprint", "new-fingerprint", emptySet(), mockk()),
                DesktopExtensionInstallStart.TrustRequired("dismiss", "old-2", "new-2", emptySet(), mockk()),
            ),
        )
        val updateStarts = ArrayDeque<DesktopExtensionInstallStart>(listOf(
            DesktopExtensionInstallStart.Started(flowOf(ExtensionInstallState.Failed(failure))),
            DesktopExtensionInstallStart.Started(flowOf(ExtensionInstallState.Failed(failure))),
        ))
        val manager = mockk<DesktopExtensionManager> { every { removeExtensionWithMeta(stable) } returns true }
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } returns ExtensionCatalogResult(emptyList(), emptyList())
            every { availableExtensions(any()) } returns listOf(update, fresh)
            coEvery { beginInstall(any(), manager) } answers {
                if (firstArg<DesktopAvailableExtension>().pkgName == fresh.pkgName) newStarts.removeFirst() else updateStarts.removeFirst()
            }
            every { confirmTrust("confirm", manager) } returns flowOf(ExtensionInstallState.Failed(failure))
            every { discardTrust("dismiss") } returns true
        }
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, manager, installedFlow), this, ExtensionPresentationOptions(false, setOf("en")),
        )
        val controller = SourceExtensionTestModeController(model)
        try {
            assertTrue(controller.execute("extension_refresh").success)
            withTimeout(5_000) { model.state.first { it.projection != null } }
            val initial = controller.snapshot()
            assertEquals(setOf(update.pkgName, stable.pkgName), initial.installed.map { it.packageName }.toSet())
            assertTrue(initial.available.any { it.packageName == fresh.pkgName && it.sources.single().name == "New Source" })

            assertTrue(controller.execute("extension_search", mapOf("query" to "New")).success)
            assertEquals("New", model.state.value.searchQuery)
            assertEquals(setOf(fresh.pkgName), controller.snapshot().available.map { it.packageName }.toSet())
            controller.execute("extension_search", mapOf("query" to ""))
            assertEquals(SourceExtensionActionFailureCode.MISSING_PARAMETER, controller.execute("extension_install").failureCode)
            assertEquals(SourceExtensionActionFailureCode.UNKNOWN_PACKAGE, action(controller, "extension_install", "missing").failureCode)
            assertEquals(SourceExtensionActionFailureCode.ACTION_UNAVAILABLE, action(controller, "extension_update", stable.pkgName).failureCode)
            assertEquals(SourceExtensionActionFailureCode.NO_PENDING_TRUST, action(controller, "extension_trust_confirm", fresh.pkgName).failureCode)
            assertEquals(SourceExtensionActionFailureCode.UNSUPPORTED_ACTION, controller.execute("legacy_extension_select").failureCode)

            assertTrue(action(controller, "extension_install", fresh.pkgName).success)
            withTimeout(5_000) { model.state.first { fresh.pkgName in it.installErrors } }
            assertEquals(failure.toStoredAppError(), controller.snapshot().errors[fresh.pkgName])
            assertTrue(action(controller, "extension_retry", fresh.pkgName).success)
            withTimeout(5_000) { model.state.first { it.actions.installSteps[fresh.pkgName] == ExtensionPresentationInstallStep.Downloading } }
            assertTrue(action(controller, "extension_cancel", fresh.pkgName).success)
            withTimeout(5_000) { model.state.first { fresh.pkgName !in it.actions.installSteps } }

            assertTrue(action(controller, "extension_update", update.pkgName).success)
            assertTrue(controller.execute("extension_update_all").success)
            assertTrue(action(controller, "extension_uninstall", stable.pkgName).success)
            verify(exactly = 1) { manager.removeExtensionWithMeta(stable) }

            action(controller, "extension_install", fresh.pkgName)
            withTimeout(5_000) { model.state.first { it.pendingTrust?.request?.requestId == "confirm" } }
            assertEquals(listOf("old-fingerprint", "new-fingerprint"), controller.snapshot().pendingTrust?.let { listOf(it.existingFingerprint, it.incomingFingerprint) })
            assertEquals(SourceExtensionActionFailureCode.TRUST_PACKAGE_MISMATCH, action(controller, "extension_trust_confirm", update.pkgName).failureCode)
            assertTrue(action(controller, "extension_trust_confirm", fresh.pkgName).success)
            action(controller, "extension_install", fresh.pkgName)
            withTimeout(5_000) { model.state.first { it.pendingTrust?.request?.requestId == "dismiss" } }
            assertTrue(action(controller, "extension_trust_dismiss", fresh.pkgName).success)
            coVerify(exactly = 4) { api.beginInstall(fresh, manager) }
            coVerify(exactly = 2) { api.beginInstall(update, manager) }
            verify(exactly = 1) { api.confirmTrust("confirm", manager) }
            verify(exactly = 1) { api.discardTrust("dismiss") }

            val replacement = SourceExtensionTestModeController(model)
            SourceExtensionTestModeBridge.install(controller); SourceExtensionTestModeBridge.install(replacement)
            assertFalse(SourceExtensionTestModeBridge.clear(controller)); assertTrue(SourceExtensionTestModeBridge.controller === replacement)
            assertTrue(SourceExtensionTestModeBridge.clear(replacement)); assertNull(SourceExtensionTestModeBridge.controller)
        } finally {
            SourceExtensionTestModeBridge.controller?.let(SourceExtensionTestModeBridge::clear)
            model.closeAndJoin()
        }
    }

    private fun action(controller: SourceExtensionTestModeController, name: String, pkg: String) =
        controller.execute(name, mapOf("packageName" to pkg))
    private fun available(pkg: String, name: String, version: Long) = DesktopAvailableExtension(
        name, pkg, "1.0", version, 1.5, "en", false, "https://repo/$pkg.jar", "", "https://repo",
        sources = listOf(DesktopAvailableSource(1, "en", "New Source", "https://source")),
    )
}
