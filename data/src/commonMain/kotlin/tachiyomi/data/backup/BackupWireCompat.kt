package tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer

internal object BackupWireCompat {
    private const val BACKUP_EXTENSION_FIELD = 106
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5

    fun normalizeWire(payload: ByteArray): ByteArray {
        val reader = WireReader(payload)
        var modified = false
        val writer = WireWriter()

        while (!reader.isAtEnd) {
            val tag = reader.readVarint()
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()

            if (fieldNumber == BACKUP_EXTENSION_FIELD) {
                if (wireType != WIRE_LENGTH_DELIMITED) {
                    throw InvalidBackupException("Invalid wire type $wireType for repeated extension field 106")
                }
                val lengthLong = reader.readVarint()
                if (lengthLong < 0 || lengthLong > Int.MAX_VALUE) {
                    throw InvalidBackupException("Invalid length for field 106: $lengthLong")
                }
                val length = lengthLong.toInt()
                val entryBytes = reader.readByteArray(length)
                val normalizedEntry = normalizeExtensionEntry(entryBytes)
                if (normalizedEntry !== entryBytes) {
                    modified = true
                    writer.writeLengthDelimited(BACKUP_EXTENSION_FIELD, normalizedEntry)
                } else {
                    writer.writeLengthDelimited(BACKUP_EXTENSION_FIELD, entryBytes)
                }
            } else {
                when (wireType) {
                    WIRE_VARINT -> {
                        val value = reader.readVarint()
                        writer.writeTag(fieldNumber, wireType)
                        writer.writeVarint(value)
                    }
                    WIRE_FIXED64 -> {
                        val fixed = reader.readByteArray(8)
                        writer.writeTag(fieldNumber, wireType)
                        writer.writeRawBytes(fixed)
                    }
                    WIRE_LENGTH_DELIMITED -> {
                        val lengthLong = reader.readVarint()
                        if (lengthLong < 0 || lengthLong > Int.MAX_VALUE) {
                            throw InvalidBackupException("Invalid length for field $fieldNumber: $lengthLong")
                        }
                        val length = lengthLong.toInt()
                        val data = reader.readByteArray(length)
                        writer.writeLengthDelimited(fieldNumber, data)
                    }
                    WIRE_FIXED32 -> {
                        val fixed = reader.readByteArray(4)
                        writer.writeTag(fieldNumber, wireType)
                        writer.writeRawBytes(fixed)
                    }
                    else -> throw InvalidBackupException("Invalid wire type $wireType at field $fieldNumber")
                }
            }
        }

        return if (modified) writer.toByteArray() else payload
    }

    private fun normalizeExtensionEntry(entryBytes: ByteArray): ByteArray {
        val reader = WireReader(entryBytes)
        var hasVarintField7 = false
        var hasDelimitedField7 = false

        var field1IndexUrl: String? = null
        var field2Name: String? = null
        var field3BadgeLabel: String? = null
        var field4ContactWebsite: String? = null
        var field5SigningKey: String? = null
        var field6Discord: String? = null
        var field8ListUrl: String? = null

        while (!reader.isAtEnd) {
            val tag = reader.readVarint()
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()

            when (fieldNumber) {
                7 -> {
                    when (wireType) {
                        WIRE_VARINT -> {
                            if (hasVarintField7 || hasDelimitedField7) {
                                throw InvalidBackupException("Conflicting or duplicate field 7 in extension entry")
                            }
                            hasVarintField7 = true
                            reader.readVarint()
                        }
                        WIRE_LENGTH_DELIMITED -> {
                            if (hasVarintField7 || hasDelimitedField7) {
                                throw InvalidBackupException("Conflicting or duplicate field 7 in extension entry")
                            }
                            hasDelimitedField7 = true
                            val len = reader.readVarint().toInt()
                            reader.skip(len)
                        }
                        else -> throw InvalidBackupException("Invalid wire type $wireType for field 7")
                    }
                }
                1 -> {
                    if (wireType != WIRE_LENGTH_DELIMITED) throw InvalidBackupException("Invalid wire type for field 1")
                    val len = reader.readVarint().toInt()
                    field1IndexUrl = reader.readByteArray(len).decodeToString()
                }
                2 -> {
                    if (wireType != WIRE_LENGTH_DELIMITED) throw InvalidBackupException("Invalid wire type for field 2")
                    val len = reader.readVarint().toInt()
                    field2Name = reader.readByteArray(len).decodeToString()
                }
                3 -> {
                    if (wireType != WIRE_LENGTH_DELIMITED) throw InvalidBackupException("Invalid wire type for field 3")
                    val len = reader.readVarint().toInt()
                    field3BadgeLabel = reader.readByteArray(len).decodeToString()
                }
                4 -> {
                    if (wireType != WIRE_LENGTH_DELIMITED) throw InvalidBackupException("Invalid wire type for field 4")
                    val len = reader.readVarint().toInt()
                    field4ContactWebsite = reader.readByteArray(len).decodeToString()
                }
                5 -> {
                    if (wireType != WIRE_LENGTH_DELIMITED) throw InvalidBackupException("Invalid wire type for field 5")
                    val len = reader.readVarint().toInt()
                    field5SigningKey = reader.readByteArray(len).decodeToString()
                }
                6 -> {
                    if (wireType != WIRE_LENGTH_DELIMITED) throw InvalidBackupException("Invalid wire type for field 6")
                    val len = reader.readVarint().toInt()
                    field6Discord = reader.readByteArray(len).decodeToString()
                }
                8 -> {
                    if (wireType != WIRE_LENGTH_DELIMITED) throw InvalidBackupException("Invalid wire type for field 8")
                    val len = reader.readVarint().toInt()
                    field8ListUrl = reader.readByteArray(len).decodeToString()
                }
                else -> {
                    when (wireType) {
                        WIRE_VARINT -> reader.readVarint()
                        WIRE_FIXED64 -> reader.skip(8)
                        WIRE_LENGTH_DELIMITED -> {
                            val len = reader.readVarint().toInt()
                            reader.skip(len)
                        }
                        WIRE_FIXED32 -> reader.skip(4)
                        else -> throw InvalidBackupException("Invalid wire type $wireType for field $fieldNumber")
                    }
                }
            }
        }

        if (hasVarintField7) {
            // It is an upstream BackupExtensionStore
            val indexUrl = field1IndexUrl ?: throw InvalidBackupException("Missing indexUrl in BackupExtensionStore")
            val name = field2Name ?: throw InvalidBackupException("Missing name in BackupExtensionStore")
            val signingKey = field5SigningKey.orEmpty()
            val website = field4ContactWebsite.orEmpty()

            val normalizedRepo = BackupExtensionRepos(
                baseUrl = indexUrl,
                name = name,
                shortName = field3BadgeLabel ?: name,
                website = website,
                signingKeyFingerprint = signingKey,
                indexUrl = indexUrl,
                extensionListUrl = field8ListUrl,
                contactDiscord = field6Discord,
            )
            return ProtoBuf.encodeToByteArray(BackupExtensionRepos.serializer(), normalizedRepo)
        }

        return entryBytes
    }

    private class WireReader(private val bytes: ByteArray) {
        var position = 0
        val isAtEnd: Boolean get() = position >= bytes.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (position < bytes.size) {
                val b = bytes[position++].toLong()
                result = result or ((b and 0x7FL) shl shift)
                if ((b and 0x80L) == 0L) {
                    return result
                }
                shift += 7
                if (shift > 64) throw InvalidBackupException("Malformed varint in protobuf wire")
            }
            throw InvalidBackupException("Unexpected EOF while reading varint")
        }

        fun readByteArray(length: Int): ByteArray {
            if (length < 0 || position + length > bytes.size) {
                throw InvalidBackupException("Unexpected EOF while reading length-delimited bytes")
            }
            val slice = bytes.copyOfRange(position, position + length)
            position += length
            return slice
        }

        fun skip(length: Int) {
            if (length < 0 || position + length > bytes.size) {
                throw InvalidBackupException("Unexpected EOF while skipping bytes")
            }
            position += length
        }
    }

    private class WireWriter {
        private val buffer = Buffer()

        fun writeVarint(value: Long) {
            var v = value
            while ((v and 0x7FL.inv()) != 0L) {
                buffer.writeByte(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            buffer.writeByte((v and 0x7F).toInt())
        }

        fun writeTag(fieldNumber: Int, wireType: Int) {
            writeVarint((fieldNumber.toLong() shl 3) or wireType.toLong())
        }

        fun writeLengthDelimited(fieldNumber: Int, payload: ByteArray) {
            writeTag(fieldNumber, WIRE_LENGTH_DELIMITED)
            writeVarint(payload.size.toLong())
            buffer.write(payload)
        }

        fun writeRawBytes(bytes: ByteArray) {
            buffer.write(bytes)
        }

        fun toByteArray(): ByteArray = buffer.readByteArray()
    }
}
