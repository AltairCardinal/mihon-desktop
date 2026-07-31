package mihon.desktop.release

import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class DesktopExtensionRuntimeAcceptanceTest {

    @Test
    fun `runtime acceptance arguments require guarded headless test mode`(@TempDir tempDir: Path) {
        val args = acceptanceArgs(tempDir)

        assertEquals(null, desktopExtensionRuntimeAcceptanceRequest(emptyArray()))
        assertThrows<IllegalArgumentException> {
            desktopExtensionRuntimeAcceptanceRequest(args.filterNot { it == "--test-mode" }.toTypedArray())
        }
        assertThrows<IllegalArgumentException> {
            desktopExtensionRuntimeAcceptanceRequest(args.filterNot { it == "--headless" }.toTypedArray())
        }

        val request = requireNotNull(desktopExtensionRuntimeAcceptanceRequest(args))
        assertEquals(tempDir.toAbsolutePath().normalize(), request.profileDirectory)
        assertEquals(tempDir.resolve("result.json").toAbsolutePath().normalize(), request.resultFile)
        assertEquals("eu.kanade.tachiyomi.extension.zh.fixture", request.packageName)
        assertEquals(28L, request.versionCode)
        assertEquals(1234L, request.expectedSourceId)
        assertEquals("http://127.0.0.1:18080/apk/fixture.apk", request.artifactUrl)
    }

    @Test
    fun `runtime acceptance writes installed production result`(@TempDir tempDir: Path) = runBlocking {
        val request = requireNotNull(desktopExtensionRuntimeAcceptanceRequest(acceptanceArgs(tempDir)))

        val result = executeDesktopExtensionRuntimeAcceptance(request, "0.11.14.80.test") {
            assertEquals(request, it)
            listOf(1234L)
        }

        assertTrue(result.success)
        assertEquals("0.11.14.80.test", result.appVersion)
        assertEquals(listOf(1234L), result.sourceIds)
        val persisted = Json.parseToJsonElement(request.resultFile.toFile().readText()).jsonObject
        assertTrue(persisted.getValue("success").jsonPrimitive.content.toBoolean())
        assertEquals(request.packageName, persisted.getValue("packageName").jsonPrimitive.content)
        assertEquals("1234", persisted.getValue("sourceIds").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `runtime acceptance preserves the conversion root cause`(@TempDir tempDir: Path) = runBlocking {
        val request = requireNotNull(desktopExtensionRuntimeAcceptanceRequest(acceptanceArgs(tempDir)))

        val result = executeDesktopExtensionRuntimeAcceptance(request, "0.11.14.80.test") {
            throw IllegalStateException("DEX conversion failed", IOException("cant find zipfs support"))
        }

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("cant find zipfs support"), result.error)
        assertTrue(request.resultFile.toFile().isFile)
    }

    private fun acceptanceArgs(tempDir: Path) = arrayOf(
        "--test-mode",
        "--headless",
        "--test-extension-runtime",
        "--test-extension-runtime-profile=${tempDir.toAbsolutePath()}",
        "--test-extension-runtime-result=${tempDir.resolve("result.json").toAbsolutePath()}",
        "--test-extension-runtime-url=http://127.0.0.1:18080/apk/fixture.apk",
        "--test-extension-runtime-package=eu.kanade.tachiyomi.extension.zh.fixture",
        "--test-extension-runtime-name=Fixture",
        "--test-extension-runtime-version=1.4.28",
        "--test-extension-runtime-code=28",
        "--test-extension-runtime-fingerprint=9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
        "--test-extension-runtime-sha256=200cfc4b3b9e98f387824e3cecb13f97f4b0971f8fb678ce49c60aab6856c0c8",
        "--test-extension-runtime-source-id=1234",
    )
}
