package mihon.desktop.extension

import cafe.adriel.voyager.core.screen.Screen
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.ui.extension.ExtensionDetailsScreen
import mihon.desktop.ui.settings.DesktopDirectoryOpener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopExtensionProductBaselineTest {
    @Test
    fun `authority baseline records Android and Desktop call chains`() {
        val baseline = repositoryRoot().resolve(AUTHORITY_BASELINE)

        assertTrue(Files.isRegularFile(baseline), "Missing authority baseline $AUTHORITY_BASELINE")
        val content = Files.readString(baseline)
        REQUIRED_AUTHORITY_SYMBOLS.forEach { symbol ->
            assertTrue(content.contains(symbol), "Authority baseline must map $symbol")
        }
    }

    @Test
    fun `every compat API has a real fixture and protection test`() {
        val root = repositoryRoot()
        val evidence = loadCompatEvidence("extensions/compat-evidence.json")

        assertTrue(evidence.isNotEmpty(), "Compat evidence must contain at least one observed API")
        assertTrue(hasUniqueEvidenceIdentities(evidence), "Compat evidence identities must be unique")
        assertFalse(
            hasUniqueEvidenceIdentities(evidence + evidence.first()),
            "A duplicated compat evidence identity must be rejected",
        )
        evidence.forEach { item ->
            assertTrue(item.symbol.isNotBlank(), "Compat evidence symbol must not be blank")
            assertTrue(item.fixture.isNotBlank(), "${item.symbol}: fixture must not be blank")
            val versionSeparator = item.fixture.lastIndexOf('@')
            assertTrue(
                versionSeparator in 1..<item.fixture.lastIndex,
                "${item.symbol}: fixture must use artifact-path@digest",
            )
            assertTrue(item.status in COMPAT_STATUSES, "${item.symbol}: invalid status ${item.status}")
            assertTrue(item.removalCondition.isNotBlank(), "${item.symbol}: removalCondition must not be blank")
            val artifact = root.resolve(item.fixture.substringBeforeLast('@')).normalize()
            assertTrue(artifact.startsWith(root), "${item.symbol}: artifact must remain inside the repository")
            assertTrue(Files.isRegularFile(artifact), "${item.symbol}: missing real artifact $artifact")
            assertTrue(
                Files.isRegularFile(root.resolve(item.test)),
                "${item.symbol}: missing protection test ${item.test}",
            )
        }
    }

    @Test
    fun `APK conversion remains a Desktop product capability`(@TempDir directory: Path) {
        val apk = directory.resolve("representative.apk").toFile()
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(Base64.getDecoder().decode(MINIMAL_DEX_BASE64))
            zip.closeEntry()
        }

        val converted = ApkToJarConverter().convert(apk, directory.toFile())

        assertTrue(converted?.exists() == true)
        assertTrue((converted?.length() ?: 0L) > 0L)
    }

    @Test
    fun `extension replacement remains atomic at the product boundary`(@TempDir directory: Path) {
        val destination = directory.resolve("extension.jar").toFile().also { it.writeText("installed") }
        val candidate = directory.resolve("candidate.jar").toFile().also { it.writeText("update") }

        DefaultDesktopExtensionFileSystem.replaceFromSnapshot(candidate, destination)

        assertEquals("update", destination.readText())
        assertEquals("update", candidate.readText())
        assertFalse(directory.toFile().listFiles().orEmpty().any { it.name.endsWith(".replace.tmp") })
    }

    @Test
    fun `extension details route and file tool remain available`(@TempDir directory: Path) {
        val extensionDirectory = directory.resolve("extensions").toFile()
        val jar = File(extensionDirectory, "fixture.jar")
        val screen = ExtensionDetailsScreen(jar.absolutePath)
        var launchedDirectory: File? = null

        val opened = DesktopDirectoryOpener.open(extensionDirectory) { launchedDirectory = it }

        assertInstanceOf(Screen::class.java, screen)
        assertEquals(jar.absolutePath, screen.jarPath)
        assertTrue(opened)
        assertTrue(extensionDirectory.isDirectory)
        assertEquals(extensionDirectory.canonicalFile, launchedDirectory?.canonicalFile)
    }

    private fun loadCompatEvidence(resourceName: String): List<CompatEvidence> {
        val path = repositoryRoot().resolve("app-desktop/src/test/resources").resolve(resourceName)
        assertTrue(Files.isRegularFile(path), "Missing compat evidence resource $resourceName")
        return Json.parseToJsonElement(Files.readString(path)).jsonArray.mapIndexed { index, element ->
            val item = element.jsonObject
            assertEquals(COMPAT_FIELDS, item.keys, "Compat evidence item $index has an unexpected schema")
            CompatEvidence(
                symbol = item.getValue("symbol").jsonPrimitive.content,
                fixture = item.getValue("fixture").jsonPrimitive.content,
                test = item.getValue("test").jsonPrimitive.content,
                status = item.getValue("status").jsonPrimitive.content,
                removalCondition = item.getValue("removalCondition").jsonPrimitive.content,
            )
        }
    }

    private fun repositoryRoot() =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private fun hasUniqueEvidenceIdentities(evidence: List<CompatEvidence>): Boolean =
        evidence.size == evidence.map { Triple(it.symbol, it.fixture, it.test) }.toSet().size

    private data class CompatEvidence(
        val symbol: String,
        val fixture: String,
        val test: String,
        val status: String,
        val removalCondition: String,
    )

    private companion object {
        const val AUTHORITY_BASELINE = "docs/roadmap/source-extension-authority-baseline.md"
        const val MINIMAL_DEX_BASE64 =
            "ZGV4CjAzNQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAcAAAAHhWNBIAAAAAAAAAAHAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAcAAAAAEAAAAAAAAAAQAAAAAAAAA="
        val COMPAT_FIELDS = setOf("symbol", "fixture", "test", "status", "removalCondition")
        val COMPAT_STATUSES = setOf("required", "unsupported")
        val REQUIRED_AUTHORITY_SYMBOLS =
            setOf(
                "`ExtensionApi`",
                "`ExtensionManager`",
                "`ExtensionLoader`",
                "`SourcesScreenModel`",
                "`GlobalSearchScreenModel`",
                "`ExtensionsScreenModel`",
                "`DesktopExtensionApi`",
                "`DesktopExtensionManager`",
                "`DesktopExtensionLoader`",
                "`BrowseTab`",
                "`GlobalSearchScreen`",
                "`ExtensionListScreen`",
            )
    }
}
