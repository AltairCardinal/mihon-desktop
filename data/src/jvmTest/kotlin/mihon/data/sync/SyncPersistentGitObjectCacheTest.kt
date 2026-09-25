package mihon.data.sync

import kotlinx.coroutines.runBlocking
import mihon.data.sync.transport.SyncGitObjectKind
import mihon.data.sync.transport.SyncPersistentGitObjectCache
import mihon.data.sync.transport.SyncPersistentGitObjectKey
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files

class SyncPersistentGitObjectCacheTest {
    @Test
    fun `object cache key isolates every repository and validation binding`() = runBlocking {
        withCache { cache ->
            val content = "scoped blob".encodeToByteArray()
            val key = key(
                kind = SyncGitObjectKind.BLOB,
                objectFormat = "git-sha-40",
                objectOid = blobOid(content, 40),
            )
            cache.write(key, content)

            assertArrayEquals(content, cache.read(key))
            listOf(
                key.copy(apiOrigin = "https://api.other.test"),
                key.copy(repositoryId = "repository-3"),
                key.copy(repository = "another/private"),
                key.copy(branch = "release"),
                key.copy(validationScope = "space=space-b;generation=1;validator=sync-v1"),
                key.copy(validationScope = "space=space-a;generation=2;validator=sync-v1"),
                key.copy(connectionRevision = "account-3-repository-4"),
            ).forEach { otherKey ->
                assertNull(cache.read(otherKey), "cache key changed context but reused object bytes: $otherKey")
            }
        }
    }

    @Test
    fun `blob cache record must match the requested git object id`() = runBlocking {
        withCache { cache ->
            val expected = "expected blob".encodeToByteArray()
            val key = key(
                kind = SyncGitObjectKind.BLOB,
                objectFormat = "git-sha-40",
                objectOid = blobOid(expected, 40),
            )

            cache.write(key, "tampered blob".encodeToByteArray())

            assertNull(cache.read(key), "a checksummed cache file must not bypass Git blob OID validation")
        }
    }

    @Test
    fun `tree cache record must match the requested oid in its json`() = runBlocking {
        withCache { cache ->
            val key = key(
                kind = SyncGitObjectKind.TREE,
                objectFormat = "git-sha-40",
                objectOid = "a".repeat(40),
            )

            cache.write(key, """{"sha":"${"b".repeat(40)}","truncated":false,"tree":[]}""".encodeToByteArray())

            assertNull(cache.read(key), "a cache record with a different tree JSON OID must be a miss")
        }
    }

    @Test
    fun `silently short cache writes are rejected before publication`() = runBlocking {
        withCacheDirectory { directory ->
            val content = "validated bytes".encodeToByteArray()
            val key = key(SyncGitObjectKind.BLOB, "git-sha-40", blobOid(content, 40))
            val fileSystem = FaultInjectingCacheFileSystem(shortWrite = true)
            val cache = SyncPersistentGitObjectCache(directory, fileSystem)

            cache.write(key, content)

            assertNull(cache.read(key), "a truncated temporary record must be a cache miss")
            assertFalse(cacheFiles(directory).any { it.name.endsWith(".obj") })
            assertTrue(fileSystem.shortWrites > 0, "the production cache must write through the injected filesystem")

            val repaired = SyncPersistentGitObjectCache(directory)
            repaired.write(key, content)
            assertArrayEquals(content, repaired.read(key))
        }
    }

    @Test
    fun `disk full while writing cache content leaves no readable object and permits retry`() = runBlocking {
        withCacheDirectory { directory ->
            val content = "validated bytes".encodeToByteArray()
            val key = key(SyncGitObjectKind.BLOB, "git-sha-40", blobOid(content, 40))
            val fileSystem = FaultInjectingCacheFileSystem(diskFull = true)
            val cache = SyncPersistentGitObjectCache(directory, fileSystem)

            cache.write(key, content)

            assertNull(cache.read(key), "a partial record after disk-full must not be admitted")
            assertFalse(cacheFiles(directory).any { it.name.endsWith(".obj") })
            assertTrue(fileSystem.diskFullWrites > 0, "the injected write failure must be exercised")

            val repaired = SyncPersistentGitObjectCache(directory)
            repaired.write(key, content)
            assertArrayEquals(content, repaired.read(key))
        }
    }

    @Test
    fun `atomic move failure leaves a cache miss without damaging other objects`() = runBlocking {
        withCacheDirectory { directory ->
            val retainedContent = "retained object".encodeToByteArray()
            val rejectedContent = "unpublished object".encodeToByteArray()
            val retainedKey = key(SyncGitObjectKind.BLOB, "git-sha-40", blobOid(retainedContent, 40))
            val rejectedKey = key(SyncGitObjectKind.BLOB, "git-sha-40", blobOid(rejectedContent, 40))
            val healthy = SyncPersistentGitObjectCache(directory)
            healthy.write(retainedKey, retainedContent)

            val fileSystem = FaultInjectingCacheFileSystem(failObjectAtomicMove = true)
            val faulted = SyncPersistentGitObjectCache(directory, fileSystem)
            faulted.write(rejectedKey, rejectedContent)

            assertNull(faulted.read(rejectedKey), "an uncommitted temporary record must remain invisible")
            assertArrayEquals(retainedContent, faulted.read(retainedKey))
            assertTrue(fileSystem.failedObjectMoves > 0, "the object atomic-move failure must be exercised")
        }
    }

    private suspend fun withCache(block: suspend (SyncPersistentGitObjectCache) -> Unit) {
        withCacheDirectory { directory -> block(SyncPersistentGitObjectCache(directory)) }
    }

    private suspend fun withCacheDirectory(block: suspend (Path) -> Unit) {
        val directory = Files.createTempDirectory("mihon-persistent-git-cache-test-").toString().toPath()
        try {
            block(directory)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    private fun cacheFiles(directory: Path) =
        FileSystem.SYSTEM.listOrNull(directory.resolve("github-git-objects-v1")).orEmpty()

    private class FaultInjectingCacheFileSystem(
        private val shortWrite: Boolean = false,
        private val diskFull: Boolean = false,
        private val failObjectAtomicMove: Boolean = false,
    ) : ForwardingFileSystem(FileSystem.SYSTEM) {
        var shortWrites: Int = 0
            private set
        var diskFullWrites: Int = 0
            private set
        var failedObjectMoves: Int = 0
            private set

        override fun sink(file: Path, mustCreate: Boolean): Sink {
            val delegate = super.sink(file, mustCreate)
            if (!file.name.endsWith(".tmp") || (!shortWrite && !diskFull)) return delegate
            return object : ForwardingSink(delegate) {
                private var injected = false

                override fun write(source: Buffer, byteCount: Long) {
                    if (injected) {
                        if (diskFull) {
                            source.skip(byteCount)
                            throw IOException("injected disk full")
                        }
                        super.write(source, byteCount)
                        return
                    }
                    injected = true
                    val persistedBytes = byteCount / 2
                    super.write(source, persistedBytes)
                    if (shortWrite) {
                        source.skip(byteCount - persistedBytes)
                        shortWrites++
                    } else {
                        source.skip(byteCount - persistedBytes)
                        diskFullWrites++
                        throw IOException("injected disk full")
                    }
                }
            }
        }

        override fun atomicMove(source: Path, target: Path) {
            if (failObjectAtomicMove && source.name.endsWith(".tmp") && target.name.endsWith(".obj")) {
                failedObjectMoves++
                throw IOException("injected atomic move failure")
            }
            super.atomicMove(source, target)
        }
    }

    private fun key(kind: SyncGitObjectKind, objectFormat: String, objectOid: String) =
        SyncPersistentGitObjectKey(
            apiOrigin = "https://api.example.test",
            repositoryId = "2",
            repository = "owner/private",
            branch = "main",
            kind = kind,
            objectFormat = objectFormat,
            objectOid = objectOid,
            validationScope = "space=space-a;generation=1;validator=sync-v1",
            connectionRevision = "account-1-repository-2",
        )

    private fun blobOid(content: ByteArray, oidLength: Int): String {
        val objectBytes = ("blob ${content.size}\u0000".encodeToByteArray() + content).toByteString()
        return when (oidLength) {
            40 -> objectBytes.sha1().hex()
            64 -> objectBytes.sha256().hex()
            else -> error("unsupported oid length")
        }
    }
}
