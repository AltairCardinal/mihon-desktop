package eu.kanade.tachiyomi.extension

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.cash.sqldelight.db.SqlDriver
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionInstalledSourceNavigationInstrumentationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun installedSourceBrowsesThroughDefaultDiAndOpensPersistedMangaDetails() {
        val lastUsed = Injekt.get<SourcePreferences>().lastUsedSource()
        val priorLastUsed = lastUsed.get()
        val wasLastUsedSet = lastUsed.isSet()
        try {
            ExtensionV16LifecycleInstrumentationTest().runLifecycle(BasePreferences.ExtensionInstaller.PRIVATE) {
                    installed,
                    _,
                ->
                val source = installed.sources.single { it.lang == "en" }
                assertSame(source, Injekt.get<SourceManager>().get(source.id))
                val repository = Injekt.get<MangaRepository>()
                val urls = listOf("/aex00/popular-en-1", "/aex00/popular-en-2")
                urls.forEach { check(repository.getMangaByUrlAndSourceId(it, source.id) == null) }
                val visible = mutableStateOf(true)
                lateinit var navigator: Navigator
                try {
                    compose.setContent {
                        MaterialTheme {
                            if (visible.value) {
                                Navigator(BrowseSourceScreen(source.id, GetRemoteManga.QUERY_POPULAR)) {
                                    navigator = it
                                    CurrentScreen()
                                }
                            }
                        }
                    }
                    val title = "AEX-00 popular-en-1"
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
                    }
                    val persisted = requireNotNull(repository.getMangaByUrlAndSourceId(urls.first(), source.id))
                    assertTrue(persisted.id > 0)
                    compose.onNodeWithText(title).performClick()
                    compose.waitUntil(15_000) { navigator.lastItem is MangaScreen }
                    compose.runOnIdle {
                        val destination = navigator.lastItem as MangaScreen
                        assertTrue(destination.fromSource)
                        val id = destination.javaClass.getDeclaredField("mangaId").apply { isAccessible = true }
                        assertEquals(persisted.id, id.getLong(destination))
                        assertTrue(navigator.items.first() is BrowseSourceScreen)
                    }
                    val updated = withTimeout(15_000) {
                        repository.getMangaByIdAsFlow(persisted.id).first {
                            it.memo["aex00.memo"].toString() == "\"preserved-en\""
                        }
                    }
                    assertEquals(source.id, updated.source)
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithText("AEX-00 chapter en").fetchSemanticsNodes().isNotEmpty()
                    }
                    assertEquals(
                        "/aex00/chapter-en",
                        Injekt.get<ChapterRepository>().getChapterByMangaId(persisted.id).single().url,
                    )
                } finally {
                    compose.runOnIdle { visible.value = false }
                    compose.waitForIdle()
                    // The fixed source has exactly two popular pages; never delete unrelated source/library rows.
                    val driver = Injekt.get<SqlDriver>()
                    for (url in urls) {
                        Injekt.get<DatabaseHandler>().await(inTransaction = true) {
                            driver.execute(null, "DELETE FROM mangas WHERE source = ? AND url = ?", 2) {
                                bindLong(0, source.id)
                                bindString(1, url)
                            }
                        }
                        assertEquals(null, repository.getMangaByUrlAndSourceId(url, source.id))
                    }
                }
            }
        } finally {
            if (wasLastUsedSet) lastUsed.set(priorLastUsed) else lastUsed.delete()
        }
    }
}
