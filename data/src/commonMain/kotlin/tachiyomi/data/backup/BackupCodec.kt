package tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.GzipSink
import okio.GzipSource
import okio.buffer

/** Platform-neutral codec for Mihon's canonical protobuf backup envelope. */
object BackupCodec {
    private val protobuf = ProtoBuf

    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray {
        val output = Buffer()
        GzipSink(output).buffer().use { it.write(encodePlain(serializer, value)) }
        return output.readByteArray()
    }

    fun <T> encodePlain(serializer: KSerializer<T>, value: T): ByteArray =
        protobuf.encodeToByteArray(serializer, value)

    fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T {
        if (bytes.isEmpty() || bytes.looksLikeJson()) throw InvalidBackupException("Unsupported JSON backup")
        return try {
            val payload = if (bytes.isGzip()) {
                GzipSource(Buffer().write(bytes)).buffer().use { it.readByteArray() }
            } else {
                bytes
            }
            protobuf.decodeFromByteArray(serializer, payload).also(::validateSemantics)
        } catch (error: Exception) {
            if (error is InvalidBackupException) throw error
            throw InvalidBackupException("Invalid protobuf backup", error)
        }
    }

    private fun ByteArray.isGzip() = size >= 2 && this[0] == 0x1f.toByte() && this[1] == 0x8b.toByte()

    private fun ByteArray.looksLikeJson() = firstOrNull { !it.toInt().toChar().isWhitespace() } == '{'.code.toByte()

    private fun validateSemantics(value: Any?) {
        if (
            value is Backup &&
            value.backupManga.isEmpty() &&
            value.backupCategories.isEmpty() &&
            value.backupSources.isEmpty() &&
            value.backupPreferences.isEmpty() &&
            value.backupSourcePreferences.isEmpty() &&
            value.backupExtensionRepo.isEmpty()
        ) {
            throw InvalidBackupException("Backup contains no restorable data")
        }
    }
}

class InvalidBackupException(message: String, cause: Throwable? = null) : SerializationException(message, cause)
