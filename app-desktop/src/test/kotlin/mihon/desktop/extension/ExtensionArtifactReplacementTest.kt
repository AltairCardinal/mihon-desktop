package mihon.desktop.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ExtensionArtifactReplacementTest {
    @Test
    fun `replacement overwrites existing artifact and removes backup`(@TempDir directory: Path) {
        val destination = directory.resolve("extension.jar").toFile().also { it.writeText("old") }
        val candidate = directory.resolve("candidate.jar").toFile().also { it.writeText("new") }

        replaceExtensionArtifact(candidate, destination)

        assertEquals("new", destination.readText())
        assertFalse(candidate.exists())
        assertFalse(directory.resolve("extension.jar.backup").toFile().exists())
    }
}
