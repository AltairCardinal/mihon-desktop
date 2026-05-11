package mihon.desktop.ci

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Stage 24.0 — Desktop CI workflow contract tests.
 *
 * Verifies that the desktop-ci.yml workflow file exists and contains
 * the required Gradle tasks for Desktop JVM testing.
 */
class DesktopCIWorkflowTest {

    private val workflowFile: File by lazy {
        // Walk up from test class location to find repo root, then locate workflow
        var dir = File(System.getProperty("user.dir"))
        // Find the .github directory
        while (!File(dir, ".github").exists() && dir.parentFile != null) {
            dir = dir.parentFile
        }
        File(dir, ".github/workflows/desktop-ci.yml")
    }

    @Test
    fun `desktop-ci workflow file exists`() {
        assertTrue(workflowFile.exists(), "Expected .github/workflows/desktop-ci.yml to exist")
    }

    @Test
    fun `workflow runs jvmTest task`() {
        val content = workflowFile.readText()
        assertTrue(
            content.contains(":app-desktop:jvmTest") || content.contains("app-desktop:jvmTest"),
            "Workflow should run :app-desktop:jvmTest",
        )
    }

    @Test
    fun `workflow runs spotlessCheck`() {
        val content = workflowFile.readText()
        assertTrue(content.contains("spotlessCheck"), "Workflow should run spotlessCheck")
    }

    @Test
    fun `workflow triggers on push to main`() {
        val content = workflowFile.readText()
        assertTrue(content.contains("push") && content.contains("main"), "Workflow should trigger on push to main")
    }

    @Test
    fun `workflow triggers on pull request`() {
        val content = workflowFile.readText()
        assertTrue(content.contains("pull_request"), "Workflow should trigger on pull_request")
    }
}
