package mihon.desktop.extension

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.runBlocking
import mihon.desktop.di.initDesktopDIForTest
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.service.ExtensionInstallRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Isolated
class RealExtensionInstallTransactionTest {
    private var previousHttpAgent: String? = null

    @BeforeEach
    fun clearHttpAgent() {
        previousHttpAgent = System.getProperty(HTTP_AGENT_PROPERTY)
        System.clearProperty(HTTP_AGENT_PROPERTY)
    }

    @AfterEach
    fun restoreHttpAgent() {
        previousHttpAgent?.let { System.setProperty(HTTP_AGENT_PROPERTY, it) }
            ?: System.clearProperty(HTTP_AGENT_PROPERTY)
    }

    @Test
    fun `immutable real APK completes the production install transaction`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val apk = repositoryRoot().resolve(MANHUAGUI_APK).toFile()
        assertEquals(MANHUAGUI_SHA256, sha256(apk))
        val extensionsDirectory = tempDir.resolve("extensions").toFile()
        val diContext = initDesktopDIForTest(
            appDir = tempDir.resolve("app").toFile(),
            preferenceStore = DesktopPreferenceStore(),
        )
        try {
            val installed = install(
                apk = apk,
                artifact = artifact(
                    name = "ManHuaGui",
                    packageName = MANHUAGUI_PACKAGE,
                    versionName = "1.4.28",
                    versionCode = 28,
                    repository = RepositoryIdentity("https://raw.githubusercontent.com/keiyoushi/extensions/repo", "Keiyoushi", "fixture"),
                    downloadUrl = MANHUAGUI_URL,
                ),
                extensionsDirectory = extensionsDirectory,
                authenticator = DesktopArtifactAuthenticator { _, _, _ -> },
            )

            assertTrue(installed.isFile)
            assertEquals(
                CURRENT_APK_CONVERSION_VERSION,
                requireNotNull(readExtensionMeta(installed)).apkConversionVersion,
            )
            val loaded = DesktopExtensionLoader(extensionsDirectory).loadPackage(MANHUAGUI_PACKAGE)
            try {
                assertTrue(loaded.isNotEmpty())
                assertTrue(loaded.all { it.source.javaClass.name.startsWith("$MANHUAGUI_PACKAGE.") })
            } finally {
                loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
            }
        } finally {
            diContext.closeAndJoin()
        }
    }

    /**
     * The archived CopyManga repository declares no redistribution license, so the APK is not
     * committed as a fixture. This opt-in test pins its immutable Git commit/blob identity and
     * verifies the downloaded bytes before executing the same production install transaction.
     *
     * Git commit: c7254c03b37a39c41413c719c8a4e3b2481641ce
     * Git blob: 2eee1fc8419ad4008444cdcaf476ff9d6fbcbc3a
     */
    @Test
    @Tag("integration")
    @Tag("live-network")
    fun `CopyManga v1_4_53 completes the production install transaction`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val extensionsDirectory = tempDir.resolve("extensions").toFile()
        val diContext = initDesktopDIForTest(
            appDir = tempDir.resolve("app").toFile(),
            preferenceStore = DesktopPreferenceStore(),
        )
        try {
            val apk = copyMangaApk(tempDir)
            val installed = install(
                apk = apk,
                artifact = artifact(
                    name = "CopyManga",
                    packageName = COPYMANGA_PACKAGE,
                    versionName = "1.4.53",
                    versionCode = 53,
                    repository = RepositoryIdentity(COPYMANGA_REPOSITORY, "CopyManga", COPYMANGA_FINGERPRINT),
                    sources = listOf(
                        ExtensionSourceDescriptor(
                            id = COPYMANGA_SOURCE_ID,
                            language = "zh",
                            name = "拷贝漫画",
                            baseUrl = "https://www.mangacopy.com",
                        ),
                    ),
                    downloadUrl = COPYMANGA_URL,
                ),
                extensionsDirectory = extensionsDirectory,
                authenticator = DefaultDesktopArtifactAuthenticator,
            )

            assertTrue(installed.isFile)
            val loaded = DesktopExtensionLoader(extensionsDirectory).loadPackage(COPYMANGA_PACKAGE)
            try {
                assertEquals(listOf(COPYMANGA_SOURCE_ID), loaded.map { it.source.id })
            } finally {
                loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
            }
        } finally {
            diContext.closeAndJoin()
        }
    }

    @Test
    @Tag("integration")
    @Tag("live-network")
    fun `installing CopyManga keeps ManHuaGui installed before and after reload`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val manHuaGuiApk = repositoryRoot().resolve(MANHUAGUI_APK).toFile()
        val extensionsDirectory = tempDir.resolve("extensions").toFile()
        val diContext = initDesktopDIForTest(
            appDir = tempDir.resolve("app").toFile(),
            preferenceStore = DesktopPreferenceStore(),
        )
        val copyMangaApk = copyMangaApk(tempDir)
        val artifacts = listOf(
            artifact(
                name = "ManHuaGui",
                packageName = MANHUAGUI_PACKAGE,
                versionName = "1.4.28",
                versionCode = 28,
                repository = RepositoryIdentity(
                    "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
                    "Keiyoushi",
                    "fixture",
                ),
                downloadUrl = MANHUAGUI_URL,
            ),
            artifact(
                name = "CopyManga",
                packageName = COPYMANGA_PACKAGE,
                versionName = "1.4.53",
                versionCode = 53,
                repository = RepositoryIdentity(COPYMANGA_REPOSITORY, "CopyManga", COPYMANGA_FINGERPRINT),
                sources = listOf(
                    ExtensionSourceDescriptor(
                        id = COPYMANGA_SOURCE_ID,
                        language = "zh",
                        name = "CopyManga",
                        baseUrl = "https://www.mangacopy.com",
                    ),
                ),
                downloadUrl = COPYMANGA_URL,
            ),
        )
        val apks = mapOf(
            MANHUAGUI_PACKAGE to manHuaGuiApk,
            COPYMANGA_PACKAGE to copyMangaApk,
        )
        val loader = DesktopExtensionLoader(extensionsDirectory)
        val manager = DesktopExtensionManager(
            loader = loader,
            artifactProvider = { artifact, destination ->
                Files.copy(
                    checkNotNull(apks[artifact.packageName]).toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            },
            artifactAuthenticator = DesktopArtifactAuthenticator { _, _, _ -> },
        )
        try {
            manager.loadAll()
            artifacts.forEach { current ->
                manager.installExtension(current)
                assertEquals(
                    artifacts.takeWhile { it != current }.map { it.packageName }.toSet() + current.packageName,
                    manager.installedExtensions.value.map { it.pkgName }.toSet(),
                )
            }

            manager.loadAll()

            assertEquals(
                setOf(MANHUAGUI_PACKAGE, COPYMANGA_PACKAGE),
                manager.installedExtensions.value.map { it.pkgName }.toSet(),
            )
            assertTrue(loader.diagnostics.isEmpty(), "Extension loader diagnostics: ${loader.diagnostics}")
        } finally {
            manager.close()
            diContext.closeAndJoin()
        }
    }

    private suspend fun install(
        apk: File,
        artifact: ExtensionArtifact,
        extensionsDirectory: File,
        authenticator: DesktopArtifactAuthenticator,
    ): File {
        val port = DesktopExtensionInstallPort(
            extensionsDirectory = extensionsDirectory,
            artifactProvider = { _, destination ->
                Files.copy(apk.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            },
            apkConverter = ApkToJarConverter(),
            loader = DesktopExtensionLoader(extensionsDirectory),
            artifactAuthenticator = authenticator,
            releaseRuntime = {},
            reloadRuntime = { _, _ -> },
        )
        val prepared = port.prepare(ExtensionInstallRequest(artifact))
        try {
            port.validate(prepared)
            port.commit(prepared)
            port.reload(artifact.packageName)
            return extensionArtifactFile(extensionsDirectory, artifact.packageName, "jar")
        } finally {
            port.cleanup(prepared)
        }
    }

    private fun artifact(
        name: String,
        packageName: String,
        versionName: String,
        versionCode: Long,
        repository: RepositoryIdentity,
        sources: List<ExtensionSourceDescriptor> = emptyList(),
        downloadUrl: String,
    ) = ExtensionArtifact(
        name = name,
        packageName = packageName,
        versionName = versionName,
        versionCode = versionCode,
        language = "zh",
        isNsfw = true,
        sources = sources,
        repository = repository,
        downloadUrl = downloadUrl,
        iconUrl = "",
        declaredSha256 = null,
    )

    private fun download(client: OkHttpClient, url: String, destination: File) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "CopyManga fixture download failed: HTTP ${response.code}" }
            response.body.byteStream().use { input -> destination.outputStream().use(input::copyTo) }
        }
    }

    private fun copyMangaApk(tempDir: Path): File {
        val apk = tempDir.resolve("tachiyomi-zh.copymanga-v1.4.53.apk").toFile()
        val localFixture = System.getenv("MIHON_COPYMANGA_APK")?.let(::File)?.takeIf(File::isFile)
        if (localFixture != null) {
            Files.copy(localFixture.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            download(Injekt.get<NetworkHelper>().client, COPYMANGA_URL, apk)
        }
        assertEquals(COPYMANGA_SIZE, apk.length())
        assertEquals(COPYMANGA_SHA256, sha256(apk))
        return apk
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun repositoryRoot() = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private companion object {
        const val MANHUAGUI_APK = "app-desktop/src/test/resources/extensions/real/keiyoushi-manhuagui-1.4.28.apk"
        const val MANHUAGUI_URL =
            "https://raw.githubusercontent.com/keiyoushi/extensions/" +
                "7d5052fb895d086ae2ec6e3cca861146ee3ea0ec/apk/tachiyomi-zh.manhuagui-v1.4.28.apk"
        const val MANHUAGUI_SHA256 = "200cfc4b3b9e98f387824e3cecb13f97f4b0971f8fb678ce49c60aab6856c0c8"
        const val MANHUAGUI_PACKAGE = "eu.kanade.tachiyomi.extension.zh.manhuagui"

        const val COPYMANGA_REPOSITORY = "https://raw.githubusercontent.com/stevenyomi/copymanga/repo"
        const val COPYMANGA_URL =
            "https://raw.githubusercontent.com/stevenyomi/copymanga/" +
                "c7254c03b37a39c41413c719c8a4e3b2481641ce/apk/tachiyomi-zh.copymanga-v1.4.53.apk"
        const val COPYMANGA_SHA256 = "ab331e2c9f7a0197858cc86c43356508ef5203a4122d30c6ec7b3c44c7193675"
        const val COPYMANGA_SIZE = 142451L
        const val COPYMANGA_PACKAGE = "eu.kanade.tachiyomi.extension.zh.copymanga"
        const val COPYMANGA_FINGERPRINT = "0cf45b9c21bb577fdd006b2de8853f070a543ed5e4710e99cfad8b272fa5af5a"
        const val COPYMANGA_SOURCE_ID = 6696312508930833206L
        const val HTTP_AGENT_PROPERTY = "http.agent"
    }
}
