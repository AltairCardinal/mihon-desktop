package mihon.desktop.extension

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import mihon.desktop.di.initDesktopDIForTest
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.service.ExtensionInstallState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import uy.kohesive.injekt.Injekt

@Isolated
class SignedManifestExtensionInstallTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `signed real obfuscated WNACG installs and reloads through production manager`() = withManager { manager ->
        val result = manager.installExtension(artifact())
        assertInstanceOf(ExtensionInstallState.Installed::class.java, result, result.toString())
        assertEquals(listOf(PACKAGE), manager.installedExtensions.value.map { it.pkgName })
        assertNotNull(manager.getSource(SOURCE_ID))
        assertFalse(manager.getSource(SOURCE_ID)!!.javaClass.name.startsWith(PACKAGE))
        manager.loadAll()
        assertNotNull(manager.getSource(SOURCE_ID))
        assertEquals(
            "keiyoushi.source.Generated",
            readExtensionMeta(File(directory, "extensions/$PACKAGE.jar"))?.extensionClass,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["package", "missing-sources", "wrong-source", "extra-source", "signer"])
    fun `signed artifact rejects conflicting catalog identity`(case: String) = withManager { manager ->
        val expected = artifact()
        val request = when (case) {
            "package" -> expected.copy(packageName = "eu.kanade.tachiyomi.extension.zh.other")
            "missing-sources" -> expected.copy(sources = emptyList())
            "wrong-source" -> expected.copy(sources = listOf(expected.sources.single().copy(id = 123L)))
            "extra-source" -> expected.copy(sources = expected.sources + expected.sources.single().copy(id = 123L))
            else -> expected.copy(repository = expected.repository.copy(signingKeyFingerprint = "0".repeat(64)))
        }
        assertInstanceOf(ExtensionInstallState.Failed::class.java, manager.installExtension(request))
        assertTrue(manager.installedExtensions.value.isEmpty())
        assertFalse(File(directory, "extensions/${request.packageName}.jar").exists())
    }

    @ParameterizedTest
    @ValueSource(strings = ["unsigned", "tampered"])
    fun `real artifact must retain its original verified signature`(case: String) {
        val modified = File(directory, "$case.jar")
        ZipFile(fixture()).use { input ->
            ZipOutputStream(modified.outputStream()).use { output ->
                input.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    if (case == "unsigned" && entry.name.startsWith("META-INF/")) return@forEach
                    output.putNextEntry(ZipEntry(entry.name))
                    val bytes = input.getInputStream(entry).use { it.readBytes() }
                    output.write(
                        if (case == "tampered" && entry.name == "AndroidManifest.xml") bytes + ' '.code.toByte() else bytes,
                    )
                    output.closeEntry()
                }
            }
        }
        withManager(modified) { manager ->
            val state = manager.installExtension(artifact())
            assertInstanceOf(ExtensionInstallState.Failed::class.java, state)
            assertInstanceOf(AppError.Authentication::class.java, (state as ExtensionInstallState.Failed).error)
            assertTrue(manager.installedExtensions.value.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "malformed", "missing-package", "missing-entry", "unknown-entry", "host-entry", "partial-entry", "doctype",
            "binary-axml",
        ],
    )
    fun `manifest identity fails closed before scan fallback`(case: String) {
        val modified = File(directory, "manifest-$case.jar")
        ZipFile(fixture()).use { input ->
            val original = input.getInputStream(input.getEntry("AndroidManifest.xml"))
                .use { it.readBytes().toString(Charsets.UTF_8) }
            val manifest = when (case) {
                "malformed" -> "<manifest"
                "missing-package" -> original.replace("package=\"$PACKAGE\"", "")
                "missing-entry" -> original.replace("tachiyomi.extension.class", "absent")
                "unknown-entry" -> original.replace("keiyoushi.source.Generated", "$PACKAGE.Missing")
                "host-entry" -> original.replace("keiyoushi.source.Generated", FixtureNewSource::class.java.name)
                "partial-entry" -> original.replace(
                    "keiyoushi.source.Generated",
                    "keiyoushi.source.Generated:$PACKAGE.Missing",
                )
                "doctype" -> original.replace(
                    "<!-- Generated",
                    "<!DOCTYPE manifest [<!ENTITY xxe SYSTEM 'file:///never-read'>]><!-- Generated",
                )
                else -> original
            }
            val manifestBytes = if (case == "binary-axml") {
                AXmlBuilder().buildManifest(PACKAGE, "keiyoushi.source.Generated")
            } else {
                manifest.toByteArray(Charsets.UTF_8)
            }
            ZipOutputStream(modified.outputStream()).use { output ->
                input.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    // Mutated payload cannot retain the original JarFile verifier metadata.
                    // These cases deliberately isolate identity validation after authentication.
                    if (entry.name.startsWith("META-INF/")) return@forEach
                    output.putNextEntry(ZipEntry(entry.name))
                    output.write(
                        if (entry.name == "AndroidManifest.xml") {
                            manifestBytes
                        } else {
                            input.getInputStream(entry).use { it.readBytes() }
                        },
                    )
                    output.closeEntry()
                }
            }
        }
        // Authentication is tested separately with the immutable signed fixture. Here only the
        // signature gate is bypassed so malformed signed-payload identities reach production validation.
        withManager(modified, DesktopArtifactAuthenticator { _, _, _ -> }) { manager ->
            assertInstanceOf(ExtensionInstallState.Failed::class.java, manager.installExtension(artifact()))
            assertTrue(manager.installedExtensions.value.isEmpty())
        }
    }

    private fun withManager(
        input: File = fixture(),
        authenticator: DesktopArtifactAuthenticator = DefaultDesktopArtifactAuthenticator,
        block: suspend (DesktopExtensionManager) -> Unit,
    ) = runBlocking {
        val previous = Injekt
        val preferences = IsolatedDesktopPreferenceStore.create()
        val context = initDesktopDIForTest(File(directory, "app"), preferences.store)
        val manager = DesktopExtensionManager(
            loader = DesktopExtensionLoader(File(directory, "extensions")),
            artifactProvider = { _, destination -> input.copyTo(destination, overwrite = true) },
            artifactAuthenticator = authenticator,
        )
        try {
            block(manager)
        } finally {
            manager.close()
            context.closeAndJoin()
            Injekt = previous
            preferences.close()
        }
    }

    private fun fixture(): File {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app-desktop").isDirectory }
        return File(root, "app-desktop/src/test/resources/extensions/real/keiyoushi-wnacg-1.6.0.jar").also {
            val digest = MessageDigest.getInstance("SHA-256").digest(it.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) }
            assertEquals(SHA256, digest)
        }
    }

    private fun artifact() = ExtensionArtifact(
        name = "WNACG",
        packageName = PACKAGE,
        versionName = "1.6.0",
        versionCode = 106000L,
        language = "zh",
        isNsfw = true,
        sources = listOf(ExtensionSourceDescriptor(SOURCE_ID, "zh", "WNACG", "https://www.wnacg.com")),
        repository = RepositoryIdentity(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
            "Keiyoushi",
            FINGERPRINT,
        ),
        downloadUrl = "https://github.com/keiyoushi/extensions/releases/download/f303b9c/tachiyomi-zh.wnacg-v1.6.0.jar",
        iconUrl = "",
        declaredSha256 = null,
    )

    private companion object {
        const val PACKAGE = "eu.kanade.tachiyomi.extension.zh.wnacg"
        const val SOURCE_ID = 6551136894818591762L
        const val FINGERPRINT = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
        const val SHA256 = "be94cbb32f8e11be90e0c2dda79971b5da2a1330f6513d87ff75d031859ad9ad"
    }
}
