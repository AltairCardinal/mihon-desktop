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
}
