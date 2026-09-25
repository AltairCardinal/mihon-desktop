package mihon.data.sync

import kotlinx.coroutines.runBlocking
import mihon.data.sync.transport.SyncGitObjectKind
import mihon.data.sync.transport.SyncPersistentGitObjectCache
import mihon.data.sync.transport.SyncPersistentGitObjectKey
import okio.ByteString.Companion.toByteString
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class SyncPersistentGitObjectCacheComplexityTest {
    @Test
    fun `warm reads keep filesystem work linear at 100 and 200 objects`() = runBlocking {
        val small = measureWarmReads(100)
        val large = measureWarmReads(200)

        assertBoundedWarmWork("warm-read", small, large)
    }

    @Test
    fun `continuous appends keep filesystem work linear at 100 and 200 objects`() = runBlocking {
        val small = measureAppends(100)
        val large = measureAppends(200)

        assertBoundedWarmWork("append", small, large)
    }

    @Test
    fun `capacity and lru state is shared by cache instances for one directory`() = runBlocking {
        withTemporaryDirectory { directory ->
            val firstCache = SyncPersistentGitObjectCache(directory, maxBytes = LRU_CAPACITY_BYTES)
            val secondCache = SyncPersistentGitObjectCache(directory, maxBytes = LRU_CAPACITY_BYTES)
            val first = item("shared-first")
            val second = item("shared-second")
            val third = item("shared-third")

            firstCache.write(first.key, first.content)
            secondCache.write(second.key, second.content)
            assertArrayEquals(first.content, requireNotNull(secondCache.read(first.key)))
            firstCache.write(third.key, third.content)

            assertArrayEquals(first.content, requireNotNull(secondCache.read(first.key)))
            assertNull(secondCache.read(second.key), "the least recently used object should be evicted")
            assertArrayEquals(third.content, requireNotNull(firstCache.read(third.key)))
        }
    }

    @Test
    fun `same logical directory path stays isolated across filesystem instances`() = runBlocking {
        val logicalDirectory = Files.createTempDirectory("mihon-cache-logical-").toString().toPath()
        val firstBacking = Files.createTempDirectory("mihon-cache-fs-a-").toString().toPath()
        val secondBacking = Files.createTempDirectory("mihon-cache-fs-b-").toString().toPath()
        try {
            val firstFileSystem = PathMappedFileSystem(logicalDirectory, firstBacking)
            val secondFileSystem = PathMappedFileSystem(logicalDirectory, secondBacking)
            val firstCache = SyncPersistentGitObjectCache(
                logicalDirectory,
                firstFileSystem,
                maxBytes = LRU_CAPACITY_BYTES,
            )
            val secondCache = SyncPersistentGitObjectCache(
                logicalDirectory,
                secondFileSystem,
                maxBytes = LRU_CAPACITY_BYTES,
            )
            val first = item("filesystem-a-first")
            val second = item("filesystem-b-first")
            val third = item("filesystem-b-second")

            firstCache.write(first.key, first.content)
            secondCache.write(second.key, second.content)
            secondCache.write(third.key, third.content)

            assertArrayEquals(first.content, requireNotNull(firstCache.read(first.key)))
            assertArrayEquals(second.content, requireNotNull(secondCache.read(second.key)))
            assertArrayEquals(third.content, requireNotNull(secondCache.read(third.key)))
            assertNull(firstCache.read(second.key), "the other filesystem's files are not in this backing store")
        } finally {
            FileSystem.SYSTEM.deleteRecursively(logicalDirectory)
            FileSystem.SYSTEM.deleteRecursively(firstBacking)
            FileSystem.SYSTEM.deleteRecursively(secondBacking)
        }
    }

    @Test
    fun `oversized inventory degrades without rescanning on every operation`() = runBlocking {
        withTemporaryDirectory { directory ->
            val objectDirectory = directory.resolve(CACHE_DIRECTORY)
            val fileSystem = OversizedListingFileSystem(objectDirectory, MAX_TRACKED_FILES + 1)
            val cache = SyncPersistentGitObjectCache(directory, fileSystem, maxBytes = TEST_CAPACITY_BYTES)
            val missing = item("not-in-oversized-listing")

            assertNull(cache.read(missing.key))
            assertNull(cache.read(missing.key))
            cache.write(missing.key, missing.content)

            assertEquals(
                1L,
                fileSystem.rootListCalls,
                "the bounded registry should retain one untrackable sentinel " +
                    "instead of rescanning the oversized directory",
            )
            println(
                "{\"scenario\":\"oversized-sentinel\"," +
                    "\"objects\":${MAX_TRACKED_FILES + 1},\"listCalls\":${fileSystem.rootListCalls}}",
            )
        }
    }

    private suspend fun measureWarmReads(count: Int): FileSystemCounts = withTemporaryDirectory { directory ->
        val fileSystem = CountingFileSystem(directory.resolve(CACHE_DIRECTORY))
        val cache = SyncPersistentGitObjectCache(directory, fileSystem, maxBytes = TEST_CAPACITY_BYTES)
        val items = (0 until count).map { item("warm-$count-$it") }
        items.forEach { cache.write(it.key, it.content) }

        fileSystem.resetCounts()
        items.forEach { assertArrayEquals(it.content, requireNotNull(cache.read(it.key))) }

        fileSystem.snapshot().also { println(it.toJson("warm-read", count)) }
    }

    private suspend fun measureAppends(count: Int): FileSystemCounts = withTemporaryDirectory { directory ->
        val fileSystem = CountingFileSystem(directory.resolve(CACHE_DIRECTORY))
        val cache = SyncPersistentGitObjectCache(directory, fileSystem, maxBytes = TEST_CAPACITY_BYTES)
        (0 until count).map { item("seed-$count-$it") }.forEach { cache.write(it.key, it.content) }
        val additions = (0 until count).map { item("append-$count-$it") }

        fileSystem.resetCounts()
        additions.forEach { cache.write(it.key, it.content) }

        val counts = fileSystem.snapshot().also { println(it.toJson("append", count)) }
        additions.forEach { assertArrayEquals(it.content, requireNotNull(cache.read(it.key))) }
        counts
    }

    private fun assertBoundedWarmWork(
        scenario: String,
        small: FileSystemCounts,
        large: FileSystemCounts,
    ) {
        assertEquals(0L, small.listCalls, "$scenario N=100 should not enumerate the warmed cache directory: $small")
        assertEquals(0L, large.listCalls, "$scenario N=200 should not enumerate the warmed cache directory: $large")
        assertEquals(0L, small.listedEntries, "$scenario N=100 should enumerate no directory entries: $small")
        assertEquals(0L, large.listedEntries, "$scenario N=200 should enumerate no directory entries: $large")
        if (scenario == "append") {
            assertTrue(small.metadataCalls <= MAX_METADATA_CALLS_PER_OBJECT * 100, "N=100 exceeded its budget: $small")
            assertTrue(large.metadataCalls <= MAX_METADATA_CALLS_PER_OBJECT * 200, "N=200 exceeded its budget: $large")
            assertTrue(
                large.metadataCalls <= small.metadataCalls * 2 + LINEAR_SCALING_ALLOWANCE,
                "N=200 metadata work exceeded twice the N=100 work plus fixed allowance: $small -> $large",
            )
        } else {
            assertEquals(2L * 100, small.metadataCalls, "$scenario N=100 should inspect object and marker per item")
            assertEquals(2L * 200, large.metadataCalls, "$scenario N=200 should inspect object and marker per item")
        }
    }

    private suspend fun <T> withTemporaryDirectory(block: suspend (Path) -> T): T {
        val directory = Files.createTempDirectory("mihon-cache-complexity-").toString().toPath()
        try {
            return block(directory)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    private fun item(value: String): CacheItem {
        val content = value.encodeToByteArray()
        val objectBytes = ("blob ${content.size}\u0000".encodeToByteArray() + content).toByteString()
        return CacheItem(
            key = SyncPersistentGitObjectKey(
                apiOrigin = "https://api.example.test",
                repositoryId = "77",
                repository = "owner/private",
                branch = "main",
                kind = SyncGitObjectKind.BLOB,
                objectFormat = "git-sha-40",
                objectOid = objectBytes.sha1().hex(),
                validationScope = "space=space-a;generation=1;validator=sync-v1",
                connectionRevision = "account-1-repository-77",
            ),
            content = content,
        )
    }

    private data class CacheItem(val key: SyncPersistentGitObjectKey, val content: ByteArray)

    private data class FileSystemCounts(
        val listCalls: Long,
        val listedEntries: Long,
        val metadataCalls: Long,
    ) {
        fun toJson(scenario: String, count: Int) =
            "{\"scenario\":\"$scenario\"," +
                "\"objects\":$count,\"listCalls\":$listCalls," +
                "\"listedEntries\":$listedEntries,\"metadataCalls\":$metadataCalls}"
    }

    private class CountingFileSystem(
        private val objectDirectory: Path,
    ) : ForwardingFileSystem(FileSystem.SYSTEM) {
        private var listCalls = 0L
        private var listedEntries = 0L
        private var metadataCalls = 0L

        override fun listOrNull(dir: Path): List<Path>? {
            val entries = super.listOrNull(dir)
            if (dir == objectDirectory) {
                listCalls++
                listedEntries += (entries?.size ?: 0).toLong()
            }
            return entries
        }

        override fun metadataOrNull(path: Path): FileMetadata? {
            val metadata = super.metadataOrNull(path)
            if (path == objectDirectory || path.parent == objectDirectory) metadataCalls++
            return metadata
        }

        fun resetCounts() {
            listCalls = 0
            listedEntries = 0
            metadataCalls = 0
        }

        fun snapshot() = FileSystemCounts(listCalls, listedEntries, metadataCalls)
    }

    private class OversizedListingFileSystem(
        private val objectDirectory: Path,
        private val objectCount: Int,
    ) : ForwardingFileSystem(FileSystem.SYSTEM) {
        var rootListCalls = 0L
            private set

        override fun listOrNull(dir: Path): List<Path>? {
            if (dir != objectDirectory) return super.listOrNull(dir)
            rootListCalls++
            return (0 until objectCount).map { index -> objectDirectory.resolve("synthetic-$index$OBJECT_SUFFIX") }
        }

        override fun metadataOrNull(path: Path): FileMetadata? {
            if (
                path.parent == objectDirectory && path.name.startsWith("synthetic-") &&
                path.name.endsWith(OBJECT_SUFFIX)
            ) {
                return FileMetadata(isRegularFile = true, size = 1L, lastModifiedAtMillis = 1L)
            }
            return super.metadataOrNull(path)
        }
    }

    /** Gives two FileSystem instances the same logical path while keeping their backing stores separate. */
    private class PathMappedFileSystem(
        private val logicalRoot: Path,
        private val backingRoot: Path,
    ) : ForwardingFileSystem(FileSystem.SYSTEM) {
        override fun canonicalize(path: Path): Path = path

        override fun metadataOrNull(path: Path): FileMetadata? = delegate.metadataOrNull(toBackingPath(path))

        override fun list(dir: Path): List<Path> = delegate.list(toBackingPath(dir)).map(::toLogicalPath)

        override fun listOrNull(dir: Path): List<Path>? = delegate.listOrNull(toBackingPath(dir))?.map(::toLogicalPath)

        override fun source(file: Path): Source = delegate.source(toBackingPath(file))

        override fun sink(file: Path, mustCreate: Boolean): Sink = delegate.sink(toBackingPath(file), mustCreate)

        override fun appendingSink(file: Path, mustExist: Boolean): Sink =
            delegate.appendingSink(toBackingPath(file), mustExist)

        override fun createDirectory(dir: Path, mustCreate: Boolean) {
            delegate.createDirectory(toBackingPath(dir), mustCreate)
        }

        override fun atomicMove(source: Path, target: Path) {
            delegate.atomicMove(toBackingPath(source), toBackingPath(target))
        }

        override fun delete(path: Path, mustExist: Boolean) {
            delegate.delete(toBackingPath(path), mustExist)
        }

        private fun toBackingPath(path: Path): Path {
            val suffix = path.relativeTo(logicalRoot)
            return suffix.segments.fold(backingRoot) { current, segment ->
                current.resolve(segment)
            }
        }

        private fun toLogicalPath(path: Path): Path =
            path.relativeTo(backingRoot).segments.fold(logicalRoot) { current, segment -> current.resolve(segment) }
    }

    private companion object {
        const val CACHE_DIRECTORY = "github-git-objects-v1"
        const val OBJECT_SUFFIX = ".obj"
        const val LRU_CAPACITY_BYTES = 540L
        const val TEST_CAPACITY_BYTES = 32L * 1024 * 1024
        const val MAX_TRACKED_FILES = 20_000
        const val MAX_METADATA_CALLS_PER_OBJECT = 3L
        const val LINEAR_SCALING_ALLOWANCE = 8L
    }
}
