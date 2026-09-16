package mihon.desktop.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TestArgumentsTest {

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
