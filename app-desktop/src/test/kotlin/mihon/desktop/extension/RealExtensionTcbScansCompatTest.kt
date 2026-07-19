package mihon.desktop.extension

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.di.initDesktopDIForTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@Isolated
class RealExtensionTcbScansCompatTest {

    @Test
    fun `real TCBScans constructor deletes legacy preference file and logs it`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val previousInjekt = Injekt
        val previousHome = System.getProperty("user.home")
        val previousErr = System.err
        val stderrBytes = ByteArrayOutputStream()
        val capturedErr = PrintStream(stderrBytes, true, Charsets.UTF_8)
        try {
            val home = tempDir.resolve("home")
            System.setProperty("user.home", home.toString())
            System.setErr(capturedErr)
            val provenance = Json.parseToJsonElement(
                Files.readString(repositoryRoot().resolve(PROVENANCE_PATH)),
            ).jsonObject
            assertEquals(REPOSITORY_COMMIT, provenance.getValue("repositoryCommit").jsonPrimitive.content)
            assertEquals(GIT_BLOB, provenance.getValue("gitBlob").jsonPrimitive.content)
            assertEquals(APK_SHA256, provenance.getValue("sha256").jsonPrimitive.content)

            val apkPath = repositoryRoot().resolve(APK_PATH)
            assertTrue(Files.isRegularFile(apkPath), "Missing immutable TCBScans fixture: $apkPath")
            assertEquals(APK_SIZE, Files.size(apkPath))
            assertEquals(APK_SHA256, sha256(apkPath))
            assertEquals(EXTENSION_CLASS, ManifestClassExtractor.extractFromApk(apkPath.toFile()))
            val legacyFile = home.resolve(".mihon/shared_prefs/source_${SOURCE_ID}_updateTime.xml")
            Files.createDirectories(legacyFile.parent)
            Files.writeString(legacyFile, "<map />")

            val diContext = initDesktopDIForTest(
                appDir = tempDir.resolve("app").toFile(),
                preferenceStore = DesktopPreferenceStore(),
            )
            try {
                val sourcePreferences = Injekt.get<Application>().getSharedPreferences(
                    "source_$SOURCE_ID",
                    Context.MODE_PRIVATE,
                )
                val hadLegacyFlag = sourcePreferences.contains(LEGACY_FLAG)
                val previousLegacyFlag = sourcePreferences.getBoolean(LEGACY_FLAG, false)
                sourcePreferences.edit().putBoolean(LEGACY_FLAG, false).commit()
                try {
                    val convertedJar = ApkToJarConverter().convert(apkPath.toFile(), tempDir.toFile())
                    assertNotNull(convertedJar, "Production converter rejected the immutable TCBScans APK")
                    val jar = requireNotNull(convertedJar)
                    writeExtensionMeta(
                        jar,
                        ExtensionMeta(
                            pkgName = PACKAGE_NAME,
                            versionCode = VERSION_CODE,
                            versionName = VERSION_NAME,
                            artifactSha256 = APK_SHA256,
                            source = ExtensionOrigin.CONVERTED_APK,
                            name = "TCB Scans",
                            language = "en",
                            extensionClass = EXTENSION_CLASS,
                        ),
                    )
                    val loader = DesktopExtensionLoader(tempDir.toFile())
                    val loaded = loader.loadFromSingleJar(jar)
                    try {
                        assertTrue(loader.diagnostics.isEmpty(), "TCBScans loader diagnostics: ${loader.diagnostics}")
                        if (loaded.isEmpty()) exposeConstructorFailure(jar)
                        capturedErr.flush()
                        assertEquals(
                            1,
                            loaded.size,
                            "TCBScans did not load through production Desktop DI: ${stderrBytes.toString(Charsets.UTF_8)}",
                        )
                        assertFalse(Files.exists(legacyFile), "TCBScans did not delete its legacy preference file")
                        capturedErr.flush()
                        val stderr = stderrBytes.toString(Charsets.UTF_8)
                        assertTrue(stderr.lineSequence().any { it == EXPECTED_LOG }, "Unexpected stderr: $stderr")
                    } finally {
                        loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
                    }
                } finally {
                    sourcePreferences.edit().apply {
                        if (hadLegacyFlag) putBoolean(LEGACY_FLAG, previousLegacyFlag) else remove(LEGACY_FLAG)
                    }.commit()
                }
            } finally {
                diContext.closeAndJoin()
            }
        } finally {
            Injekt = previousInjekt
            if (previousHome == null) System.clearProperty("user.home") else System.setProperty("user.home", previousHome)
            System.setErr(previousErr)
            capturedErr.close()
        }
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun exposeConstructorFailure(jar: java.io.File): Nothing {
        ExtensionClassLoader(jar.toURI().toURL(), DesktopExtensionLoader::class.java.classLoader).use { classLoader ->
            try {
                classLoader.loadClass(EXTENSION_CLASS).getDeclaredConstructor().newInstance()
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
        error("Production loader returned no TCBScans source without a constructor failure")
    }

    private fun repositoryRoot() =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private companion object {
        const val PROVENANCE_PATH =
            "app-desktop/src/test/resources/extensions/real/keiyoushi-tcbscans-1.4.12.provenance.json"
        const val APK_PATH = "app-desktop/src/test/resources/extensions/real/keiyoushi-tcbscans-1.4.12.apk"
        const val REPOSITORY_COMMIT = "04bd989e5ff1f9dda0148c0aad6bac0889e03edb"
        const val GIT_BLOB = "12ed843aee2449b8b8793857b874efac0cf98957"
        const val APK_SHA256 = "bf5a2bfd907d54c1ab5438f09a3a45693b597fcc27fc914241d9cd3e491ce1d2"
        const val APK_SIZE = 29_544L
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.en.tcbscans"
        const val VERSION_CODE = 12L
        const val VERSION_NAME = "1.4.12"
        const val EXTENSION_CLASS = "eu.kanade.tachiyomi.extension.en.tcbscans.ExtensionGenerated"
        const val SOURCE_ID = 1_435_116_756_378_369_709L
        const val LEGACY_FLAG = "legacy_updateTime_removed"
        const val EXPECTED_LOG = "D/TCB Scans: Deleting source_${SOURCE_ID}_updateTime.xml"
    }
}
