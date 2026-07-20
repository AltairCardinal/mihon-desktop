package mihon.desktop.extension

import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.desktop.di.initDesktopDIForTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import uy.kohesive.injekt.Injekt
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import javax.imageio.ImageIO

@Isolated
class RealExtensionComixDescramblerCompatTest {

    @Test
    fun `real Comix client decodes XOR and restores every grid tile`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val preferences = IsolatedDesktopPreferenceStore.create()
        val previousInjekt = Injekt
        try {
            val root = repositoryRoot()
            val provenance = Json.parseToJsonElement(
                Files.readString(root.resolve(PROVENANCE_PATH)),
            ).jsonObject
            val apkPath = root.resolve(provenance.getValue("fixturePath").jsonPrimitive.content)
            assertEquals(APK_SHA256, sha256(Files.readAllBytes(apkPath)))
            assertEquals(EXTENSION_CLASS, ManifestClassExtractor.extractFromApk(apkPath.toFile()))

            val fixture = Base64.getDecoder().decode(IMAGE_FIXTURE_BASE64)
            assertEquals(IMAGE_SHA256, sha256(fixture))

            MockWebServer().use { server ->
                server.start()
                server.enqueue(scrambledResponse(fixture, includeGrid = false))
                server.enqueue(scrambledResponse(fixture, includeGrid = true))
                val diContext = initDesktopDIForTest(
                    appDir = tempDir.resolve("app").toFile(),
                    preferenceStore = preferences.store,
                )
                try {
                    val jar = requireNotNull(ApkToJarConverter().convert(apkPath.toFile(), tempDir.toFile()))
                    writeExtensionMeta(
                        jar,
                        ExtensionMeta(
                            pkgName = PACKAGE_NAME,
                            versionCode = VERSION_CODE,
                            versionName = VERSION_NAME,
                            artifactSha256 = APK_SHA256,
                            source = ExtensionOrigin.CONVERTED_APK,
                            name = "Comix",
                            language = "en",
                            extensionClass = EXTENSION_CLASS,
                        ),
                    )

                    val loader = DesktopExtensionLoader(tempDir.toFile())
                    val loaded = loader.loadFromSingleJar(jar)
                    try {
                        assertEquals(1, loaded.size, "Comix did not load through production Desktop DI")
                        assertTrue(loader.diagnostics.isEmpty(), "Comix loader diagnostics: ${loader.diagnostics}")
                        val source = loaded.single().source as HttpSource
                        val codeSource = Path.of(source.javaClass.protectionDomain.codeSource.location.toURI())
                        assertEquals(jar.canonicalFile.toPath(), codeSource.toFile().canonicalFile.toPath())

                        assertAll(
                            {
                                assertTiles(
                                    callAndDecode(source, server, "/xor-only.png", grid = false),
                                    SCRAMBLED_COLOR_ORDER.map(EXPECTED_COLORS::get),
                                )
                            },
                            { assertTiles(callAndDecode(source, server, "/xor-grid.png", grid = true), EXPECTED_COLORS) },
                        )
                        assertEquals("/xor-only.png", server.takeRequest().url.encodedPath)
                        assertEquals("/xor-grid.png", server.takeRequest().url.encodedPath)
                    } finally {
                        loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
                    }
                } finally {
                    diContext.closeAndJoin()
                }
            }
        } finally {
            Injekt = previousInjekt
            preferences.close()
        }
    }

    private fun scrambledResponse(fixture: ByteArray, includeGrid: Boolean): MockResponse {
        val builder = MockResponse.Builder()
            .addHeader("Content-Type", "image/png")
            .addHeader("x-enc-seed", ENCODED_SEED)
            .addHeader("x-enc-len", fixture.size)
            .addHeader("x-enc-algo", "1")
            .body(Buffer().write(fixture))
        if (includeGrid) {
            builder.addHeader("x-scramble-grid", "5x5")
                .addHeader("x-scramble-seed", SCRAMBLE_SEED)
                .addHeader("x-scramble-algo", "1")
        }
        return builder.build()
    }

    private fun callAndDecode(source: HttpSource, server: MockWebServer, path: String, grid: Boolean): BufferedImage {
        source.client.newCall(Request.Builder().url(server.url(path)).build()).execute().use { response ->
            assertTrue(response.isSuccessful)
            assertEquals("image/jpeg", response.body.contentType().toString())
            val bytes = response.body.bytes()
            if (grid) assertNull(response.header("Content-Length"))
            else assertEquals(bytes.size.toString(), response.header("Content-Length"))
            return requireNotNull(ImageIO.read(ByteArrayInputStream(bytes))) {
                "Comix returned no decodable image"
            }
        }
    }

    private fun assertTiles(image: BufferedImage, expectedColors: List<Color>) {
        assertNotNull(image)
        assertEquals(IMAGE_SIZE, image.width)
        assertEquals(IMAGE_SIZE, image.height)
        expectedColors.forEachIndexed { index, expected ->
            val actual = Color(image.getRGB((index % 5) * 10 + 5, (index / 5) * 10 + 5))
            assertColorNear(expected, actual, index)
        }
    }

    private fun assertColorNear(expected: Color, actual: Color, tile: Int) {
        val delta = maxOf(
            kotlin.math.abs(expected.red - actual.red),
            kotlin.math.abs(expected.green - actual.green),
            kotlin.math.abs(expected.blue - actual.blue),
        )
        assertTrue(delta <= JPEG_TOLERANCE, "tile $tile expected $expected, got $actual (delta=$delta)")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun repositoryRoot() = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private companion object {
        const val PROVENANCE_PATH =
            "app-desktop/src/test/resources/extensions/real/keiyoushi-comix-1.4.34.provenance.json"
        const val APK_SHA256 = "5d46a6ef98c1ac4f2ab22a29347748a36eb32b6995fb8a08e092446424e366d8"
        const val IMAGE_SHA256 = "b5454afef4dbd1ceac4fbea18ce791275b386aa60450b14d0bc08b4fb16ceae0"
        const val IMAGE_FIXTURE_BASE64 =
            "5slTpnLsWWqURYRDYhI5D4gJnh5h6OSzLN2WLsyz7zxGKnKQsomYlno7qK9iWE4gZvJq+L1Hw84skXIadbagRZYPhsi" +
                "B7MaFU2WTc1stApjBgcf4yo2T7EusnRYK99Vgx6hMnUqrjlHwWfJ3ud55Nn/Rc/QBz+KKNrmlqxp8ri/nAf/LBL5QSW" +
                "ymwPxHxslLN60+k46EMW+r6FwnmUHW0qzc7I4+Yh4rkVTJqlcB2Pf97NJVblEPlCbngXde+xA4b766Z5PJWcTbHxb" +
                "S24AZzS1hQLsk0+gpoFRmJ/XrvLq8iMMkXK1B1p9/4tG3jlyM516EvVgEzMGfkRFQ7H0tJVC5HZZOLqC5zMq5ejMIC" +
                "ZvhL2f4pdjs8ndBc8yvfMvkyxlKKMs9a2nWZTNdMwoSJYGwxfcoE8pKllBnSAE4ce2WSDMx58M2iBceHVlrRkeYZYz" +
                "rn+Hr8nsNfrxqOHJT2TE817UjX9whQDpCgK7BCWDLjCbS1hkSlMPY4yqP766tX/7vnSOYIrbTE8SNHc5qwmhNz8dui" +
                "REdMsj2Pw6mkVspbFktN5AzGjn/2baLIRNAeYLdZGCV0b9frjphruYh5YxQ2tU="
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.en.comix"
        const val EXTENSION_CLASS = "eu.kanade.tachiyomi.extension.en.comix.ExtensionGenerated"
        const val VERSION_CODE = 34L
        const val VERSION_NAME = "1.4.34"
        const val ENCODED_SEED = 314159
        const val SCRAMBLE_SEED = 271828
        const val IMAGE_SIZE = 50
        const val JPEG_TOLERANCE = 40
        val SCRAMBLED_COLOR_ORDER = listOf(
            23, 0, 24, 5, 21,
            2, 1, 15, 8, 17,
            3, 19, 11, 10, 9,
            20, 13, 12, 7, 6,
            14, 4, 16, 22, 18,
        )
        val EXPECTED_COLORS = listOf(
            0xE53935, 0xD81B60, 0x8E24AA, 0x5E35B1, 0x3949AB,
            0x1E88E5, 0x039BE5, 0x00ACC1, 0x00897B, 0x43A047,
            0x7CB342, 0xC0CA33, 0xFDD835, 0xFFB300, 0xFB8C00,
            0xF4511E, 0x6D4C41, 0x757575, 0x546E7A, 0x000000,
            0xFFFFFF, 0x880E4F, 0x311B92, 0x004D40, 0x33691E,
        ).map(::Color)
    }
}
