package tachiyomi.data.release

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopPlatformInfoTest {

    private val platformInfo = DesktopPlatformInfo()

    @Test
    fun `preferredAbi returns non-null on standard JVM`() {
        val abi = platformInfo.preferredAbi
        assertNotNull(abi, "preferredAbi should not be null on standard JVM")
    }

    @Test
    fun `preferredAbi returns a known architecture`() {
        val abi = platformInfo.preferredAbi
        val knownAbis = setOf("x86_64", "arm64-v8a", "x86")
        assertTrue(
            abi in knownAbis,
            "preferredAbi '$abi' should be one of $knownAbis",
        )
    }
}
