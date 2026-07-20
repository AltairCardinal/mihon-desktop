package mihon.desktop.extension

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.service.ExtensionInstallFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopExtensionArtifactAuthenticityTest {
    @Test
    fun `matching declared digest cannot authenticate an APK signed by another repository key`(
        @TempDir directory: Path,
    ) = runBlocking {
        val apk = repositoryRoot().resolve(MANGADEX_APK).toFile()
        assertEquals(MANGADEX_SHA256, apk.sha256())
        val manager = DesktopExtensionManager(
            loader = DesktopExtensionLoader(directory.toFile()),
            artifactProvider = { _, destination -> apk.copyTo(destination, overwrite = true) },
        )

        val terminal = try {
            manager.installExtension(artifact(ATTACKER_FINGERPRINT, MANGADEX_SHA256))
        } finally {
            manager.close()
        }

        val failure = assertInstanceOf(ExtensionInstallState.Failed::class.java, terminal)
        val authentication = assertInstanceOf(AppError.Authentication::class.java, failure.error)
        assertEquals("Extension artifact signer does not match repository identity", authentication.cause?.message)
        assertFalse(directory.resolve("$MANGADEX_PACKAGE.jar").toFile().exists())
        assertFalse(directory.resolve("$MANGADEX_PACKAGE.meta.json").toFile().exists())
    }

    @Test
    fun `repository fingerprint matching the real APK signer passes authenticity verification`() {
        val apk = repositoryRoot().resolve(MANGADEX_APK).toFile()

        assertDoesNotThrow {
            DefaultDesktopArtifactAuthenticator.authenticate(apk, MANGADEX_SIGNER_SHA256, isApk = true)
        }
    }

    @Test
    fun `unsigned native JAR fails closed with typed authentication error`(@TempDir directory: Path) {
        val jar = directory.resolve("unsigned.jar").toFile().also {
            it.writeBytes(nativeJarBytes())
        }

        val failure = assertThrows(ExtensionInstallFailure::class.java) {
            DefaultDesktopArtifactAuthenticator.authenticate(jar, MANGADEX_SIGNER_SHA256, isApk = false)
        }

        assertInstanceOf(AppError.Authentication::class.java, failure.error)
    }

    @Test
    fun `native JAR signed by repository certificate remains supported`(@TempDir directory: Path) {
        val jar = directory.resolve("signed.jar").toFile().also { it.writeBytes(nativeJarBytes()) }
        val keyStore = directory.resolve("repository.p12")
        val certificate = directory.resolve("repository.cer")
        runJdkTool(
            "keytool",
            "-genkeypair", "-alias", "repository", "-keyalg", "RSA", "-storetype", "PKCS12",
            "-keystore", keyStore.toString(), "-storepass", TEST_KEY_PASSWORD, "-keypass", TEST_KEY_PASSWORD,
            "-dname", "CN=Mihon Desktop Test Repository", "-validity", "3650", "-noprompt",
        )
        runJdkTool(
            "jarsigner",
            "-keystore", keyStore.toString(), "-storepass", TEST_KEY_PASSWORD, "-keypass", TEST_KEY_PASSWORD,
            jar.absolutePath, "repository",
        )
        runJdkTool(
            "keytool",
            "-exportcert", "-alias", "repository", "-keystore", keyStore.toString(),
            "-storepass", TEST_KEY_PASSWORD, "-file", certificate.toString(),
        )
        val signer = certificate.toFile().inputStream().use { input ->
            java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(input) as java.security.cert.X509Certificate
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(signer.encoded)
            .joinToString("") { "%02x".format(it) }

        assertDoesNotThrow {
            DefaultDesktopArtifactAuthenticator.authenticate(jar, fingerprint, isApk = false)
        }
    }

    @Test
    fun `declared digest mismatch is rejected before artifact authentication`(@TempDir directory: Path) = runBlocking {
        val apk = repositoryRoot().resolve(MANGADEX_APK).toFile()
        val manager = DesktopExtensionManager(
            loader = DesktopExtensionLoader(directory.toFile()),
            artifactProvider = { _, destination -> apk.copyTo(destination, overwrite = true) },
        )

        val terminal = try {
            manager.installExtension(artifact(ATTACKER_FINGERPRINT, declaredSha256 = "0".repeat(64)))
        } finally {
            manager.close()
        }

        val failure = assertInstanceOf(ExtensionInstallState.Failed::class.java, terminal)
        val malformed = assertInstanceOf(AppError.MalformedData::class.java, failure.error)
        assertEquals("Downloaded extension digest mismatch", malformed.cause?.message)
    }

    private fun artifact(repositoryFingerprint: String, declaredSha256: String) = ExtensionArtifact(
        name = "MangaDex",
        packageName = MANGADEX_PACKAGE,
        versionName = "1.4.211",
        versionCode = 211,
        language = "all",
        isNsfw = false,
        sources = emptyList(),
        repository = RepositoryIdentity(
            baseUrl = "https://repo.example",
            name = "Repository",
            signingKeyFingerprint = repositoryFingerprint,
        ),
        downloadUrl = "https://repo.example/apk/mangadex.apk",
        iconUrl = "",
        declaredSha256 = declaredSha256,
    )

    private fun nativeJarBytes() = java.io.ByteArrayOutputStream().use { bytes ->
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("example/Source.class"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("META-INF/services/example.Source"))
            zip.write("example.Source".toByteArray())
            zip.closeEntry()
        }
        bytes.toByteArray()
    }

    private fun runJdkTool(name: String, vararg arguments: String) {
        val executable = Path.of(
            System.getProperty("java.home"),
            "bin",
            name + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "",
        )
        val process = ProcessBuilder(executable.toString(), *arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "$name failed: $output" }
    }

    private fun repositoryRoot() = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private fun File.sha256(): String = inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MANGADEX_APK =
            "app-desktop/src/test/resources/extensions/real/keiyoushi-mangadex-1.4.211.apk"
        const val MANGADEX_SHA256 = "eff4ee157380f0cd4f19a2150f93220ca7a9bcd4e5d570736f639230ef338236"
        const val MANGADEX_SIGNER_SHA256 = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
        const val ATTACKER_FINGERPRINT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val MANGADEX_PACKAGE = "eu.kanade.tachiyomi.extension.all.mangadex"
        const val TEST_KEY_PASSWORD = "changeit"
    }
}
