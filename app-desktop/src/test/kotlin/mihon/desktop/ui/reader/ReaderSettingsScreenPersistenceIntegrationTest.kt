package mihon.desktop.ui.reader

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.Navigator
import dev.mihon.injekt.patchInjekt
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.reader.DesktopReaderRuntimeFactory
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.reader.ReaderScrollMode
import mihon.domain.reader.ReaderSettingsCodec
import mihon.domain.reader.ReaderSettingsMode
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File
import java.util.prefs.Preferences

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ReaderSettingsScreenPersistenceIntegrationTest {
    @Test
    fun `real reader settings clicks persist through runtime repository and reopening`(
        @TempDir directory: File,
    ) = runTest {
        val root = Preferences.userRoot().node("/mihon/reader-settings-screen/${System.nanoTime()}")
        val prefs = ReaderPreferences(DesktopPreferenceStore(root.node("store")), root.node("legacy"))
        val repository = FakeMangaRepository().apply { seed(Manga.create().copy(id = 44L, source = 9L)) }
        val sources = mockk<SourceManager>(relaxed = true) {
            every { isInitialized } returns MutableStateFlow(true)
            every { this@mockk.get(any<Long>()) } returns null
            every { getOrStub(any()) } answers { StubSource(firstArg(), "", "") }
        }
        val factory = spyk(
            DesktopReaderRuntimeFactory(
                prefs = prefs,
                downloadProvider = DesktopDownloadProvider(directory.resolve("downloads")),
                sourceManager = sources,
                networkHelper = NetworkHelper(OkHttpClient()),
                progressTracker = mockk<ReaderProgressTracker>(relaxed = true),
                mangaRepository = repository,
                encodedCacheDirectory = directory.resolve("encoded"),
            ),
        )
        lateinit var model: ReaderScreenModel
        val createdModels = mutableListOf<ReaderScreenModel>()
        every { factory.createModel(any(), any(), any(), any(), any(), any()) } answers {
            callOriginal().also {
                model = it
                createdModels += it
            }
        }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns
                DesktopAppPreferences(DesktopPreferenceStore(root.node("app")), root.node("legacy-app"))
            every { notificationService } returns DesktopNotificationService()
        }
        val previousInjekt = Injekt
        var scene: ImageComposeScene? = null
        var time = 0L
        fun frames() = repeat(5) {
            time += 16_000_000L
            requireNotNull(scene).render(time).close()
            runCurrent()
        }
        fun nodes(): List<SemanticsNode> {
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            return requireNotNull(scene).semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
        }
        fun click(label: String) {
            fun containsLabel(node: SemanticsNode): Boolean =
                (
                    node.config.contains(SemanticsProperties.Text) &&
                        node.config[SemanticsProperties.Text].any { it.text == label }
                    ) ||
                    (
                        node.config.contains(SemanticsProperties.ContentDescription) &&
                            label in node.config[SemanticsProperties.ContentDescription]
                        ) ||
                    node.children.any(::containsLabel)
            val node = nodes().first { it.config.contains(SemanticsActions.OnClick) && containsLabel(it) }
            val position = node.boundsInRoot.center
            requireNotNull(scene).sendPointerEvent(PointerEventType.Press, position)
            requireNotNull(scene).sendPointerEvent(PointerEventType.Release, position)
            frames()
        }
        fun mount(flags: Long) {
            val screen = DesktopReaderScreen(
                chapterTitle = "Chapter",
                chapterId = createdModels.size.toLong() + 1L,
                chapterUrl = "/chapter/${createdModels.size + 1}",
                mangaTitle = "Manga",
                mangaId = 44L,
                sourceId = 9L,
                mangaViewerFlags = flags,
            )
            scene = ImageComposeScene(900, 1600, coroutineContext = coroutineContext) {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(screen) { screen.Content() }
                }
            }
            frames()
            model.toggleUI()
            frames()
            click(MR.strings.desktop_ui_reader_settings.localized())
        }
        try {
            patchInjekt()
            Injekt.addSingleton<DesktopReaderRuntimeFactory>(factory)
            mount(0L)
            click(MR.strings.reader_settings_custom.localized())
            click(MR.strings.reader_settings_vertical.localized())
            click(MR.strings.reader_settings_paged.localized())
            val custom = requireNotNull(repository.get(44L)).viewerFlags
            assertEquals(ReaderSettingsMode.VERTICAL, ReaderSettingsCodec.custom(custom).mode)
            assertEquals(ReaderScrollMode.PAGED, ReaderSettingsCodec.custom(custom).scroll)
            click(MR.strings.reader_settings_follow_default.localized())
            val followed = requireNotNull(repository.get(44L)).viewerFlags
            assertTrue(ReaderSettingsCodec.isFollowingDefault(followed))
            requireNotNull(scene).close()
            scene = null
            runCurrent()
            mount(followed)
            click(MR.strings.reader_settings_custom.localized())
            assertEquals(2, createdModels.size)
            assertEquals(ReaderSettingsCodec.custom(custom), model.state.value.readerSettings)
            assertEquals(
                ReaderSettingsCodec.custom(custom),
                ReaderSettingsCodec.custom(requireNotNull(repository.get(44L)).viewerFlags),
            )
            assertTrue(repository.updates.all { it.id == 44L })
        } finally {
            scene?.close()
            createdModels.forEach(ReaderScreenModel::onDispose)
            factory.stop()
            factory.awaitStopped()
            Injekt = previousInjekt
            root.removeNode()
        }
    }
}
