package mihon.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mihon.data.sync.security.EncryptedFileSyncSecureStore
import mihon.data.sync.security.SyncRecordCipher
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files

@Timeout(30)
class SyncSecureProcessLockTest {
    @Test
    fun `external process lock blocks a write and waiting cancellation writes nothing`() = runBlocking {
        val directory = Files.createTempDirectory("sync-process-lock").toFile()
        val source = File(directory, "LockHolder.java")
        source.writeText(
            """
            import java.io.*;
            import java.nio.file.*;
            public class LockHolder {
              public static void main(String[] args) throws Exception {
                try (var file = new RandomAccessFile(args[0], "rw");
                     var lock = file.getChannel().lock()) {
                  Files.writeString(Path.of(args[1]), "ready");
                  Thread.sleep(10000);
                }
              }
            }
            """.trimIndent(),
        )
        val ready = File(directory, "ready")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            source.path,
            File(directory, ".lock").path,
            ready.path,
        ).redirectErrorStream(true).start()
        try {
            repeat(150) { if (!ready.exists() && process.isAlive) delay(50) }
            assertTrue(ready.exists(), "external JVM must own the production lock")
            val cipher = object : SyncRecordCipher {
                override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray = error("must not write")
                override fun decrypt(ciphertext: ByteArray, aad: ByteArray): ByteArray = error("must not read")
            }
            val operation = async(Dispatchers.IO) {
                EncryptedFileSyncSecureStore(directory, cipher).compareAndSet("auth", null, "secret")
            }
            delay(150)
            assertFalse(operation.isCompleted)
            operation.cancelAndJoin()
            assertTrue(directory.listFiles()!!.none { it.extension == "record" })
        } finally {
            process.destroyForcibly()
            process.waitFor()
            directory.deleteRecursively()
        }
    }
}
