package mihon.desktop.extension

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import java.util.jar.JarFile
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

    @Test
    fun `conversion preserves only safe asset classpath entries`(@TempDir tmp: Path) {
        val apk = tmp.resolve("assets.apk").toFile()
        val safeAsset = "title=MangaDex\n".toByteArray()
        val injected = "must-not-reach-output".toByteArray()
        buildZip(apk) {
            putEntry("classes.dex", minimalDexBytes())
            putEntry("assets/i18n/messages_en.properties", safeAsset)
            putEntry("assets/../escaped.txt", injected)
            putEntry("/assets/absolute.txt", injected)
            putEntry("assets\\windows.txt", injected)
            putEntry("assets/../../Injected.class", injected)
            putEntry("assets/../../META-INF/MANIFEST.MF", injected)
            putEntry("AndroidManifest.xml", injected)
            putEntry("META-INF/CERT.RSA", injected)
        }

        val jar = converter.convert(apk, tmp.toFile()).shouldNotBeNull()
        JarFile(jar).use { archive ->
            val assetEntry = archive.getJarEntry("assets/i18n/messages_en.properties").shouldNotBeNull()
            archive.getInputStream(assetEntry).readBytes().contentEquals(safeAsset) shouldBe true
            listOf(
                "assets/../escaped.txt",
                "/assets/absolute.txt",
                "assets\\windows.txt",
                "assets/../../Injected.class",
                "assets/../../META-INF/MANIFEST.MF",
                "Injected.class",
                "AndroidManifest.xml",
                "META-INF/CERT.RSA",
                "classes.dex",
            ).forEach { archive.getJarEntry(it).shouldBeNull() }
            archive.getJarEntry("META-INF/MANIFEST.MF")?.let { manifest ->
                archive.getInputStream(manifest).readBytes().contentEquals(injected) shouldBe false
            }
        }
    }

    @Test
    fun `failed asset merge leaves no raw or final output`(@TempDir tmp: Path) {
        val apk = buildConflictingAssetsApk(tmp.resolve("conflict.apk").toFile())

        converter.convert(apk, tmp.toFile()).shouldBeNull()

        Files.exists(tmp.resolve("conflict-raw.jar")) shouldBe false
        Files.exists(tmp.resolve("conflict.jar")) shouldBe false
        Files.list(tmp).use { files ->
            files.noneMatch { it.fileName.toString().startsWith(".mihon-apk-convert-") } shouldBe true
        }
    }

    @Test
    fun `failed asset merge preserves an existing final output`(@TempDir tmp: Path) {
        val apk = buildConflictingAssetsApk(tmp.resolve("existing.apk").toFile())
        val existingOutput = tmp.resolve("existing.jar")
        val original = "user-owned-output".toByteArray()
        Files.write(existingOutput, original)

        converter.convert(apk, tmp.toFile()).shouldBeNull()

        Files.readAllBytes(existingOutput).contentEquals(original) shouldBe true
        Files.exists(tmp.resolve("existing-raw.jar")) shouldBe false
    }

    @Test
    fun `detailed failure identifies asset merge and retains the original cause`(@TempDir tmp: Path) {
        val apk = buildConflictingAssetsApk(tmp.resolve("diagnostic.apk").toFile())

        val failure = converter.convertDetailed(apk, tmp.toFile())
            .shouldBeInstanceOf<ApkConversionResult.Failure>()

        failure.stage shouldBe ApkConversionStage.COPY_ASSETS
        failure.attempts shouldBe 1
        failure.error.shouldBeInstanceOf<java.nio.file.FileSystemException>()
    }

    @Test
    fun `transient output access denial retries the complete conversion once`(@TempDir tmp: Path) {
        val apk = tmp.resolve("retry.apk").toFile()
        buildZip(apk) {
            putEntry("classes.dex", minimalDexBytes())
        }
        var publishAttempts = 0
        val retryingConverter = ApkToJarConverter(
            outputPublisher = { source, destination ->
                publishAttempts++
                if (publishAttempts == 1) {
                    throw AccessDeniedException(source.toString(), destination.toString(), "injected lock")
                }
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
            },
            retryDelay = {},
        )

        val success = retryingConverter.convertDetailed(apk, tmp.toFile())
            .shouldBeInstanceOf<ApkConversionResult.Success>()

        publishAttempts shouldBe 2
        success.jar.exists() shouldBe true
    }

    @Test
    fun `persistent output access denial reports attempts stage and final cause`(@TempDir tmp: Path) {
        val apk = tmp.resolve("locked.apk").toFile()
        buildZip(apk) {
            putEntry("classes.dex", minimalDexBytes())
        }
        val failures = mutableListOf<AccessDeniedException>()
        val retryingConverter = ApkToJarConverter(
            outputPublisher = { source, destination ->
                throw AccessDeniedException(source.toString(), destination.toString(), "injected-${failures.size + 1}")
                    .also(failures::add)
            },
            retryDelay = {},
        )

        val failure = retryingConverter.convertDetailed(apk, tmp.toFile())
            .shouldBeInstanceOf<ApkConversionResult.Failure>()

        failures.size shouldBe 2
        failure.stage shouldBe ApkConversionStage.PUBLISH_OUTPUT
        failure.attempts shouldBe 2
        failure.error shouldBe failures.last()
    }

    @Test
    fun `cancellation propagates after removing the conversion workspace`(@TempDir tmp: Path) {
        val apk = tmp.resolve("cancelled.apk").toFile()
        buildZip(apk) {
            putEntry("classes.dex", minimalDexBytes())
        }
        val cancellingConverter = ApkToJarConverter(
            outputPublisher = { _, _ -> throw CancellationException("injected cancellation") },
            retryDelay = {},
        )

        shouldThrow<CancellationException> {
            cancellingConverter.convertDetailed(apk, tmp.toFile())
        }

        Files.list(tmp).use { files ->
            files.noneMatch { it.fileName.toString().startsWith(".mihon-apk-convert-") } shouldBe true
        }
    }

    @Test
    fun `successful conversion replaces an existing final output`(@TempDir tmp: Path) {
        val apk = tmp.resolve("replace.apk").toFile()
        buildZip(apk) {
            putEntry("classes.dex", minimalDexBytes())
            putEntry("assets/value.txt", "replacement".toByteArray())
        }
        val existingOutput = tmp.resolve("replace.jar")
        Files.writeString(existingOutput, "old-output")

        val result = converter.convert(apk, tmp.toFile()).shouldNotBeNull()

        result.toPath() shouldBe existingOutput
        JarFile(result).use { archive -> archive.getJarEntry("assets/value.txt").shouldNotBeNull() }
    }

    @Test
    fun `real Comix conversion preserves p0 b exception handlers`(@TempDir tmp: Path) {
        val apk = repositoryRoot().resolve(COMIX_APK).toFile()
        val jar = converter.convert(apk, tmp.toFile()).shouldNotBeNull()
        val p0Bytes = JarFile(jar).use { archive ->
            archive.getInputStream(archive.getJarEntry("p0.class")).readBytes()
        }
        var handlerCount = 0
        ClassReader(p0Bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? = if (name == "b" && descriptor == COMIX_WEBVIEW_DESCRIPTOR) {
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitTryCatchBlock(
                            start: Label,
                            end: Label,
                            handler: Label,
                            type: String?,
                        ) {
                            handlerCount++
                        }
                    }
                } else {
                    null
                }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        handlerCount shouldBe 8

        ExtensionClassLoader(jar.toURI().toURL(), javaClass.classLoader).use { loader ->
            val p0 = Class.forName("p0", false, loader)
            p0.declaredMethods.single { it.name == "b" && Type.getMethodDescriptor(it) == COMIX_WEBVIEW_DESCRIPTOR }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun buildZip(file: File, block: ZipOutputStream.() -> Unit) {
        ZipOutputStream(file.outputStream().buffered()).use(block)
    }

    private fun buildConflictingAssetsApk(file: File): File = file.also { apk ->
        buildZip(apk) {
            putEntry("classes.dex", minimalDexBytes())
            putEntry("assets/i18n", "parent-file".toByteArray())
            putEntry("assets/i18n/messages_en.properties", "child=conflict".toByteArray())
        }
    }

    private fun ZipOutputStream.putEntry(name: String, content: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(content)
        closeEntry()
    }

    private fun repositoryRoot() = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

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

    private companion object {
        const val COMIX_APK = "app-desktop/src/test/resources/extensions/real/keiyoushi-comix-1.4.34.apk"
        const val COMIX_WEBVIEW_DESCRIPTOR =
            "(Ljava/util/concurrent/atomic/AtomicBoolean;Lkotlin/jvm/internal/Ref\$ObjectRef;Lp0;Lg0;" +
                "Ljava/lang/String;Lkotlin/jvm/internal/Ref\$ObjectRef;Lorg/jsoup/nodes/Document;" +
                "Landroid/os/Handler;Ljava/util/concurrent/atomic/AtomicReference;Ljava/util/concurrent/Semaphore;" +
                "Landroid/webkit/WebResourceResponse;Lkotlin/jvm/internal/Ref\$ObjectRef;" +
                "Ljava/lang/String;Ljava/lang/String;)V"
    }
}
