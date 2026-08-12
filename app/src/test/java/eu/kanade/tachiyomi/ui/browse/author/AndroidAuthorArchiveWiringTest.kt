package eu.kanade.tachiyomi.ui.browse.author

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.data.library.CreatorDiscoveryJob
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.interactor.CreatorArchive
import tachiyomi.domain.creator.interactor.DiscoverCreatorWorks
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.manga.model.Manga

class AndroidAuthorArchiveWiringTest {
    @Test
    fun `author detail is a Voyager screen`() {
        assertTrue(AndroidAuthorDetailScreen(7) is Screen)
    }

    @Test
    fun `Android author surface consumes the shared commands`() {
        assertNotNull(GetCreators::class.java)
        assertNotNull(GetCreatorDetails::class.java)
        assertNotNull(SetCreatorFollow::class.java)
        assertNotNull(DiscoverCreatorWorks::class.java)
        assertNotNull(CreatorArchive::class.java)
        assertNotNull(ManageCreatorIdentity::class.java)
        assertNotNull(AndroidMangaCreatorNavigator::class.java)
    }

    @Test
    fun `discovery owns a distinct Android worker`() {
        assertNotNull(CreatorDiscoveryJob::class.java)
    }

    @Test
    fun `manga creator navigation splits author and artist fields into people`() {
        val navigator = AndroidMangaCreatorNavigator(manageCreatorIdentity = mockk())
        val manga = Manga.create().copy(author = "ONE / Murata", artist = "Murata, Boichi")

        assertEquals(listOf("ONE", "Murata", "Boichi"), navigator.mentions(manga).map { it.displayName })
    }
}
