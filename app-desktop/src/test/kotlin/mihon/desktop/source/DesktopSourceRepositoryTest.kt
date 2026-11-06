package mihon.desktop.source

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.api.get

class DesktopSourceRepositoryTest {

    @Test
    fun `getSources returns empty list when no sources loaded`() = runBlocking {
        val repo = DesktopSourceRepository(FakeDesktopSourceManager(emptyList()), FakeHandler)
        val sources = repo.getSources().first()
        assertTrue(sources.isEmpty())
    }

    @Test
    fun `getSources maps source id and name`() = runBlocking {
        val repo =
            DesktopSourceRepository(FakeDesktopSourceManager(listOf(FakeSource(1L, "en", "TestSource"))), FakeHandler)
        val sources = repo.getSources().first()
        assertEquals(1, sources.size)
        assertEquals(1L, sources[0].id)
        assertEquals("TestSource", sources[0].name)
        assertEquals("en", sources[0].lang)
    }

    @Test
    fun `getOnlineSources returns only HttpSources`() = runBlocking {
        val repo = DesktopSourceRepository(
            FakeDesktopSourceManager(listOf(FakeHttpSource(2L, "en", "Http"), FakeSource(3L, "en", "Local"))),
            FakeHandler,
        )
        val sources = repo.getOnlineSources().first()
        assertEquals(1, sources.size)
        assertEquals(2L, sources[0].id)
    }

    @Test
    fun `registered projection flows isolate metadata failures and retain database counts`() = runBlocking {
        for (site in listOf("name", "lang", "latest")) {
            var poisoned = false
            var latestReads = 0
            val bad = io.mockk.mockk<eu.kanade.tachiyomi.source.online.HttpSource>()
            io.mockk.every { bad.id } answers { if (poisoned) throw AssertionError("registered ID") else 9L }
            io.mockk.every { bad.name } answers {
                if (poisoned && site == "name") throw NoSuchMethodError("private name") else "Bad"
            }
            io.mockk.every { bad.lang } answers {
                if (poisoned && site == "lang") throw NoSuchMethodError("private lang") else "en"
            }
            io.mockk.every { bad.supportsLatest } answers {
                latestReads++
                if (poisoned && site == "latest") throw NoSuchMethodError("private latest") else true
            }
            val healthy = FakeHttpSource(10L, "en", "Healthy")
            val manager = FakeDesktopSourceManager(listOf(bad, healthy))
            val fixture = mihon.desktop.domain.DirectorySqlFixture()
            try {
                fixture.mangas.seed(
                    tachiyomi.domain.manga.model.Manga.create().copy(
                        id = 1L,
                        source = 9L,
                        url = "/favorite",
                        title = "Favorite",
                        favorite = true,
                    ),
                )
                fixture.mangas.seed(
                    tachiyomi.domain.manga.model.Manga.create().copy(
                        id = 2L,
                        source = 9L,
                        url = "/outside",
                        title = "Outside",
                        favorite = false,
                    ),
                )
                fixture.mangas.seed(
                    tachiyomi.domain.manga.model.Manga.create().copy(
                        id = 3L,
                        source = 99L,
                        url = "/missing",
                        title = "Missing",
                        favorite = true,
                    ),
                )
                val field = fixture.javaClass.getDeclaredField("handler").apply { isAccessible = true }
                val repo = DesktopSourceRepository(manager, field.get(fixture) as tachiyomi.data.DatabaseHandler)
                poisoned = true
                val sources = repo.getSources().first()
                assertEquals(listOf(9L, 10L), sources.map { it.id })
                assertEquals("Healthy", sources.last().name)
                assertEquals(false, sources.first().supportsLatest)
                assertEquals(2, repo.getOnlineSources().first().size)
                val favorites = repo.getSourcesWithFavoriteCount().first()
                assertEquals(1L, favorites.single { it.first.id == 9L }.second)
                assertTrue(favorites.single { it.first.id == 99L }.first.isStub)
                val outside = repo.getSourcesWithNonLibraryManga().first()
                assertEquals(9L, outside.single().source.id)
                assertEquals(1L, outside.single().count)
                assertEquals(if (site == "latest") 1 else 0, latestReads)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `unknown query sources never probe metadata and database identity remains usable`() = runBlocking {
        val unknown = io.mockk.mockk<eu.kanade.tachiyomi.source.online.HttpSource>()
        io.mockk.every { unknown.id } throws AssertionError("unknown ID")
        io.mockk.every { unknown.name } throws AssertionError("unknown name")
        io.mockk.every { unknown.lang } throws AssertionError("unknown language")
        val manager = object : tachiyomi.domain.source.service.SourceManager by FakeDesktopSourceManager(emptyList()) {
            override val querySources = kotlinx.coroutines.flow.flowOf(listOf(unknown))
        }
        val repo = DesktopSourceRepository(manager, FakeHandler)
        assertTrue(repo.getSources().first().isEmpty())
        assertTrue(repo.getOnlineSources().first().isEmpty())
    }

    @Test
    fun `projection preserves ordinary IO and control failures instead of fallback`() = runBlocking {
        for (failure in listOf(
            java.io.IOException("ordinary"),
            java.io.IOException(OutOfMemoryError("controlled fatal")),
            java.io.IOException(kotlinx.coroutines.CancellationException("controlled cancel")),
            AssertionError("host"),
        )) {
            val source = io.mockk.mockk<eu.kanade.tachiyomi.source.CatalogueSource>()
            io.mockk.every { source.id } returns 8L
            io.mockk.every { source.name } throws failure
            io.mockk.every { source.lang } returns "en"
            io.mockk.every { source.supportsLatest } returns false
            val repo = DesktopSourceRepository(FakeDesktopSourceManager(listOf(source)), FakeHandler)
            val actual = runCatching { repo.getSources().first() }.exceptionOrNull()
            val expected = failure.cause ?: failure
            org.junit.jupiter.api.Assertions.assertSame(expected, actual)
        }
    }

    @Test
    fun `metadata diagnostics freeze installed ownership before getters replace the frame`() = runBlocking {
        val source = io.mockk.mockk<eu.kanade.tachiyomi.source.CatalogueSource>()
        io.mockk.every { source.id } returns 8L
        var owner = eu.kanade.tachiyomi.network.SourceFailureIdentity(8L, "old.package", "Old", "1.2.3", 123L)
        io.mockk.every { source.name } answers {
            owner = eu.kanade.tachiyomi.network.SourceFailureIdentity(8L, "new.package", "New", "9.9.9", 999L)
            throw NoSuchMethodError("private query must not be logged")
        }
        io.mockk.every { source.lang } returns "en"
        io.mockk.every { source.supportsLatest } returns false
        val reports = mutableListOf<String>()
        val repo = DesktopSourceRepository(
            FakeDesktopSourceManager(listOf(source)),
            FakeHandler,
            unknownName = { "Localized unknown" },
            failureIdentitySnapshot = { listOf(source to owner) },
            diagnosticWriter = reports::add,
        )
        val row = repo.getSources().first().single()
        assertEquals(8L, row.id)
        assertEquals("Localized unknown", row.name)
        assertEquals("en", row.lang)
        assertEquals(1, reports.size)
        assertTrue(reports.single().contains("old.package"))
        assertTrue(reports.single().contains("1.2.3"))
        org.junit.jupiter.api.Assertions.assertFalse(reports.single().contains("new.package"))
        org.junit.jupiter.api.Assertions.assertFalse(reports.single().contains("private query"))
    }

    @Test
    fun `actual DI repository uses registered IDs after source getter becomes unavailable`(
        @org.junit.jupiter.api.io.TempDir root: java.io.File,
    ) = runBlocking {
        var poisoned = false
        var badName = false
        val source = io.mockk.mockk<eu.kanade.tachiyomi.source.CatalogueSource>()
        io.mockk.every { source.id } answers { if (poisoned) throw AssertionError("registered ID") else 12L }
        io.mockk.every { source.name } answers {
            if (badName) throw NoSuchMethodError("private metadata") else "Healthy DI"
        }
        io.mockk.every { source.lang } returns "en"
        io.mockk.every { source.supportsLatest } returns true
        val context = mihon.desktop.di.initDesktopDIForTest(
            root,
            mihon.desktop.di.inMemoryDesktopPreferenceStore(),
            updateManga = { mihon.desktop.domain.LibraryUpdateChecker.UpdateResult(0) },
            startDownloadWorker = false,
            builtInSources = listOf(source),
        )
        try {
            poisoned = true
            val repo = uy.kohesive.injekt.Injekt.get<tachiyomi.domain.source.repository.SourceRepository>()
            assertEquals(12L, repo.getSources().first().single().id)
            assertTrue(repo.getSources().first().single().supportsLatest)
            val manager = uy.kohesive.injekt.Injekt.get<mihon.desktop.extension.DesktopExtensionManager>()
            val field = manager.javaClass.getDeclaredField("mutableRuntimeSnapshot").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val published = field.get(manager) as kotlinx.coroutines.flow.MutableStateFlow<
                mihon.desktop.extension.DesktopExtensionRuntimeSnapshot,
                >
            val original = published.value
            val previousError = System.err
            val bytes = java.io.ByteArrayOutputStream()
            try {
                System.setErr(java.io.PrintStream(bytes, true, Charsets.UTF_8))
                published.value = original.copy(
                    installed = listOf(
                        mihon.desktop.extension.InstalledExtension(
                            java.io.File(root, "not-owner.jar"),
                            listOf(source),
                            metadataSnapshot = mihon.desktop.extension.ExtensionMeta(
                                "registered.package",
                                123L,
                                "1.2.3",
                                name = "Registered owner",
                            ),
                            registeredSources = listOf(tachiyomi.domain.source.service.SourceRegistration(source, 12L)),
                        ),
                    ),
                )
                badName = true
                assertEquals(12L, repo.getSources().first().single().id)
                val report = bytes.toString(Charsets.UTF_8)
                assertTrue(report.contains("registered.package"), report)
                assertTrue(report.contains("1.2.3"), report)
                org.junit.jupiter.api.Assertions.assertFalse(report.contains("private metadata"))
            } finally {
                badName = false
                System.setErr(previousError)
                published.value = original
            }
        } finally {
            context.closeAndJoin()
        }
    }
}
