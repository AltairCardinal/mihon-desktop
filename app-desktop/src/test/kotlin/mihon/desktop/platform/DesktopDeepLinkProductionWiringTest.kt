package mihon.desktop.platform

import kotlinx.coroutines.test.runTest
import mihon.desktop.backup.BackupRestoreScreenModelFactory
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.ui.browse.GlobalSearchScreen
import mihon.desktop.ui.settings.BackupRestoreUiState
import mihon.domain.platform.ExternalActionInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import java.nio.file.Files
import java.nio.file.Path

class DesktopDeepLinkProductionWiringTest {

    @Test
    fun `GlobalSearch screen consumes handler target without losing query`() = runTest {
        val target = assertInstanceOf(
            DesktopExternalActionTarget.GlobalSearch::class.java,
            handler().resolve(ExternalActionInput.Search("no matching source")),
        )

        val screen = GlobalSearchScreen.fromExternalActionTarget(target)

        assertEquals("no matching source", screen.initialQuery)
    }

    @Test
    fun `backup factory rejects remote missing and directory paths`(@TempDir directory: Path) = runTest {
        val remote = handler().resolve(ExternalActionInput.ViewUri("https://example.org/backup.tachibk"))
        val missingUri = directory.resolve("missing.tachibk").toUri().toString()
        val missing = handler().resolve(ExternalActionInput.ViewUri(missingUri))
        val disguisedDirectory = directory.resolve("folder.tachibk").also(Files::createDirectory)
        val notAFile = handler().resolve(ExternalActionInput.ViewUri(disguisedDirectory.toUri().toString()))

        assertInstanceOf(DesktopExternalActionTarget.Rejected::class.java, remote)
        assertInstanceOf(DesktopExternalActionTarget.Rejected::class.java, missing)
        assertInstanceOf(DesktopExternalActionTarget.Rejected::class.java, notAFile)
    }

    @Test
    fun `valid backup target is selected by existing restore factory`(@TempDir directory: Path) = runTest {
        val backup = directory.resolve("valid.tachibk").also { Files.write(it, byteArrayOf(1)) }
        val resolved = handler().resolve(ExternalActionInput.ViewUri(backup.toUri().toString()))
        val target = assertInstanceOf(DesktopExternalActionTarget.Backup::class.java, resolved)
        val factory = BackupRestoreScreenModelFactory(
            mangaRepository = io.mockk.mockk(relaxed = true),
            chapterRepository = io.mockk.mockk(relaxed = true),
            categoryRepository = io.mockk.mockk(relaxed = true),
            historyRepository = io.mockk.mockk(relaxed = true),
            getExcludedScanlators = io.mockk.mockk(relaxed = true),
            setExcludedScanlators = io.mockk.mockk(relaxed = true),
            trackRepository = io.mockk.mockk(relaxed = true),
            preferenceStore = io.mockk.mockk(relaxed = true),
            extensionRepoRepository = io.mockk.mockk(relaxed = true),
        )

        val model = factory.create(target, backgroundScope)

        assertEquals(backup.toRealPath().toFile(), target.file)
        val loading = assertInstanceOf(BackupRestoreUiState.Loading::class.java, model.state.value)
        assertEquals(backup.fileName.toString(), loading.fileName)
        model.onDispose()
    }

    private fun handler(): DesktopDeepLinkHandler {
        val mangaRepository = FakeMangaRepository()
        val save = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepository), mangaRepository, FakeChapterRepository())
        return DesktopDeepLinkHandler(FakeDesktopSourceManager(emptyList()), save)
    }
}
