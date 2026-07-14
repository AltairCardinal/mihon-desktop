package mihon.desktop.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.UUID

class PlatformCredentialBackendTest {
    @Test
    fun `macOS uses generic password service and account with secret only on stdin`() {
        val runner = RecordingCommandRunner(
            CommandResult(0, "", ""),
            CommandResult(0, "秘密 value !@#\n", ""),
            CommandResult(0, "", ""),
        )
        val backend = PlatformCredentialBackend(OperatingSystem.MACOS, runner)

        backend.save("provider/账户", "秘密 value !@#".toCharArray())
        assertEquals("秘密 value !@#", backend.load("provider/账户")?.concatToString())
        backend.delete("provider/账户")

        assertEquals(
            listOf(
                listOf("security", "add-generic-password", "-U", "-a", "provider/账户", "-s", "mihon-desktop-tracker"),
                listOf("security", "find-generic-password", "-a", "provider/账户", "-s", "mihon-desktop-tracker", "-w"),
                listOf("security", "delete-generic-password", "-a", "provider/账户", "-s", "mihon-desktop-tracker"),
            ),
            runner.invocations.map { it.arguments },
        )
        assertEquals("秘密 value !@#\n", runner.invocations.first().stdin)
        assertTrue(runner.invocations.drop(1).all { it.stdin == null })
        assertTrue(runner.invocations.flattenedArguments().none { it.contains("秘密 value") })
    }

    @Test
    fun `Linux secret service uses stdin and distinguishes absent permission and missing CLI`() {
        val absent = PlatformCredentialBackend(
            OperatingSystem.LINUX,
            RecordingCommandRunner(CommandResult(1, "", ""), CommandResult(1, "", "")),
        )
        assertNull(absent.load("missing"))
        absent.delete("missing")

        val deniedRunner = RecordingCommandRunner(CommandResult(1, "", "org.freedesktop.Secret.Error.IsLocked"))
        val denied = PlatformCredentialBackend(OperatingSystem.LINUX, deniedRunner)
        val deniedError = assertThrows(PlatformCredentialException::class.java) { denied.load("账户") }
        assertFalse(deniedError.message.orEmpty().contains("org.freedesktop"))

        val unavailable = PlatformCredentialBackend(
            OperatingSystem.LINUX,
            RecordingCommandRunner(failure = CommandUnavailableException("secret-tool")),
        )
        val unavailableError = assertThrows(PlatformCredentialUnavailableException::class.java) {
            unavailable.save("account", "token-value".toCharArray())
        }
        assertFalse(unavailableError.message.orEmpty().contains("token-value"))
    }

    @Test
    fun `macOS absent and permission failures have distinct semantics without leaking output`() {
        val absent = PlatformCredentialBackend(
            OperatingSystem.MACOS,
            RecordingCommandRunner(CommandResult(44, "", "security: SecKeychainSearchCopyNext: item not found")),
        )
        assertNull(absent.load("missing"))

        val denied = PlatformCredentialBackend(
            OperatingSystem.MACOS,
            RecordingCommandRunner(CommandResult(36, "", "User interaction is not allowed: sensitive-detail")),
        )
        val error = assertThrows(PlatformCredentialException::class.java) { denied.load("account") }
        assertFalse(error.message.orEmpty().contains("sensitive-detail"))
    }

    @Test
    fun `backend and command objects never stringify secrets`() {
        val runner = RecordingCommandRunner(CommandResult(0, "", ""))
        val backend = PlatformCredentialBackend(OperatingSystem.LINUX, runner)
        backend.save("account", "super-secret".toCharArray())

        assertFalse(backend.toString().contains("super-secret"))
        assertFalse(runner.invocations.single().toString().contains("super-secret"))
    }

    @Test
    @Tag("integration")
    fun `Windows DPAPI credential round trip overwrite and delete on current machine`() {
        if (!System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")) return
        val key = "integration.${UUID.randomUUID()}"
        val store = DesktopCredentialStore(PlatformCredentialBackend(OperatingSystem.WINDOWS))
        try {
            store.save(key, "初始 secret !@#")
            assertEquals("初始 secret !@#", store.load(key))
            store.save(key, "覆盖 secret /?&")
            assertEquals("覆盖 secret /?&", store.load(key))
            store.delete(key)
            assertNull(store.load(key))
        } finally {
            store.delete(key)
        }
    }

    @Test
    @Tag("integration")
    fun `macOS Keychain credential round trip overwrite and delete on current machine`() {
        if (!System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac")) return
        val key = "integration.${UUID.randomUUID()}"
        val store = DesktopCredentialStore(PlatformCredentialBackend(OperatingSystem.MACOS))
        try {
            store.save(key, "初始 secret !@#")
            assertEquals("初始 secret !@#", store.load(key))
            store.save(key, "覆盖 secret /?&")
            assertEquals("覆盖 secret /?&", store.load(key))
            store.delete(key)
            assertNull(store.load(key))
        } finally {
            store.delete(key)
        }
    }

    private class RecordingCommandRunner(
        vararg results: CommandResult,
        private val failure: RuntimeException? = null,
    ) : CommandRunner {
        private val results = ArrayDeque(results.toList())
        val invocations = mutableListOf<CommandInvocation>()

        override fun run(arguments: List<String>, stdin: CharArray?): CommandResult {
            invocations += CommandInvocation(arguments.toList(), stdin?.concatToString())
            failure?.let { throw it }
            return results.removeFirst()
        }
    }

    private data class CommandInvocation(val arguments: List<String>, val stdin: String?) {
        override fun toString(): String = "CommandInvocation(arguments=$arguments, stdin=<redacted>)"
    }

    private fun List<CommandInvocation>.flattenedArguments() = flatMap { it.arguments }
}
