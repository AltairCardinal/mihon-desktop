package mihon.desktop.ui.extension

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File

class ExtensionListCopyContractTest {
    @Test
    fun `available list no longer contains obsolete JVM-only warning`() {
        val source = File("src/main/kotlin/mihon/desktop/ui/extension/ExtensionListScreen.kt").readText()

        assertFalse(source.contains("Desktop only supports JVM-compiled extensions"))
        assertFalse(source.contains("Standard repositories serve Android-only extensions"))
    }
}
