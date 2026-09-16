package eu.kanade.tachiyomi.extension

import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.source.model.SChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceMangaUpdateService
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

class LegacyExtensionInstallInstrumentationTest {
    @Test
    fun privateControlledV15InstallsAndRunsLegacyUpdate() = verify(V15, BasePreferences.ExtensionInstaller.PRIVATE)

    @Test
    fun systemControlledV15InstallsAndRunsLegacyUpdate() = verify(
        V15,
        BasePreferences.ExtensionInstaller.PACKAGEINSTALLER,
    )

    @Test
    fun privateHistoricalV14InstallsAndLoadsRealPageList() = verify(V14, BasePreferences.ExtensionInstaller.PRIVATE)

    @Test
    fun systemHistoricalV14InstallsAndLoadsRealPageList() = verify(
        V14,
        BasePreferences.ExtensionInstaller.PACKAGEINSTALLER,
    )

    private fun verify(fixture: LifecycleApkFixture, installer: BasePreferences.ExtensionInstaller) =
        ExtensionV16LifecycleInstrumentationTest().runLifecycle(installer, fixture = fixture) { installed, _ ->
            assertEquals(fixture.versionName, installed.versionName)
            assertEquals(fixture.versionCode, installed.versionCode)
            if (fixture == V15) {
                val source = installed.sources.single()
                assertEquals(0xAE0015L, source.id)
                assertEquals("AEX-00 v1.5 suspend-only fixture", source.name)
                val updated = SourceMangaUpdateService().await(
                    source,
                    Manga.create().copy(source = source.id, url = "/aex00/v15", title = "Legacy"),
                    emptyList(),
                    fetchDetails = true,
                    fetchChapters = true,
                )
                assertEquals("Legacy (v1.5)", updated.manga.title)
                assertTrue(updated.chapters.isEmpty())
            } else {
                val source = installed.sources.first()
                val serverFailure = AtomicReference<Throwable?>()
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                    val worker = Thread {
                        try {
                            server.accept().use { socket ->
                                socket.soTimeout = 10_000
                                val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                                check(input.readLine().startsWith("GET /legacy-chapter "))
                                while (!input.readLine().isNullOrEmpty()) Unit
                                val body = PAGE_HTML.toByteArray(Charsets.UTF_8)
                                socket.getOutputStream().use { output ->
                                    output.write(
                                        (
                                            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                            )
                                            .toByteArray(Charsets.US_ASCII),
                                    )
                                    output.write(body)
                                }
                            }
                        } catch (error: Throwable) {
                            if (!server.isClosed) serverFailure.set(error)
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }
                    try {
                        val pages = source.getPageList(
                            SChapter.create().apply {
                                url = "http://127.0.0.1:${server.localPort}/legacy-chapter"
                                name = "AEX-04 legacy ComicFury"
                            },
                        )
                        serverFailure.get()?.let { throw AssertionError("Legacy HTTP boundary failed", it) }
                        assertEquals("https://example.invalid/page.png", pages.single().imageUrl)
                    } finally {
                        server.close()
                        worker.join(5_000)
                    }
                }
            }
        }

    private companion object {
        val V15 = LifecycleApkFixture(
            "aex00-external-v15-controlled-sample.apk",
            "caf80d849e2eb5ad8f0be5121f914d9cee1ee06c15653d15f33321602183a316",
            "9be8a18439915033e8362f25426323e8b7b94f223eadca4962ce5f91a23d6021",
            "aex00.external.v15.controlled",
            "AEX-00 v1.5 controlled",
            "1.5.0",
            150000,
            1.5,
        )
        val V14 = LifecycleApkFixture(
            "keiyoushi-comicfury-1.4.8.apk",
            "9403d439eefec8ccff3fa7a3edd810046a12206d944302013bc3f94538b3def7",
            "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
            "eu.kanade.tachiyomi.extension.all.comicfury",
            "ComicFury",
            "1.4.8",
            8,
            1.4,
        )
        const val PAGE_HTML = "<div class=\"is--comic-page\"><div class=\"is--image-segment\"><div>" +
            "<img src=\"https://example.invalid/page.png\"></div></div></div>"
    }
}
