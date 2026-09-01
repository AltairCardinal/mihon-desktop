package mihon.desktop.download

/** Shared mapping between Reader ordinals and committed/staging download page filenames. */
object DownloadPageFileNamingPolicy {
    fun committedFileName(readerOrdinal: Int, extension: String): String {
        require(readerOrdinal >= 0) { "Reader ordinal must be non-negative" }
        val normalizedExtension = extension.trim().trimStart('.').lowercase().ifBlank { "jpg" }
        return "${baseName(readerOrdinal)}.$normalizedExtension"
    }

    fun stagingFileName(readerOrdinal: Int, generation: Long): String =
        "${baseName(readerOrdinal)}.$generation.tmp"

    fun readerOrdinal(fileName: String): Int? {
        val parts = fileName.split('.')
        if (parts.size != 2 || parts[1].equals("tmp", ignoreCase = true)) return null
        return ordinalFromBaseName(parts[0])
    }

    fun stagingReaderOrdinal(fileName: String): Int? {
        val parts = fileName.split('.')
        if (!parts.lastOrNull().equals("tmp", ignoreCase = true)) return null
        if (parts.size !in 2..3) return null
        if (parts.size == 3 && parts[1].toLongOrNull() == null) return null
        return ordinalFromBaseName(parts[0])
    }

    private fun ordinalFromBaseName(baseName: String): Int? {
        val pageNumber = baseName.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull() ?: return null
        return (pageNumber - 1).takeIf { it >= 0 }
    }

    private fun baseName(readerOrdinal: Int): String = (readerOrdinal + 1).toString().padStart(3, '0')
}
