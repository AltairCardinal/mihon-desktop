package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.manga.model.Manga

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SinglePageOptionsWiringTest {
    @Test
    fun `production pager gates single page image options without clearing saved values`() = runTest {
        val preferences = ReaderPreferences(
            AndroidPreferenceStore(
                RuntimeEnvironment.getApplication(),
                RuntimeEnvironment.getApplication().getSharedPreferences("single-page-${System.nanoTime()}", 0),
            ),
        ).apply {
            landscapeZoom().set(true)
            dualPageSplitPaged().set(true)
            dualPageRotateToFit().set(true)
            dualPageRotateToFitInvert().set(true)
        }
        val state = MutableStateFlow(ReaderViewModel.State(manga = Manga.create().copy(viewerFlags = 1L)))
        val viewer = mockk<L2RPagerViewer>(relaxed = true)
        every { viewer.activity.viewModel.state } returns state
        val config = PagerConfig(viewer, backgroundScope, preferences)
        runCurrent()
        assertTrue(
            config.landscapeZoom && config.dualPageSplit && config.dualPageRotateToFit &&
                config.dualPageRotateToFitInvert,
        )
        for (mode in listOf(6L, 7L, mihon.domain.reader.ReaderModeFlags.AUTO_LTR, 3L, 4L, 5L)) {
            state.value =
                state.value.copy(
                    manga = state.value.manga!!.copy(viewerFlags = mihon.domain.reader.ReaderModeFlags.write(0L, mode)),
                )
            assertFalse(
                "mode=$mode",
                config.landscapeZoom || config.dualPageSplit || config.dualPageRotateToFit ||
                    config.dualPageRotateToFitInvert,
            )
        }
        state.value = state.value.copy(manga = state.value.manga!!.copy(viewerFlags = 2L))
        assertTrue(config.landscapeZoom && config.dualPageSplit && config.dualPageRotateToFit)
        assertTrue(preferences.dualPageSplitPaged().get())
        state.value = state.value.copy(manga = state.value.manga!!.copy(viewerFlags = 0L))
        preferences.setDefaultReaderSettings(
            mihon.domain.reader.ReaderSettings(layout = mihon.domain.reader.ReaderSettingsLayout.SINGLE),
        )
        assertTrue(config.landscapeZoom && config.dualPageSplit && config.dualPageRotateToFit)
        preferences.setDefaultReaderSettings(
            mihon.domain.reader.ReaderSettings(layout = mihon.domain.reader.ReaderSettingsLayout.AUTO),
        )
        assertFalse(config.landscapeZoom || config.dualPageSplit || config.dualPageRotateToFit)
        assertTrue(preferences.dualPageSplitPaged().get())
        preferences.dualPageSplitWebtoon().set(true)
        preferences.dualPageRotateToFitWebtoon().set(true)
        val webtoon = WebtoonConfig(backgroundScope, preferences)
        runCurrent()
        assertFalse(webtoon.dualPageSplit || webtoon.dualPageRotateToFit)
    }
}
