package tachiyomi.domain.creator.service

data class ChapterVariant(
    val volumeNumber: Double?,
    val chapterNumber: Double?,
    val partNumber: Double?,
    val type: ChapterVariantType,
    val confidence: Double,
    val evidence: String,
)

enum class ChapterVariantType {
    REGULAR,
    SPLIT,
    VOLUME,
    EXTRA,
    SPECIAL,
    UNKNOWN,
}

object ChapterVariantNormalizer {

    private val volumeRegex = Regex("""(?i)\bvol(?:ume)?\.?\s*(\d+(?:\.\d+)?)""")
    private val chapterRegex = Regex("""(?i)\bch(?:apter)?\.?\s*(\d+(?:\.\d+)?)""")
    private val partRegex = Regex("""(?i)\b(?:part|pt)\.?\s*(\d+(?:\.\d+)?)""")
    private val extraRegex = Regex("""(?i)\b(extra|omake|bonus|special|side story)\b""")

    fun normalize(name: String, recognizedChapterNumber: Double): ChapterVariant {
        val volume = volumeRegex.find(name)?.groupValues?.get(1)?.toDoubleOrNull()
        val chapter = chapterRegex.find(name)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: recognizedChapterNumber.takeIf { it >= 0.0 }
        val part = partRegex.find(name)?.groupValues?.get(1)?.toDoubleOrNull()
        val extra = extraRegex.find(name)?.groupValues?.get(1)?.lowercase()

        return when {
            part != null -> ChapterVariant(volume, chapter, part, ChapterVariantType.SPLIT, 0.9, "part token")
            extra == "special" -> ChapterVariant(
                volume,
                chapter,
                null,
                ChapterVariantType.SPECIAL,
                0.8,
                "special token",
            )
            extra != null -> ChapterVariant(volume, chapter, null, ChapterVariantType.EXTRA, 0.8, "extra token")
            chapter != null -> ChapterVariant(volume, chapter, null, ChapterVariantType.REGULAR, 0.85, "chapter number")
            volume != null -> ChapterVariant(volume, null, null, ChapterVariantType.VOLUME, 0.75, "volume token")
            else -> ChapterVariant(null, null, null, ChapterVariantType.UNKNOWN, 0.0, "unrecognized")
        }
    }
}
