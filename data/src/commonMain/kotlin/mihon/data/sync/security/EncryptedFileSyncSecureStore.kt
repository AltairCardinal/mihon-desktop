package mihon.data.sync.security

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mihon.domain.sync.security.SyncSecureStore
import mihon.domain.sync.security.SyncSecureStoreException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

interface SyncRecordCipher {
    fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray, aad: ByteArray): ByteArray
}

fun interface SyncSecureFileCommit {
    fun commit(staged: Path, target: Path)
}

class EncryptedFileSyncSecureStore(
    private val directory: File,
    private val cipher: SyncRecordCipher,
    private val commit: SyncSecureFileCommit? = null,
) : SyncSecureStore {
    override suspend fun read(key: String): String? = guarded(key) { root, target, aad ->
        readRecord(target, aad)
    }

    override suspend fun compareAndSet(key: String, expected: String?, value: String?): Boolean =
        guarded(key) { root, target, aad ->
            expected?.let { encode(it).fill(0) }
            val plaintext = value?.let(::encode)
            try {
                if (readRecord(target, aad) != expected) return@guarded false
                if (value == null) {
                    Files.deleteIfExists(target)
                } else {
                    val encrypted = cipher.encrypt(plaintext!!, aad)
                    check(encrypted.size in 28..MAX_VALUE_BYTES + 28)
                    val staged = Files.createTempFile(root, ".pending-", ".tmp")
                    try {
                        RandomAccessFile(staged.toFile(), "rw").use {
                            it.write(1)
                            it.write(encrypted)
                            it.fd.sync()
                        }
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        if (commit != null) {
                            commit.commit(staged, target)
                        } else {
                            Files.move(
                                staged,
                                target,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        }
                    } finally {
                        Files.deleteIfExists(staged)
                    }
                }
                true
            } finally {
                plaintext?.fill(0)
            }
        }

    private suspend fun <T> guarded(key: String, operation: (Path, Path, ByteArray) -> T): T = try {
        check(key.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}")))
        withContext(Dispatchers.IO) {
            val root = directory.canonicalFile.toPath()
            locks.computeIfAbsent(root.toString()) { Mutex() }.withLock {
                Files.createDirectories(root)
                RandomAccessFile(root.resolve(".lock").toFile(), "rw").use { file ->
                    val channel = file.channel
                    var lock = tryLock(channel)
                    while (lock == null) {
                        delay(10)
                        lock = tryLock(channel)
                    }
                    lock.use {
                        currentCoroutineContext().ensureActive()
                        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
                            .joinToString("") { "%02x".format(it) }
                        val aad = "mihon-sync-secure-v1\u0000$root\u0000$key".toByteArray(Charsets.UTF_8)
                        runInterruptible { operation(root, root.resolve("$name.record"), aad) }
                    }
                }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw SyncSecureStoreException()
    }

    private fun readRecord(target: Path, aad: ByteArray): String? {
        val attributes = try {
            Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return null
        }
        check(attributes.isRegularFile && attributes.size() in 29L..MAX_VALUE_BYTES + 29L)
        val bytes = Files.readAllBytes(target)
        check(bytes.size in 29..MAX_VALUE_BYTES + 29 && bytes[0] == 1.toByte())
        val plaintext = cipher.decrypt(bytes.copyOfRange(1, bytes.size), aad)
        return try {
            check(plaintext.size <= MAX_VALUE_BYTES)
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plaintext)).toString()
        } finally {
            plaintext.fill(0)
        }
    }

    private fun encode(value: String): ByteArray {
        check(value.length <= MAX_VALUE_BYTES)
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
        check(bytes.remaining() <= MAX_VALUE_BYTES)
        return ByteArray(bytes.remaining()).also(bytes::get)
    }

    private fun tryLock(channel: java.nio.channels.FileChannel): java.nio.channels.FileLock? = try {
        channel.tryLock()
    } catch (_: OverlappingFileLockException) {
        null
    }

    override fun toString(): String = "EncryptedFileSyncSecureStore(<redacted>)"

    companion object {
        private const val MAX_VALUE_BYTES = 64 * 1024
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}
