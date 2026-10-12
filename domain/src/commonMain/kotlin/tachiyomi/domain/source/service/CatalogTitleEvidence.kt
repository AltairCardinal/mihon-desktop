package tachiyomi.domain.source.service

import tachiyomi.domain.creator.model.CreatorArchiveLanguageTag

/** Language/translation evidence only. This does not parse creators or creative circles. */
internal data class CatalogTitleEvidence(
    val languageTags: Set<String> = emptySet(),
    val conflict: Boolean = false,
    val rawLanguageEvidence: List<String> = emptyList(),
    val translationGroups: List<TranslationGroupCredit> = emptyList(),
    val textOutsideMarkers: String = "",
    val complete: Boolean = true,
)

internal object CatalogLanguageLabels {
    private val aliases = mapOf(
        "english" to "en",
        "英语" to "en",
        "英語" to "en",
        "japanese" to "ja",
        "日语" to "ja",
        "日語" to "ja",
        "日本語" to "ja",
        "korean" to "ko",
        "韩语" to "ko",
        "韓語" to "ko",
        "한국어" to "ko",
        "chinese" to "zh",
        "中文" to "zh",
        "汉语" to "zh",
        "漢語" to "zh",
        "中国翻訳" to "zh",
        "中国翻译" to "zh",
        "中國翻譯" to "zh",
        "简体中文" to "zh-hans",
        "簡體中文" to "zh-hans",
        "简体" to "zh-hans",
        "簡體" to "zh-hans",
        "繁体中文" to "zh-hant",
        "繁體中文" to "zh-hant",
        "繁体" to "zh-hant",
        "繁體" to "zh-hant",
        "simplified chinese" to "zh-hans",
        "traditional chinese" to "zh-hant",
        "french" to "fr",
        "spanish" to "es",
        "portuguese" to "pt",
        "german" to "de",
        "italian" to "it",
        "russian" to "ru",
        "arabic" to "ar",
        "vietnamese" to "vi",
        "indonesian" to "id",
        "thai" to "th",
    )
    private val supportedLanguageCodes = aliases.values.map { it.substringBefore('-') }.toSet()
    private val whitespace = Regex("\\s+")

    /** Normalize the interpretation of a language label, never a translation group's identity. */
    fun normalizeLabel(value: String): String = value.map { character ->
        when (character) {
            in '\uff01'..'\uff5e' -> (character.code - 0xfee0).toChar()
            '\u3000' -> ' '
            else -> character
        }
    }.joinToString("").trim().lowercase().replace(whitespace, " ").replace('_', '-')

    fun languageTag(value: String): String? {
        if (value.length > 128) return null
        val label = normalizeLabel(value)
        aliases[label]?.let { return it }
        if (label.substringBefore('-') !in supportedLanguageCodes) return null
        return CreatorArchiveLanguageTag.normalize(label).takeUnless { it == "und" }
    }

    /** Generic tags may narrow to a compatible script/region; different scripts remain conflicts. */
    fun compatibleTag(tags: Collection<String>): String? = tags.distinct().sortedByDescending(String::length)
        .firstOrNull()?.takeIf { mostSpecific -> tags.all { mostSpecific == it || mostSpecific.startsWith("$it-") } }
}

internal object CatalogTitleEvidenceParser {
    private const val MAX_TITLE_LENGTH = 4096
    private const val MAX_MARKER_LENGTH = 512
    private const val MAX_BRACKET_DEPTH = 8
    private val closingBrackets = mapOf('[' to ']', '［' to '］', '【' to '】', '(' to ')', '（' to '）')
    private val allClosingBrackets = closingBrackets.values.toSet()
    private val chineseRoleLabels = setOf(
        "汉化",
        "漢化",
        "汉化组",
        "漢化組",
        "汉化組",
        "漢化组",
        "中文翻译",
        "中文翻譯",
        "中文机翻",
        "中文機翻",
        "ai汉化",
        "ai漢化",
        "机器汉化",
        "機器漢化",
        "个人汉化",
        "個人漢化",
        "机翻汉化",
        "機翻漢化",
    )
    private val chineseGroupSuffixes = listOf("汉化组", "漢化組", "汉化組", "漢化组", "汉化", "漢化")

    // User-confirmed translation credit. Require edition-credit context, not a bare name mention.
    private val knownChineseEditionGroups = setOf("轟媽")
    private val editionSuffixes = listOf("無修正版", "无修正版")
    private val whitespace = Regex("\\s+")
    private val translationRole = Regex(
        "^(翻译|翻譯|译者|譯者|translator|translation|汉化|漢化)\\s*[:：]\\s*(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val excludedRoleLabel = Regex(
        "^(?:(?:未|非|无|無|求|待|不|未经|未經|禁止|无需|無需)(?:汉化|漢化|翻译|翻譯)(?:组|組|版|版本)?" +
            "|(?:汉化|漢化|翻译|翻譯)(?:组|組)?(?:招募|招新|招聘|募集)中?" +
            "|(?:招募|招新|招聘|募集)(?:汉化|漢化|翻译|翻譯)(?:组|組)?)$",
    )
    private val bilingualLabels = mapOf(
        "中英双语" to setOf("zh", "en"),
        "中英雙語" to setOf("zh", "en"),
        "中日双语" to setOf("zh", "ja"),
        "中日雙語" to setOf("zh", "ja"),
        "中韩双语" to setOf("zh", "ko"),
        "中韓雙語" to setOf("zh", "ko"),
    )

    fun parse(title: String): CatalogTitleEvidence {
        if (title.length > MAX_TITLE_LENGTH) return CatalogTitleEvidence(complete = false)
        val segments = mutableListOf<Marker>()
        val outside = StringBuilder()
        val stack = mutableListOf<Char>()
        var markerStart = 0
        var nested = false
        for ((index, character) in title.withIndex()) {
            when {
                character in closingBrackets -> {
                    if (stack.isEmpty()) {
                        markerStart = index
                        nested = false
                    } else {
                        nested = true
                    }
                    stack.add(character)
                    if (stack.size > MAX_BRACKET_DEPTH) return CatalogTitleEvidence(complete = false)
                }
                character in allClosingBrackets -> {
                    if (stack.isEmpty() || closingBrackets[stack.removeAt(stack.lastIndex)] != character) {
                        return CatalogTitleEvidence(complete = false)
                    }
                    if (stack.isEmpty()) {
                        if (!nested && index - markerStart + 1 <= MAX_MARKER_LENGTH) {
                            segments += Marker(
                                title.substring(markerStart, index + 1),
                                title.substring(markerStart + 1, index),
                            )
                        }
                        outside.append(' ')
                    }
                }
                stack.isEmpty() -> outside.append(character)
            }
        }
        if (stack.isNotEmpty()) return CatalogTitleEvidence(complete = false)

        // Some catalogues omit brackets around the trailing edition credit. Whole tokens only:
        // never extract a group from an embedded mention, an author marker or a description.
        outside.toString().split(whitespace).filter { chineseEditionGroup(it) != null }.forEach {
            segments += Marker(it, it)
        }

        val languageAssertions = mutableListOf<Set<String>>()
        val rawEvidence = mutableListOf<String>()
        val groups = mutableListOf<TranslationGroupCredit>()
        for (marker in segments) {
            val name = marker.content.trim()
            val label = CatalogLanguageLabels.normalizeLabel(name)
            if (excludedRoleLabel.matches(label)) continue
            val role = translationRole.matchEntire(name)
            val editionGroup = chineseEditionGroup(name)
            val explicitLanguage = CatalogLanguageLabels.languageTag(label)
            val languages = bilingualLabels[label]
                ?: explicitLanguage?.let(::setOf)
                ?: setOf("zh").takeIf {
                    editionGroup != null || label in chineseRoleLabels || chineseGroupSuffixes.any(name::endsWith) ||
                        role?.groupValues?.get(1) in setOf("汉化", "漢化")
                }
            if (languages != null) {
                languageAssertions += languages
                rawEvidence += marker.raw
            }
            val groupName = when {
                editionGroup != null -> editionGroup
                label in chineseRoleLabels || explicitLanguage != null || label in bilingualLabels -> null
                role != null -> role.groupValues[2].trim().takeIf { it.isNotBlank() }
                else -> name.takeIf(::isNamedChineseGroup)
            }
            if (groupName != null) {
                val components = groupName.split('×').map(String::trim)
                val names = components.takeIf { it.size > 1 && it.all(::isNamedChineseGroup) } ?: listOf(groupName)
                groups += names.map { TranslationGroupCredit(it, it, marker.raw) }
            }
        }

        val languages = resolveLanguages(languageAssertions)
        return CatalogTitleEvidence(
            languageTags = languages.orEmpty(),
            conflict = languageAssertions.isNotEmpty() && languages == null,
            rawLanguageEvidence = rawEvidence,
            translationGroups = groups.distinctBy(TranslationGroupCredit::key),
            textOutsideMarkers = outside.toString(),
        )
    }

    private fun isNamedChineseGroup(name: String): Boolean {
        val label = CatalogLanguageLabels.normalizeLabel(name)
        if (label in chineseRoleLabels || excludedRoleLabel.matches(label)) return false
        val suffix = chineseGroupSuffixes.firstOrNull(name::endsWith) ?: return false
        return name.removeSuffix(suffix).any(Char::isLetterOrDigit)
    }

    private fun chineseEditionGroup(name: String): String? {
        val suffix = editionSuffixes.firstOrNull(name::endsWith) ?: return null
        val group = name.removeSuffix(suffix).trim()
        return group.takeIf { it in knownChineseEditionGroups || isNamedChineseGroup(it) }
    }

    private fun resolveLanguages(assertions: List<Set<String>>): Set<String>? {
        if (assertions.isEmpty()) return emptySet()
        val multilingual = assertions.firstOrNull { it.size > 1 }
        val roots = (multilingual ?: assertions.first()).mapTo(linkedSetOf()) { it.substringBefore('-') }
        if (assertions.any { tags ->
                val assertionRoots = tags.mapTo(linkedSetOf()) { it.substringBefore('-') }
                !roots.containsAll(assertionRoots) || (tags.size > 1 && assertionRoots != roots)
            }
        ) {
            return null
        }
        return roots.mapTo(linkedSetOf()) { root ->
            CatalogLanguageLabels.compatibleTag(assertions.flatten().filter { it.substringBefore('-') == root })
                ?: return null
        }
    }

    private data class Marker(val raw: String, val content: String)
}
