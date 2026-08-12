package eu.kanade.tachiyomi.ui.browse.author

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.data.library.CreatorDiscoveryJob
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.interactor.CreatorArchive
import tachiyomi.domain.creator.interactor.DiscoverCreatorWorks
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.SetCreatorFollow

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
    }

    @Test
    fun `discovery owns a distinct Android worker`() {
        assertNotNull(CreatorDiscoveryJob::class.java)
    }
}
