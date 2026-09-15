package eu.kanade.tachiyomi.data.backup.restore

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorArchiveSection
import eu.kanade.tachiyomi.data.backup.models.BackupCreatorIdentity
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionRepoRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import mihon.data.sync.journal.NoopBackupRestoreSync
import org.junit.jupiter.api.Test
import tachiyomi.data.backup.AuthorArchiveBackupContributor
import java.io.File

class BackupRestorerBehaviorTest {

    @Test
    fun `current Android restorer sends fixed-main manga through production restore and progress`() = runTest {
        val bytes = repositoryFile("data/src/commonTest/resources/backup/android-full.tachibk").readBytes()
        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } returns bytes.inputStream()
        val notifier = mockk<BackupNotifier>(relaxed = true)
        val mangaRestorer = mockk<MangaRestorer>()
        val authorArchiveBackupContributor = mockk<AuthorArchiveBackupContributor>(relaxed = true)
        coEvery { mangaRestorer.sortByNew(any()) } answers { firstArg() }
        coEvery { mangaRestorer.restore(any(), any()) } returns Unit
        val restorer = BackupRestorer(
            context = context,
            notifier = notifier,
            isSync = false,
            categoriesRestorer = mockk<CategoriesRestorer>(relaxed = true),
            preferenceRestorer = mockk<PreferenceRestorer>(relaxed = true),
            extensionRepoRestorer = mockk<ExtensionRepoRestorer>(relaxed = true),
            mangaRestorer = mangaRestorer,
            authorArchiveBackupContributor = authorArchiveBackupContributor,
            backupRestoreSync = NoopBackupRestoreSync,
        )

        restorer.restore(
            uri,
            RestoreOptions(
                libraryEntries = true,
                categories = false,
                appSettings = false,
                extensionRepoSettings = false,
                sourceSettings = false,
            ),
        )

        coVerify(exactly = 1) {
            mangaRestorer.sortByNew(match { it.single().title == "Canonical manga" })
        }
        coVerify(exactly = 1) {
            mangaRestorer.restore(
                match<BackupManga> { it.title == "Canonical manga" && it.categories == listOf(1L) },
                emptyList(),
            )
        }
        verify(exactly = 1) {
            notifier.showRestoreProgress(
                content = "Canonical manga",
                progress = 1,
                maxAmount = 1,
                sync = false,
            )
        }
        verify(exactly = 1) {
            notifier.showRestoreComplete(any(), 0, any(), any(), false)
        }
        coVerify(exactly = 0) { authorArchiveBackupContributor.restoreSection(any()) }
    }

    @Test
    fun `Android restore applies field 107 through shared contributor`() = runTest {
        val section = BackupAuthorArchiveSection(
            creators = listOf(BackupCreatorIdentity("creator-one", "ONE", "one")),
        )
        val bytes = tachiyomi.data.backup.BackupCodec.encode(
            eu.kanade.tachiyomi.data.backup.models.Backup.serializer(),
            eu.kanade.tachiyomi.data.backup.models.Backup(
                backupManga = emptyList(),
                backupAuthorArchive = section,
            ),
        )
        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } returns bytes.inputStream()
        val contributor = mockk<AuthorArchiveBackupContributor>()
        coEvery { contributor.restoreSection(section) } returns Unit

        BackupRestorer(
            context = context,
            notifier = mockk(relaxed = true),
            isSync = false,
            categoriesRestorer = mockk(relaxed = true),
            preferenceRestorer = mockk(relaxed = true),
            extensionRepoRestorer = mockk(relaxed = true),
            mangaRestorer = mockk(relaxed = true),
            authorArchiveBackupContributor = contributor,
            backupRestoreSync = NoopBackupRestoreSync,
        ).restore(
            uri,
            RestoreOptions(
                libraryEntries = true,
                categories = false,
                appSettings = false,
                extensionRepoSettings = false,
                sourceSettings = false,
            ),
        )

        coVerify(exactly = 1) { contributor.restoreSection(section) }
    }

    private fun repositoryFile(relativePath: String): File =
        generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile, File::getParentFile)
            .map { it.resolve(relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Repository file not found: $relativePath")
}
