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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
            manager.installExtension(
                ExtensionArtifact(
                    name = "MangaDex",
                    packageName = MANGADEX_PACKAGE,
                    versionName = "1.4.211",
                    versionCode = 211,
                    language = "all",
                    isNsfw = false,
                    sources = emptyList(),
                    repository = RepositoryIdentity(
                        baseUrl = "https://repo.example",
                        name = "Compromised index",
                        signingKeyFingerprint = "attacker-controlled-fingerprint",
                    ),
                    downloadUrl = "https://repo.example/apk/mangadex.apk",
                    iconUrl = "",
                    declaredSha256 = MANGADEX_SHA256,
                ),
            )
        } finally {
            manager.close()
        }

        val failure = assertInstanceOf(ExtensionInstallState.Failed::class.java, terminal)
        assertInstanceOf(AppError.Authentication::class.java, failure.error)
        assertFalse(directory.resolve("$MANGADEX_PACKAGE.jar").toFile().exists())
        assertFalse(directory.resolve("$MANGADEX_PACKAGE.meta.json").toFile().exists())
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
        const val MANGADEX_PACKAGE = "eu.kanade.tachiyomi.extension.all.mangadex"
    }
}
