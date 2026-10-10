package mihon.desktop.platform

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopRecoveryProfileTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `new recovery instance retains original database and does not copy actors or credentials`() {
        val original = DesktopPlatformPaths.resolve("Linux", File(directory, "original").path, emptyMap())
        original.databaseFile.writeText("preserved original database", Charsets.UTF_8)
        val secrets = File(original.configDir, "sync-secrets").apply { mkdirs() }
        File(secrets, "unreadable.record").writeText("original encrypted bytes", Charsets.UTF_8)
        val root = DesktopRecoveryProfile.create(File(directory, "fresh"), original)
        val selected = DesktopPlatformPaths.resolve(
            "Linux", directory.path, mapOf(DesktopRecoveryProfile.ENVIRONMENT_KEY to root.path),
        )
        assertEquals("preserved original database", original.databaseFile.readText(Charsets.UTF_8))
        assertEquals("original encrypted bytes", File(secrets, "unreadable.record").readText(Charsets.UTF_8))
        assertFalse(selected.databaseFile.exists())
        assertFalse(File(selected.configDir, "sync-secrets").exists())
        assertEquals(original.configDir.canonicalPath, DesktopRecoveryProfile.originalLabel(root))
        assertTrue(selected.defaultDirectories().all { it.canonicalPath.startsWith(root.canonicalPath + File.separator) })
    }

    @Test
    fun `nonempty directory and original instance are rejected without changing original bytes`() {
        val original = DesktopPlatformPaths.resolve("Linux", File(directory, "original").path, emptyMap())
        val occupied = File(directory, "occupied").apply { mkdirs() }
        File(occupied, "user.txt").writeText("user bytes", Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) { DesktopRecoveryProfile.create(occupied, original) }
        assertThrows(IllegalArgumentException::class.java) {
            DesktopRecoveryProfile.create(File(original.configDir, "fresh"), original)
        }
        assertEquals("user bytes", File(occupied, "user.txt").readText(Charsets.UTF_8))
    }

    @Test
    fun `recovery credential namespace cannot read change or delete original accounts`() {
        val original = DesktopPlatformPaths.resolve("Linux", File(directory, "original").path, emptyMap())
        val root = DesktopRecoveryProfile.create(File(directory, "fresh"), original)
        val records = mutableMapOf("app-lock" to "original-lock", "tracker" to "original-session")
        val backend = object : CredentialBackend {
            override fun save(account: String, secret: CharArray) { records[account] = secret.concatToString() }
            override fun load(account: String): CharArray? = records[account]?.toCharArray()
            override fun delete(account: String) { records.remove(account) }
        }
        val scoped = DesktopRecoveryProfile.credentialBackend(root, backend)
        assertNull(scoped.load("app-lock"))
        assertNull(scoped.load("tracker"))
        scoped.save("app-lock", "new-lock".toCharArray())
        scoped.delete("tracker")
        assertEquals("original-lock", records["app-lock"])
        assertEquals("original-session", records["tracker"])
        assertEquals("new-lock", scoped.load("app-lock")!!.concatToString())
        assertEquals("original-lock", DesktopRecoveryProfile.credentialBackend(null, backend)
            .load("app-lock")!!.concatToString())
    }

    @Test
    fun `recovery launch uses packaged application and leaves a persistent explicit entry`() {
        val original = DesktopPlatformPaths.resolve("Linux", File(directory, "original").path, emptyMap())
        val root = DesktopRecoveryProfile.create(File(directory, "fresh"), original)
        val packaged = File(directory, "installed/Mihon Desktop.exe").apply {
            parentFile.mkdirs()
            writeText("fixture executable", Charsets.UTF_8)
        }
        File(packaged.parentFile, "app/.jpackage.xml").apply {
            parentFile.mkdirs()
            writeText("fixture package marker", Charsets.UTF_8)
        }
        File(packaged.parentFile, "runtime").mkdirs()
        val commands = mutableListOf<Pair<File, List<String>>>()
        assertTrue(DesktopRecoveryLauncher.launch(root, packaged, OperatingSystem.WINDOWS) { executable, args ->
            commands += executable to args
            true
        })
        assertEquals(listOf(packaged to listOf("--recovery-profile=${root.canonicalPath}")), commands)
        assertTrue(DesktopRecoveryLauncher.writeEntry(root, packaged, OperatingSystem.WINDOWS).isFile)
    }

    @Test
    fun `bad preceding marker remains preserved while the new packaged instance can launch`() {
        val damaged = File(directory, "damaged").apply { mkdirs() }
        File(damaged, ".mihon-recovery-profile").writeText("damaged-marker", Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            DesktopRecoveryProfile.configure(emptyArray(), mapOf(DesktopRecoveryProfile.ENVIRONMENT_KEY to damaged.path))
        }
        val original = DesktopPlatformPaths.preservedRecoveryPaths(damaged)
        val root = DesktopRecoveryProfile.create(File(directory, "fresh"), original)
        val packaged = File(directory, "installed/Mihon Desktop.exe").apply {
            parentFile.mkdirs(); writeText("fixture executable", Charsets.UTF_8)
        }
        File(packaged.parentFile, "app/.jpackage.xml").apply {
            parentFile.mkdirs(); writeText("fixture package marker", Charsets.UTF_8)
        }
        File(packaged.parentFile, "runtime").mkdirs()
        assertTrue(DesktopRecoveryLauncher.launch(root, packaged, OperatingSystem.WINDOWS) { _, _ -> true })
        assertEquals("damaged-marker", File(damaged, ".mihon-recovery-profile").readText(Charsets.UTF_8))
        assertEquals(original.configDir.canonicalPath, DesktopRecoveryProfile.originalLabel(root))
    }

    @Test
    fun `recovery launcher rejects a system runtime and never submits a command`() {
        val original = DesktopPlatformPaths.resolve("Linux", File(directory, "original").path, emptyMap())
        val root = DesktopRecoveryProfile.create(File(directory, "fresh"), original)
        val runtime = File(directory, "java.exe").apply { writeText("system runtime fixture", Charsets.UTF_8) }
        var called = false
        assertThrows(IllegalArgumentException::class.java) {
            DesktopRecoveryLauncher.launch(root, runtime, OperatingSystem.WINDOWS) { _, _ -> called = true; true }
        }
        assertFalse(called)
    }
}
