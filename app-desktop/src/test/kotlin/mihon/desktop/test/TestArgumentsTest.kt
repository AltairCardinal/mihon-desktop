package mihon.desktop.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TestArgumentsTest {

    @Test
    fun `acceptance repository requires test mode and isolated profile before DI`(@TempDir directory: File) {
        val flag = "--test-sync-repository=mihon-sync-acceptance-fixture-none"
        val profile = "--test-profile=${directory.absolutePath}"
        listOf(
            arrayOf(flag),
            arrayOf("--test-mode", flag),
            arrayOf("--test-mode", "--test-profile-dir=/tmp/fixture", flag),
            arrayOf("--test-mode", profile, "--test-sync-repository=mihon-sync"),
            arrayOf("--test-mode", profile, flag, flag),
        ).forEach { args -> assertThrows(IllegalArgumentException::class.java) { TestArguments.parse(args) } }
        assertEquals(
            "mihon-sync-acceptance-fixture-none",
            TestArguments.parse(arrayOf("--test-mode", profile, flag)).syncRepository,
        )
    }

    @Test
    fun `isolated profile requires explicit test mode and an absolute nonempty path`() {
        listOf(
            arrayOf("--test-profile=/tmp/mihon-test"),
            arrayOf("--test-mode", "--test-profile="),
            arrayOf("--test-mode", "--test-profile=relative"),
            arrayOf("--test-mode", "--test-profile"),
            arrayOf("--test-mode", "--test-profile=/one", "--test-profile=/two"),
        ).forEach { args ->
            assertThrows(IllegalArgumentException::class.java, { TestArguments.parse(args) }, args.joinToString())
        }
    }

    @Test
    fun `legacy screenshot directory argument is ignored`() {
        val baseline = TestArguments.parse(arrayOf("--test-mode"))
        val withLegacyArgument = TestArguments.parse(
            arrayOf("--test-mode", "--screenshot-dir=/tmp/should-not-be-used"),
        )

        assertEquals(baseline, withLegacyArgument)
    }
}
