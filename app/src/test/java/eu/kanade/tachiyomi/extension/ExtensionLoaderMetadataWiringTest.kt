package eu.kanade.tachiyomi.extension

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Bundle
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.AndroidCommitPlan
import eu.kanade.tachiyomi.extension.util.AndroidInstallLocation
import eu.kanade.tachiyomi.extension.util.DefaultAndroidInstallGateway
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

/** PackageManager is the OS boundary; version admission and trust execute production code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ExtensionLoaderMetadataWiringTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `loader applies explicit package protocol before real trust decision`() = runTest {
        val previous = Injekt
        val preferences = SourcePreferences(AndroidPreferenceStore(RuntimeEnvironment.getApplication()))
        val repositories = mockk<ExtensionRepoRepository>()
        coEvery { repositories.getAll() } returns emptyList()
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(preferences)
        Injekt.addSingleton(TrustExtension(repositories, preferences))
        try {
            val packageManager = mockk<PackageManager>()
            val context = mockk<Context>()
            every { context.filesDir } returns temporaryFolder.root
            every { context.packageManager } returns packageManager
            every { packageManager.getApplicationLabel(any()) } returns "Tachiyomi: Legacy label"
            val info = PackageInfo().apply {
                packageName = "fixture.metadata"
                versionName = "9.0.12"
                setLongVersionCode(12)
                reqFeatures = arrayOf(FeatureInfo().apply { name = "tachiyomi.extension" })
                applicationInfo = spyk(
                    ApplicationInfo().apply {
                        metaData = Bundle().apply {
                            putFloat("tachiyomix.extensionLib", 1.6f)
                            putString("tachiyomix.name", "Modern source")
                        }
                    },
                )
                signingInfo = mockk<SigningInfo>().also {
                    every { it.hasMultipleSigners() } returns false
                    every { it.signingCertificateHistory } returns arrayOf(Signature(byteArrayOf(1, 2, 3)))
                }
            }
            every { packageManager.getPackageInfo("fixture.metadata", any<Int>()) } returns info
            val archive = temporaryFolder.newFile("candidate.apk")
            every { packageManager.getPackageArchiveInfo(archive.absolutePath, any<Int>()) } returns info
            val gateway = DefaultAndroidInstallGateway(
                context,
                installSystem = { _, _, _ -> error("Inspection must not install") },
                commitPlanProvider = { AndroidCommitPlan(AndroidInstallLocation.PRIVATE) },
            )
            assertEquals(1.6, gateway.inspect(archive)!!.libVersion)
            val result = ExtensionLoader.loadExtensionFromPkgName(context, info.packageName)
            assertTrue("A supported but untrusted package must reach the trust gate", result is LoadResult.Untrusted)
            result as LoadResult.Untrusted
            assertEquals("Modern source", result.extension.name)
            assertEquals(1.6, result.extension.libVersion, 0.0)
            assertTrue(preferences.trustedExtensions().get().isEmpty())

            info.versionName = "1.4.12"
            for (raw in listOf("invalid", "1.45", "1.7")) {
                info.applicationInfo!!.metaData.putString("tachiyomix.extensionLib", raw)
                assertEquals(LoadResult.Error, ExtensionLoader.loadExtensionFromPkgName(context, info.packageName))
            }
            info.applicationInfo!!.metaData.remove("tachiyomix.extensionLib")
            for (version in listOf("1.4.12", "1.5.12")) {
                info.versionName = version
                assertTrue(ExtensionLoader.loadExtensionFromPkgName(context, info.packageName) is LoadResult.Untrusted)
            }

            // Malformed OS/archive metadata must become an explicit load error, not escape as a host crash.
            val originalApplication = info.applicationInfo
            val originalSigning = info.signingInfo
            val outcomes = linkedMapOf<String, Any?>()
            suspend fun capture(label: String) {
                outcomes[label] = runCatching {
                    ExtensionLoader.loadExtensionFromPkgName(context, info.packageName)
                }.fold({ it }, { it.javaClass.simpleName })
            }
            info.applicationInfo = null
            capture("shared missing application")
            info.applicationInfo = originalApplication
            info.signingInfo = null
            capture("shared missing signing info")
            info.signingInfo = originalSigning
            every { originalSigning!!.signingCertificateHistory } returns emptyArray()
            capture("shared empty certificate history")
            every { originalSigning!!.signingCertificateHistory } returns arrayOf(Signature(byteArrayOf(1, 2, 3)))
            val privateArchive = temporaryFolder.newFolder("exts").resolve("${info.packageName}.ext")
            assertTrue(privateArchive.createNewFile())
            every { packageManager.getPackageArchiveInfo(privateArchive.absolutePath, any<Int>()) } returns info
            info.applicationInfo = null
            capture("private missing application")
            assertEquals(
                outcomes.keys.associateWith { LoadResult.Error },
                outcomes,
            )
            assertTrue(privateArchive.delete())
            info.applicationInfo = originalApplication
            originalApplication!!.sourceDir = archive.absolutePath
            every { context.classLoader } returns javaClass.classLoader
            TrustExtension(repositories, preferences).trust(info.packageName, 12, result.extension.signatureHash)
            outcomes.clear()
            val originalMetadata = originalApplication.metaData
            originalApplication.metaData = null
            capture("trusted missing metadata bundle")
            originalApplication.metaData = Bundle()
            capture("trusted missing entry class")
            originalApplication.metaData.putInt("tachiyomi.extension.class", 42)
            capture("trusted wrong entry class type")
            for (entry in listOf("", " ", "fixture.DoesNotExist", "java.lang.String")) {
                originalApplication.metaData.putString("tachiyomi.extension.class", entry)
                capture("trusted invalid entry '$entry'")
            }
            originalApplication.metaData = originalMetadata
            assertEquals(outcomes.keys.associateWith { LoadResult.Error }, outcomes)

            // Trust/OS/classloader are real production boundaries; only package metadata is supplied.
            info.versionName = "1.6.12"
            originalMetadata!!.putFloat("tachiyomix.extensionLib", 1.6f)
            every { packageManager.getDefaultActivityIcon() } returns android.graphics.drawable.ColorDrawable()
            every { packageManager.getDrawable(any(), any(), any()) } returns android.graphics.drawable.ColorDrawable()
            every { originalApplication.loadIcon(packageManager) } returns android.graphics.drawable.ColorDrawable()
            LoaderLinkageFixture.site = "healthy"
            LoaderLinkageFixture.entered.clear()
            originalMetadata.putString("tachiyomi.extension.class", MetadataLinkageFixture::class.java.name)
            suspend fun loadPlatformFixture() = ExtensionLoader.loadExtensionFromPkgName(context, info.packageName) {
                    _,
                    parent,
                ->
                parent
            }
            val healthyControl = loadPlatformFixture()
            assertTrue(
                "The platform seam must load a healthy real HttpSource before fault assertions: $healthyControl",
                healthyControl is LoadResult.Success,
            )
            assertEquals(0, LoaderLinkageFixture.clientReads)
            val linkageOutcomes = linkedMapOf<String, String?>()
            listOf("constructor", "factory", "id", "name", "lang").forEach { site ->
                LoaderLinkageFixture.site = site
                originalMetadata.putString(
                    "tachiyomi.extension.class",
                    when (site) {
                        "constructor" -> ConstructorLinkageFixture::class.java.name
                        "factory" -> FactoryLinkageFixture::class.java.name
                        else -> MetadataLinkageFixture::class.java.name
                    },
                )
                linkageOutcomes[site] = runCatching {
                    loadPlatformFixture()
                }.fold({ result ->
                    if (result is LoadResult.Failure) {
                        assertEquals(info.packageName, result.failure.pkgName)
                        assertEquals("1.6.12", result.failure.versionName)
                        assertEquals(12L, result.failure.versionCode)
                        assertEquals(
                            when (site) {
                                "constructor" -> eu.kanade.tachiyomi.network.SourceOperationPhase.CONSTRUCTION
                                "factory" -> eu.kanade.tachiyomi.network.SourceOperationPhase.FACTORY
                                else -> eu.kanade.tachiyomi.network.SourceOperationPhase.METADATA
                            },
                            result.failure.phase,
                        )
                        assertTrue(result.failure.error is mihon.domain.error.AppError.ExtensionIncompatible)
                        assertTrue(
                            result.failure.error.cause is eu.kanade.tachiyomi.network.SourceCompatibilityException,
                        )
                    }
                    result::class.simpleName
                }, { "escaped:${it::class.simpleName}" })
            }
            assertTrue(LoaderLinkageFixture.entered.getOrDefault("constructor", 0) > 0)
            assertTrue(LoaderLinkageFixture.entered.getOrDefault("factory", 0) > 0)
            assertTrue(LoaderLinkageFixture.entered.getOrDefault("lang", 0) > 0)
            assertEquals(linkageOutcomes.keys.associateWith { "Failure" }, linkageOutcomes)
            // A factory may return a healthy provider before a broken provider. Publish neither.
            LoaderLinkageFixture.site = "id"
            originalMetadata.putString("tachiyomi.extension.class", AtomicFactoryLinkageFixture::class.java.name)
            assertTrue(loadPlatformFixture() is LoadResult.Failure)
            originalMetadata.putString("tachiyomi.extension.class", FactoryLinkageFixture::class.java.name)
            val cancellation = kotlinx.coroutines.CancellationException("loader cancellation")
            val fatal = object : VirtualMachineError("controlled VM failure") {}
            val hostBug = AssertionError("ordinary extension bug is not ABI incompatibility")
            listOf<Throwable>(cancellation, fatal, hostBug).forEach { expected ->
                LoaderLinkageFixture.failure = expected
                val actual = runCatching { loadPlatformFixture() }.exceptionOrNull()
                org.junit.Assert.assertSame(expected, actual)
            }
            LoaderLinkageFixture.failure = null
            LoaderLinkageFixture.site = "healthy"
            originalMetadata.putString("tachiyomi.extension.class", MetadataLinkageFixture::class.java.name)
            val healthy = loadPlatformFixture()
            assertTrue(healthy is LoadResult.Success)
            assertEquals(0, LoaderLinkageFixture.clientReads)
            val actualSource = (healthy as LoadResult.Success).extension.sources.single()
            val callResult = tachiyomi.domain.source.service.SourceMangaSearchService().loadPageResult(
                actualSource,
                tachiyomi.domain.source.service.SourcePageRequest(
                    actualSource.id,
                    1,
                    1L,
                    tachiyomi.domain.source.service.SourceQuery.Search(
                        "client boundary",
                        eu.kanade.tachiyomi.source.model.FilterList(),
                    ),
                ),
            )
            assertTrue(callResult is tachiyomi.domain.source.service.SourcePageResult.Failure)
            val callFailure = callResult as tachiyomi.domain.source.service.SourcePageResult.Failure
            assertTrue(callFailure.error is mihon.domain.error.AppError.ExtensionIncompatible)
            assertEquals(tachiyomi.domain.source.service.SourceRecoveryAction.None, callFailure.recoveryAction)
            assertEquals(
                eu.kanade.tachiyomi.network.SourceOperationPhase.SEARCH,
                (callFailure.error.cause as eu.kanade.tachiyomi.network.SourceCompatibilityException).phase,
            )
            assertEquals(1, LoaderLinkageFixture.clientReads)
            preferences.trustedExtensions().delete()

            val signedCandidate = PackageInfo().apply {
                packageName = info.packageName
                versionName = "1.6.13"
                setLongVersionCode(13)
                reqFeatures = info.reqFeatures
                applicationInfo = originalApplication
                signingInfo = mockk<SigningInfo>().also {
                    every { it.hasMultipleSigners() } returns false
                    every { it.signingCertificateHistory } returns arrayOf(Signature(byteArrayOf(1, 2, 3)))
                }
            }
            every { packageManager.getPackageArchiveInfo(archive.absolutePath, any<Int>()) } returns signedCandidate
            val protectedBytes = "test-owned previously installed APK".toByteArray(Charsets.UTF_8)
            val replacementOutcomes = linkedMapOf<String, Pair<Any?, Boolean>>()
            for (missing in listOf(true, false)) {
                info.signingInfo = if (missing) null else originalSigning
                every { originalSigning!!.signingCertificateHistory } returns emptyArray()
                privateArchive.setWritable(true)
                privateArchive.writeBytes(protectedBytes)
                val installResult = runCatching { ExtensionLoader.installPrivateExtensionFile(context, archive) }
                    .fold({ it }, { it.javaClass.simpleName })
                val oldFilePreserved =
                    privateArchive.exists() && privateArchive.readBytes().contentEquals(protectedBytes)
                replacementOutcomes[if (missing) "missing prior signing info" else "empty prior certificate history"] =
                    installResult to oldFilePreserved
            }
            assertEquals(replacementOutcomes.keys.associateWith { false to true }, replacementOutcomes)

            assertTrue(privateArchive.delete())
            every { packageManager.getPackageInfo(info.packageName, any<Int>()) } throws
                PackageManager.NameNotFoundException(info.packageName)
            val candidateSigning = signedCandidate.signingInfo
            val unsignedFile = spyk(archive)
            var copySourceChecks = 0
            every { unsignedFile.exists() } answers {
                copySourceChecks++
                callOriginal()
            }
            val firstInstallOutcomes = linkedMapOf<String, Pair<Any?, Int>>()
            for (missing in listOf(true, false)) {
                signedCandidate.signingInfo = if (missing) null else candidateSigning
                every { candidateSigning!!.signingCertificateHistory } returns emptyArray()
                copySourceChecks = 0
                val outcome = runCatching { ExtensionLoader.installPrivateExtensionFile(context, unsignedFile) }
                    .fold({ it }, { it.javaClass.simpleName })
                firstInstallOutcomes[if (missing) "unsigned first install" else "empty first install certificates"] =
                    outcome to copySourceChecks
            }
            // The real copy helper checks its source file first; rejected metadata must never reach that helper.
            assertEquals(firstInstallOutcomes.keys.associateWith { false to 0 }, firstInstallOutcomes)
        } finally {
            Injekt = previous
        }
    }
}

object LoaderLinkageFixture {
    var site = "healthy"
    var clientReads = 0
    var failure: Throwable? = null
    val entered = mutableMapOf<String, Int>()
    fun enter(site: String) {
        entered[site] = entered.getOrDefault(site, 0) + 1
    }
}

class ConstructorLinkageFixture : eu.kanade.tachiyomi.source.Source {
    init {
        LoaderLinkageFixture.enter("constructor")
        throw NoSuchMethodError("future constructor")
    }
    override val id = 901L
    override val name = "constructor fixture"
}

class FactoryLinkageFixture : eu.kanade.tachiyomi.source.SourceFactory {
    override fun createSources(): List<eu.kanade.tachiyomi.source.Source> {
        LoaderLinkageFixture.enter("factory")
        LoaderLinkageFixture.failure?.let { throw it }
        throw NoClassDefFoundError("future factory")
    }
}

class AtomicFactoryLinkageFixture : eu.kanade.tachiyomi.source.SourceFactory {
    override fun createSources() = listOf(
        object : eu.kanade.tachiyomi.source.Source {
            override val id = 903L
            override val name = "healthy first factory provider"
        },
        MetadataLinkageFixture(),
    )
}

class MetadataLinkageFixture : eu.kanade.tachiyomi.source.online.HttpSource() {
    override val baseUrl = "https://fixture.invalid"
    override val supportsLatest = false
    override fun chapterPageParse(response: okhttp3.Response) = eu.kanade.tachiyomi.source.model.SChapter.create()
    override val id: Long get() {
        LoaderLinkageFixture.enter("id")
        return if (LoaderLinkageFixture.site ==
            "id"
        ) {
            throw NoSuchFieldError("future id")
        } else {
            902L
        }
    }
    override val name: String get() {
        LoaderLinkageFixture.enter("name")
        return if (LoaderLinkageFixture.site ==
            "name"
        ) {
            throw AbstractMethodError("future name")
        } else {
            "metadata fixture"
        }
    }
    override val lang: String get() {
        LoaderLinkageFixture.enter("lang")
        return if (LoaderLinkageFixture.site ==
            "lang"
        ) {
            throw NoSuchMethodError("future lang")
        } else {
            "en"
        }
    }
    override val client: okhttp3.OkHttpClient get() {
        LoaderLinkageFixture.clientReads++
        throw NoSuchMethodError("client must not be probed while loading")
    }
}
