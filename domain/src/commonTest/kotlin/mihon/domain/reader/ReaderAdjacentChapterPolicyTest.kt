package mihon.domain.reader

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ReaderAdjacentChapterPolicyTest {

    @Test
    fun `ten page chapter starts page-list-only effect at anchor index five`() {
        assertNull(ReaderAdjacentChapterPolicy.effectForPageAnchor(anchorPageIndex = 4, pageCount = 10))
        assertSame(
            ReaderAdjacentChapterEffect.LoadAdjacentChapterPageList,
            ReaderAdjacentChapterPolicy.effectForPageAnchor(anchorPageIndex = 5, pageCount = 10),
        )
    }

    @Test
    fun `short chapters request the adjacent page list from their first anchor`() {
        assertSame(
            ReaderAdjacentChapterEffect.LoadAdjacentChapterPageList,
            ReaderAdjacentChapterPolicy.effectForPageAnchor(anchorPageIndex = 0, pageCount = 1),
        )
        assertSame(
            ReaderAdjacentChapterEffect.LoadAdjacentChapterPageList,
            ReaderAdjacentChapterPolicy.effectForPageAnchor(anchorPageIndex = 0, pageCount = 4),
        )
    }

    @Test
    fun `transition page keeps its unconditional page-list-only effect`() {
        assertSame(
            ReaderAdjacentChapterEffect.LoadAdjacentChapterPageList,
            ReaderAdjacentChapterPolicy.transitionPageEffect(),
        )
    }
}
