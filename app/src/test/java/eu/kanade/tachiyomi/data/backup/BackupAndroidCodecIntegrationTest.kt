package eu.kanade.tachiyomi.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackupAndroidCodecIntegrationTest {
    @Test
    fun `LTR dual page survives production backup codec and manga restoration`() {
        val flags = mihon.domain.reader.ReaderModeFlags.write(0x38L, 8L)
        val original = Backup(listOf(BackupManga(source = 7, url = "/dual", viewer_flags = flags.toInt())))
        val restored = BackupDecoder(mockk(relaxed = true))
            .decode(BackupCreator.encodeForBackup(original).inputStream()).backupManga.single().getMangaImpl()
        assertEquals(flags, restored.viewerFlags)
        assertEquals(8L, mihon.domain.reader.ReaderModeFlags.read(restored.viewerFlags))
    }
    private val expected = Backup(listOf(BackupManga(source = 7, url = "/android", title = "Android")))

    @Test
    fun `decoder reads common codec backup from input stream`() {
        val decoder = BackupDecoder(mockk(relaxed = true))

        assertEquals(expected, decoder.decode(BackupCreator.encodeForBackup(expected).inputStream()))
    }

    @Test
    fun `decoder reads common codec backup through content uri`() {
        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        val context = mockk<Context>()
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } returns BackupCreator.encodeForBackup(expected).inputStream()

        assertEquals(expected, BackupDecoder(context).decode(uri))
    }

    @Test
    fun `decoder reads original Mihon v0_21_1 backup through input stream and content uri`() {
        val fixtureFile = generateSequence(java.io.File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { java.io.File(it, "data/src/commonTest/resources/backup/mihon-v0.21.1-full.tachibk") }
            .first { it.isFile }
        val bytes = fixtureFile.readBytes()

        val decoder = BackupDecoder(mockk(relaxed = true))
        val decodedFromStream = decoder.decode(bytes.inputStream())
        assertEquals(1, decodedFromStream.backupManga.size)
        assertEquals("V0.21.1 Canonical manga", decodedFromStream.backupManga.single().title)
        assertEquals(2, decodedFromStream.backupExtensionRepo.size)
        assertEquals("Keiyoushi", decodedFromStream.backupExtensionRepo[0].name)
        assertEquals("Modern Store", decodedFromStream.backupExtensionRepo[1].name)

        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        val context = mockk<Context>()
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } returns bytes.inputStream()

        val decodedFromUri = BackupDecoder(context).decode(uri)
        assertEquals(decodedFromStream, decodedFromUri)
    }
}
