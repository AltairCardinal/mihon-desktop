package mihon.desktop.platform

import kotlinx.coroutines.runBlocking
import mihon.desktop.DesktopAppRuntime
import mihon.desktop.DesktopInstanceStartResult
import mihon.desktop.DesktopOwnerIngressDependencies
import mihon.desktop.DesktopRuntimeService
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.startProductionDesktopApplication
import mihon.domain.platform.ExternalAction
import mihon.domain.platform.ExternalActionInput
import mihon.domain.platform.ExternalActionParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import tachiyomi.core.common.preference.DesktopPreferenceStore

class DesktopUriSchemeCapabilityTest {
    @Test
    fun `packaging resources register only canonical tachiyomi scheme`() {
        val windows = resource("platform/windows/tachiyomi-url-protocol.reg.template")
        assertTrue(windows.contains("Software\\Classes\\tachiyomi"))
        assertTrue(windows.contains("{{EXECUTABLE}}"))
        assertFalse(windows.contains("Software\\Classes\\mihon"))

        val linux = resource("platform/linux/mihon-desktop.desktop")
        assertTrue(linux.lineSequence().any { it == "MimeType=x-scheme-handler/tachiyomi;" })
        assertTrue(linux.lineSequence().any { it == "Exec=\"{{EXECUTABLE}}\" %u" })
        assertFalse(linux.contains("x-scheme-handler/mihon"))

        val macDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            "<dict>${resource("platform/macos/tachiyomi-url-types.plist")}</dict>".byteInputStream(),
        )
        val strings = macDocument.getElementsByTagName("string")
        val values = (0 until strings.length).map { (strings.item(it) as Element).textContent }
        assertTrue("tachiyomi" in values)
        assertFalse("mihon" in values)
    }

    @Test
    fun `add repository URI semantics remain owned by Task1 parser`() {
        assertEquals(
            ExternalAction.AddRepository("https://repo.example/index.json"),
            ExternalActionParser.resolve(
                ExternalActionInput.ViewUri(
                    "tachiyomi://add-repo?url=https%3A%2F%2Frepo.example%2Findex.json",
                ),
            ),
        )
        assertTrue(
            ExternalActionParser.resolve(
                ExternalActionInput.ViewUri("mihon://add-repo?url=https%3A%2F%2Frepo.example"),
            ) is ExternalAction.Rejected,
        )
    }

    @Test
    fun `production owner registers URI scheme once and starts runtime window`(@TempDir tempDir: File) = runBlocking {
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore())
        val broker = DesktopExternalActionBroker(File(tempDir, "owner-instance.json"))
        val ownerRegistrar = RecordingRegistrar()
        val runtimeService = RecordingRuntimeService()
        val runtime = runtime(runtimeService)
        var ownerFactories = 0
        var windows = 0
        var runtimeWasRunning = false
        try {
            assertEquals(
                DesktopInstanceStartResult.Owner,
                startProductionDesktopApplication(
                    args = emptyArray(),
                    broker = broker,
                    registrar = ownerRegistrar,
                    openUriEventPort = unsupportedOpenUriPort,
                    ownerIngressDependencies = {
                        ownerFactories++
                        DesktopOwnerIngressDependencies(runtime, DesktopUiDependencies.fromInjekt())
                    },
                    runWindowEventLoop = { _, requestClose ->
                        windows++
                        runtimeWasRunning = runtime.isRunning
                        requestClose()
                    },
                ),
            )
            assertEquals(1, ownerRegistrar.calls)
            assertEquals(1, ownerFactories)
            assertEquals(1, windows)
            assertTrue(runtimeWasRunning)
            assertEquals(1, runtimeService.starts)
            assertEquals(1, runtimeService.stops)
        } finally {
            context.closeAndJoin()
        }
    }

    @Test
    fun `production secondary forwards without registering or starting owner`(@TempDir tempDir: File) = runBlocking {
        val stateFile = File(tempDir, "secondary-instance.json")
        val owner = DesktopExternalActionBroker(stateFile)
        assertTrue(owner.startOrForward(null) is DesktopExternalActionBroker.StartResult.Owner)
        val registrar = RecordingRegistrar()
        var ownerFactories = 0
        var windows = 0
        try {
            assertEquals(
                DesktopInstanceStartResult.Forwarded,
                startProductionDesktopApplication(
                    args = arrayOf("tachiyomi://add-repo?url=https%3A%2F%2Frepo.example"),
                    broker = DesktopExternalActionBroker(stateFile),
                    registrar = registrar,
                    openUriEventPort = unsupportedOpenUriPort,
                    ownerIngressDependencies = {
                        ownerFactories++
                        error("secondary must not initialize owner dependencies")
                    },
                    runWindowEventLoop = { _, _ -> windows++ },
                ),
            )
            assertEquals(0, registrar.calls)
            assertEquals(0, ownerFactories)
            assertEquals(0, windows)
        } finally {
            owner.close()
        }
    }

    @Test
    fun `production registration exception is structured while owner runtime window still start`(@TempDir tempDir: File) = runBlocking {
        val context = initDesktopDIForTest(tempDir, DesktopPreferenceStore())
        val broker = DesktopExternalActionBroker(File(tempDir, "registration-instance.json"))
        val runtimeService = RecordingRuntimeService()
        val runtime = runtime(runtimeService)
        var registrarCalls = 0
        var reported: DesktopUriSchemeRegistration.Result? = null
        var windows = 0
        try {
            val result = startProductionDesktopApplication(
                args = emptyArray(),
                broker = broker,
                registrar = DesktopUriSchemeRegistrar {
                    registrarCalls++
                    error("registration failure")
                },
                reportRegistration = { reported = it },
                openUriEventPort = unsupportedOpenUriPort,
                ownerIngressDependencies = {
                    DesktopOwnerIngressDependencies(runtime, DesktopUiDependencies.fromInjekt())
                },
                runWindowEventLoop = { _, requestClose ->
                    windows++
                    requestClose()
                },
            )

            assertEquals(DesktopInstanceStartResult.Owner, result)
            assertEquals(1, registrarCalls)
            assertEquals(
                DesktopUriSchemeRegistration.Result.Failed(
                    DesktopUriSchemeRegistration.FailureReason.UNEXPECTED_FAILURE,
                ),
                reported,
            )
            assertEquals(1, windows)
            assertEquals(1, runtimeService.starts)
            assertEquals(1, runtimeService.stops)
        } finally {
            context.closeAndJoin()
        }
    }

    private fun resource(path: String): String = checkNotNull(javaClass.classLoader.getResourceAsStream(path)) {
        "Missing test resource: $path"
    }.bufferedReader().use { it.readText() }

    private class RecordingRegistrar : DesktopUriSchemeRegistrar {
        var calls = 0

        override fun register(): DesktopUriSchemeRegistration.Result {
            calls++
            return DesktopUriSchemeRegistration.Result.Configured(
                DesktopUriSchemeRegistration.Mechanism.WINDOWS_CURRENT_USER_REGISTRY,
            )
        }
    }

    private fun runtime(service: RecordingRuntimeService) = DesktopAppRuntime(
        libraryUpdateScheduler = service,
        localSourceScanService = RecordingRuntimeService(),
        autoBackupScheduler = RecordingRuntimeService(),
        startupCleanup = {},
    )

    private class RecordingRuntimeService : DesktopRuntimeService {
        var starts = 0
        var stops = 0

        override fun start() {
            starts++
        }

        override fun stop() {
            stops++
        }
    }

    private companion object {
        val unsupportedOpenUriPort = DesktopOpenUriEventPort { DesktopOpenUriInstallResult.Unsupported }
    }
}
