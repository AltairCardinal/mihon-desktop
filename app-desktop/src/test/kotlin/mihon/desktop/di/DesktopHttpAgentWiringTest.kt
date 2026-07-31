package mihon.desktop.di

import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import uy.kohesive.injekt.Injekt

@Isolated
class DesktopHttpAgentWiringTest {

    @Test
    fun `desktop network initialization supplies the Android compatible http agent`(
        @TempDir tempDir: Path,
    ) = withRestoredHttpAgent {
        System.clearProperty(HTTP_AGENT_PROPERTY)

        val context = initDesktopDIForTest(
            appDir = tempDir.toFile(),
            preferenceStore = DesktopPreferenceStore(),
        )
        try {
            assertEquals(DEFAULT_HTTP_AGENT, System.getProperty(HTTP_AGENT_PROPERTY))
        } finally {
            context.closeAndJoin()
        }
    }

    @Test
    fun `desktop network initialization preserves an explicit http agent`(
        @TempDir tempDir: Path,
    ) = withRestoredHttpAgent {
        System.setProperty(HTTP_AGENT_PROPERTY, CUSTOM_HTTP_AGENT)

        val context = initDesktopDIForTest(
            appDir = tempDir.toFile(),
            preferenceStore = DesktopPreferenceStore(),
        )
        try {
            assertEquals(CUSTOM_HTTP_AGENT, System.getProperty(HTTP_AGENT_PROPERTY))
        } finally {
            context.closeAndJoin()
        }
    }

    private fun withRestoredHttpAgent(block: suspend () -> Unit) = runBlocking {
        val previousInjekt = Injekt
        val previousHttpAgent = System.getProperty(HTTP_AGENT_PROPERTY)
        try {
            block()
        } finally {
            if (previousHttpAgent == null) {
                System.clearProperty(HTTP_AGENT_PROPERTY)
            } else {
                System.setProperty(HTTP_AGENT_PROPERTY, previousHttpAgent)
            }
            Injekt = previousInjekt
        }
    }

    private companion object {
        const val HTTP_AGENT_PROPERTY = "http.agent"
        const val DEFAULT_HTTP_AGENT = "Mihon Desktop/1.0"
        const val CUSTOM_HTTP_AGENT = "ExplicitDesktopAgent"
    }
}
