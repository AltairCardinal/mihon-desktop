package tachiyomi.domain.creator.service

data class ChapterVariant(
    val volumeNumber: Double?,
    val chapterNumber: Double?,
    val partNumber: Double?,
    val type: ChapterVariantType,
    val confidence: Double,
    val evidence: String,
)

data class ChapterVariantInput(
    val naturalKey: String,
    val rawName: String,
    val recognizedChapterNumber: Double,
    val scanlator: String?,
)

data class ChapterVariantRecord(
    val naturalKey: String,
    val rawName: String,
    val scanlator: String?,
    val volumeNumber: Double?,
    val chapterNumber: Double?,
    val partNumber: Double?,
    val type: ChapterVariantType,
    val confidence: Double,
    val evidence: String,
)

data class ChapterVariantSummary(
    val variants: List<ChapterVariantRecord>,
    val regularChapterCount: Int,
    val splitChapterCount: Int,
    val decimalChapterCount: Int,
    val volumeCount: Int,
    val extraChapterCount: Int,
    val specialChapterCount: Int,
    val duplicateReleaseCount: Int,
    val missingChapterNumbers: List<Long>,
    val unknownRawNames: List<String>,
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

    fun summarize(inputs: List<ChapterVariantInput>): ChapterVariantSummary {
        val variants = inputs.distinctBy(ChapterVariantInput::naturalKey).map { input ->
            val variant = normalize(input.rawName, input.recognizedChapterNumber)
            ChapterVariantRecord(
                naturalKey = input.naturalKey,
                rawName = input.rawName,
                scanlator = input.scanlator,
                volumeNumber = variant.volumeNumber,
                chapterNumber = variant.chapterNumber,
                partNumber = variant.partNumber,
                type = variant.type,
                confidence = variant.confidence,
                evidence = variant.evidence,
            )
        }
        val numbered = variants.filter { it.chapterNumber != null && it.type != ChapterVariantType.UNKNOWN }
        val integerNumbers = numbered.mapNotNull { record ->
            record.chapterNumber?.takeIf { it % 1.0 == 0.0 }?.toLong()
        }.distinct().sorted()
        val missing = integerNumbers.zipWithNext().flatMap { (left, right) ->
            val gap = right - left
            if (gap in 2..MAX_TRUSTED_MISSING_GAP) ((left + 1) until right).toList() else emptyList()
        }
        val duplicateReleases = numbered
            .filter { it.partNumber == null && it.type == ChapterVariantType.REGULAR }
            .groupBy { it.chapterNumber }
            .values
            .sumOf { (it.size - 1).coerceAtLeast(0) }
        return ChapterVariantSummary(
            variants = variants,
            regularChapterCount = variants.count { it.type == ChapterVariantType.REGULAR },
            splitChapterCount = variants.count { it.type == ChapterVariantType.SPLIT },
            decimalChapterCount = numbered.count { checkNotNull(it.chapterNumber) % 1.0 != 0.0 },
            volumeCount = variants.mapNotNull(ChapterVariantRecord::volumeNumber).distinct().size,
            extraChapterCount = variants.count { it.type == ChapterVariantType.EXTRA },
            specialChapterCount = variants.count { it.type == ChapterVariantType.SPECIAL },
            duplicateReleaseCount = duplicateReleases,
            missingChapterNumbers = missing,
            unknownRawNames = variants.filter {
                it.type == ChapterVariantType.UNKNOWN
            }.map(ChapterVariantRecord::rawName),
        )
    }

    private const val MAX_TRUSTED_MISSING_GAP = 3L
}
