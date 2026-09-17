package mihon.desktop.platform

import android.content.DesktopSharedPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.test.TestMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.ServerSocket
import java.net.URI
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences

class DesktopTestProfileTest {
    @Test
    fun `real main bootstraps isolated production DI and Test Mode server`(@TempDir dir: File) {
        probe(dir, File(dir, "entry-profile"), "entry")
    }

    @Test
    fun `fixed author sync fixture uses isolated real main and production inbox`(@TempDir directory: File) {
        probe(directory, File(directory, "profile"), "authors")
    }

    @Test
    fun `fresh JVM isolates persistent platform and extension state`(@TempDir dir: File) {
        val profile = File(dir, "profile")
        probe(dir, profile, "write")
        probe(dir, profile, "read")
        probe(dir, File(dir, "other-profile"), "empty")
    }

    @Test
    fun `unmarked occupied directory and real home fail closed`(@TempDir dir: File) {
        File(dir, "sentinel").writeText("untouched", Charsets.UTF_8)
        probe(dir, dir, "reject")
        assertEquals("untouched", File(dir, "sentinel").readText(Charsets.UTF_8))
        probe(dir, File(System.getProperty("user.home")), "reject")
    }

    @Test
    fun `ordinary startup keeps process configuration unchanged`(@TempDir dir: File) {
        probe(dir, File(dir, "unused"), "ordinary")
    }

    private fun probe(dir: File, profile: File, mode: String) {
        val classpath = generateSequence(javaClass.classLoader) { it.parent }
            .filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }
            .map { File(it.toURI()).absolutePath }
            .plus(System.getProperty("java.class.path").split(File.pathSeparator))
            .distinct().joinToString(File.pathSeparator)
        val arguments = File(dir, "probe-$mode.args")
        val output = File(dir, "probe-$mode.log")
        fun quote(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        val options = if (mode == "entry" || mode == "authors") {
            // If main forgets bootstrap, refuse preference construction rather than touching a user's registry.
            listOf(
                "-Djava.util.prefs.PreferencesFactory=${IsolatedDesktopPreferencesFactory::class.java.name}",
                "-Duser.home=${File(dir, "fallback-home").absolutePath}",
                "-Djava.awt.headless=true",
            )
        } else {
            emptyList()
        }
        arguments.writeText(
            (options + listOf("-cp", classpath, DesktopTestProfileProbe::class.java.name, profile.absolutePath, mode))
                .joinToString("\n", transform = ::quote),
            Charsets.UTF_8,
        )
        val java = File(System.getProperty("java.home"), "bin/java${if (File.separatorChar == '\\') ".exe" else ""}")
        val builder = ProcessBuilder(java.absolutePath, "@${arguments.absolutePath}")
            .redirectErrorStream(true).redirectOutput(output)
        builder.environment()["APPDATA"] = File(dir, "fallback-roaming").absolutePath
        builder.environment()["LOCALAPPDATA"] = File(dir, "fallback-local").absolutePath
        val process = builder.start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Isolated profile probe timed out")
            assertEquals(0, process.exitValue(), output.readText(Charsets.UTF_8))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

/** Fresh processes are essential: java.util.prefs chooses its factory only once per JVM. */
object DesktopTestProfileProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val profile = File(args[0]).canonicalFile
        val mode = args[1]
        if (mode == "entry" || mode == "authors") {
            verifyRealEntry(profile, mode == "authors")
            return
        }
        val originalHome = System.getProperty("user.home")
        val originalFactory = System.getProperty("java.util.prefs.PreferencesFactory")
        if (mode == "ordinary") {
            DesktopTestProfile.configure(emptyArray())
            check(System.getProperty("user.home") == originalHome)
            check(System.getProperty("java.util.prefs.PreferencesFactory") == originalFactory)
            check(!profile.exists())
            return
        }
        val configure = { DesktopTestProfile.configure(arrayOf("--test-mode", "--test-profile=$profile")) }
        if (mode == "reject") {
            check(runCatching(configure).isFailure) { "Unsafe profile was accepted" }
            check(System.getProperty("user.home") == originalHome)
            return
        }
        configure()
        check(File(System.getProperty("user.home")).canonicalFile == File(profile, "home")) {
            "Legacy paths are not isolated"
        }
        val paths = DesktopPlatformPaths.current()
        (paths.defaultDirectories() + paths.databaseFile + paths.cookiesFile + paths.instanceStateFile).forEach {
            check(it.canonicalFile.toPath().startsWith(profile.toPath())) { "Production path escaped profile: $it" }
        }
        val store = DesktopPreferenceStore()
        val extension = DesktopSharedPreferences("source_123")
        val root = Preferences.userRoot()
        val system = Preferences.systemRoot()
        when (mode) {
            "write" -> {
                store.getString("profile-probe", "").set("中文设置")
                check(extension.edit().putString("setting", "扩展设置").commit())
                root.node("tree/子节点").put("key", "value")
                root.node("tree/removed").put("gone", "yes")
                root.node("tree/removed").removeNode()
                root.node("config").put("separate", "first")
                root.node("confiM").put("separate", "second")
                root.node("界".repeat(80)).put("unicode", "preserved")
                check(root.node("config").get("separate", null) == "first") {
                    "Distinct preference nodes collided on this filesystem"
                }
                system.node("probe").put("system-only", "yes")
                root.flush()
                system.flush()
            }
            "read" -> {
                check(store.getString("profile-probe", "").get() == "中文设置")
                check(extension.getString("setting", null) == "扩展设置")
                check(root.node("tree").childrenNames().toSet() == setOf("子节点"))
                check(root.node("tree/子节点").keys().toSet() == setOf("key"))
                check(!root.nodeExists("tree/removed"))
                check(root.node("config").get("separate", null) == "first")
                check(root.node("confiM").get("separate", null) == "second")
                check(root.node("界".repeat(80)).get("unicode", null) == "preserved")
                check("界".repeat(80) in root.childrenNames())
                root.node("confiM").removeNode()
                check(root.node("config").get("separate", null) == "first")
                check(system.node("probe").get("system-only", null) == "yes")
                check(root.node("probe").get("system-only", null) == null)
            }
            "empty" -> {
                check(store.getString("profile-probe", "").get().isEmpty())
                check(extension.getString("setting", null) == null)
            }
        }
        val services = mutableListOf<String>()
        val runner = object : CommandRunner {
            override fun run(arguments: List<String>, stdin: CharArray?): CommandResult {
                services += arguments[arguments.indexOf("-s") + 1]
                return CommandResult(44, "", "") // Keychain item absent; never invoke the real OS store.
            }
        }
        CredentialNamespace.entries.forEach { namespace ->
            OsCredentialBackend("Mac OS X", runner, namespace).load("test-account")
            check(services.last().startsWith("${namespace.service}.test-")) {
                "Test mode could access ordinary Keychain entries"
            }
        }
        if (mode == "write") File(profile, "services.txt").writeText(services.joinToString("\n"), Charsets.UTF_8)
        if (mode == "read") check(File(profile, "services.txt").readLines(Charsets.UTF_8) == services)
    }

    private fun verifyRealEntry(profile: File, authors: Boolean = false) {
        val port = ServerSocket(0).use { it.localPort }
        val failure = AtomicReference<Throwable?>()
        val application = Thread {
            try {
                runBlocking {
                    mihon.desktop.main(
                        arrayOf("--test-mode", "--headless", "--test-profile=$profile", "--test-http-port=$port"),
                    )
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply { start() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            var healthy = false
            while (!healthy && System.nanoTime() < deadline && failure.get() == null) {
                healthy = runCatching {
                    val connection = URI("http://127.0.0.1:$port/test/health").toURL()
                        .openConnection(Proxy.NO_PROXY) as HttpURLConnection
                    try {
                        connection.connectTimeout = 200
                        connection.readTimeout = 200
                        connection.responseCode == 200
                    } finally {
                        connection.disconnect()
                    }
                }.getOrDefault(false)
                if (!healthy) Thread.sleep(50)
            }
            failure.get()?.let { throw it }
            check(healthy) { "Production Test Mode never became healthy" }
            check(DesktopTestProfile.root == profile)
            val paths = DesktopPlatformPaths.current()
            check(paths.databaseFile.isFile && paths.databaseFile.toPath().startsWith(profile.toPath()))
            check(paths.instanceStateFile.isFile && paths.instanceStateFile.toPath().startsWith(profile.toPath()))
            check(Preferences.userRoot() is IsolatedDesktopPreferences)
            if (authors) {
                fun action(name: String, body: String, expectedCode: Int = 200): String {
                    val connection = URI("http://127.0.0.1:$port/test/action/$name").toURL()
                        .openConnection(Proxy.NO_PROXY) as HttpURLConnection
                    try {
                        connection.requestMethod = "POST"
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                        val code = connection.responseCode
                        val result = (if (code == 200) connection.inputStream else connection.errorStream)
                            .bufferedReader(Charsets.UTF_8).use { it.readText() }
                        check(code == expectedCode) { "author runtime action failed $name: $code $result" }
                        return result
                    } finally {
                        connection.disconnect()
                    }
                }
                action("author_sync_fixture", """{"step":"arbitrary"}""", 409)
                val marker = profile.resolve(".mihon-test-profile")
                marker.writeText("invalid", Charsets.UTF_8)
                action("author_sync_fixture", """{"step":"add"}""", 409)
                marker.writeText("mihon-desktop-test-profile-v1\n", Charsets.UTF_8)
                val initial = Json.parseToJsonElement(action("author_sync_fixture", """{"step":"add"}"""))
                    .jsonObject.getValue("authors").jsonObject.getValue("identities").jsonArray
                val target = initial.single { it.jsonObject.getValue("displayName").jsonPrimitive.content == "GA06 验收作者" }.jsonObject
                val alias = initial.single { it.jsonObject.getValue("displayName").jsonPrimitive.content == "GA06 验收别名" }.jsonObject
                val targetId = target.getValue("id").jsonPrimitive.content
                val aliasId = alias.getValue("id").jsonPrimitive.content
                action(
                    "author_add_aliases",
                    """{"creatorId":$targetId,"revision":${target.getValue("revision")},"selectedRevisions":{"$aliasId":${alias.getValue("revision")}},"idempotencyKey":"fixture-merge"}""",
                )
                listOf("remove", "confirm_remove", "replay", "refollow").forEach { step ->
                    action("author_sync_fixture", """{"step":"$step"}""")
                }
                action("author_unfollow", """{"creatorId":$targetId}""")
                action("author_sync_fixture", """{"step":"verify_local_cancel"}""")
            }
        } finally {
            TestMode.stop()
            application.join(5_000)
        }
        failure.get()?.let { throw it }
        check(!application.isAlive) { "Production owner did not terminate" }
    }
}
