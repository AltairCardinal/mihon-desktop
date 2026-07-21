package mihon.desktop.platform

import mihon.desktop.DesktopInstanceStartResult
import mihon.desktop.startDesktopApplication
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
    fun `Main production startup registers only for elected owner`(@TempDir tempDir: File) {
        val stateFile = File(tempDir, "instance.json")
        val ownerBroker = DesktopExternalActionBroker(stateFile)
        val ownerRegistrar = RecordingRegistrar()
        var ownerStarts = 0

        assertEquals(
            DesktopInstanceStartResult.Owner,
            startDesktopApplication(
                args = emptyArray(),
                broker = ownerBroker,
                registrar = ownerRegistrar,
                startOwnerApplication = { ownerStarts++ },
            ),
        )
        assertEquals(1, ownerRegistrar.calls)
        assertEquals(1, ownerStarts)

        val secondaryRegistrar = RecordingRegistrar()
        val secondary = DesktopExternalActionBroker(stateFile)
        var secondaryStarts = 0
        assertEquals(
            DesktopInstanceStartResult.Forwarded,
            startDesktopApplication(
                args = arrayOf("tachiyomi://add-repo?url=https%3A%2F%2Frepo.example"),
                broker = secondary,
                registrar = secondaryRegistrar,
                startOwnerApplication = { secondaryStarts++ },
            ),
        )
        assertEquals(0, secondaryRegistrar.calls)
        assertEquals(0, secondaryStarts)
        ownerBroker.close()
    }

    @Test
    fun `registration exception is reported without blocking owner application`(@TempDir tempDir: File) {
        val broker = DesktopExternalActionBroker(File(tempDir, "instance.json"))
        var reported: DesktopUriSchemeRegistration.Result? = null
        var ownerStarts = 0

        val result = startDesktopApplication(
            args = emptyArray(),
            broker = broker,
            registrar = DesktopUriSchemeRegistrar { error("registration failure") },
            reportRegistration = { reported = it },
            startOwnerApplication = { ownerStarts++ },
        )

        assertEquals(DesktopInstanceStartResult.Owner, result)
        assertEquals(
            DesktopUriSchemeRegistration.Result.Failed(
                DesktopUriSchemeRegistration.FailureReason.UNEXPECTED_FAILURE,
            ),
            reported,
        )
        assertEquals(1, ownerStarts)
        broker.close()
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
}
