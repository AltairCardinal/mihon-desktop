package mihon.domain.extension

import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.RepositoryIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionProtocolSupportContractTest {
    @Test
    fun `known published APIs include the preserved historical 1_5 branch`() {
        for (version in listOf(1.4, 1.5, 1.6)) {
            assertEquals(ExtensionCompatibility.Compatible, artifact(version).compatibility())
        }
    }

    @Test
    fun `unknown APIs inside and outside the old numeric range are rejected`() {
        for (version in listOf(1.45, 1.55, 1.3, 1.7, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue(artifact(version).compatibility() is ExtensionCompatibility.UnsupportedLib, "$version")
        }
    }

    private fun artifact(version: Double) = ExtensionArtifact(
        name = "Fixture", packageName = "fixture.extension", versionName = "1.4.1", versionCode = 1,
        language = "en", isNsfw = false, sources = emptyList(),
        repository = RepositoryIdentity("https://fixture.invalid", "Fixture", "key"),
        downloadUrl = "https://fixture.invalid/extension.apk", iconUrl = "", declaredSha256 = null,
        declaredLibVersion = version,
    )
}
