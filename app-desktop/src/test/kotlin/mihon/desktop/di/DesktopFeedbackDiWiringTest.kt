package mihon.desktop.di

import kotlinx.coroutines.runBlocking
import mihon.desktop.DesktopUiDependencies
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

@Isolated
class DesktopFeedbackDiWiringTest {
    @Test
    fun `feedback runtime resolves through real DI and the default UI dependencies`(@TempDir root: File) = runBlocking {
        val context = initDesktopDIForTest(root, isolatedDesktopPreferenceStore())
        try {
            val feedback = Injekt.get<mihon.desktop.feedback.DesktopFeedbackRuntime>()
            assertSame(feedback, DesktopUiDependencies.fromInjekt().feedbackRuntime)
            assertTrue(feedback.store.state.value.session == null)
        } finally {
            context.closeAndJoin()
        }
    }
}
