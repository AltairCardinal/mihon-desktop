package mihon.desktop.extension

import android.app.Application
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import mihon.desktop.di.initDesktopDIForTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.lang.reflect.InvocationTargetException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

@Isolated
class RealExtensionComicFuryTextCompatTest {

    @Test
    fun `real ComicFury page list renders author notes through its public image pipeline`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val preferences = IsolatedDesktopPreferenceStore.create()
        val previousInjekt = Injekt
        try {
            val paint = Paint().apply { setTypeface(Typeface.DEFAULT) }
            assertSame(Typeface.DEFAULT, paint.setTypeface(Typeface.DEFAULT_BOLD))
            assertSame(Typeface.DEFAULT_BOLD, paint.getTypeface())
            val apkPath = repositoryRoot().resolve(APK_PATH)
            assertTrue(Files.isRegularFile(apkPath), "Missing immutable ComicFury fixture: $apkPath")
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
                        name = "Comic Fury",
                        language = "all",
                        extensionClass = EXTENSION_CLASS,
                    ),
                )
                val loader = DesktopExtensionLoader(tempDir.toFile())
                val loaded = loader.loadFromSingleJar(jar)
                try {
                    if (loaded.isEmpty()) exposeFactoryFailure(jar)
                    assertEquals(14, loaded.size, "ComicFury SourceFactory must contribute every source")
                    assertTrue(loader.diagnostics.isEmpty(), "ComicFury loader diagnostics: ${loader.diagnostics}")
                    val source = loaded.first().source as HttpSource
                    val preferences = Injekt.get<Application>().getSharedPreferences(
                        "source_${source.id}",
                        Context.MODE_PRIVATE,
                    )
                    val hadSetting = preferences.contains(SHOW_AUTHOR_NOTES)
                    val previousSetting = preferences.getBoolean(SHOW_AUTHOR_NOTES, false)
                    preferences.edit().putBoolean(SHOW_AUTHOR_NOTES, true).commit()
                    try {
                        MockWebServer().also { it.start() }.use { server ->
                            server.enqueue(MockResponse(body = chapterHtml("Short note 😀")))
                            server.enqueue(MockResponse(body = chapterHtml(LONG_NOTE)))
                            val chapter = SChapter.create().apply {
                                url = server.url("/chapter").toString()
                                name = "Fixture chapter"
                            }
                            val shortPage = source.getPageList(chapter).single(::isAuthorNote)
                            val longPage = source.getPageList(chapter).single(::isAuthorNote)
                            assertTrue(shortPage.imageUrl.orEmpty().contains(ENCODED_EMOJI))
                            assertTrue(
                                shortPage.imageUrl != longPage.imageUrl,
                                "Page-list did not encode distinct notes: short=${shortPage.imageUrl}, long=${longPage.imageUrl}",
                            )
                            val short = render(source, shortPage)
                            val long = render(source, longPage)
                            assertEquals(1000, short.width)
                            assertTrue(short.height > 50)
                            assertTrue(
                                long.height > short.height,
                                "Long author note did not wrap: short=${short.height}, long=${long.height}",
                            )
                            assertTrue(short.whitePixelCount > short.darkPixelCount)
                            assertTrue(short.darkPixelCount > 0, "Rendered note contains no dark glyph pixels")
                            assertTrue(
                                long.lowerBodyMinX in 45..75,
                                "Body should start near restored x=50, actual=${long.lowerBodyMinX}",
                            )
                        }
                    } finally {
                        preferences.edit().apply {
                            if (hadSetting) putBoolean(SHOW_AUTHOR_NOTES, previousSetting) else remove(SHOW_AUTHOR_NOTES)
                        }.commit()
                    }
                } finally {
                    loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
                }
            } finally {
                diContext.closeAndJoin()
            }
        } finally {
            Injekt = previousInjekt
            preferences.close()
        }
    }

    private fun isAuthorNote(page: Page): Boolean =
        URI(page.imageUrl ?: page.url).host == TEXT_INTERCEPTOR_HOST

    private suspend fun render(source: HttpSource, page: Page): RenderedNote = source.getImage(page).use { response ->
        assertEquals(200, response.code)
        assertEquals("image/png", response.body.contentType().toString())
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(response.body.bytes())))
        image.inspect()
    }

    private fun BufferedImage.inspect(): RenderedNote {
        var white = 0
        var dark = 0
        var lowerBodyMinX = width
        for (y in 0 until height) for (x in 0 until width) {
            val rgb = getRGB(x, y)
            val red = rgb shr 16 and 0xFF
            val green = rgb shr 8 and 0xFF
            val blue = rgb and 0xFF
            if (red > 245 && green > 245 && blue > 245) white++
            if (red < 100 && green < 100 && blue < 100) {
                dark++
                if (y >= height / 2) lowerBodyMinX = minOf(lowerBodyMinX, x)
            }
        }
        return RenderedNote(width, height, white, dark, lowerBodyMinX)
    }

    private fun repositoryRoot() = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("docs")) }

    private fun chapterHtml(note: String) = """
        <div class="is--comic-page"><img src="https://example.invalid/page.png"><div class="is--author-notes"><div class="is--comment-box"><a class="is--comment-author">Ada</a><div class="is--comment-content">$note</div></div></div></div>
    """

    private fun exposeFactoryFailure(jar: java.io.File): Nothing =
        ExtensionClassLoader(jar.toURI().toURL(), DesktopExtensionLoader::class.java.classLoader).use { classLoader ->
            val constructor = classLoader.loadClass(EXTENSION_CLASS).getDeclaredConstructor().also { it.isAccessible = true }
            val factory = try {
                constructor.newInstance() as SourceFactory
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
            factory.createSources()
            error("ComicFury factory unexpectedly succeeded outside the production loader")
        }

    private data class RenderedNote(
        val width: Int,
        val height: Int,
        val whitePixelCount: Int,
        val darkPixelCount: Int,
        val lowerBodyMinX: Int,
    )

    private companion object {
        const val APK_PATH = "app-desktop/src/test/resources/extensions/real/keiyoushi-comicfury-1.4.8.apk"
        const val APK_SHA256 = "9403d439eefec8ccff3fa7a3edd810046a12206d944302013bc3f94538b3def7"
        const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.all.comicfury"
        const val VERSION_CODE = 8L
        const val VERSION_NAME = "1.4.8"
        const val EXTENSION_CLASS = "eu.kanade.tachiyomi.extension.all.comicfury.ExtensionGenerated"
        const val SHOW_AUTHOR_NOTES = "showAuthorsNotes"
        const val TEXT_INTERCEPTOR_HOST = "tachiyomi-lib-textinterceptor"
        const val ENCODED_EMOJI = "%F0%9F%98%80"
        val LONG_NOTE = "This author note is deliberately repeated to force deterministic wrapping. ".repeat(12)
    }
}
