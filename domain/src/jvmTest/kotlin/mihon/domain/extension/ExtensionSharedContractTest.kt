package mihon.domain.extension

import kotlinx.serialization.json.Json
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.InstalledExtensionTrustRecord
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.model.toIdentity
import mihon.domain.extension.service.ExtensionTrustDecision
import mihon.domain.extension.service.ExtensionTrustPolicy
import mihon.domain.extension.service.ExtensionTrustRequest
import mihon.domain.extension.service.TrustMismatch
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.service.ExtensionRepoIndexEntryDto
import mihon.domain.extensionrepo.service.toCatalogEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionSharedContractTest {

    @Test
    fun `shared artifact model contains no File or Android platform types`() {
        val artifact = artifact()

        val fieldTypes = buildList {
            addAll(ExtensionArtifact::class.java.declaredFields.map { it.type.name })
            addAll(RepositoryIdentity::class.java.declaredFields.map { it.type.name })
        }

        assertEquals("eu.kanade.tachiyomi.extension.en.example", artifact.packageName)
        assertFalse(fieldTypes.any { it == "java.io.File" || it.startsWith("android.") })
    }

    @Test
    fun `repository index DTO maps real shape to the shared artifact`() {
        val dto = Json {
            ignoreUnknownKeys = true
        }.decodeFromString<List<ExtensionRepoIndexEntryDto>>(INDEX_JSON).single()

        val entry = dto.toCatalogEntry(repository())

        assertEquals("Example", entry.artifact.name)
        assertEquals("eu.kanade.tachiyomi.extension.en.example", entry.artifact.packageName)
        assertEquals(42L, entry.artifact.versionCode)
        assertEquals(1.4, entry.artifact.libVersion)
        assertEquals("https://repo.example/apk/example.apk", entry.artifact.downloadUrl)
        assertEquals("https://repo.example/icon/eu.kanade.tachiyomi.extension.en.example.png", entry.artifact.iconUrl)
        assertEquals("0123456789abcdef", entry.artifact.declaredSha256)
        assertEquals(repository().toIdentity(), entry.artifact.repository)
        assertEquals(listOf("Example Source"), entry.artifact.sources.map { it.name })
        assertEquals(ExtensionCompatibility.Compatible, entry.compatibility)
    }

    @Test
    fun `lib version compatibility uses the Android authoritative inclusive boundary`() {
        assertEquals(ExtensionCompatibility.Compatible, artifact(versionName = "1.4.0").compatibility())
        assertEquals(ExtensionCompatibility.Compatible, artifact(versionName = "1.5.99").compatibility())
        assertInstanceOf(
            ExtensionCompatibility.UnsupportedLib::class.java,
            artifact(versionName = "1.3.9").compatibility(),
        )
        assertInstanceOf(
            ExtensionCompatibility.UnsupportedLib::class.java,
            artifact(versionName = "1.6.0").compatibility(),
        )
    }

    @Test
    fun `update availability shares version code and lib version rules`() {
        val newerCode = artifact(versionName = "1.4.9", versionCode = 11)
        val newerLib = artifact(versionName = "1.5.0", versionCode = 10)
        val same = artifact(versionName = "1.4.9", versionCode = 10)

        assertTrue(newerCode.isUpdateAvailable(installedVersionCode = 10, installedLibVersion = 1.4))
        assertTrue(newerLib.isUpdateAvailable(installedVersionCode = 10, installedLibVersion = 1.4))
        assertFalse(same.isUpdateAvailable(installedVersionCode = 10, installedLibVersion = 1.4))
    }

    @Test
    fun `declared and downloaded digest mismatch is rejected`() {
        val decision = ExtensionTrustPolicy().evaluate(
            ExtensionTrustRequest(
                incomingArtifact = artifact(declaredSha256 = "aaaa"),
                downloadedArtifactSha256 = "bbbb",
            ),
        )

        val rejected = assertInstanceOf(ExtensionTrustDecision.Rejected::class.java, decision)
        assertInstanceOf(AppError.MalformedData::class.java, rejected.error)
    }

    @Test
    fun `repository identity switch requires explicit confirmation`() {
        val decision = trustDecision(
            installedRepository = repository().toIdentity(),
            incomingRepository = repository(fingerprint = "other-fingerprint").toIdentity(),
        )

        val required = assertInstanceOf(ExtensionTrustDecision.ConfirmationRequired::class.java, decision)
        assertTrue(required.reasons.any { it is TrustMismatch.RepositoryIdentityChanged })
    }

    @Test
    fun `legacy sidecar without repository identity requires explicit confirmation`() {
        val decision = trustDecision(
            installedRepository = null,
            incomingRepository = repository().toIdentity(),
        )

        val required = assertInstanceOf(ExtensionTrustDecision.ConfirmationRequired::class.java, decision)
        assertTrue(required.reasons.contains(TrustMismatch.LegacyMetadataMissingRepositoryIdentity))
    }

    @Test
    fun `installed repository origin change requires explicit confirmation even with same fingerprint`() {
        val decision = trustDecision(
            installedRepository = repository(baseUrl = "https://old.example").toIdentity(),
            incomingRepository = repository(baseUrl = "https://new.example").toIdentity(),
        )

        val required = assertInstanceOf(ExtensionTrustDecision.ConfirmationRequired::class.java, decision)
        assertTrue(required.reasons.any { it is TrustMismatch.InstalledOriginChanged })
    }

    @Test
    fun `installed artifact digest discontinuity is rejected`() {
        val decision = ExtensionTrustPolicy().evaluate(
            ExtensionTrustRequest(
                incomingArtifact = artifact(),
                downloadedArtifactSha256 = "incoming",
                installed = InstalledExtensionTrustRecord(
                    repository = repository().toIdentity(),
                    artifactSha256 = "recorded",
                ),
                installedArtifactSha256 = "modified",
            ),
        )

        assertInstanceOf(ExtensionTrustDecision.Rejected::class.java, decision)
    }

    @Test
    fun `matching identity digests and installed origin are trusted`() {
        val decision = trustDecision(
            installedRepository = repository(baseUrl = "https://repo.example/").toIdentity(),
            incomingRepository = repository(fingerprint = "repo-fingerprint").toIdentity(),
        )

        assertEquals(ExtensionTrustDecision.Trusted, decision)
    }

    private fun trustDecision(
        installedRepository: RepositoryIdentity?,
        incomingRepository: RepositoryIdentity,
    ): ExtensionTrustDecision {
        return ExtensionTrustPolicy().evaluate(
            ExtensionTrustRequest(
                incomingArtifact = artifact(repository = incomingRepository),
                downloadedArtifactSha256 = "incoming",
                installed = InstalledExtensionTrustRecord(
                    repository = installedRepository,
                    artifactSha256 = "installed",
                ),
                installedArtifactSha256 = "installed",
            ),
        )
    }

    private fun artifact(
        versionName: String = "1.4.9",
        versionCode: Long = 10,
        repository: RepositoryIdentity = repository().toIdentity(),
        declaredSha256: String? = "incoming",
    ) = ExtensionArtifact(
        name = "Example",
        packageName = "eu.kanade.tachiyomi.extension.en.example",
        versionName = versionName,
        versionCode = versionCode,
        language = "en",
        isNsfw = false,
        sources = emptyList(),
        repository = repository,
        downloadUrl = "https://repo.example/apk/example.apk",
        iconUrl = "https://repo.example/icon/example.png",
        declaredSha256 = declaredSha256,
    )

    private fun repository(
        baseUrl: String = "https://repo.example",
        fingerprint: String = "REPO-FINGERPRINT",
    ) = ExtensionRepo(
        baseUrl = baseUrl,
        name = "Example repository",
        shortName = "Example",
        website = "https://repo.example/about",
        signingKeyFingerprint = fingerprint,
    )

    private companion object {
        const val INDEX_JSON =
            """[{"name":"Tachiyomi: Example","pkg":"eu.kanade.tachiyomi.extension.en.example","apk":"example.apk","lang":"en","code":42,"version":"1.4.7","nsfw":0,"sha256":"0123456789abcdef","sources":[{"id":7,"lang":"en","name":"Example Source","baseUrl":"https://source.example"}]}]"""
    }
}
