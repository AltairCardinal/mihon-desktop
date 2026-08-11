package tachiyomi.domain.creator.service

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WorkMatchScorerTest {

    @Test
    fun `scores same title and creator as high confidence`() {
        val score = WorkMatchScorer.score(
            current = WorkMatchInput(title = "One-Punch Man", creators = listOf("ONE", "Murata"), language = "en"),
            candidate = WorkMatchInput(
                title = "One Punch Man",
                creators = listOf("One", "Yusuke Murata"),
                language = "en",
            ),
        )

        score.value shouldBeGreaterThan 0.85
        score.tier shouldBe WorkMatchTier.STRONG
        score.recommendedState shouldBe tachiyomi.domain.creator.model.WorkDecisionState.SUGGESTED
    }

    @Test
    fun `scores unrelated works as low confidence`() {
        val score = WorkMatchScorer.score(
            current = WorkMatchInput(title = "One Piece", creators = listOf("Eiichiro Oda"), language = "en"),
            candidate = WorkMatchInput(title = "Bleach", creators = listOf("Tite Kubo"), language = "en"),
        )

        score.value shouldBeLessThan 0.5
        score.tier shouldBe WorkMatchTier.WEAK
    }

    @Test
    fun `title alias contributes explainable evidence without auto confirming`() {
        val score = WorkMatchScorer.score(
            current = WorkMatchInput(
                title = "Shingeki no Kyojin",
                titleAliases = listOf("Attack on Titan"),
                creators = listOf("Hajime Isayama"),
                language = "ja",
            ),
            candidate = WorkMatchInput(
                title = "Attack on Titan",
                creators = listOf("Hajime Isayama"),
                language = "en",
            ),
        )

        score.value shouldBeGreaterThan 0.8
        score.evidence.map(WorkMatchEvidence::kind) shouldContain WorkMatchEvidenceKind.TITLE_ALIAS
        score.evidence.map(WorkMatchEvidence::kind) shouldContain WorkMatchEvidenceKind.LANGUAGE_CONFLICT
        score.recommendedState shouldBe tachiyomi.domain.creator.model.WorkDecisionState.SUGGESTED
        score.eligibleForAutomaticConfirmation shouldBe false
    }

    @Test
    fun `same title by different creators remains a suggestion with conflict evidence`() {
        val score = WorkMatchScorer.score(
            current = WorkMatchInput(title = "Monster", creators = listOf("Naoki Urasawa"), language = "ja"),
            candidate = WorkMatchInput(title = "Monster", creators = listOf("Someone Else"), language = "ja"),
        )

        score.value shouldBeLessThan 0.75
        score.evidence.map(WorkMatchEvidence::kind) shouldContain WorkMatchEvidenceKind.CREATOR_CONFLICT
        score.eligibleForAutomaticConfirmation shouldBe false
    }

    @Test
    fun `matching structured external id is the only automatic confirmation evidence`() {
        val score = WorkMatchScorer.score(
            current = WorkMatchInput(
                title = "Localized title",
                creators = listOf("Author"),
                language = "en",
                externalIds = mapOf("anilist" to "30013"),
            ),
            candidate = WorkMatchInput(
                title = "Original title",
                creators = listOf("Author"),
                language = "ja",
                externalIds = mapOf("anilist" to "30013"),
            ),
        )

        score.tier shouldBe WorkMatchTier.STABLE_ID
        score.evidence.map(WorkMatchEvidence::kind) shouldContain WorkMatchEvidenceKind.STABLE_EXTERNAL_ID
        score.recommendedState shouldBe tachiyomi.domain.creator.model.WorkDecisionState.CONFIRMED
        score.eligibleForAutomaticConfirmation shouldBe true
    }

    @Test
    fun `chapter coverage is evidence but cannot confirm a work`() {
        val score = WorkMatchScorer.score(
            current = WorkMatchInput(
                title = "Example",
                creators = listOf("Author"),
                language = "en",
                chapterCount = 12,
            ),
            candidate = WorkMatchInput(
                title = "Example",
                creators = listOf("Author"),
                language = "en",
                chapterCount = 10,
            ),
        )

        score.evidence.map(WorkMatchEvidence::kind) shouldContain WorkMatchEvidenceKind.CHAPTER_COVERAGE
        score.recommendedState shouldBe tachiyomi.domain.creator.model.WorkDecisionState.SUGGESTED
        score.algorithmVersion shouldBe WorkMatchScorer.ALGORITHM_VERSION
    }
}
