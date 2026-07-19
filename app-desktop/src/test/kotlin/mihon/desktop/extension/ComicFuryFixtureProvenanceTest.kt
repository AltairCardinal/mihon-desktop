package mihon.desktop.extension

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

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

        val manifest = extractManifestMetadata(apkPath)
        assertEquals(PACKAGE_NAME, manifest.packageName)
        assertEquals(VERSION_CODE, manifest.versionCode)
        assertEquals(VERSION_NAME, manifest.versionName)
        assertEquals(EXTENSION_LIB_VERSION, manifest.extensionLibVersion)
    }

    private fun extractManifestMetadata(apkPath: Path): ManifestMetadata = ZipFile(apkPath.toFile()).use { apk ->
        val entry = requireNotNull(apk.getEntry("AndroidManifest.xml"))
        parseManifestMetadata(apk.getInputStream(entry).readBytes())
    }

    private fun parseManifestMetadata(bytes: ByteArray): ManifestMetadata {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.unsignedShort() == CHUNK_XML)
        buffer.position(8)
        val strings = parseStringPool(buffer)

        var packageName: String? = null
        var versionCode: Int? = null
        var versionName: String? = null
        var extensionLibVersion: String? = null

        while (buffer.remaining() >= CHUNK_HEADER_SIZE) {
            val chunkStart = buffer.position()
            val type = buffer.unsignedShort()
            buffer.unsignedShort()
            val chunkSize = buffer.int
            require(chunkSize >= CHUNK_HEADER_SIZE && chunkStart + chunkSize <= buffer.limit())

            if (type == CHUNK_XML_START_ELEMENT) {
                val element = parseStartElement(buffer, chunkStart, strings)
                when (element.name) {
                    "manifest" -> {
                        packageName = element.attributes["package"]
                        versionCode = element.attributes["versionCode"]?.toInt()
                        versionName = element.attributes["versionName"]
                    }
                    "meta-data" -> if (element.attributes["name"] == EXTENSION_LIB_META_KEY) {
                        extensionLibVersion = element.attributes["value"]
                    }
                }
            }
            buffer.position(chunkStart + chunkSize)
        }

        return ManifestMetadata(packageName, versionCode, versionName, extensionLibVersion)
    }

    private fun parseStringPool(buffer: ByteBuffer): List<String> {
        val chunkStart = buffer.position()
        require(buffer.unsignedShort() == CHUNK_STRING_POOL)
        buffer.unsignedShort()
        val chunkSize = buffer.int
        val stringCount = buffer.int
        buffer.int // style count
        val flags = buffer.int
        val stringsStart = buffer.int
        buffer.int // styles start
        require(stringCount in 0..MAX_STRING_COUNT)

        val offsets = IntArray(stringCount) { buffer.int }
        val strings = offsets.map { offset ->
            buffer.position(chunkStart + stringsStart + offset)
            if (flags and UTF8_FLAG != 0) readUtf8String(buffer) else readUtf16String(buffer)
        }
        buffer.position(chunkStart + chunkSize)
        return strings
    }

    private fun parseStartElement(
        buffer: ByteBuffer,
        chunkStart: Int,
        strings: List<String>,
    ): AxmlElement {
        val attributeExtensionStart = chunkStart + XML_NODE_HEADER_SIZE
        buffer.position(attributeExtensionStart)
        buffer.int // namespace
        val elementName = strings[buffer.int]
        val attributesStart = buffer.unsignedShort()
        val attributeSize = buffer.unsignedShort()
        val attributeCount = buffer.unsignedShort()
        buffer.position(buffer.position() + 6) // id, class, and style indices
        require(attributeSize >= ATTRIBUTE_SIZE)

        val attributesBase = attributeExtensionStart + attributesStart
        val attributes = buildMap<String, String> {
            repeat(attributeCount) { index ->
                buffer.position(attributesBase + index * attributeSize)
                buffer.int // namespace
                val name = strings[buffer.int]
                val rawValueIndex = buffer.int
                buffer.short // typed value size
                buffer.get() // res0
                val dataType = buffer.get().toInt() and 0xFF
                val data = buffer.int
                val value = when {
                    rawValueIndex != NO_INDEX -> strings[rawValueIndex]
                    dataType == TYPE_STRING -> strings[data]
                    dataType == TYPE_FLOAT -> Float.fromBits(data).toString()
                    dataType == TYPE_INT_DEC -> data.toString()
                    else -> null
                }
                if (value != null) put(name, value)
            }
        }
        return AxmlElement(elementName, attributes)
    }

    private fun readUtf8String(buffer: ByteBuffer): String {
        readUtf8Length(buffer) // UTF-16 character count
        val byteLength = readUtf8Length(buffer)
        return ByteArray(byteLength) { buffer.get() }.toString(Charsets.UTF_8)
    }

    private fun readUtf8Length(buffer: ByteBuffer): Int {
        val first = buffer.get().toInt() and 0xFF
        return if (first and 0x80 == 0) first else ((first and 0x7F) shl 8) or (buffer.get().toInt() and 0xFF)
    }

    private fun readUtf16String(buffer: ByteBuffer): String {
        val first = buffer.unsignedShort()
        val length = if (first and 0x8000 == 0) first else ((first and 0x7FFF) shl 16) or buffer.unsignedShort()
        return CharArray(length) { buffer.char }.concatToString()
    }

    private fun ByteBuffer.unsignedShort() = short.toInt() and 0xFFFF

    private data class AxmlElement(
        val name: String,
        val attributes: Map<String, String>,
    )

    private data class ManifestMetadata(
        val packageName: String?,
        val versionCode: Int?,
        val versionName: String?,
        val extensionLibVersion: String?,
    )

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun repositoryRoot() = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private companion object {
        const val CHUNK_XML = 0x0003
        const val CHUNK_STRING_POOL = 0x0001
        const val CHUNK_XML_START_ELEMENT = 0x0102
        const val CHUNK_HEADER_SIZE = 8
        const val XML_NODE_HEADER_SIZE = 16
        const val ATTRIBUTE_SIZE = 20
        const val UTF8_FLAG = 0x100
        const val MAX_STRING_COUNT = 65536
        const val NO_INDEX = -1
        const val TYPE_FLOAT = 0x04
        const val TYPE_STRING = 0x03
        const val TYPE_INT_DEC = 0x10
        const val EXTENSION_LIB_META_KEY = "tachiyomix.extensionLib"
        const val PROVENANCE_PATH =
            "app-desktop/src/test/resources/extensions/real/keiyoushi-comicfury-1.4.8.provenance.json"
        const val APK_SHA256 = "9403d439eefec8ccff3fa7a3edd810046a12206d944302013bc3f94538b3def7"
        const val APK_SIZE = 41496L
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.all.comicfury"
        const val VERSION_CODE = 8
        const val VERSION_NAME = "1.4.8"
        const val EXTENSION_LIB_VERSION = "1.4"
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
            "packageName" to PACKAGE_NAME,
            "versionCode" to VERSION_CODE.toString(),
            "versionName" to VERSION_NAME,
            "extensionLibVersion" to EXTENSION_LIB_VERSION,
            "extensionClass" to EXTENSION_CLASS,
            "expectedOutcome" to "success",
            "rawUrl" to "https://raw.githubusercontent.com/keiyoushi/extensions/7d5052fb895d086ae2ec6e3cca861146ee3ea0ec/apk/tachiyomi-all.comicfury-v1.4.8.apk",
            "retrievedAt" to "2026-07-20",
        )
    }
}
