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
    fun `1000 and 9000 objects stay within cold warm append and recreated cache budgets`() = runBlocking {
        assertTrue(
            2 * 9_000 <= MAX_TRACKED_FILES,
            "the seed ledger and rebuilt ledger must fit the shared record budget before appends",
        )
        val small = measureScaleMatrix(1_000)
        val large = measureScaleMatrix(9_000)

        assertEquals(0L, small.warm.listCalls)
        assertEquals(0L, large.warm.listCalls)
        assertEquals(1L, small.coldCreate.listCalls)
        assertEquals(1L, large.coldCreate.listCalls)
        assertEquals(2_000L, small.warm.metadataCalls)
        assertEquals(18_000L, large.warm.metadataCalls)
        assertEquals(1_000L, small.coldCreate.markerWrites)
        assertEquals(9_000L, large.coldCreate.markerWrites)
        assertEquals(1L, small.cold.markerWrites)
        assertEquals(1L, large.cold.markerWrites)
        assertEquals(1_000L, small.warm.markerWrites)
        assertEquals(9_000L, large.warm.markerWrites)
        assertEquals(1L, small.recreated.markerWrites)
        assertEquals(1L, large.recreated.markerWrites)
        assertTrue(small.append.metadataCalls <= 3_000L, "N=1000 append budget exceeded: ${small.append}")
        assertTrue(large.append.metadataCalls <= 27_000L, "N=9000 append budget exceeded: ${large.append}")
        assertEquals(1_000L, small.append.markerWrites)
        assertEquals(9_000L, large.append.markerWrites)
        assertTrue(
            large.append.metadataCalls <= small.append.metadataCalls * 9 + LINEAR_SCALING_ALLOWANCE,
            "N=9000 append work exceeded nine times N=1000 plus fixed allowance: ${small.append} -> ${large.append}",
        )
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

    private suspend fun measureScaleMatrix(count: Int): ScaleMeasurement = withTemporaryDirectory { directory ->
        val objectDirectory = directory.resolve(CACHE_DIRECTORY)
        val fileSystem = CountingFileSystem(objectDirectory)
        val cache = SyncPersistentGitObjectCache(directory, fileSystem, maxBytes = SCALE_TEST_CAPACITY_BYTES)
        val initialItems = (0 until count).map { item("scale-seed-$count-$it") }
        initialItems.forEach { cache.write(it.key, it.content) }
        val coldCreate = fileSystem.snapshot()
        assertEquals(
            1L,
            coldCreate.listCalls,
            "initial cold create should inventory the empty directory once: $coldCreate",
        )
        assertEquals(0L, coldCreate.listedEntries)
        assertEquals(2L * count, coldCreate.metadataCalls)
        assertEquals(count.toLong(), coldCreate.markerWrites)
        assertObjectPopulation(objectDirectory, count)
        println(coldCreate.toJson("cold-create", count, returnedObjects = 0))

        // A new FileSystem identity forces a cold in-memory ledger rebuild over the durable files.
        // This models rebuilding process-local state; it is not an operating-system process restart.
        val coldFileSystem = CountingFileSystem(objectDirectory)
        val rebuiltCache = SyncPersistentGitObjectCache(
            directory,
            coldFileSystem,
            maxBytes = SCALE_TEST_CAPACITY_BYTES,
        )
        val first = initialItems.first()
        assertArrayEquals(first.content, requireNotNull(rebuiltCache.read(first.key)))
        val cold = coldFileSystem.snapshot()
        assertEquals(1L, cold.listCalls, "cold inventory should list the directory once: $cold")
        assertEquals(2L * count, cold.listedEntries, "one object and marker per item should be enumerated: $cold")
        assertEquals(
            2L * count + 2,
            cold.metadataCalls,
            "cold inventory and first hit should inspect 2N+2 files: $cold",
        )
        assertTrue(cold.metadataBytes <= SCALE_TEST_CAPACITY_BYTES, "cold inventory bytes exceeded the capacity: $cold")
        println(cold.toJson("cold-ledger-rebuild", count, returnedObjects = 1))

        coldFileSystem.resetCounts()
        var warmReturned = 0
        initialItems.forEach { item ->
            assertArrayEquals(item.content, requireNotNull(rebuiltCache.read(item.key)))
            warmReturned++
        }
        val warm = coldFileSystem.snapshot()
        assertEquals(count, warmReturned)
        assertEquals(0L, warm.listCalls, "warmed reads must not enumerate the directory: $warm")
        assertEquals(0L, warm.listedEntries)
        assertEquals(2L * count, warm.metadataCalls)
        println(warm.toJson("warm-read", count, returnedObjects = warmReturned))

        val recreatedInstance = SyncPersistentGitObjectCache(
            directory,
            coldFileSystem,
            maxBytes = SCALE_TEST_CAPACITY_BYTES,
        )
        coldFileSystem.resetCounts()
        assertArrayEquals(first.content, requireNotNull(recreatedInstance.read(first.key)))
        val recreated = coldFileSystem.snapshot()
        assertEquals(0L, recreated.listCalls, "a cache instance should reuse same-process ledger state: $recreated")
        assertEquals(0L, recreated.listedEntries)
        assertEquals(2L, recreated.metadataCalls)
        println(recreated.toJson("cache-instance-recreation", count, returnedObjects = 1))

        val additions = (0 until count).map { item("scale-append-$count-$it") }
        coldFileSystem.resetCounts()
        additions.forEach { rebuiltCache.write(it.key, it.content) }
        val append = coldFileSystem.snapshot()
        assertEquals(0L, append.listCalls, "continuous append must not enumerate the directory: $append")
        assertEquals(0L, append.listedEntries)

        var appendReturned = 0
        additions.forEach { item ->
            assertArrayEquals(item.content, requireNotNull(rebuiltCache.read(item.key)))
            appendReturned++
        }
        assertEquals(count, appendReturned)
        assertObjectPopulation(objectDirectory, count * 2)
        assertCapacityBound(count * 2)
        println(append.toJson("append", count, returnedObjects = appendReturned))

        ScaleMeasurement(coldCreate, cold, warm, recreated, append)
    }

    private fun assertObjectPopulation(objectDirectory: Path, objectCount: Int) {
        val files = FileSystem.SYSTEM.list(objectDirectory)
        assertEquals(objectCount, files.count { it.name.endsWith(OBJECT_SUFFIX) })
        assertEquals(objectCount, files.count { it.name.endsWith(ACCESS_SUFFIX) })
        assertTrue(files.none { it.name.endsWith(TEMP_SUFFIX) }, "temporary cache files should be cleaned up")
    }

    private fun assertCapacityBound(objectCount: Int) {
        // Fixture values are short; a 512-byte object-record bound plus each 8-byte marker is conservative.
        val worstCaseBytes = objectCount.toLong() * (MAX_TEST_OBJECT_BYTES + ACCESS_MARKER_BYTES)
        assertTrue(worstCaseBytes < SCALE_TEST_CAPACITY_BYTES, "test dataset could trigger capacity eviction")
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
        val metadataBytes: Long,
        val markerWrites: Long,
    ) {
        fun toJson(scenario: String, count: Int, returnedObjects: Int? = null) =
            "{\"scenario\":\"$scenario\"," +
                "\"objects\":$count,\"listCalls\":$listCalls," +
                "\"listedEntries\":$listedEntries,\"metadataCalls\":$metadataCalls," +
                "\"metadataBytes\":$metadataBytes,\"markerWrites\":$markerWrites" +
                (returnedObjects?.let { ",\"returnedObjects\":$it" } ?: "") + "}"
    }

    private data class ScaleMeasurement(
        val coldCreate: FileSystemCounts,
        val cold: FileSystemCounts,
        val warm: FileSystemCounts,
        val recreated: FileSystemCounts,
        val append: FileSystemCounts,
    )

    private class CountingFileSystem(
        private val objectDirectory: Path,
    ) : ForwardingFileSystem(FileSystem.SYSTEM) {
        private var listCalls = 0L
        private var listedEntries = 0L
        private var metadataCalls = 0L
        private var metadataBytes = 0L
        private var markerWrites = 0L

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
            if (path.parent == objectDirectory) metadataBytes += metadata?.size ?: 0L
            return metadata
        }

        override fun atomicMove(source: Path, target: Path) {
            if (target.parent == objectDirectory && target.name.endsWith(ACCESS_SUFFIX)) markerWrites++
            super.atomicMove(source, target)
        }

        fun resetCounts() {
            listCalls = 0
            listedEntries = 0
            metadataCalls = 0
            metadataBytes = 0
            markerWrites = 0
        }

        fun snapshot() = FileSystemCounts(listCalls, listedEntries, metadataCalls, metadataBytes, markerWrites)
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
        const val ACCESS_SUFFIX = ".access"
        const val TEMP_SUFFIX = ".tmp"
        const val LRU_CAPACITY_BYTES = 540L
        const val TEST_CAPACITY_BYTES = 32L * 1024 * 1024
        const val SCALE_TEST_CAPACITY_BYTES = 64L * 1024 * 1024
        const val MAX_TEST_OBJECT_BYTES = 512L
        const val ACCESS_MARKER_BYTES = 8L
        const val MAX_TRACKED_FILES = 20_000
        const val MAX_METADATA_CALLS_PER_OBJECT = 3L
        const val LINEAR_SCALING_ALLOWANCE = 8L
    }
}
