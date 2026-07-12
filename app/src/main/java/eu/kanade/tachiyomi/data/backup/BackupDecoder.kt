package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.models.Backup
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.data.backup.BackupCodec
import tachiyomi.data.backup.InvalidBackupException
import tachiyomi.i18n.MR
import java.io.IOException
import java.io.InputStream

class BackupDecoder(
    private val context: Context,
) {
    /**
     * Decode a potentially-gzipped backup.
     */
    fun decode(uri: Uri): Backup {
        return context.contentResolver.openInputStream(uri)!!.use { inputStream ->
            decode(inputStream)
        }
    }

    fun decode(inputStream: InputStream): Backup {
        try {
            return BackupCodec.decode(Backup.serializer(), inputStream.readBytes())
        } catch (error: InvalidBackupException) {
            val message = if (error.message?.contains("JSON") == true) {
                MR.strings.invalid_backup_file_json
            } else {
                MR.strings.invalid_backup_file_unknown
            }
            throw IOException(context.stringResource(message), error)
        }
    }
}
