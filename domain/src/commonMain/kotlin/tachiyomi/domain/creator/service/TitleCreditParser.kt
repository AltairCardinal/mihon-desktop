package tachiyomi.domain.creator.service

import tachiyomi.domain.creator.model.CreatorMention
import tachiyomi.domain.creator.model.CreatorMentionEvidence
import tachiyomi.domain.creator.model.CreatorMetadataField
import tachiyomi.domain.creator.model.CreatorRole

/** Bounded front credits. Bare names require a candidate or explicitly confirmed creator context. */
object TitleCreditParser {
    private const val MAX_TITLE_LENGTH = 4096
    private const val MAX_PREFIX_LENGTH = 512
    private val activity = Regex(
        """[（(](?:C[0-9]{1,4}|(?:19|20)[0-9]{2}-(?:0[1-9]|1[0-2]) +[^()（）\[\]［］\r\n]{1,96})[）)]""",
    )
    private val ambiguousNameCharacters = setOf(',', '，', '、', '/', '／', ';', '；', '&', '+', '|')
    private val brackets = setOf('(', ')', '（', '）', '[', ']', '［', '］')
    private val nonCreatorLabels = listOf(
        "汉化", "漢化", "翻译", "翻譯", "翻訳", "掃圖", "扫图", "scanlation", "translation", "translator",
        "uploader", "上传", "上傳", "转载", "轉載", "搬运", "搬運",
    )
    private val metadataLabels = setOf(
        "chinese", "english", "japanese", "korean", "中文", "简体中文", "簡體中文", "繁体中文", "繁體中文",
        "日本語", "日文", "日语", "日語", "韓文", "韩文", "全彩", "彩色", "color", "colour", "カラー",
        "zh", "zh-cn", "zh-tw", "zh-hans", "zh-hant", "en", "ja", "ko", "uncensored", "無修正", "无修正",
    )
    private val eventLabel =
        Regex("(?:C[0-9]{1,4}|COMIC1(?:[☆★ ]*[0-9]+)?|COMITIA[ ]*[0-9]+|例大祭[ ]*[0-9]*)", RegexOption.IGNORE_CASE)
    private val personalLabel = Regex("(?:作者|画师|畫師|绘师|繪師|author|artist)[ ]*[:：][ ]*(.+)", RegexOption.IGNORE_CASE)

    fun parse(title: String, allowStandaloneCreator: Boolean = false): List<CreatorMention> {
        if (title.length > MAX_TITLE_LENGTH) return emptyList()
        var start = 0
        while (start < title.length && title[start].isWhitespace()) start++
        if (start == title.length) return emptyList()
        if (title[start] == '(' || title[start] == '（') {
            val marker = activity.find(title, start)?.takeIf { it.range.first == start } ?: return emptyList()
            if (!paired(title[marker.range.first], title[marker.range.last])) return emptyList()
            start = marker.range.last + 1
            while (start < title.length && title[start].isWhitespace()) start++
        }
        if (start >= title.length || title[start] !in setOf('[', '［')) return emptyList()
        val end = title.indexOfFirstFrom(start + 1) { it == ']' || it == '］' }
        if (end < 0 || end - start > MAX_PREFIX_LENGTH || !paired(title[start], title[end])) return emptyList()
        val rawCredits = title.substring(start + 1, end).trim()
        val actualTitle = title.substring(end + 1).trim()
        if (actualTitle.isBlank() || actualTitle.first() in brackets) return emptyList()
        val personalStart = rawCredits.indexOfFirst { it == '(' || it == '（' }
        val credits = if (personalStart < 0) {
            val explicitlyNamed = personalLabel.matchEntire(rawCredits)?.groupValues?.get(1)?.trim()
            val creator = explicitlyNamed ?: rawCredits.takeIf { allowStandaloneCreator } ?: return emptyList()
            if (!validName(creator)) return emptyList()
            listOf(creator to CreatorMetadataField.TITLE_CREATOR)
        } else {
            if (rawCredits.isEmpty() || !paired(rawCredits[personalStart], rawCredits.last())) return emptyList()
            val circle = rawCredits.substring(0, personalStart).trim()
            val creator = rawCredits.substring(personalStart + 1, rawCredits.lastIndex).trim()
            if (!validName(creator)) return emptyList()
            if (personalStart == 0) {
                listOf(creator to CreatorMetadataField.TITLE_CREATOR)
            } else {
                if (!validName(circle)) return emptyList()
                listOf(creator to CreatorMetadataField.TITLE_CREATOR, circle to CreatorMetadataField.TITLE_CIRCLE)
            }
        }
        return credits.groupBy({ it.first }, { it.second }).entries.mapIndexed { index, (name, fields) ->
            CreatorMention(
                displayName = name,
                normalizedName = CreatorNameNormalizer.normalize(name),
                role = CreatorRole.UNKNOWN,
                order = index.toLong(),
                evidence = fields.map { CreatorMentionEvidence(it, title, name, index) },
            )
        }
    }

    private fun validName(name: String): Boolean = name.isNotBlank() &&
        name.none { it in brackets || it in ambiguousNameCharacters } &&
        name.lowercase() !in metadataLabels && !eventLabel.matches(name) &&
        nonCreatorLabels.none { name.contains(it, ignoreCase = true) }

    private fun paired(open: Char, close: Char): Boolean = when (open) {
        '(' -> close == ')'
        '（' -> close == '）'
        '[' -> close == ']'
        '［' -> close == '］'
        else -> false
    }

    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (index in start until length.coerceAtMost(start + MAX_PREFIX_LENGTH + 1)) {
            if (predicate(this[index])) return index
        }
        return -1
    }
}
