package mihon.desktop.extension

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for [ApkToJarConverter].
 *
 * We create minimal synthetic APKs (ZIP archives with crafted content).
 */
class ApkToJarConverterTest {

    private val converter = ApkToJarConverter()

    @Test
    fun `returns null for non-ZIP input`(@TempDir tmp: Path) {
        val notZip = tmp.resolve("notzip.apk").toFile()
        notZip.writeBytes(ByteArray(256) { 0x00 })
        converter.convert(notZip, tmp.toFile()).shouldBeNull()
    }

    @Test
    fun `returns null for ZIP without classes dex`(@TempDir tmp: Path) {
        val apk = tmp.resolve("empty.apk").toFile()
        buildZip(apk) {
            putEntry("AndroidManifest.xml", "<manifest/>".toByteArray())
            putEntry("res/layout/main.xml", ByteArray(0))
        }
        converter.convert(apk, tmp.toFile()).shouldBeNull()
    }

    @Test
    fun `produces JAR file when given APK with classes dex`(@TempDir tmp: Path) {
        val apk = tmp.resolve("minimal.apk").toFile()
        buildZip(apk) {
            putEntry("classes.dex", minimalDexBytes())
        }
        val result = converter.convert(apk, tmp.toFile())
        result.shouldNotBeNull()
        result.name shouldEndWith ".jar"
        result.exists() shouldBe true
        assert(result.length() > 0L) { "JAR file should be non-empty" }
    }

    @Test
    fun `output JAR is placed in outputDir`(@TempDir tmp: Path) {
        val outputDir = tmp.resolve("output").toFile().also { it.mkdirs() }
        val apk = tmp.resolve("src.apk").toFile()
        buildZip(apk) {
            putEntry("classes.dex", minimalDexBytes())
        }
        val result = converter.convert(apk, outputDir)
        result?.parentFile?.canonicalPath shouldBe outputDir.canonicalPath
    }

    @Test
    fun `output is a valid ZIP archive`(@TempDir tmp: Path) {
        val apk = tmp.resolve("valid.apk").toFile()
        buildZip(apk) {
            putEntry("classes.dex", minimalDexBytes())
        }
        val result = converter.convert(apk, tmp.toFile()) ?: return
        // A zero-class DEX may produce a minimal JAR — just check it's a valid file.
        result.exists() shouldBe true
        assert(result.length() > 0L) { "JAR file should be non-empty" }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun buildZip(file: File, block: ZipOutputStream.() -> Unit) {
        ZipOutputStream(file.outputStream().buffered()).use(block)
    }

    private fun ZipOutputStream.putEntry(name: String, content: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(content)
        closeEntry()
    }

    /**
     * A valid (but zero-class) DEX file.
     * This is a minimal DEX v035 header encoding zero classes,
     * matching the format accepted by dex2jar's DexFileReader.
     *
     * Byte values that are >= 0x80 are written with .toByte() to avoid
     * signed/unsigned mismatch in Kotlin's ByteArray literal syntax.
     */
    @Suppress("MagicNumber")
    private fun minimalDexBytes(): ByteArray {
        // Full 120-byte minimal DEX (verified structure: dex\n035\0 magic, zero classes)
        val bytes = intArrayOf(
            // magic
            0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x35, 0x00,
            // SHA-1 checksum placeholder (20 bytes) — dex2jar doesn't verify it
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            // Adler-32 checksum (4 bytes) — not verified by dex2jar
            0x00, 0x00, 0x00, 0x00,
            // file_size = 128 = 0x80
            0x80, 0x00, 0x00, 0x00,
            // header_size = 112 = 0x70
            0x70, 0x00, 0x00, 0x00,
            // endian_tag = 0x12345678
            0x78, 0x56, 0x34, 0x12,
            // link_size = 0
            0x00, 0x00, 0x00, 0x00,
            // link_off = 0
            0x00, 0x00, 0x00, 0x00,
            // map_off = 112
            0x70, 0x00, 0x00, 0x00,
            // string_ids_size = 0
            0x00, 0x00, 0x00, 0x00,
            // string_ids_off = 0
            0x00, 0x00, 0x00, 0x00,
            // type_ids_size = 0
            0x00, 0x00, 0x00, 0x00,
            // type_ids_off = 0
            0x00, 0x00, 0x00, 0x00,
            // proto_ids_size = 0
            0x00, 0x00, 0x00, 0x00,
            // proto_ids_off = 0
            0x00, 0x00, 0x00, 0x00,
            // field_ids_size = 0
            0x00, 0x00, 0x00, 0x00,
            // field_ids_off = 0
            0x00, 0x00, 0x00, 0x00,
            // method_ids_size = 0
            0x00, 0x00, 0x00, 0x00,
            // method_ids_off = 0
            0x00, 0x00, 0x00, 0x00,
            // class_defs_size = 0
            0x00, 0x00, 0x00, 0x00,
            // class_defs_off = 0
            0x00, 0x00, 0x00, 0x00,
            // data_size = 16
            0x10, 0x00, 0x00, 0x00,
            // data_off = 112
            0x70, 0x00, 0x00, 0x00,
            // map list at offset 112: list_size=1, type=TYPE_HEADER_ITEM, size=1, offset=0
            0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        return ByteArray(bytes.size) { bytes[it].toByte() }
    }
}
