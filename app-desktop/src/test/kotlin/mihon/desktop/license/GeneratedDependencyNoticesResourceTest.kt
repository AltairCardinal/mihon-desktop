package mihon.desktop.license

import mihon.domain.license.model.LicenseNoticeResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeneratedDependencyNoticesResourceTest {

    @Test
    fun `packaged metadata flows through the runtime provider and notice policy`() {
        val result = ClasspathDependencyNoticeProvider().getNotices()
        val notices = assertInstanceOf(LicenseNoticeResult.Success::class.java, result).notices
        val names = notices.map { it.name }

        assertEquals(195, notices.size)
        assertEquals(
            names.sortedWith(compareBy<String> { it.lowercase() }.thenBy { it }),
            names,
        )
        assertTrue(
            names.any { "kotlinx-coroutines-core" in it },
            "real Desktop dependency missing: $names",
        )
        assertTrue(
            names.containsAll(listOf("okhttp-zstd", "zstd-kmp", "zstd-kmp-okio")),
            "Zstandard dependency notices missing: $names",
        )
        val coroutines = notices.first { "kotlinx-coroutines-core" in it.name }
        assertTrue(
            coroutines.license?.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION") == true,
            "coroutines notice must expose the packaged Apache license content, not its ID or name",
        )
    }
}
