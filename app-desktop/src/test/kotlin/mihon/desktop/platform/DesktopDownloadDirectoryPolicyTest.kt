package mihon.desktop.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DesktopDownloadDirectoryPolicyTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `startup resolution keeps a legal configured path unknown without probing storage`() {
        val probe = RecordingProbe(DesktopDownloadDirectoryProbeResult.available())
        val policy = DesktopDownloadDirectoryPolicy(probe)
        val defaultDirectory = tempDir.resolve("default").toFile()
        val configuredDirectory = tempDir.resolve("removable").resolve("missing").toFile()

        val state = policy.resolveStartup(defaultDirectory, configuredDirectory.path)

        assertEquals(defaultDirectory.toPath().toAbsolutePath().normalize().toFile(), state.defaultDirectory)
        assertEquals(configuredDirectory.toPath().toAbsolutePath().normalize().toFile(), state.configuredDirectory)
        assertEquals(configuredDirectory.toPath().toAbsolutePath().normalize().toFile(), state.activeDirectory)
        assertEquals(configuredDirectory.toPath().toAbsolutePath().normalize().toFile(), state.pendingDirectory)
        assertEquals(DesktopDownloadDirectoryAvailability.UNKNOWN, state.availability)
        assertFalse(state.restartRequired)
        assertEquals(0, probe.inspectedDirectories.size)
    }

    @Test
    fun `startup resolution falls back to default only when configured syntax is unusable`() {
        val probe = RecordingProbe(DesktopDownloadDirectoryProbeResult.available())
        val policy = DesktopDownloadDirectoryPolicy(probe)
        val defaultDirectory = tempDir.resolve("default").toFile()

        val state = policy.resolveStartup(defaultDirectory, "bad\u0000path")

        assertEquals(defaultDirectory.toPath().toAbsolutePath().normalize().toFile(), state.activeDirectory)
        assertEquals(defaultDirectory.toPath().toAbsolutePath().normalize().toFile(), state.pendingDirectory)
        assertEquals(null, state.configuredDirectory)
        assertEquals(DesktopDownloadDirectoryAvailability.INVALID_SYNTAX, state.availability)
        assertEquals(0, probe.inspectedDirectories.size)
    }

    @Test
    fun `selection rejects relative and invalid paths before touching storage`() {
        val probe = RecordingProbe(DesktopDownloadDirectoryProbeResult.available())
        val policy = DesktopDownloadDirectoryPolicy(probe)

        val relative = assertInstanceOf(
            DesktopDownloadDirectorySelection.Invalid::class.java,
            policy.validateSelection("relative/downloads"),
        )
        val invalid = assertInstanceOf(
            DesktopDownloadDirectorySelection.Invalid::class.java,
            policy.validateSelection("bad\u0000path"),
        )

        assertEquals(DesktopDownloadDirectoryAvailability.INVALID_SYNTAX, relative.availability)
        assertEquals(DesktopDownloadDirectoryAvailability.INVALID_SYNTAX, invalid.availability)
        assertEquals(0, probe.inspectedDirectories.size)
    }

    @Test
    fun `selection exposes every bounded storage probe failure without changing its meaning`() {
        val directory = tempDir.resolve("candidate")
        listOf(
            DesktopDownloadDirectoryAvailability.MISSING,
            DesktopDownloadDirectoryAvailability.NOT_DIRECTORY,
            DesktopDownloadDirectoryAvailability.NOT_WRITABLE,
        ).forEach { availability ->
            val cause = IllegalStateException(availability.name)
            val policy = DesktopDownloadDirectoryPolicy(
                RecordingProbe(DesktopDownloadDirectoryProbeResult(availability, cause)),
            )

            val result = assertInstanceOf(
                DesktopDownloadDirectorySelection.Invalid::class.java,
                policy.validateSelection(directory.toString()),
            )

            assertEquals(availability, result.availability)
            assertEquals(cause, result.cause)
        }
    }

    @Test
    fun `selection accepts an existing nonempty directory without altering its contents`() {
        val directory = tempDir.resolve("existing")
        Files.createDirectories(directory)
        val existingFile = Files.writeString(directory.resolve("keep.txt"), "keep")

        val result = assertInstanceOf(
            DesktopDownloadDirectorySelection.ValidCustom::class.java,
            DesktopDownloadDirectoryPolicy().validateSelection(directory.toString()),
        )

        assertEquals(directory.toAbsolutePath().normalize().toFile(), result.directory)
        assertTrue(Files.exists(existingFile))
        assertEquals("keep", Files.readString(existingFile))
    }

    @Test
    fun `selection reports a regular file as not directory`() {
        val file = Files.writeString(tempDir.resolve("downloads.txt"), "not a directory")

        val result = assertInstanceOf(
            DesktopDownloadDirectorySelection.Invalid::class.java,
            DesktopDownloadDirectoryPolicy().validateSelection(file.toString()),
        )

        assertEquals(DesktopDownloadDirectoryAvailability.NOT_DIRECTORY, result.availability)
    }

    @Test
    fun `selection creates and keeps a missing user directory while removing probe files`() {
        val directory = tempDir.resolve("new").resolve("downloads")
        assertFalse(Files.exists(directory))

        val result = assertInstanceOf(
            DesktopDownloadDirectorySelection.ValidCustom::class.java,
            DesktopDownloadDirectoryPolicy().validateSelection(directory.toString()),
        )

        assertEquals(directory.toAbsolutePath().normalize().toFile(), result.directory)
        assertTrue(Files.isDirectory(directory), "A successfully selected user directory must remain available")
        assertTrue(Files.list(directory).use { entries -> entries.noneMatch { it.fileName.toString().startsWith(".mihon-") } })
    }

    @Test
    fun `selection never cleans a directory created by another actor after the missing snapshot`() {
        val directory = tempDir.resolve("concurrent-foreign")
        val beforeCreate = CountDownLatch(1)
        val allowCreate = CountDownLatch(1)
        val operations = object : DesktopDownloadDirectoryFileOperations by NioDesktopDownloadDirectoryFileOperations {
            override fun createDirectory(path: Path) {
                if (path == directory) {
                    beforeCreate.countDown()
                    assertTrue(allowCreate.await(5, TimeUnit.SECONDS))
                }
                NioDesktopDownloadDirectoryFileOperations.createDirectory(path)
            }
        }
        val policy = DesktopDownloadDirectoryPolicy(NioDesktopDownloadDirectoryProbe(operations))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<DesktopDownloadDirectorySelection> {
                policy.validateSelection(directory.toString())
            }
            assertTrue(beforeCreate.await(5, TimeUnit.SECONDS))
            Files.createDirectory(directory)
            allowCreate.countDown()

            assertInstanceOf(DesktopDownloadDirectorySelection.ValidCustom::class.java, result.get(5, TimeUnit.SECONDS))
            assertTrue(Files.isDirectory(directory), "The validator must not delete a directory created by another actor")
        } finally {
            allowCreate.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `two concurrent selections keep the shared user directory`() {
        val directory = tempDir.resolve("concurrent-validations")
        val firstCreateBlocked = CountDownLatch(1)
        val allowFirstCreate = CountDownLatch(1)
        val targetCreateCalls = AtomicInteger(0)
        val operations = object : DesktopDownloadDirectoryFileOperations by NioDesktopDownloadDirectoryFileOperations {
            override fun createDirectory(path: Path) {
                if (path == directory && targetCreateCalls.incrementAndGet() == 1) {
                    firstCreateBlocked.countDown()
                    assertTrue(allowFirstCreate.await(5, TimeUnit.SECONDS))
                }
                NioDesktopDownloadDirectoryFileOperations.createDirectory(path)
            }
        }
        val policy = DesktopDownloadDirectoryPolicy(NioDesktopDownloadDirectoryProbe(operations))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<DesktopDownloadDirectorySelection> {
                policy.validateSelection(directory.toString())
            }
            assertTrue(firstCreateBlocked.await(5, TimeUnit.SECONDS))
            val owner = executor.submit<DesktopDownloadDirectorySelection> {
                policy.validateSelection(directory.toString())
            }
            assertInstanceOf(DesktopDownloadDirectorySelection.ValidCustom::class.java, owner.get(5, TimeUnit.SECONDS))
            assertTrue(Files.isDirectory(directory), "One successful selection must not clean the shared user directory")

            allowFirstCreate.countDown()
            assertInstanceOf(DesktopDownloadDirectorySelection.ValidCustom::class.java, first.get(5, TimeUnit.SECONDS))
            assertTrue(Files.isDirectory(directory))
        } finally {
            allowFirstCreate.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `generic create IO failure is not misreported as missing`() {
        val directory = tempDir.resolve("disk-full")
        val failure = IOException("disk full")
        val operations = object : DesktopDownloadDirectoryFileOperations by NioDesktopDownloadDirectoryFileOperations {
            override fun createDirectory(path: Path) {
                throw failure
            }
        }

        val result = assertInstanceOf(
            DesktopDownloadDirectorySelection.Invalid::class.java,
            DesktopDownloadDirectoryPolicy(NioDesktopDownloadDirectoryProbe(operations))
                .validateSelection(directory.toString()),
        )

        assertEquals(DesktopDownloadDirectoryAvailability.NOT_WRITABLE, result.availability)
        assertEquals(failure, result.cause)
    }

    @Test
    fun `probe file cleanup failure is typed and the bounded fallback still removes the probe file`() {
        val directory = Files.createDirectory(tempDir.resolve("probe-cleanup"))
        val failure = IOException("probe delete denied")
        var probeFile: Path? = null
        val operations = object : DesktopDownloadDirectoryFileOperations by NioDesktopDownloadDirectoryFileOperations {
            override fun createProbeFile(directory: Path): Path {
                return NioDesktopDownloadDirectoryFileOperations.createProbeFile(directory).also { probeFile = it }
            }

            override fun delete(path: Path) {
                if (path == probeFile) throw failure
                NioDesktopDownloadDirectoryFileOperations.delete(path)
            }
        }

        val result = assertInstanceOf(
            DesktopDownloadDirectorySelection.Invalid::class.java,
            DesktopDownloadDirectoryPolicy(NioDesktopDownloadDirectoryProbe(operations))
                .validateSelection(directory.toString()),
        )

        assertEquals(DesktopDownloadDirectoryAvailability.NOT_WRITABLE, result.availability)
        assertEquals(failure, result.cause)
        assertFalse(Files.exists(probeFile))
    }

    @Test
    fun `successful selection never schedules the created user directory for cleanup`() {
        val directory = tempDir.resolve("retained-directory")
        var directoryDeleteAttempted = false
        val operations = object : DesktopDownloadDirectoryFileOperations by NioDesktopDownloadDirectoryFileOperations {
            override fun delete(path: Path) {
                if (path == directory) {
                    directoryDeleteAttempted = true
                    throw IOException("user directory must not be deleted")
                }
                NioDesktopDownloadDirectoryFileOperations.delete(path)
            }
        }

        assertInstanceOf(
            DesktopDownloadDirectorySelection.ValidCustom::class.java,
            DesktopDownloadDirectoryPolicy(NioDesktopDownloadDirectoryProbe(operations))
                .validateSelection(directory.toString()),
        )

        assertFalse(directoryDeleteAttempted)
        assertTrue(Files.isDirectory(directory))
    }

    private class RecordingProbe(
        private val result: DesktopDownloadDirectoryProbeResult,
    ) : DesktopDownloadDirectoryProbe {
        val inspectedDirectories = mutableListOf<Path>()

        override fun inspect(directory: Path): DesktopDownloadDirectoryProbeResult {
            inspectedDirectories.add(directory)
            return result
        }
    }
}
