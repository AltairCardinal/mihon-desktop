package mihon.desktop.extension

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mihon.desktop.domain.fakes.FakeExtensionRepoRepository
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.service.ExtensionInstallState
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopExtensionInstallTransactionTest {
    @Test
    fun `jvm jar installs through production api loader and manager`(@TempDir directory: Path) = runBlocking {
        val bytes = sourceJar(FixtureNewSource::class.java)

        val result = install(bytes, directory, FixtureNewSource.ID)

        assertInstanceOf(DesktopExtensionApi.InstallResult.Success::class.java, result)
        val installed = directory.resolve("$PACKAGE.jar").toFile()
        assertArrayEquals(bytes, installed.readBytes())
        val loaded = DesktopExtensionLoader(directory.toFile()).loadPackage(PACKAGE)
        try {
            assertEquals(FixtureNewSource.ID, loaded.single().source.id)
        } finally {
            loaded.map { it.classLoader }.distinct().forEach { (it as? AutoCloseable)?.close() }
        }
        assertNoTransactionFiles(directory)
    }

    @Test
    fun `dex apk converts to jar and records converted origin`(@TempDir directory: Path) = runBlocking {
        val apk = zip("classes.dex" to Base64.getDecoder().decode(MINIMAL_DEX_BASE64))

        val result = install(apk, directory)

        assertInstanceOf(DesktopExtensionApi.InstallResult.Success::class.java, result)
        val installed = directory.resolve("$PACKAGE.jar").toFile()
        assertTrue(installed.isFile)
        assertEquals(ExtensionOrigin.CONVERTED_APK, readExtensionMeta(installed)?.source)
        assertNoTransactionFiles(directory)
    }

    @Test
    fun `corrupt zip preserves installed jar and sidecar`(@TempDir directory: Path) = runBlocking {
        val snapshot = installedSnapshot(directory)

        val result = install("broken zip".toByteArray(), directory, FixtureNewSource.ID)

        assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
        snapshot.assertUnchanged()
        assertNoTransactionFiles(directory)
    }

    @Test
    fun `wrong package jar leaves installed artifact and metadata unchanged`(@TempDir directory: Path) = runBlocking {
        val snapshot = installedSnapshot(directory)

        val result = install(fakeJar("wrong.package.NewSource"), directory, FixtureNewSource.ID)

        assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
        snapshot.assertUnchanged()
        assertNoTransactionFiles(directory)
    }

    @Test
    fun `dex conversion failure preserves installed jar and removes temporary files`(@TempDir directory: Path) = runBlocking {
        val snapshot = installedSnapshot(directory)

        val result = install(zip("classes.dex" to byteArrayOf(1, 2, 3)), directory, FixtureNewSource.ID)

        assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
        snapshot.assertUnchanged()
        assertNoTransactionFiles(directory)
    }

    @Test
    fun `declared digest mismatch preserves installed jar and sidecar`(@TempDir directory: Path) = runBlocking {
        val snapshot = installedSnapshot(directory)

        val result = install(sourceJar(FixtureNewSource::class.java), directory, FixtureNewSource.ID, "0000")

        assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
        snapshot.assertUnchanged()
        assertNoTransactionFiles(directory)
    }

    @Test
    fun `reload failure restores old artifact metadata and runtime`(@TempDir directory: Path) = runBlocking {
        val snapshot = installedSnapshot(directory)
        val loader = FailOnceLoader(directory.toFile())
        val manager = DesktopExtensionManager(loader).also { it.loadAll() }
        loader.failNextReload = true

        val terminal = manager.installExtension(
            artifact = artifact(FixtureNewSource.ID),
            artifactProvider = { _, destination -> destination.writeBytes(sourceJar(FixtureNewSource::class.java)) },
        )

        try {
            assertInstanceOf(ExtensionInstallState.Failed::class.java, terminal)
            snapshot.assertUnchanged()
            assertNotNull(manager.getSource(FixtureOldSource.ID))
            assertNull(manager.getSource(FixtureNewSource.ID))
            assertNoTransactionFiles(directory)
        } finally {
            manager.close()
        }
    }

    private fun api() = DesktopExtensionApi(
        client = OkHttpClient(),
        json = Json { ignoreUnknownKeys = true },
        extensionRepoRepository = FakeExtensionRepoRepository(),
    )

    private suspend fun install(
        bytes: ByteArray,
        directory: Path,
        sourceId: Long? = null,
        digest: String? = null,
    ): DesktopExtensionApi.InstallResult = MockWebServer().use { server ->
        server.start()
        server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
        api().installExtension(available(server, sourceId, digest), directory.toFile())
    }

    private fun available(server: MockWebServer, sourceId: Long?, digest: String?) = DesktopAvailableExtension(
        name = "Expected",
        pkgName = PACKAGE,
        versionName = "1.4.2",
        versionCode = 2,
        libVersion = 1.4,
        lang = "en",
        isNsfw = false,
        jarUrl = server.url("/extension").toString(),
        iconUrl = "",
        repoUrl = "https://repo.example",
        repoName = "Repository",
        repoFingerprint = "fingerprint",
        declaredSha256 = digest,
        sources = sourceId?.let { listOf(DesktopAvailableSource(it, "en", "Fixture", "https://example.com")) }.orEmpty(),
    )

    private fun artifact(sourceId: Long) = ExtensionArtifact(
        name = "Expected",
        packageName = PACKAGE,
        versionName = "1.4.2",
        versionCode = 2,
        language = "en",
        isNsfw = false,
        sources = listOf(ExtensionSourceDescriptor(sourceId, "en", "Fixture", "https://example.com")),
        repository = RepositoryIdentity("https://repo.example", "Repository", "fingerprint"),
        downloadUrl = "https://repo.example/extension.jar",
        iconUrl = "",
        declaredSha256 = null,
    )

    private fun installedSnapshot(directory: Path): Snapshot {
        val jar = directory.resolve("$PACKAGE.jar").toFile().also { it.writeBytes(sourceJar(FixtureOldSource::class.java)) }
        writeExtensionMeta(
            jar,
            ExtensionMeta(
                pkgName = PACKAGE,
                versionCode = 1,
                versionName = "1.4.1",
                repoUrl = "https://repo.example",
                repoName = "Repository",
                repoFingerprint = "fingerprint",
                artifactSha256 = jar.readBytes().sha256(),
            ),
        )
        return Snapshot(jar, jar.readBytes(), sidecar(jar).readBytes())
    }

    private fun sourceJar(source: Class<out Source>): ByteArray {
        val path = source.name.replace('.', '/') + ".class"
        val classBytes = checkNotNull(source.classLoader.getResourceAsStream(path)).use { it.readBytes() }
        return zip(path to classBytes, SERVICE to source.name.toByteArray())
    }

    private fun fakeJar(provider: String) = zip(
        provider.replace('.', '/') + ".class" to byteArrayOf(0),
        SERVICE to provider.toByteArray(),
    )

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        bytes.toByteArray()
    }

    private fun sidecar(jar: File) = File(jar.parentFile, "${jar.nameWithoutExtension}.meta.json")

    private fun assertNoTransactionFiles(directory: Path) {
        assertTrue(directory.toFile().listFiles().orEmpty().none { ".tmp" in it.name || ".backup" in it.name })
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    private data class Snapshot(val jar: File, val jarBytes: ByteArray, val metaBytes: ByteArray) {
        fun assertUnchanged() {
            assertArrayEquals(jarBytes, jar.readBytes())
            assertArrayEquals(metaBytes, File(jar.parentFile, "${jar.nameWithoutExtension}.meta.json").readBytes())
        }
    }

    private class FailOnceLoader(directory: File) : DesktopExtensionLoader(directory) {
        var failNextReload = false

        override fun loadPackage(packageName: String): List<LoadedExtension> {
            if (failNextReload) {
                failNextReload = false
                error("fake reload failure")
            }
            return super.loadPackage(packageName)
        }
    }

    private companion object {
        const val PACKAGE = "mihon.desktop.extension"
        const val SERVICE = "META-INF/services/eu.kanade.tachiyomi.source.Source"
        const val MINIMAL_DEX_BASE64 =
            "ZGV4CjAzNQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAcAAAAHhWNBIAAAAAAAAAAHAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAcAAAAAEAAAAAAAAAAQAAAAAAAAA="
    }
}

class FixtureOldSource : Source {
    override val id = ID
    override val name = "Old"
    override val lang = "en"
    override suspend fun getMangaDetails(manga: SManga) = manga
    override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
    override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()

    companion object { const val ID = 7001L }
}

class FixtureNewSource : Source {
    override val id = ID
    override val name = "New"
    override val lang = "en"
    override suspend fun getMangaDetails(manga: SManga) = manga
    override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
    override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()

    companion object { const val ID = 7002L }
}
