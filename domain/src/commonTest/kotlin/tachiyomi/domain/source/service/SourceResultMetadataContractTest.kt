package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.model.SManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.service.LanguageEvidence
import tachiyomi.domain.creator.service.MangaLanguageDetector
import tachiyomi.domain.manga.model.Manga

/** Shared Android/Desktop contract: exercise the production detector and result projection. */
class SourceResultMetadataContractTest {
    @Test
    fun `complete language markers and translation credits override retained Japanese original title`() {
        for ((title, expected) in listOf(
            "旅の記録 [Chinese]" to "zh",
            "旅の記録 【中文】" to "zh",
            "旅の記録 ［Ｃｈｉｎｅｓｅ］" to "zh",
            "旅の記録 [中国翻訳]" to "zh",
            "旅の記録 [简体中文]" to "zh-hans",
            "旅の記録 [繁體中文]" to "zh-hant",
            "旅の記録 [zh-Hans]" to "zh-hans",
            "旅の記録｜我们的旅行日记 [青叶汉化组]" to "zh",
            "[星空☆漢化] 旅の記録" to "zh",
            "旅の記録 [English]" to "en",
        )) {
            val result = detect(title)
            assertEquals(expected, result.tag, title)
            assertEquals(LanguageEvidence.TEXT_DETECTED, result.evidence, title)
            assertFalse(result.conflict, title)
            assertTrue(result.rawEvidence.isNotEmpty(), title)
        }
    }

    @Test
    fun `negative incomplete and ambiguous labels do not become Chinese or translation groups`() {
        for (marker in listOf(
            "[未汉化]", "[非汉化]", "[求汉化]", "[汉化组招募]", "[汉化组招新]",
            "[Chinese learning club]", "[CN]", "[CH]", "[繁]", "[RAW]",
            "[青叶汉化组", "［青叶汉化组]", "[创作社团 (青叶汉化组)]",
        )) {
            val title = "A Journey $marker"
            val metadata = SourceResultMetadata.from(sourceManga(title))
            assertEquals("und", metadata.language.tag, title)
            assertTrue(metadata.translationGroups.isEmpty(), title)
        }
        assertEquals("und", detect("東京旅行 山田太郎").tag)
        assertEquals("und", detect("我们一起去看海").tag)
    }

    @Test
    fun `conflicting complete title markers stay unknown rather than selecting first marker or source language`() {
        for (title in listOf(
            "Journey [Chinese] [Japanese]",
            "Journey [简体中文] [繁體中文]",
            "Journey [English] [青叶汉化组]",
        )) {
            val result = MangaLanguageDetector.detect("zh", null, title, null, emptyList())
            assertEquals("und", result.tag, title)
            assertTrue(result.conflict, title)
            assertTrue(result.rawEvidence.size >= 2, title)
        }
        assertEquals("zh-hans", detect("Journey [Chinese] [简体中文]").tag)
    }

    @Test
    fun `structured reading language keeps precedence over title estimates`() {
        val result = MangaLanguageDetector.detect(null, "en", "Journey [Chinese]", null, emptyList())
        assertEquals("en", result.tag)
        assertEquals(LanguageEvidence.EXPLICIT_METADATA, result.evidence)
    }

    @Test
    fun `translation credits preserve exact display names and do not split ambiguous joint brands`() {
        val title = "[星舟社 (林风)] Journey [青叶×白云汉化组] [星空☆漢化] [翻译：Azure Team]"
        val metadata = SourceResultMetadata.from(sourceManga(title))
        assertEquals(listOf("青叶×白云汉化组", "星空☆漢化", "Azure Team"), metadata.translationGroups.map { it.displayName })
        assertEquals(listOf("青叶×白云汉化组", "星空☆漢化", "Azure Team"), metadata.translationGroups.map { it.key })
        assertEquals("[翻译：Azure Team]", metadata.translationGroups.last().rawEvidence)
        assertEquals("[青叶×白云汉化组]", metadata.translationGroups.first().rawEvidence)
    }

    @Test
    fun `translator role does not imply Chinese and production metadata does not invent source language`() {
        val metadata = SourceResultMetadata.from(sourceManga("A Journey [翻译：Azure Team]"))
        assertEquals("und", metadata.language.tag)
        assertEquals(listOf("Azure Team"), metadata.translationGroups.map { it.displayName })
        assertTrue(SourceResultMetadata.from(sourceManga("A Journey [中文机翻]")).translationGroups.isEmpty())
        assertEquals("zh", SourceResultMetadata.from(sourceManga("A Journey [中文机翻]")).language.tag)
    }

    @Test
    fun `SManga and Manga projections agree without modifying original metadata`() {
        val title = "旅の記録 [Chinese] [青叶汉化组]"
        val source = sourceManga(title).also { it.genre = "Adventure" }
        val persisted = Manga.create().copy(title = title, genre = listOf("Adventure"))
        assertEquals(SourceResultMetadata.from(source), SourceResultMetadata.from(persisted))
        assertEquals("Adventure", source.genre)
        assertEquals(title, source.title)
        assertTrue(SourceResultMetadata.from(source).translationGroups.isNotEmpty())
    }

    @Test
    fun `result filters compose languages as OR and translation groups as OR then AND`() {
        val chinese = SourceResultMetadata.from(sourceManga("Journey [Chinese] [青叶汉化组]"))
        val english = SourceResultMetadata.from(sourceManga("Journey [English] [翻译：Azure Team]"))
        val filter = CatalogResultFilter(setOf("zh", "en"), setOf("青叶汉化组", "白云汉化组"))
        assertTrue(filter.isActive)
        assertTrue(filter.matches(chinese))
        assertFalse(filter.matches(english))
        assertFalse(CatalogResultFilter(setOf("ja"), setOf("青叶汉化组")).matches(chinese))
        assertTrue(CatalogResultFilter().matches(chinese))
        assertFalse(CatalogResultFilter().isActive)
    }

    @Test
    fun `unknown and conflicts require explicit selections independently on each filter dimension`() {
        val unknown = SourceResultMetadata.from(sourceManga("東京旅行"))
        val conflict = SourceResultMetadata.from(sourceManga("Journey [Chinese] [Japanese]"))
        val chineseWithoutGroup = SourceResultMetadata.from(sourceManga("Journey [Chinese]"))
        assertFalse(CatalogResultFilter(setOf("zh")).matches(unknown))
        assertFalse(CatalogResultFilter(setOf("zh")).matches(conflict))
        assertTrue(CatalogResultFilter(setOf("zh"), includeUnknownLanguage = true).matches(conflict))
        assertTrue(CatalogResultFilter(includeUnknownLanguage = true).matches(unknown))
        assertFalse(CatalogResultFilter(includeUnknownLanguage = true).matches(chineseWithoutGroup))
        assertFalse(CatalogResultFilter(translationGroupKeys = setOf("青叶汉化组")).matches(chineseWithoutGroup))
        assertTrue(CatalogResultFilter(includeUnknownTranslationGroup = true).matches(chineseWithoutGroup))
        assertTrue(
            CatalogResultFilter(includeUnknownLanguage = true, includeUnknownTranslationGroup = true).matches(unknown),
        )
    }

    @Test
    fun `Chinese family selection includes explicit scripts but script selection does not guess generic Chinese`() {
        val generic = SourceResultMetadata.from(sourceManga("Journey [Chinese]"))
        val simplified = SourceResultMetadata.from(sourceManga("Journey [简体中文]"))
        val traditional = SourceResultMetadata.from(sourceManga("Journey [繁體中文]"))
        assertTrue(CatalogResultFilter(setOf("zh")).matches(simplified))
        assertTrue(CatalogResultFilter(setOf("zh")).matches(traditional))
        assertFalse(CatalogResultFilter(setOf("zh-hans")).matches(generic))
        assertFalse(CatalogResultFilter(setOf("zh-hans")).matches(traditional))
        assertTrue(CatalogResultFilter(setOf("zh-Hans")).matches(simplified))
    }

    @Test
    fun `explicit bilingual marker matches either reading language without reclassifying contradictory markers`() {
        val bilingual = SourceResultMetadata.from(sourceManga("Journey [中英双语]"))
        val conflict = SourceResultMetadata.from(sourceManga("Journey [Chinese] [English]"))
        assertEquals(setOf("zh", "en"), bilingual.readingLanguageTags)
        assertFalse(bilingual.language.conflict)
        assertTrue(CatalogResultFilter(setOf("zh")).matches(bilingual))
        assertTrue(CatalogResultFilter(setOf("en")).matches(bilingual))
        assertFalse(CatalogResultFilter(includeUnknownLanguage = true).matches(bilingual))
        assertTrue(conflict.readingLanguageTags.isEmpty())
        assertTrue(conflict.language.conflict)
    }

    @Test
    fun `unbounded title input and non language genres do not produce new strong metadata`() {
        val source = sourceManga("A".repeat(4097) + " [Chinese] [青叶汉化组]").also {
            it.genre = "Adventure, BL, CN"
        }
        val metadata = SourceResultMetadata.from(source)
        assertEquals("und", metadata.language.tag)
        assertTrue(metadata.translationGroups.isEmpty())
        val oversizedMarker = SourceResultMetadata.from(sourceManga("Journey [" + "A".repeat(512) + "汉化组]"))
        assertTrue(oversizedMarker.translationGroups.isEmpty())
    }

    @Test
    fun `equally ranked genre languages preserve conflict while compatible Chinese labels narrow script`() {
        for (genres in listOf(listOf("Chinese", "English"), listOf("English", "Chinese"))) {
            val detection = MangaLanguageDetector.detect("zh", null, "Journey", null, genres)
            assertEquals("und", detection.tag)
            assertTrue(detection.conflict)
            assertEquals(genres.toSet(), detection.rawEvidence.toSet())
            val source = sourceManga("Journey").also { it.genre = genres.joinToString(", ") }
            val metadata = SourceResultMetadata.from(source)
            assertTrue(metadata.language.conflict)
            assertTrue(metadata.readingLanguageTags.isEmpty())
        }
        for (genres in listOf(listOf("Chinese", "简体中文"), listOf("简体中文", "Chinese"))) {
            val detection = MangaLanguageDetector.detect(null, null, "Journey", null, genres)
            assertEquals("zh-hans", detection.tag)
            assertFalse(detection.conflict)
        }
    }

    @Test
    fun `bare translation role labels signal Chinese without inventing a translation organization`() {
        for (marker in listOf("[汉化]", "[漢化]", "[汉化组]", "[中文翻译]", "[中文翻譯]")) {
            val metadata = SourceResultMetadata.from(sourceManga("旅の記録 $marker"))
            assertEquals("zh", metadata.language.tag, marker)
            assertFalse(metadata.language.conflict, marker)
            assertTrue(metadata.translationGroups.isEmpty(), marker)
            assertTrue(CatalogResultFilter(setOf("zh")).matches(metadata), marker)
        }
    }

    @Test
    fun `Kana inside translation credits does not become evidence about reading language`() {
        for (credit in listOf("[翻译：あおい Team]", "[翻譯：カモメ]")) {
            val title = "A Journey $credit"
            val detection = detect(title)
            assertEquals("und", detection.tag, title)
            val metadata = SourceResultMetadata.from(sourceManga(title))
            assertEquals("und", metadata.language.tag, title)
            assertEquals(1, metadata.translationGroups.size, title)
            assertEquals(credit, metadata.translationGroups.single().rawEvidence, title)
            assertFalse(CatalogResultFilter(setOf("ja")).matches(metadata), title)
        }
        assertEquals("ja", detect("旅の記録 [翻译：Azure Team]").tag)
    }

    @Test
    fun `translation names beginning with negative or recruiting characters retain their actual role`() {
        for (group in listOf("無邪気漢化組", "招财猫汉化组", "无限汉化组", "无忧汉化组")) {
            val metadata = SourceResultMetadata.from(sourceManga("Journey [$group]"))
            assertEquals("zh", metadata.language.tag, group)
            assertEquals(listOf(group), metadata.translationGroups.map { it.displayName }, group)
            assertEquals(listOf(group), metadata.translationGroups.map { it.key }, group)
        }
        for (role in listOf("未汉化", "非汉化", "无汉化", "無漢化", "求汉化", "待汉化", "汉化组招募")) {
            val metadata = SourceResultMetadata.from(sourceManga("Journey [$role]"))
            assertEquals("und", metadata.language.tag, role)
            assertTrue(metadata.translationGroups.isEmpty(), role)
        }
    }

    @Test
    fun `machine and anonymous translation descriptions are language clues without organization identities`() {
        for (role in listOf("AI汉化", "机器汉化", "機器漢化", "个人汉化", "個人漢化", "机翻汉化")) {
            val metadata = SourceResultMetadata.from(sourceManga("Journey [$role]"))
            assertEquals("zh", metadata.language.tag, role)
            assertTrue(metadata.translationGroups.isEmpty(), role)
            assertTrue(CatalogResultFilter(includeUnknownTranslationGroup = true).matches(metadata), role)
        }
    }

    @Test
    fun `joint credit splits only when each component has its own explicit translation role`() {
        val joint = SourceResultMetadata.from(sourceManga("Journey [青叶汉化组×白云汉化组]"))
        assertEquals(listOf("青叶汉化组", "白云汉化组"), joint.translationGroups.map { it.displayName })
        assertEquals(listOf("青叶汉化组", "白云汉化组"), joint.translationGroups.map { it.key })
        assertTrue(joint.translationGroups.all { it.rawEvidence == "[青叶汉化组×白云汉化组]" })
        assertTrue(CatalogResultFilter(translationGroupKeys = setOf("青叶汉化组")).matches(joint))
        assertTrue(CatalogResultFilter(translationGroupKeys = setOf("白云汉化组")).matches(joint))
        val ambiguous = SourceResultMetadata.from(sourceManga("Journey [青叶×白云汉化组]"))
        assertEquals(listOf("青叶×白云汉化组"), ambiguous.translationGroups.map { it.displayName })
        assertFalse(CatalogResultFilter(translationGroupKeys = setOf("白云汉化组")).matches(ambiguous))
    }

    private fun detect(title: String) = MangaLanguageDetector.detect(null, null, title, null, emptyList())

    private fun sourceManga(title: String) = SManga.create().also {
        it.url = "/journey"
        it.title = title
    }
}
