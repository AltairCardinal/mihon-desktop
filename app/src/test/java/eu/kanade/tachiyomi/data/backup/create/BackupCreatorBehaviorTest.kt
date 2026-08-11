package eu.kanade.tachiyomi.data.backup.create

import android.content.Context
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionRepoBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorArchiveSection
import eu.kanade.tachiyomi.data.backup.models.BackupCreatorIdentity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.data.backup.AuthorArchiveBackupContributor
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.repository.MangaRepository

class BackupCreatorBehaviorTest {

    @Test
    fun `Android payload includes field 107 through shared contributor`() = runTest {
        val section = BackupAuthorArchiveSection(
            creators = listOf(BackupCreatorIdentity("creator-one", "ONE", "one")),
        )
        val getFavorites = mockk<GetFavorites>()
        coEvery { getFavorites.await() } returns emptyList()
        val mangaBackupCreator = mockk<MangaBackupCreator>()
        coEvery { mangaBackupCreator(any(), any()) } returns emptyList()
        val sourcesBackupCreator = mockk<SourcesBackupCreator>()
        every { sourcesBackupCreator(any()) } returns emptyList()
        val contributor = mockk<AuthorArchiveBackupContributor>()
        coEvery { contributor.createSection() } returns section
        val creator = BackupCreator(
            context = mockk<Context>(relaxed = true),
            isAutoBackup = false,
            getFavorites = getFavorites,
            backupPreferences = mockk<BackupPreferences>(relaxed = true),
            mangaRepository = mockk<MangaRepository>(relaxed = true),
            categoriesBackupCreator = mockk<CategoriesBackupCreator>(relaxed = true),
            mangaBackupCreator = mangaBackupCreator,
            preferenceBackupCreator = mockk<PreferenceBackupCreator>(relaxed = true),
            extensionRepoBackupCreator = mockk<ExtensionRepoBackupCreator>(relaxed = true),
            sourcesBackupCreator = sourcesBackupCreator,
            authorArchiveBackupContributor = contributor,
        )

        val backup = creator.createPayload(
            BackupOptions(
                libraryEntries = true,
                categories = false,
                readEntries = false,
                appSettings = false,
                extensionRepoSettings = false,
                sourceSettings = false,
            ),
        )

        assertEquals(section, backup.backupAuthorArchive)
    }
}
