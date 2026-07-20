package mihon.domain.extension

import kotlinx.serialization.json.Json
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCatalogEntry
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.InstalledExtensionTrustRecord
import mihon.domain.extension.model.RepositoryCatalogFailure
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.model.toIdentity
import mihon.domain.extension.service.ExtensionCatalogService
import mihon.domain.extension.service.ExtensionTrustDecision
import mihon.domain.extension.service.ExtensionTrustPolicy
import mihon.domain.extension.service.ExtensionTrustRequest
import mihon.domain.extension.service.ExtensionUpdatePolicy
import mihon.domain.extension.service.RepositoryFetchResult
import mihon.domain.extension.service.SharedExtensionUpdatePolicy
import mihon.domain.extension.service.TrustMismatch
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.service.ExtensionRepoIndexEntryDto
import mihon.domain.extensionrepo.service.toCatalogEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

class ExtensionSharedContractTest {

    @Test
    fun `shared extension model and service surface contains no File or Android platform types`() {
        val artifact = artifact()
        val sharedSurface = listOf(
            ExtensionArtifact::class.java,
            RepositoryIdentity::class.java,
            ExtensionSourceDescriptor::class.java,
            InstalledExtensionTrustRecord::class.java,
            ExtensionCatalogEntry::class.java,
            ExtensionCatalogResult::class.java,
            RepositoryCatalogFailure::class.java,
            ExtensionCompatibility::class.java,
            RepositoryFetchResult::class.java,
            ExtensionTrustDecision::class.java,
            TrustMismatch::class.java,
            ExtensionTrustRequest::class.java,
            ExtensionUpdatePolicy::class.java,
            SharedExtensionUpdatePolicy::class.java,
            ExtensionCatalogService::class.java,
            ExtensionTrustPolicy::class.java,
        ).flatMap { it.withNestedClasses() }
        val surfaceTypeNames = sharedSurface.flatMap { it.surfaceTypeNames() }

        assertEquals("eu.kanade.tachiyomi.extension.en.example", artifact.packageName)
        assertFalse(surfaceTypeNames.any { it == "java.io.File" || it.startsWith("android.") })
    }

    @Test
    fun `platform guard traverses complete API surface and recursive generic type shapes`() {
        val surfaceTypeNames = PlatformTypeSurfaceFixture::class.java.surfaceTypeNames().toSet()

        assertTrue(
            surfaceTypeNames.containsAll(
                setOf(
                    FieldMarker::class.java.name,
                    ConstructorMarker::class.java.name,
                    MethodParameterMarker::class.java.name,
                    MethodReturnMarker::class.java.name,
                    InterfaceMarker::class.java.name,
                    TypeVariableMarker::class.java.name,
                ),
            ),
        )

        val classArray = PlatformTypeSurfaceFixture::class.java.getDeclaredField("classArray").genericType
        assertTrue(classArray is Class<*> && classArray.isArray)
        assertTrue(classArray.typeNames().contains(ClassArrayMarker::class.java.name))

        val parameterized = PlatformTypeSurfaceFixture::class.java.getDeclaredField("parameterized").genericType
        assertInstanceOf(ParameterizedType::class.java, parameterized)
        assertTrue(parameterized.typeNames().contains(ParameterizedMarker::class.java.name))

        val upperWildcard = PlatformTypeSurfaceFixture::class.java.getDeclaredField("upperWildcard").genericType
        assertInstanceOf(
            WildcardType::class.java,
            (upperWildcard as ParameterizedType).actualTypeArguments.single(),
        )
        assertTrue(upperWildcard.typeNames().contains(UpperWildcardMarker::class.java.name))

        val lowerWildcard = PlatformTypeSurfaceFixture::class.java.getDeclaredField("lowerWildcard").genericType
        assertInstanceOf(
            WildcardType::class.java,
            (lowerWildcard as ParameterizedType).actualTypeArguments.single(),
        )
        assertTrue(lowerWildcard.typeNames().contains(LowerWildcardMarker::class.java.name))

        val genericArray = PlatformTypeSurfaceFixture::class.java.getDeclaredField("genericArray").genericType
        assertInstanceOf(GenericArrayType::class.java, genericArray)
        assertTrue(genericArray.typeNames().contains(TypeVariableMarker::class.java.name))
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
    fun `lib version compatibility preserves the fixed-main fixture inclusive boundary for both consumers`() {
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
    fun `default update policy executes the shared version rule`() {
        assertTrue(
            SharedExtensionUpdatePolicy.isUpdateAvailable(
                availableVersionCode = 10,
                availableLibVersion = 1.5,
                installedVersionCode = 10,
                installedLibVersion = 1.4,
            ),
        )
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
            installedRepository = repository(baseUrl = "HTTPS://REPO.EXAMPLE/").toIdentity(),
            incomingRepository = repository(
                baseUrl = "https://repo.example",
                fingerprint = "repo-fingerprint",
            ).toIdentity(),
        )

        assertEquals(ExtensionTrustDecision.Trusted, decision)
    }

    @Test
    fun `repository path case change requires explicit confirmation`() {
        val decision = trustDecision(
            installedRepository = repository(baseUrl = "https://repo.example/Trusted").toIdentity(),
            incomingRepository = repository(baseUrl = "https://repo.example/trusted").toIdentity(),
        )

        val required = assertInstanceOf(ExtensionTrustDecision.ConfirmationRequired::class.java, decision)
        assertTrue(required.reasons.any { it is TrustMismatch.InstalledOriginChanged })
    }

    @Test
    fun `repository query case change requires explicit confirmation`() {
        val decision = trustDecision(
            installedRepository = repository(baseUrl = "https://repo.example/index?Channel=Stable").toIdentity(),
            incomingRepository = repository(baseUrl = "https://repo.example/index?Channel=stable").toIdentity(),
        )

        val required = assertInstanceOf(ExtensionTrustDecision.ConfirmationRequired::class.java, decision)
        assertTrue(required.reasons.any { it is TrustMismatch.InstalledOriginChanged })
    }

    @Test
    fun `repository query value trailing slash change requires explicit confirmation`() {
        val decision = trustDecision(
            installedRepository = repository(baseUrl = "https://repo.example/index?channel=stable/").toIdentity(),
            incomingRepository = repository(baseUrl = "https://repo.example/index?channel=stable").toIdentity(),
        )

        val required = assertInstanceOf(ExtensionTrustDecision.ConfirmationRequired::class.java, decision)
        assertTrue(required.reasons.any { it is TrustMismatch.InstalledOriginChanged })
    }

    @Test
    fun `repository fragment trailing slash change requires explicit confirmation`() {
        val decision = trustDecision(
            installedRepository = repository(baseUrl = "https://repo.example/index#stable/").toIdentity(),
            incomingRepository = repository(baseUrl = "https://repo.example/index#stable").toIdentity(),
        )

        val required = assertInstanceOf(ExtensionTrustDecision.ConfirmationRequired::class.java, decision)
        assertTrue(required.reasons.any { it is TrustMismatch.InstalledOriginChanged })
    }

    @Test
    fun `repository path trailing slash remains equivalent before query and fragment`() {
        val decision = trustDecision(
            installedRepository = repository(
                baseUrl = "https://repo.example/index/?channel=stable#catalog",
            ).toIdentity(),
            incomingRepository = repository(
                baseUrl = "https://repo.example/index?channel=stable#catalog",
            ).toIdentity(),
        )

        assertEquals(ExtensionTrustDecision.Trusted, decision)
    }

    @Test
    fun `repository user info change requires explicit confirmation`() {
        val decision = trustDecision(
            installedRepository = repository(baseUrl = "https://reader:secret@repo.example/index").toIdentity(),
            incomingRepository = repository(baseUrl = "https://reader:changed@repo.example/index").toIdentity(),
        )

        val required = assertInstanceOf(ExtensionTrustDecision.ConfirmationRequired::class.java, decision)
        assertTrue(required.reasons.any { it is TrustMismatch.InstalledOriginChanged })
    }

    @Test
    fun `repository port change requires explicit confirmation`() {
        val decision = trustDecision(
            installedRepository = repository(baseUrl = "https://repo.example:8443/index").toIdentity(),
            incomingRepository = repository(baseUrl = "https://repo.example:9443/index").toIdentity(),
        )

        val required = assertInstanceOf(ExtensionTrustDecision.ConfirmationRequired::class.java, decision)
        assertTrue(required.reasons.any { it is TrustMismatch.InstalledOriginChanged })
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

private fun Class<*>.withNestedClasses(): List<Class<*>> =
    listOf(this) + declaredClasses.flatMap { it.withNestedClasses() }

private fun Class<*>.surfaceTypeNames(): List<String> = buildList<Type> {
    addAll(typeParameters)
    addAll(genericInterfaces)
    genericSuperclass?.let(::add)
    declaredFields.forEach { add(it.genericType) }
    declaredConstructors.forEach { constructor ->
        addAll(constructor.typeParameters)
        addAll(constructor.genericParameterTypes)
        addAll(constructor.genericExceptionTypes)
    }
    declaredMethods.forEach { method ->
        addAll(method.typeParameters)
        add(method.genericReturnType)
        addAll(method.genericParameterTypes)
        addAll(method.genericExceptionTypes)
    }
}.flatMap { it.typeNames() }

private fun Type.typeNames(): List<String> = typeNames(mutableSetOf())

private fun Type.typeNames(visited: MutableSet<Type>): List<String> {
    if (!visited.add(this)) return emptyList()

    return when (this) {
        is Class<*> -> listOf(name) + if (isArray) componentType.typeNames(visited) else emptyList()
        is ParameterizedType -> {
            rawType.typeNames(visited) +
                listOfNotNull(ownerType).flatMap { it.typeNames(visited) } +
                actualTypeArguments.flatMap { it.typeNames(visited) }
        }
        is WildcardType -> {
            lowerBounds.flatMap { it.typeNames(visited) } + upperBounds.flatMap { it.typeNames(visited) }
        }
        is GenericArrayType -> genericComponentType.typeNames(visited)
        is TypeVariable<*> -> bounds.flatMap { it.typeNames(visited) }
        else -> listOf(typeName)
    }
}

private interface PlatformSurfaceInterface<T>

private open class TypeVariableMarker
private class FieldMarker
private class ConstructorMarker
private class MethodParameterMarker
private class MethodReturnMarker
private class InterfaceMarker
private class ClassArrayMarker
private class ParameterizedMarker
private class UpperWildcardMarker
private class LowerWildcardMarker

private class PlatformTypeSurfaceFixture<T : TypeVariableMarker> : PlatformSurfaceInterface<InterfaceMarker> {
    lateinit var field: FieldMarker
    lateinit var classArray: Array<ClassArrayMarker>
    lateinit var parameterized: List<ParameterizedMarker>
    lateinit var upperWildcard: List<@JvmWildcard UpperWildcardMarker>
    lateinit var lowerWildcard: MutableList<in LowerWildcardMarker>
    lateinit var genericArray: Array<T>

    constructor(@Suppress("UNUSED_PARAMETER") marker: ConstructorMarker)

    fun service(@Suppress("UNUSED_PARAMETER") parameter: MethodParameterMarker): MethodReturnMarker {
        return MethodReturnMarker()
    }
}
