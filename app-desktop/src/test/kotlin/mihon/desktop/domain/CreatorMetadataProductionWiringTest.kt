package mihon.desktop.domain

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.runBlocking
import mihon.desktop.di.inMemoryDesktopPreferenceStore
import mihon.desktop.di.initDesktopDIForTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.CreatorDiscoveryService
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class CreatorMetadataProductionWiringTest {
    @Test
    fun `actual desktop Creator DI never probes registered ID or raw language before safe metadata`(
        @TempDir root: File,
    ) = runBlocking<Unit> {
        val observations = mutableListOf<String>()
        for (site in listOf("id", "name", "language")) {
            val broken = MetadataSource(10L)
            val healthy = MetadataSource(20L)
            val context = initDesktopDIForTest(
                File(root, site),
                inMemoryDesktopPreferenceStore(),
                startDownloadWorker = false,
                builtInSources = listOf(broken, healthy),
            )
            try {
                Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().enabledLanguages.set(setOf("en"))
                val creator = Injekt.get<CreatorRepository>().upsertCreator("Metadata $site")
                val archive = Injekt.get<CreatorArchiveRepository>()
                archive.upsertWatchPolicy(
                    ArchiveWatchPolicy(
                        creator.id,
                        true,
                        60_000L,
                        setOf(10L, 20L),
                        emptySet(),
                        includeUnknown = true,
                    ),
                    System.currentTimeMillis(),
                )
                val beforeBad = broken.idReads
                val beforeHealthy = healthy.idReads
                broken.brokenAt = site
                broken.nameReads = 0
                broken.languageReads = 0
                val attempt = runCatching { Injekt.get<CreatorDiscoveryService>().discoverCreator(creator.id) }
                if (attempt.isFailure) {
                    observations += "$site:escaped:${attempt.exceptionOrNull()?.javaClass?.simpleName}"
                    continue
                }
                val results = attempt.getOrThrow().sourceResults.associateBy { it.sourceId }
                assertEquals(setOf(10L, 20L), results.keys)
                val kind = results.getValue(10L).failure?.javaClass?.simpleName ?: "Success"
                observations += "$site:$kind:${results.getValue(20L).failure}:" +
                    "${broken.idReads - beforeBad}:${healthy.idReads - beforeHealthy}"
                assertEquals(1, broken.nameReads)
                assertEquals(1, broken.languageReads)
                assertEquals(1, healthy.searches)
                assertEquals(if (site == "id") 1 else 0, broken.searches)
                assertTrue(archive.getSourceCheckpoints(creator.id).single { it.sourceId == 20L }.lastSuccessAt != null)
            } finally {
                context.closeAndJoin()
            }
        }
        assertEquals(
            listOf(
                "id:Success:null:0:0",
                "name:ExtensionIncompatible:null:0:0",
                "language:ExtensionIncompatible:null:0:0",
            ),
            observations,
        )
    }

    @Test
    fun `desktop Creator excludes hidden IDs and other languages before display metadata`(
        @TempDir root: File,
    ) = runBlocking<Unit> {
        val hidden = MetadataSource(10L)
        val french = MetadataSource(20L)
        val healthy = MetadataSource(30L)
        val context = initDesktopDIForTest(
            root,
            inMemoryDesktopPreferenceStore(),
            startDownloadWorker = false,
            builtInSources = listOf(hidden, french, healthy),
        )
        try {
            val preferences = Injekt.get<mihon.desktop.settings.DesktopAppPreferences>()
            preferences.enabledLanguages.set(setOf("en"))
            preferences.disabledSources.set(setOf("10"))
            hidden.brokenAt = "name"
            french.brokenAt = "name"
            french.language = "fr"
            hidden.nameReads = 0
            hidden.languageReads = 0
            french.nameReads = 0
            french.languageReads = 0
            val creator = Injekt.get<CreatorRepository>().upsertCreator("Selected creator")
            val archive = Injekt.get<CreatorArchiveRepository>()
            archive.upsertWatchPolicy(
                ArchiveWatchPolicy(
                    creator.id,
                    true,
                    60_000L,
                    setOf(10L, 20L, 30L),
                    emptySet(),
                    includeUnknown = true,
                ),
                System.currentTimeMillis(),
            )
            val result = Injekt.get<CreatorDiscoveryService>().discoverCreator(creator.id)
            assertEquals(listOf(30L), result.sourceResults.map { it.sourceId })
            assertEquals(0, hidden.nameReads)
            assertEquals(0, hidden.languageReads)
            assertEquals(0, french.nameReads)
            assertEquals(1, french.languageReads)
            assertEquals(1, healthy.searches)
            assertEquals(0, hidden.searches + french.searches)
        } finally {
            context.closeAndJoin()
        }
    }

    private class MetadataSource(private val registeredId: Long) : CatalogueSource {
        var brokenAt: String? = null
        var idReads = 0
        var nameReads = 0
        var languageReads = 0
        var searches = 0
        var language = "en"
        override val id: Long get() {
            idReads++
            if (brokenAt == "id") throw NoSuchMethodError("future ID")
            return registeredId
        }
        override val name: String get() {
            nameReads++
            if (brokenAt == "name") throw NoSuchMethodError("future name")
            return "Creator metadata source"
        }
        override val lang: String get() {
            languageReads++
            if (brokenAt ==
                "language"
            ) {
                throw NoSuchMethodError("future language")
            }
            return language
        }
        override val supportsLatest = false
        override fun getFilterList() = FilterList()
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            searches++
            return MangasPage(emptyList(), false)
        }
    }
}
