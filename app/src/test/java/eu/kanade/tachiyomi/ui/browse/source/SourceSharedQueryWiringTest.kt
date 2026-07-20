package eu.kanade.tachiyomi.ui.browse.source

import eu.kanade.domain.DomainModule
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceMangaSearchService
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourceSharedQueryWiringTest {

    @Test
    fun `Android domain DI resolves shared source query service`() {
        Injekt.importModule(DomainModule())

        assertNotNull(Injekt.get<SourceMangaSearchService>())
    }
}
