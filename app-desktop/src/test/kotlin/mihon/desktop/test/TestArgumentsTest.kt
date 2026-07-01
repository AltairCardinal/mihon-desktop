package mihon.desktop.test

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestArgumentsTest {

    @Test
    fun `default screenshot directory is not volatile temp storage`() {
        assertFalse(TestArguments.DEFAULT_SCREENSHOT_DIR.startsWith("/tmp"))
        assertTrue(TestArguments.DEFAULT_SCREENSHOT_DIR.contains("Mihon") || TestArguments.DEFAULT_SCREENSHOT_DIR.contains(".mihon"))
    }

    @Test
    fun `parse uses platform screenshot directory by default`() {
        val args = TestArguments.parse(arrayOf("--test-mode"))

        assertFalse(args.screenshotDir.startsWith("/tmp"))
        assertTrue(args.screenshotDir.contains("Mihon") || args.screenshotDir.contains(".mihon"))
    }
}
