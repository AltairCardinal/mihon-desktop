package mihon.desktop.extension

import eu.kanade.tachiyomi.source.Source
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.service.ExtensionInstallFailure
import mihon.domain.extension.service.ExtensionInstallRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExtensionArtifactReplacementTest {
    @Test
    fun `atomic move unsupported fails without replacing destination`(@TempDir directory: Path) {
        val destination = directory.resolve("extension.jar").toFile().also { it.writeText("old") }
        val snapshot = directory.resolve("candidate.jar").toFile().also { it.writeText("new") }
        var attempts = 0
        val fileSystem = NioDesktopExtensionFileSystem { source, target ->
            attempts++
            throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "injected")
        }

        assertThrows(AtomicMoveNotSupportedException::class.java) {
            fileSystem.replaceFromSnapshot(snapshot, destination)
        }

        assertEquals(1, attempts)
        assertEquals("old", destination.readText())
        assertEquals("new", snapshot.readText())
        assertTrue(directory.toFile().listFiles().orEmpty().none { it.name.endsWith(".replace.tmp") })
    }

    @Test
    fun `transient access denied retries atomic replacement until third attempt`(@TempDir directory: Path) {
        val destination = directory.resolve("extension.jar").toFile().also { it.writeText("old") }
        val snapshot = directory.resolve("candidate.jar").toFile().also { it.writeText("new") }
        var attempts = 0
        val fileSystem = NioDesktopExtensionFileSystem { source, target ->
            attempts++
            if (attempts < 3) {
                throw AccessDeniedException(source.toString(), target.toString(), "injected-$attempts")
            }
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        fileSystem.replaceFromSnapshot(snapshot, destination)

        assertEquals(3, attempts)
        assertEquals("new", destination.readText())
        assertEquals("new", snapshot.readText())
        assertTrue(directory.toFile().listFiles().orEmpty().none { it.name.endsWith(".replace.tmp") })
    }

    @Test
    fun `persistent access denied exhausts three attempts and preserves replacement inputs`(@TempDir directory: Path) {
        val destination = directory.resolve("extension.jar").toFile().also { it.writeText("old") }
        val snapshot = directory.resolve("candidate.jar").toFile().also { it.writeText("new") }
        val failures = mutableListOf<AccessDeniedException>()
        val fileSystem = NioDesktopExtensionFileSystem { source, target ->
            throw AccessDeniedException(source.toString(), target.toString(), "injected-${failures.size + 1}")
                .also(failures::add)
        }

        val thrown = assertThrows(AccessDeniedException::class.java) {
            fileSystem.replaceFromSnapshot(snapshot, destination)
        }

        assertEquals(3, failures.size)
        assertSame(failures.last(), thrown)
        assertEquals("old", destination.readText())
        assertEquals("new", snapshot.readText())
        assertTrue(directory.toFile().listFiles().orEmpty().none { it.name.endsWith(".replace.tmp") })
    }

    @Test
    fun `ordinary io failure is not retried and preserves destination`(@TempDir directory: Path) {
        val destination = directory.resolve("extension.jar").toFile().also { it.writeText("old") }
        val snapshot = directory.resolve("candidate.jar").toFile().also { it.writeText("new") }
        var attempts = 0
        val failure = IOException("injected")
        val fileSystem = NioDesktopExtensionFileSystem { _, _ ->
            attempts++
            throw failure
        }

        val thrown = assertThrows(IOException::class.java) {
            fileSystem.replaceFromSnapshot(snapshot, destination)
        }

        assertEquals(1, attempts)
        assertSame(failure, thrown)
        assertEquals("old", destination.readText())
        assertEquals("new", snapshot.readText())
        assertTrue(directory.toFile().listFiles().orEmpty().none { it.name.endsWith(".replace.tmp") })
    }

    @Test
    fun `snapshot replacement overwrites destination without consuming snapshot`(@TempDir directory: Path) {
        val destination = directory.resolve("extension.jar").toFile().also { it.writeText("old") }
        val snapshot = directory.resolve("candidate.jar").toFile().also { it.writeText("new") }

        DefaultDesktopExtensionFileSystem.replaceFromSnapshot(snapshot, destination)

        assertEquals("new", destination.readText())
        assertEquals("new", snapshot.readText())
        assertTrue(directory.toFile().listFiles().orEmpty().none { it.name.endsWith(".replace.tmp") })
    }

    @Test
    fun `same transaction can retry metadata commit and rollback second steps`(@TempDir directory: Path) = runBlocking {
        val installed = directory.resolve("$PACKAGE.jar").toFile().also { it.writeBytes(sourceJar(FixtureOldSource::class.java)) }
        writeExtensionMeta(
            installed,
            ExtensionMeta(
                pkgName = PACKAGE,
                versionCode = 1,
                versionName = "old",
                repoUrl = "https://repo.example",
                repoName = "repo",
                repoFingerprint = "fingerprint",
                artifactSha256 = "old",
            ),
        )
        val oldJar = installed.readBytes()
        val metadata = directory.resolve("$PACKAGE.meta.json").toFile()
        val oldMeta = metadata.readBytes()
        val fileSystem = FailMetadataReplaceFileSystem(metadata, setOf(1, 3))
        val port = DesktopExtensionInstallPort(
            extensionsDirectory = directory.toFile(),
            artifactProvider = { _, destination -> destination.writeBytes(sourceJar(FixtureNewSource::class.java)) },
            apkConverter = ApkToJarConverter(),
            loader = DesktopExtensionLoader(directory.toFile()),
            artifactAuthenticator = DesktopArtifactAuthenticator { _, _, _ -> },
            releaseRuntime = {},
            reloadRuntime = { _, _ -> },
            fileSystem = fileSystem,
        )
        val prepared = port.prepare(ExtensionInstallRequest(artifact()))
        val rollback = port.validate(prepared)

        assertThrows(ExtensionInstallFailure::class.java) { runBlocking { port.commit(prepared) } }
        port.commit(prepared)
        val loaded = DesktopExtensionLoader(directory.toFile()).loadPackage(PACKAGE)
        try {
            assertEquals(FixtureNewSource.ID, loaded.single().source.id)
        } finally {
            loaded.map { it.classLoader }.distinct().forEach { (it as? AutoCloseable)?.close() }
        }

        assertThrows(ExtensionInstallFailure::class.java) { runBlocking { port.rollback(rollback) } }
        port.rollback(rollback)
        assertTrue(oldJar.contentEquals(installed.readBytes()))
        assertTrue(oldMeta.contentEquals(metadata.readBytes()))

        port.cleanup(prepared)
        assertTrue(directory.toFile().listFiles().orEmpty().none { it.name.startsWith(".install-") })
    }

    private fun artifact() = ExtensionArtifact(
        name = "Fixture",
        packageName = PACKAGE,
        versionName = "new",
        versionCode = 2,
        language = "en",
        isNsfw = false,
        sources = listOf(ExtensionSourceDescriptor(FixtureNewSource.ID, "en", "Fixture", "https://example.com")),
        repository = RepositoryIdentity("https://repo.example", "repo", "fingerprint"),
        downloadUrl = "https://repo.example/fixture.jar",
        iconUrl = "",
        declaredSha256 = null,
    )

    private fun sourceJar(source: Class<out Source>): ByteArray {
        val path = source.name.replace('.', '/') + ".class"
        val bytes = checkNotNull(source.classLoader.getResourceAsStream(path)).use { it.readBytes() }
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                listOf(
                    path to bytes,
                    SERVICE to source.name.toByteArray(),
                ).forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
    }

    private class FailMetadataReplaceFileSystem(
        private val metadata: File,
        private val failureOccurrences: Set<Int>,
    ) : DesktopExtensionFileSystem by DefaultDesktopExtensionFileSystem {
        private var occurrence = 0

        override fun replaceFromSnapshot(snapshot: File, destination: File) {
            if (destination == metadata && ++occurrence in failureOccurrences) {
                throw IOException("injected metadata replacement failure")
            }
            DefaultDesktopExtensionFileSystem.replaceFromSnapshot(snapshot, destination)
        }
    }

    private companion object {
        const val PACKAGE = "mihon.desktop.extension"
        const val SERVICE = "META-INF/services/eu.kanade.tachiyomi.source.Source"
    }
}
