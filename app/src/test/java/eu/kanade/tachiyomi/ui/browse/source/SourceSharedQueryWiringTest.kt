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
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceMangaSearchService
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
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
