package mihon.desktop.platform

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files

@Timeout(30)
class CredentialCommandCancellationTest {
    @Test
    fun `interrupting the runner kills its real process`() {
        val directory = Files.createTempDirectory("credential-cancellation").toFile()
        val source = File(directory, "CommandSleeper.java")
        source.writeText(
            """
            import java.nio.file.*;
            public class CommandSleeper {
              public static void main(String[] args) throws Exception {
                Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(3000);
              }
            }
            """.trimIndent(),
        )
        val pidFile = File(directory, "pid")
        val worker = Thread {
            runCatching {
                ProcessCommandRunner(10000).run(
                    listOf(File(System.getProperty("java.home"), "bin/java").path, source.path, pidFile.path),
                )
            }
        }
        var child: ProcessHandle? = null
        try {
            worker.start()
            repeat(150) { if (!pidFile.exists() && worker.isAlive) Thread.sleep(50) }
            assertTrue(pidFile.exists())
            child = ProcessHandle.of(pidFile.readText().toLong()).orElseThrow()
            worker.interrupt()
            worker.join(1500)
            assertFalse(worker.isAlive, "interruption must not wait for command completion")
            assertFalse(child.isAlive, "the interrupted credential child must terminate")
        } finally {
            child?.destroyForcibly()
            worker.interrupt()
            worker.join(5000)
            directory.deleteRecursively()
        }
    }
}
