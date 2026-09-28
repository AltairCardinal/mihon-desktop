package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.di.AppModule
import io.mockk.mockk
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidReaderProgressAppModuleTest {
    @Test
    fun `AppModule resolves one production reader progress coordinator`() {
        val previous = Injekt
        try {
            Injekt = InjektScope(DefaultRegistrar())
            Injekt.importModule(AppModule(RuntimeEnvironment.getApplication() as Application))
            Injekt.addSingleton<TrackChapter>(mockk())
            Injekt.addSingleton<UpdateChapter>(mockk())
            Injekt.addSingleton<GetChaptersByMangaId>(mockk())
            Injekt.addSingleton<DownloadManager>(mockk())

            assertSame(Injekt.get<AndroidReaderProgressCoordinator>(), Injekt.get<AndroidReaderProgressCoordinator>())
        } finally {
            Injekt = previous
        }
    }
}
