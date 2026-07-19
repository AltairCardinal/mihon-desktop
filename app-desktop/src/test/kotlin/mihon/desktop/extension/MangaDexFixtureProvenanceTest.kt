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

class MangaDexFixtureProvenanceTest {

    @Test
    fun `tracked MangaDex fixture matches its immutable provenance`() {
        val root = repositoryRoot()
        val provenance = Json.parseToJsonElement(Files.readString(root.resolve(PROVENANCE_PATH))).jsonObject

        assertEquals(EXPECTED_PROVENANCE.keys, provenance.keys)
        EXPECTED_PROVENANCE.forEach { (key, value) ->
            assertEquals(value, provenance.getValue(key).jsonPrimitive.content, key)
        }

        val apkPath = root.resolve(provenance.getValue("fixturePath").jsonPrimitive.content)
        assertTrue(Files.isRegularFile(apkPath), "Missing immutable MangaDex fixture: $apkPath")
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
            "app-desktop/src/test/resources/extensions/real/keiyoushi-mangadex-1.4.211.provenance.json"
        const val APK_SHA256 = "eff4ee157380f0cd4f19a2150f93220ca7a9bcd4e5d570736f639230ef338236"
        const val APK_SIZE = 111390L
        const val EXTENSION_CLASS = "eu.kanade.tachiyomi.extension.all.mangadex.ExtensionGenerated"
        val EXPECTED_PROVENANCE = mapOf(
            "authorityRef" to "main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8",
            "repository" to "https://github.com/keiyoushi/extensions",
            "repositoryCommit" to "7d5052fb895d086ae2ec6e3cca861146ee3ea0ec",
            "repositoryRootTree" to "35127622c9911a3f7e50c809a71dfc0057843e34",
            "repositoryParent" to "0dae9cf45bef459a60cefb1f3ad1b4eedea3554b",
            "gitBlob" to "2110eaccdbce98e2bf10c827f1136b63c9c35481",
            "license" to "Apache-2.0",
            "fixturePath" to "app-desktop/src/test/resources/extensions/real/keiyoushi-mangadex-1.4.211.apk",
            "sha256" to APK_SHA256,
            "sizeBytes" to APK_SIZE.toString(),
            "packageName" to "eu.kanade.tachiyomi.extension.all.mangadex",
            "versionCode" to "211",
            "versionName" to "1.4.211",
            "extensionLibVersion" to "1.4",
            "extensionClass" to EXTENSION_CLASS,
            "expectedOutcome" to "success",
            "rawUrl" to "https://raw.githubusercontent.com/keiyoushi/extensions/7d5052fb895d086ae2ec6e3cca861146ee3ea0ec/apk/tachiyomi-all.mangadex-v1.4.211.apk",
            "retrievedAt" to "2026-07-20",
        )
    }
}
