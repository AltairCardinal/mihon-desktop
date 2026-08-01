package mihon.desktop.extension

import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.network.DesktopPluginNetworkSupport
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.image.DesktopSourceImage
import mihon.desktop.image.createDesktopImageLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.jar.JarFile

@Isolated
class RealCopyMangaNetworkRoutingTest {

    @Test
    @Tag("integration")
    @Tag("live-network")
    fun `CopyManga search uses the production plugin network route`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val fixture = System.getenv(COPY_MANGA_APK_ENV)?.let(Path::of)
        assumeTrue(
            fixture != null && Files.isRegularFile(fixture),
            "$COPY_MANGA_APK_ENV must point to the pinned CopyManga APK",
        )
        val fixturePath = requireNotNull(fixture)
        assertEquals(COPY_MANGA_APK_SHA256, sha256(fixturePath))
        val appDir = tempDir.resolve("app")
        val extensionDir = appDir.resolve("extensions")
        Files.createDirectories(extensionDir)
        val installedJar = extensionDir.resolve("$COPY_MANGA_PACKAGE.jar")
        val converted = requireNotNull(
            ApkToJarConverter().convert(fixturePath.toFile(), extensionDir.toFile()),
        ) { "Production converter rejected the pinned CopyManga APK" }
        Files.move(converted.toPath(), installedJar, StandardCopyOption.REPLACE_EXISTING)
        JarFile(installedJar.toFile()).use { archive ->
            assertNotNull(
                archive.getJarEntry("simp.txt") ?: archive.getJarEntry("assets/simp.txt"),
                "CopyManga character dictionary was not preserved",
            )
            assertNotNull(
                archive.getJarEntry("simplified.txt") ?: archive.getJarEntry("assets/simplified.txt"),
                "CopyManga lexeme dictionary was not preserved",
            )
        }
        writeExtensionMeta(
            installedJar.toFile(),
            ExtensionMeta(
                pkgName = COPY_MANGA_PACKAGE,
                versionCode = 82,
                versionName = "1.4.82",
                artifactSha256 = "local-live-fixture",
                source = ExtensionOrigin.CONVERTED_APK,
                apkConversionVersion = CURRENT_APK_CONVERSION_VERSION,
                name = "CopyManga",
                language = "zh",
                extensionClass = COPY_MANGA_EXTENSION_CLASS,
            ),
        )
        val preferenceNode =
            java.util.prefs.Preferences.userRoot().node("/mihon-test/copymanga-${System.nanoTime()}")
        val preferenceStore = DesktopPreferenceStore(preferenceNode)
        preferenceStore.getString("network_mode", "SYSTEM").set("SYSTEM")

        val previousInjekt = Injekt
        val context = initDesktopDIForTest(appDir.toFile(), preferenceStore)
        try {
            val manager = Injekt.get<DesktopExtensionManager>()
            val source = requireNotNull(manager.getSource(COPY_MANGA_SOURCE_ID)) {
                "Production extension manager did not load CopyManga"
            }
            val catalogue = source as CatalogueSource
            val network = Injekt.get<DesktopNetworkHelper>()
            assertEquals(
                DesktopPluginNetworkSupport.FULL,
                network.pluginNetworkSupport(listOf(catalogue)),
                "CopyManga must inherit the centrally managed plugin network chain",
            )

            val page = withTimeout(45_000) {
                catalogue.getSearchManga(1, "海贼王", catalogue.getFilterList())
            }

            assertTrue(page.mangas.isNotEmpty(), "CopyManga search returned no results")
            val manga = page.mangas.first()
            assertTrue(
                manga.thumbnail_url?.isNotBlank() == true,
                "CopyManga search result did not expose its cover URL",
            )
            val thumbnailUrl = requireNotNull(manga.thumbnail_url)
            val imageLoader = createDesktopImageLoader(
                context = PlatformContext.INSTANCE,
                networkHelper = network,
                sourceManager = Injekt.get<SourceManager>(),
            )
            try {
                val coverResult = withTimeout(45_000) {
                    imageLoader.execute(
                        ImageRequest.Builder(PlatformContext.INSTANCE)
                            .data(DesktopSourceImage(thumbnailUrl, catalogue.id))
                            .build(),
                    )
                }
                assertInstanceOf(SuccessResult::class.java, coverResult)
            } finally {
                imageLoader.shutdown()
            }

            when (
                val detailsResult = safeSourceCall(timeoutMs = 45_000, tag = "CopyMangaLive") {
                    val details = catalogue.getMangaDetails(manga)
                    details to catalogue.getChapterList(details)
                }
            ) {
                is SourceCallResult.Success -> {
                    assertTrue(
                        detailsResult.value.first.thumbnail_url?.isNotBlank() == true,
                        "CopyManga details did not expose its cover URL",
                    )
                    assertTrue(detailsResult.value.second.isNotEmpty(), "CopyManga details returned no chapters")
                }
                is SourceCallResult.Error -> fail(
                    "Maintained CopyManga details failed: ${detailsResult.error} " +
                        "(${detailsResult.cause?.message.orEmpty()})",
                )
                is SourceCallResult.Timeout -> fail("CopyManga details timed out instead of returning a classified result")
            }
            assertTrue(
                network.routeObservations.value.any {
                    it.scope == COPY_MANGA_PACKAGE && it.host.startsWith("api.")
                },
                "CopyManga request did not record its production plugin route",
            )
        } finally {
            context.closeAndJoin()
            Injekt = previousInjekt
            preferenceStore.clearAndFlush()
            preferenceNode.removeNode()
        }
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val COPY_MANGA_APK_ENV = "MIHON_COPYMANGA_APK"
        const val COPY_MANGA_APK_SHA256 =
            "93fef21408bf6d3e1e8e88051956680f387701379c48ebe8390a556dac675280"
        const val COPY_MANGA_PACKAGE = "eu.kanade.tachiyomi.extension.zh.copymanga"
        const val COPY_MANGA_EXTENSION_CLASS = "$COPY_MANGA_PACKAGE.CopyManga"
        const val COPY_MANGA_SOURCE_ID = 6696312508930833206L
    }
}
