package tachiyomi.domain.creator.service

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
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
    }

    @Test
    fun `scores unrelated works as low confidence`() {
        val score = WorkMatchScorer.score(
            current = WorkMatchInput(title = "One Piece", creators = listOf("Eiichiro Oda"), language = "en"),
            candidate = WorkMatchInput(title = "Bleach", creators = listOf("Tite Kubo"), language = "en"),
        )

        score.value shouldBeLessThan 0.5
    }
}
