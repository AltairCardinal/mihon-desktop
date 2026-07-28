package mihon.desktop.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestArgumentsTest {

    @Test
    fun `legacy screenshot directory argument is ignored`() {
        val baseline = TestArguments.parse(arrayOf("--test-mode"))
        val withLegacyArgument = TestArguments.parse(
            arrayOf("--test-mode", "--screenshot-dir=/tmp/should-not-be-used"),
        )

        assertEquals(baseline, withLegacyArgument)
    }
}
