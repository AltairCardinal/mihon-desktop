package mihon.desktop.platform

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.prefs.Preferences

interface CredentialBackend {
    fun save(account: String, secret: CharArray)
    fun load(account: String): CharArray?
    fun delete(account: String)
}

class DesktopCredentialStore(
    private val backend: CredentialBackend = OsCredentialBackend(),
) {
    fun save(account: String, secret: CharArray) {
        require(account.isNotBlank())
        val copy = secret.copyOf()
        try {
            backend.save(account, copy)
        } finally {
            copy.fill('\u0000')
        }
    }

    fun save(account: String, secret: String) {
        require(account.isNotBlank())
        val chars = secret.toCharArray()
        try {
            backend.save(account, chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    internal fun <T> withSecret(account: String, operation: (CharArray?) -> T): T {
        require(account.isNotBlank())
        val chars = backend.load(account)
        return try {
            operation(chars)
        } finally {
            chars?.fill('\u0000')
        }
    }

    fun load(account: String): String? {
        require(account.isNotBlank())
        val chars = backend.load(account) ?: return null
        return try {
            chars.concatToString()
        } finally {
            chars.fill('\u0000')
        }
    }

    fun delete(account: String) {
        require(account.isNotBlank())
        backend.delete(account)
    }

    override fun toString(): String = "DesktopCredentialStore(backend=${backend::class.simpleName})"
}

enum class OperatingSystem {
    WINDOWS,
    MACOS,
    LINUX,
    UNSUPPORTED,
    ;

    companion object {
        fun detect(osName: String = System.getProperty("os.name")): OperatingSystem {
            val normalized = osName.lowercase(Locale.ROOT)
            return when {
                "win" in normalized -> WINDOWS
                "mac" in normalized || "darwin" in normalized -> MACOS
                "linux" in normalized -> LINUX
                else -> UNSUPPORTED
            }
        }
    }
}

enum class CredentialNamespace(val service: String, val windowsNode: String, val label: String) {
    TRACKER_V1("mihon-desktop-tracker", "v2", "Mihon Desktop"),
    APP_LOCK_V1("mihon-desktop-app-lock-v1", "app-lock/v1", "Mihon Desktop App Lock"),
}

class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    override fun toString(): String = "CommandResult(exitCode=$exitCode, stdout=<redacted>, stderr=<redacted>)"
}

interface CommandRunner {
    fun run(arguments: List<String>, stdin: CharArray? = null): CommandResult
}

class CommandUnavailableException(command: String, cause: Throwable? = null) :
    RuntimeException("Required credential command is unavailable: $command", cause)

open class PlatformCredentialException(message: String) : IllegalStateException(message)

class PlatformCredentialUnavailableException(platform: OperatingSystem) :
    PlatformCredentialException("Secure credential storage is unavailable on $platform")

class ProcessCommandRunner : CommandRunner {
    override fun run(arguments: List<String>, stdin: CharArray?): CommandResult {
        require(arguments.isNotEmpty())
        val process = try {
            ProcessBuilder(arguments).start()
        } catch (error: IOException) {
            throw CommandUnavailableException(arguments.first(), error)
        }
        process.outputStream.writer(StandardCharsets.UTF_8).use { writer ->
            if (stdin != null) writer.write(stdin)
        }
        val stdout = process.inputStream.reader(StandardCharsets.UTF_8).use { it.readText() }
        val stderr = process.errorStream.reader(StandardCharsets.UTF_8).use { it.readText() }
        return CommandResult(process.waitFor(), stdout, stderr)
    }

    override fun toString(): String = "ProcessCommandRunner"
}

/**
 * Stores credentials only in an OS-protected facility: CurrentUser DPAPI, macOS Keychain, or Secret Service.
 * There is deliberately no file or preferences plaintext fallback.
 */
class PlatformCredentialBackend(
    private val platform: OperatingSystem = OperatingSystem.detect(),
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val namespace: CredentialNamespace = CredentialNamespace.TRACKER_V1,
    preferencesRoot: Preferences = Preferences.userRoot().node("mihon/desktop/credentials"),
) : CredentialBackend {
    private val preferences = preferencesRoot.node(namespace.windowsNode)
    override fun save(account: String, secret: CharArray) {
        when (platform) {
            OperatingSystem.WINDOWS -> saveWindows(account, secret)
            OperatingSystem.MACOS -> saveMac(account, secret)
            OperatingSystem.LINUX -> saveLinux(account, secret)
            OperatingSystem.UNSUPPORTED -> unavailable()
        }
    }

    override fun load(account: String): CharArray? = when (platform) {
        OperatingSystem.WINDOWS -> loadWindows(account)
        OperatingSystem.MACOS -> loadMac(account)
        OperatingSystem.LINUX -> loadLinux(account)
        OperatingSystem.UNSUPPORTED -> unavailable()
    }

    override fun delete(account: String) {
        when (platform) {
            OperatingSystem.WINDOWS -> deleteWindows(account)
            OperatingSystem.MACOS -> deleteMac(account)
            OperatingSystem.LINUX -> deleteLinux(account)
            OperatingSystem.UNSUPPORTED -> unavailable()
        }
    }

    private fun saveWindows(account: String, secret: CharArray) {
        val encoded = Base64.getEncoder().encodeToString(secret.concatToString().toByteArray(StandardCharsets.UTF_8))
        val encrypted = runCommand(
            listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", PROTECT_SCRIPT),
            encoded.toCharArray(),
            "protect",
        ).stdout.trim()
        if (encrypted.isEmpty()) operationFailed("protect")
        preferences.put(preferenceKey(account), encrypted)
        preferences.flush()
    }

    private fun loadWindows(account: String): CharArray? {
        val encrypted = preferences.get(preferenceKey(account), null) ?: return null
        val result = runCommand(
            listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", UNPROTECT_SCRIPT),
            encrypted.toCharArray(),
            "unprotect",
        )
        return try {
            String(Base64.getDecoder().decode(result.stdout.trim()), StandardCharsets.UTF_8).toCharArray()
        } catch (_: IllegalArgumentException) {
            operationFailed("unprotect")
        }
    }

    private fun deleteWindows(account: String) {
        preferences.remove(preferenceKey(account))
        preferences.flush()
    }

    private fun saveMac(account: String, secret: CharArray) {
        val payload = CharArray(MAC_VALUE_PREFIX.length + secret.size)
        MAC_VALUE_PREFIX.toCharArray().copyInto(payload)
        secret.copyInto(payload, destinationOffset = MAC_VALUE_PREFIX.length)
        val stdin = CharArray((payload.size + 1) * 2)
        payload.copyInto(stdin)
        stdin[payload.size] = '\n'
        payload.copyInto(stdin, destinationOffset = payload.size + 1)
        stdin[stdin.lastIndex] = '\n'
        try {
            requireSuccess(
                runCommand(
                    listOf("security", "add-generic-password", "-U", "-a", account, "-s", namespace.service, "-w"),
                    stdin,
                    "save",
                ),
                "save",
            )
        } finally {
            payload.fill('\u0000')
            stdin.fill('\u0000')
        }
    }

    private fun loadMac(account: String): CharArray? {
        val result = runCommand(
            listOf("security", "find-generic-password", "-a", account, "-s", namespace.service, "-w"),
            operation = "load",
        )
        if (result.exitCode == MAC_ITEM_NOT_FOUND) return null
        requireSuccess(result, "load")
        return decodeMacValue(result.stdout.trimEnd('\r', '\n'))
    }

    private fun decodeMacValue(storedValue: String): CharArray {
        if (storedValue.startsWith(MAC_VALUE_PREFIX)) {
            return storedValue.substring(MAC_VALUE_PREFIX.length).toCharArray()
        }
        if (storedValue.length % 2 != 0 || storedValue.any { it.digitToIntOrNull(16) == null }) {
            return storedValue.toCharArray()
        }
        val bytes = ByteArray(storedValue.length / 2) { index ->
            val offset = index * 2
            ((storedValue[offset].digitToInt(16) shl 4) or storedValue[offset + 1].digitToInt(16)).toByte()
        }
        val decoded = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return storedValue.toCharArray()
        } finally {
            bytes.fill(0)
        }
        return if (decoded.startsWith(MAC_VALUE_PREFIX)) {
            decoded.substring(MAC_VALUE_PREFIX.length).toCharArray()
        } else {
            storedValue.toCharArray()
        }
    }

    private fun deleteMac(account: String) {
        val result = runCommand(
            listOf("security", "delete-generic-password", "-a", account, "-s", namespace.service),
            operation = "delete",
        )
        if (result.exitCode != MAC_ITEM_NOT_FOUND) requireSuccess(result, "delete")
    }

    private fun saveLinux(account: String, secret: CharArray) {
        requireSuccess(
            runCommand(
                listOf("secret-tool", "store", "--label=${namespace.label}", "service", namespace.service, "account", account),
                secret,
                "save",
            ),
            "save",
        )
    }

    private fun loadLinux(account: String): CharArray? {
        val result = runCommand(
            listOf("secret-tool", "lookup", "service", namespace.service, "account", account),
            operation = "load",
        )
        if (result.exitCode == 1 && result.stdout.isBlank() && result.stderr.isBlank()) return null
        requireSuccess(result, "load")
        return result.stdout.trimEnd('\r', '\n').toCharArray()
    }

    private fun deleteLinux(account: String) {
        val result = runCommand(
            listOf("secret-tool", "clear", "service", namespace.service, "account", account),
            operation = "delete",
        )
        if (result.exitCode != 1 || result.stdout.isNotBlank() || result.stderr.isNotBlank()) {
            requireSuccess(result, "delete")
        }
    }

    private fun runCommand(
        arguments: List<String>,
        stdin: CharArray? = null,
        operation: String,
    ): CommandResult = try {
        runner.run(arguments, stdin)
    } catch (_: CommandUnavailableException) {
        throw PlatformCredentialUnavailableException(platform)
    } catch (_: IOException) {
        throw PlatformCredentialUnavailableException(platform)
    } catch (error: PlatformCredentialException) {
        throw error
    } catch (_: RuntimeException) {
        operationFailed(operation)
    }

    private fun requireSuccess(result: CommandResult, operation: String) {
        if (result.exitCode != 0) operationFailed(operation)
    }

    private fun operationFailed(operation: String): Nothing =
        throw PlatformCredentialException("Secure credential $operation failed on $platform")

    private fun unavailable(): Nothing = throw PlatformCredentialUnavailableException(platform)

    private fun preferenceKey(account: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(account.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    override fun toString(): String = "PlatformCredentialBackend(platform=$platform, runner=${runner::class.simpleName})"

    companion object {
        private const val MAC_VALUE_PREFIX = "mihon-v1:"
        private const val MAC_ITEM_NOT_FOUND = 44
        private const val PROTECT_SCRIPT =
            "Add-Type -AssemblyName System.Security; " +
                "\$value=[Console]::In.ReadToEnd(); " +
                "\$bytes=[Convert]::FromBase64String(\$value); " +
                "\$protected=[Security.Cryptography.ProtectedData]::Protect(\$bytes,\$null,[Security.Cryptography.DataProtectionScope]::CurrentUser); " +
                "[Convert]::ToBase64String(\$protected)"
        private const val UNPROTECT_SCRIPT =
            "Add-Type -AssemblyName System.Security; " +
                "\$value=[Console]::In.ReadToEnd(); " +
                "\$bytes=[Convert]::FromBase64String(\$value); " +
                "\$plain=[Security.Cryptography.ProtectedData]::Unprotect(\$bytes,\$null,[Security.Cryptography.DataProtectionScope]::CurrentUser); " +
                "[Convert]::ToBase64String(\$plain)"
    }
}

class OsCredentialBackend(
    osName: String = System.getProperty("os.name"),
    runner: CommandRunner = ProcessCommandRunner(),
    namespace: CredentialNamespace = CredentialNamespace.TRACKER_V1,
) : CredentialBackend {
    private val platform = OperatingSystem.detect(osName)
    private val delegate = PlatformCredentialBackend(platform, runner, namespace)

    override fun save(account: String, secret: CharArray) = delegate.save(account, secret)
    override fun load(account: String): CharArray? = delegate.load(account)
    override fun delete(account: String) = delegate.delete(account)
    override fun toString(): String = "OsCredentialBackend(platform=$platform)"
}
