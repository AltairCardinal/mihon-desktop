package eu.kanade.tachiyomi.ui.browse.source

import android.app.Application
import androidx.core.content.ContextCompat
import eu.kanade.domain.DomainModule
import eu.kanade.tachiyomi.di.AppModule
import eu.kanade.tachiyomi.network.AndroidNetworkResponseAdapter
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.interactor.BatchUpdateChapters
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.repository.CreatorArchiveBootstrap
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorLibraryIndexWriter
import tachiyomi.domain.creator.repository.CreatorLibraryMangaSource
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.CreatorLibraryIndexer
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.UpdateLibraryMembership
import tachiyomi.domain.source.service.SourceMangaSearchService
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.util.concurrent.Executor

class SourceSharedQueryWiringTest {

    @Test
    fun `Android domain DI resolves shared source query service`() {
        withIsolatedInjekt {
            Injekt.importModule(DomainModule())

            assertNotNull(Injekt.get<SourceMangaSearchService>())
        }
    }

    @Test
    fun `Android domain DI resolves shared membership and chapter batch use cases`() {
        withIsolatedInjekt {
            Injekt.addSingleton<DatabaseHandler>(mockk(relaxed = true))
            Injekt.importModule(DomainModule())

            assertNotNull(Injekt.get<UpdateLibraryMembership>())
            assertNotNull(Injekt.get<BatchUpdateChapters>())
        }
    }

    @Test
    fun `Android creator facades resolve the same gated archive repository`() {
        withIsolatedInjekt {
            Injekt.addSingleton<DatabaseHandler>(mockk(relaxed = true))
            Injekt.importModule(DomainModule())

            assertSame(Injekt.get<CreatorRepository>(), Injekt.get<CreatorArchiveRepository>())
            assertSame(Injekt.get<CreatorArchiveRepository>(), Injekt.get<CreatorLibraryIndexWriter>())
            assertSame(
                Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>(),
                Injekt.get<CreatorLibraryMangaSource>(),
            )
            assertNotNull(Injekt.get<CreatorArchiveBootstrap>())
            assertNotNull(Injekt.get<CreatorLibraryIndexer>())
            assertNotNull(Injekt.get<ManageCreatorIdentity>())
            assertNotNull(Injekt.get<NetworkToLocalManga>())
        }
    }

    @Test
    fun `Android app DI resolves production shared network response adapter`() {
        withIsolatedInjekt {
            val application = mockk<Application>(relaxed = true)
            mockkStatic(ContextCompat::class)
            try {
                every { ContextCompat.getMainExecutor(application) } returns Executor { }
                Injekt.importModule(AppModule(application))

                assertNotNull(Injekt.get<AndroidNetworkResponseAdapter>())
            } finally {
                unmockkStatic(ContextCompat::class)
            }
        }
    }

    private inline fun <T> withIsolatedInjekt(block: () -> T): T {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        return try {
            block()
        } finally {
            Injekt = previous
        }
    }
}
