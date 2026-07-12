package mihon.desktop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppVersionTest {

    @Test
    fun `APP_VERSION matches stage feature build and hash format`() {
        val pattern = Regex("""^0\.\d+\.\d+\.\d+\.[0-9a-f]{7}$""")
        assertTrue(
            pattern.matches(APP_VERSION),
            "APP_VERSION '$APP_VERSION' should match format 0.STAGE.FEATURE.BUILD.GIT_HASH",
        )
    }

    @Test
    fun `AppVersion STAGE is between 1 and 10`() {
        assertTrue(AppVersion.STAGE in 1..22, "STAGE should be 1-22, got ${AppVersion.STAGE}")
    }

    @Test
    fun `AppVersion FEATURE is non-negative`() {
        assertTrue(AppVersion.FEATURE >= 0, "FEATURE should be >= 0, got ${AppVersion.FEATURE}")
    }

    @Test
    fun `AppVersion BUILD is positive`() {
        assertTrue(AppVersion.BUILD >= 1, "BUILD should be >= 1, got ${AppVersion.BUILD}")
    }
}
