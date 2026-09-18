package tachiyomi.domain.creator.interactor

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.CreatorMetadataField
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.manga.model.Manga

class ExtractCreatorsFromMangaTest {

    private val extract = ExtractCreatorsFromManga()

    @Test
    fun `splits people and folds author artist overlap into one ordered mention`() {
        val manga = Manga.create().copy(
            author = "ONE， 村田雄介 / ",
            artist = "村田雄介; Boichi",
        )

        val mentions = extract.await(manga)

        mentions.map { it.displayName }
            .shouldContainExactly("ONE", "村田雄介", "Boichi")
        mentions.map { it.normalizedName }
            .shouldContainExactly("one", "村田雄介", "boichi")
        mentions.map { it.role }
            .shouldContainExactly(CreatorRole.AUTHOR, CreatorRole.BOTH, CreatorRole.ARTIST)
        mentions.map { it.order }
            .shouldContainExactly(0L, 1L, 2L)
        mentions[1].evidence.map { it.field }
            .shouldContainExactly(CreatorMetadataField.AUTHOR, CreatorMetadataField.ARTIST)
        mentions[1].evidence.map { it.rawField }
            .shouldContainExactly("ONE， 村田雄介 / ", "村田雄介; Boichi")
        mentions[1].evidence.map { it.rawToken }
            .shouldContainExactly("村田雄介", "村田雄介")
    }

    @Test
    fun `blank metadata produces no synthetic creator`() {
        extract.await(Manga.create().copy(author = " / ， ", artist = null)) shouldBe emptyList()
    }

    @Test
    fun `full width and half width names remain exact distinct mentions`() {
        val manga = Manga.create().copy(author = "ＭＵＲＡＴＡ", artist = "Murata")

        val mentions = extract.await(manga)

        mentions.map { it.displayName }.shouldContainExactly("ＭＵＲＡＴＡ", "Murata")
        mentions.map { it.role }.shouldContainExactly(CreatorRole.AUTHOR, CreatorRole.ARTIST)
        mentions.map { it.normalizedName }.shouldContainExactly("murata", "murata")
    }

    @Test
    fun `nonblank punctuation name is retained even when search normalization is blank`() {
        val mentions = extract.await(Manga.create().copy(author = "!!!", artist = null))

        mentions.single().displayName shouldBe "!!!"
        mentions.single().normalizedName shouldBe ""
    }
}
