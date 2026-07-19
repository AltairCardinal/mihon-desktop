package mihon.desktop.extension

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class ComicFuryFixtureProvenanceTest {

    @Test
    fun `tracked ComicFury fixture matches its immutable provenance`() {
        val root = repositoryRoot()
        val provenance = Json.parseToJsonElement(Files.readString(root.resolve(PROVENANCE_PATH))).jsonObject

        assertEquals(EXPECTED_PROVENANCE.keys, provenance.keys)
        EXPECTED_PROVENANCE.forEach { (key, value) ->
            assertEquals(value, provenance.getValue(key).jsonPrimitive.content, key)
        }

        val apkPath = root.resolve(provenance.getValue("fixturePath").jsonPrimitive.content)
        assertTrue(Files.isRegularFile(apkPath), "Missing immutable ComicFury fixture: $apkPath")
        assertEquals(APK_SHA256, sha256(apkPath))
        assertEquals(APK_SIZE, Files.size(apkPath))
        assertEquals(EXTENSION_CLASS, ManifestClassExtractor.extractFromApk(apkPath.toFile()))
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun repositoryRoot() = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private companion object {
        const val PROVENANCE_PATH =
            "app-desktop/src/test/resources/extensions/real/keiyoushi-comicfury-1.4.8.provenance.json"
        const val APK_SHA256 = "9403d439eefec8ccff3fa7a3edd810046a12206d944302013bc3f94538b3def7"
        const val APK_SIZE = 41496L
        const val EXTENSION_CLASS = "eu.kanade.tachiyomi.extension.all.comicfury.ExtensionGenerated"
        val EXPECTED_PROVENANCE = mapOf(
            "authorityRef" to "main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8",
            "repository" to "https://github.com/keiyoushi/extensions",
            "repositoryCommit" to "7d5052fb895d086ae2ec6e3cca861146ee3ea0ec",
            "repositoryRootTree" to "35127622c9911a3f7e50c809a71dfc0057843e34",
            "repositoryParent" to "0dae9cf45bef459a60cefb1f3ad1b4eedea3554b",
            "gitBlob" to "8660ce4c0366cd14c031731bf2b90febc5a24d3f",
            "rawJarGitBlob" to "2a9e1e7ac8ab089fd0a2f6544c27319f2f14f672",
            "rawJarSha256" to "1fc1b0fc1a3c9c974ca0ef399658da2b9b3d74561ef79c78a1bc77957ec80d65",
            "license" to "Apache-2.0",
            "fixturePath" to "app-desktop/src/test/resources/extensions/real/keiyoushi-comicfury-1.4.8.apk",
            "sha256" to APK_SHA256,
            "sizeBytes" to APK_SIZE.toString(),
            "packageName" to "eu.kanade.tachiyomi.extension.all.comicfury",
            "versionCode" to "8",
            "versionName" to "1.4.8",
            "extensionLibVersion" to "1.4",
            "extensionClass" to EXTENSION_CLASS,
            "expectedOutcome" to "success",
            "rawUrl" to "https://raw.githubusercontent.com/keiyoushi/extensions/7d5052fb895d086ae2ec6e3cca861146ee3ea0ec/apk/tachiyomi-all.comicfury-v1.4.8.apk",
            "retrievedAt" to "2026-07-20",
        )
    }
}
