package mihon.desktop.platform

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.prefs.AbstractPreferences
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class PlatformCredentialBackendTest {
    @Test
    fun `credential store copies caller secret and clears backend copy after success and failure`() {
        val backend = ObservableCredentialBackend()
        val store = DesktopCredentialStore(backend)
        val caller = "caller-secret".toCharArray()

        store.save("account", caller)

        assertNotSame(caller, backend.savedSecret)
        assertArrayEquals("caller-secret".toCharArray(), caller)
        assertTrue(backend.savedSecret!!.all { it == '\u0000' })

        val failure = IllegalStateException("save failed")
        backend.saveFailure = failure
        val failingCaller = "failing-secret".toCharArray()
        val thrown = assertThrows(IllegalStateException::class.java) { store.save("account", failingCaller) }

        assertSame(failure, thrown)
        assertNotSame(failingCaller, backend.savedSecret)
        assertArrayEquals("failing-secret".toCharArray(), failingCaller)
        assertTrue(backend.savedSecret!!.all { it == '\u0000' })
    }

    @Test
    fun `credential store clears loaded secret after callback success and failure`() {
        val backend = ObservableCredentialBackend()
        val store = DesktopCredentialStore(backend)
        val loaded = "loaded-secret".toCharArray()
        backend.secretToLoad = loaded

        assertEquals("loaded-secret", store.withSecret("account") { it?.concatToString() })
        assertTrue(loaded.all { it == '\u0000' })

        val failingLoaded = "failing-loaded-secret".toCharArray()
        val failure = IllegalArgumentException("callback failed")
        backend.secretToLoad = failingLoaded
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            store.withSecret("account") { throw failure }
        }

        assertSame(failure, thrown)
        assertTrue(failingLoaded.all { it == '\u0000' })
    }

    @Test
    fun `credential namespaces isolate platform storage and deletion`() {
        val mac = RecordingCommandRunner(
            CommandResult(0, "", ""),
            CommandResult(0, "", ""),
            CommandResult(0, "", ""),
            CommandResult(0, "", ""),
        )
        PlatformCredentialBackend(OperatingSystem.MACOS, mac, CredentialNamespace.TRACKER_V1)
            .save("same", "tracker".toCharArray())
        PlatformCredentialBackend(OperatingSystem.MACOS, mac, CredentialNamespace.APP_LOCK_V1)
            .save("same", "lock".toCharArray())
        PlatformCredentialBackend(OperatingSystem.MACOS, mac, CredentialNamespace.TRACKER_V1).delete("same")
        PlatformCredentialBackend(OperatingSystem.MACOS, mac, CredentialNamespace.APP_LOCK_V1).delete("same")
        assertEquals(
            listOf(
                "mihon-desktop-tracker",
                "mihon-desktop-app-lock-v1",
                "mihon-desktop-tracker",
                "mihon-desktop-app-lock-v1",
            ),
            mac.services(),
        )

        val linux = RecordingCommandRunner(
            CommandResult(0, "", ""),
            CommandResult(0, "", ""),
            CommandResult(0, "", ""),
            CommandResult(0, "", ""),
        )
        PlatformCredentialBackend(OperatingSystem.LINUX, linux, CredentialNamespace.TRACKER_V1)
            .save("same", "tracker".toCharArray())
        PlatformCredentialBackend(OperatingSystem.LINUX, linux, CredentialNamespace.APP_LOCK_V1)
            .save("same", "lock".toCharArray())
        PlatformCredentialBackend(OperatingSystem.LINUX, linux, CredentialNamespace.TRACKER_V1).delete("same")
        PlatformCredentialBackend(OperatingSystem.LINUX, linux, CredentialNamespace.APP_LOCK_V1).delete("same")
        assertEquals(
            listOf(
                "mihon-desktop-tracker",
                "mihon-desktop-app-lock-v1",
                "mihon-desktop-tracker",
                "mihon-desktop-app-lock-v1",
            ),
            linux.services(),
        )

        val root = Preferences.userRoot().node("/mihon-test/credentials/${UUID.randomUUID()}")
        try {
            val runner = RecordingCommandRunner(CommandResult(0, "encrypted-one", ""), CommandResult(0, "encrypted-two", ""))
            val tracker = PlatformCredentialBackend(OperatingSystem.WINDOWS, runner, CredentialNamespace.TRACKER_V1, root)
            val appLock = PlatformCredentialBackend(OperatingSystem.WINDOWS, runner, CredentialNamespace.APP_LOCK_V1, root)
            tracker.save("same", "tracker".toCharArray())
            appLock.save("same", "lock".toCharArray())
            tracker.delete("same")
            assertEquals(0, root.node("v2").keys().size)
            assertEquals(1, root.node("app-lock/v1").keys().size)
            val persisted = root.node("app-lock/v1").get(root.node("app-lock/v1").keys().single(), "")
            assertEquals("encrypted-two", persisted)
            assertFalse(persisted.contains("lock"))
        } finally {
            root.removeNode()
        }
    }

    @Test
    fun `macOS uses generic password service and account with secret only on stdin`() {
        val runner = RecordingCommandRunner(
            CommandResult(0, "", ""),
            CommandResult(0, "6d69686f6e2d76313ae7a798e5af862076616c756520214023\n", ""),
            CommandResult(0, "", ""),
        )
        val backend = PlatformCredentialBackend(OperatingSystem.MACOS, runner)

        backend.save("provider/账户", "秘密 value !@#".toCharArray())
        assertEquals("秘密 value !@#", backend.load("provider/账户")?.concatToString())
        backend.delete("provider/账户")

        assertEquals(
            listOf(
                listOf(
                    "security",
                    "add-generic-password",
                    "-U",
                    "-a",
                    "provider/账户",
                    "-s",
                    "mihon-desktop-tracker",
                    "-w",
                ),
                listOf("security", "find-generic-password", "-a", "provider/账户", "-s", "mihon-desktop-tracker", "-w"),
                listOf("security", "delete-generic-password", "-a", "provider/账户", "-s", "mihon-desktop-tracker"),
            ),
            runner.invocations.map { it.arguments },
        )
        assertEquals("mihon-v1:秘密 value !@#\nmihon-v1:秘密 value !@#\n", runner.invocations.first().stdin)
        assertTrue(runner.invocations.drop(1).all { it.stdin == null })
        assertTrue(runner.invocations.flattenedArguments().none { it.contains("秘密 value") })
    }

    @Test
    fun `macOS preserves legacy raw keychain values`() {
        val backend = PlatformCredentialBackend(
            OperatingSystem.MACOS,
            RecordingCommandRunner(CommandResult(0, "legacy-token-!\n", "")),
        )

        assertEquals("legacy-token-!", backend.load("legacy")?.concatToString())
    }

    @Test
    fun `macOS removes version prefix from raw ASCII keychain values`() {
        val backend = PlatformCredentialBackend(
            OperatingSystem.MACOS,
            RecordingCommandRunner(CommandResult(0, "mihon-v1:ascii-token\n", "")),
        )

        assertEquals("ascii-token", backend.load("ascii")?.concatToString())
    }

    @Test
    fun `macOS preserves all hexadecimal legacy raw keychain values`() {
        val backend = PlatformCredentialBackend(
            OperatingSystem.MACOS,
            RecordingCommandRunner(CommandResult(0, "746f6b656e\n", "")),
        )

        assertEquals("746f6b656e", backend.load("legacy-hex")?.concatToString())
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
    fun `Windows clears transient stdin across every protect exit path`() {
        val root = Preferences.userRoot().node("/mihon-test/credentials/${UUID.randomUUID()}")
        try {
            val success = RecordingCommandRunner(CommandResult(0, "encrypted", ""))
            PlatformCredentialBackend(OperatingSystem.WINDOWS, success, preferencesRoot = root)
                .save("success", "success-secret".toCharArray())
            assertTrue(success.lastStdin!!.all { it == '\u0000' })

            val failing = RecordingCommandRunner(failure = IllegalStateException("runner failed"))
            assertThrows(PlatformCredentialException::class.java) {
                PlatformCredentialBackend(OperatingSystem.WINDOWS, failing, preferencesRoot = root)
                    .save("failure", "failure-secret".toCharArray())
            }
            assertTrue(failing.lastStdin!!.all { it == '\u0000' })

            val empty = RecordingCommandRunner(CommandResult(0, "", ""))
            assertThrows(PlatformCredentialException::class.java) {
                PlatformCredentialBackend(OperatingSystem.WINDOWS, empty, preferencesRoot = root)
                    .save("empty", "empty-secret".toCharArray())
            }
            assertTrue(empty.lastStdin!!.all { it == '\u0000' })

            val flushFailure = RecordingCommandRunner(CommandResult(0, "encrypted", ""))
            val flushError = assertThrows(PlatformCredentialException::class.java) {
                PlatformCredentialBackend(
                    OperatingSystem.WINDOWS,
                    flushFailure,
                    preferencesRoot = FailingFlushPreferences(),
                ).save("flush", "flush-secret".toCharArray())
            }
            assertTrue(flushFailure.lastStdin!!.all { it == '\u0000' })
            assertFalse(flushError.message.orEmpty().contains("flush-secret"))

            val malformed = RecordingCommandRunner(CommandResult(0, "encrypted", ""))
            val malformedSecret = charArrayOf('v', 'a', 'l', 'i', 'd', '-', '\uD800')
            val malformedError = assertThrows(PlatformCredentialException::class.java) {
                PlatformCredentialBackend(OperatingSystem.WINDOWS, malformed, preferencesRoot = root)
                    .save("malformed", malformedSecret)
            }
            assertNull(malformed.lastStdin)
            assertFalse(malformedError.message.orEmpty().contains("valid"))
        } finally {
            root.removeNode()
        }
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
        val appLockStore = DesktopCredentialStore(
            PlatformCredentialBackend(OperatingSystem.WINDOWS, namespace = CredentialNamespace.APP_LOCK_V1),
        )
        try {
            appLockStore.save(key, "app-lock-secret")
            val ciphertext = Preferences.userRoot()
                .node("mihon/desktop/credentials/app-lock/v1")
                .get(credentialPreferenceKey(key), "")
            assertFalse(ciphertext.isBlank())
            assertFalse(ciphertext.contains("app-lock-secret"))
            store.save(key, "初始 secret !@#")
            assertEquals("初始 secret !@#", store.load(key))
            store.save(key, "覆盖 secret /?&")
            assertEquals("覆盖 secret /?&", store.load(key))
            store.delete(key)
            assertNull(store.load(key))
            assertEquals("app-lock-secret", appLockStore.load(key))
        } finally {
            store.delete(key)
            appLockStore.delete(key)
        }
    }

    @Test
    @Tag("integration")
    fun `macOS Keychain credential round trip overwrite and delete on current machine`() {
        if (!System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac")) return
        assumeMacKeychainAccessible()
        val key = "integration.${UUID.randomUUID()}"
        val store = DesktopCredentialStore(PlatformCredentialBackend(OperatingSystem.MACOS))
        try {
            store.save(key, "initial-secret-!@#")
            assertEquals("initial-secret-!@#", store.load(key))
            store.save(key, "覆盖 secret /?&")
            assertEquals("覆盖 secret /?&", store.load(key))
            store.delete(key)
            assertNull(store.load(key))
        } finally {
            store.delete(key)
        }
    }

    private fun assumeMacKeychainAccessible() {
        val result = ProcessCommandRunner().run(listOf("security", "show-keychain-info"))
        assumeFalse(
            result.exitCode == 36 && result.stderr.contains("User interaction is not allowed"),
            "macOS Keychain interaction is unavailable for this test session",
        )
        assertEquals(0, result.exitCode, "macOS Keychain interaction probe failed")
    }

    private class RecordingCommandRunner(
        vararg results: CommandResult,
        private val failure: RuntimeException? = null,
    ) : CommandRunner {
        private val results = ArrayDeque(results.toList())
        val invocations = mutableListOf<CommandInvocation>()
        var lastStdin: CharArray? = null

        override fun run(arguments: List<String>, stdin: CharArray?): CommandResult {
            lastStdin = stdin
            invocations += CommandInvocation(arguments.toList(), stdin?.concatToString())
            failure?.let { throw it }
            return results.removeFirst()
        }
    }

    private class ObservableCredentialBackend : CredentialBackend {
        var savedSecret: CharArray? = null
        var saveFailure: RuntimeException? = null
        var secretToLoad: CharArray? = null

        override fun save(account: String, secret: CharArray) {
            savedSecret = secret
            saveFailure?.let { throw it }
        }

        override fun load(account: String) = secretToLoad

        override fun delete(account: String) = Unit
    }

    private class FailingFlushPreferences(
        parent: AbstractPreferences? = null,
        name: String = "",
    ) : AbstractPreferences(parent, name) {
        private val values = mutableMapOf<String, String>()

        override fun putSpi(key: String, value: String) {
            values[key] = value
        }

        override fun getSpi(key: String) = values[key]

        override fun removeSpi(key: String) {
            values.remove(key)
        }

        override fun removeNodeSpi() = Unit

        override fun keysSpi() = values.keys.toTypedArray()

        override fun childrenNamesSpi() = emptyArray<String>()

        override fun childSpi(name: String) = FailingFlushPreferences(this, name)

        override fun syncSpi() = Unit

        override fun flushSpi(): Unit = throw BackingStoreException("flush failed")
    }

    private data class CommandInvocation(val arguments: List<String>, val stdin: String?) {
        override fun toString(): String = "CommandInvocation(arguments=$arguments, stdin=<redacted>)"
    }

    private fun List<CommandInvocation>.flattenedArguments() = flatMap { it.arguments }

    private fun credentialPreferenceKey(account: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(account.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun RecordingCommandRunner.services() = invocations.map { invocation ->
        val marker = invocation.arguments.indexOfFirst { it == "-s" || it == "service" }
        invocation.arguments[marker + 1]
    }
}
