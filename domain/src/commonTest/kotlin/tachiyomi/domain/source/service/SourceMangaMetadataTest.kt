package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class SourceMangaMetadataTest {
    @Test
    fun `source metadata respects favorite title policy while consuming source fields and platform cover version`() {
        val stored = Manga.create().copy(
            id = 7,
            title = "My title",
            favorite = true,
            notes = "Private",
            chapterFlags = 123,
        )
        val remote = SManga.create().apply {
            title = "Source title"
            author = "Author"
            artist = "Artist"
            description = "Description"
            genre = "A, B"
            status = SManga.ONGOING
            thumbnail_url = "https://example.test/cover"
        }
        val update = sourceMangaMetadata(stored, remote, false, 42)
        update.title shouldBe null
        update.author shouldBe "Author"
        update.artist shouldBe "Artist"
        update.description shouldBe "Description"
        update.genre shouldBe listOf("A", "B")
        update.coverLastModified shouldBe 42L
        update.notes shouldBe null
        update.chapterFlags shouldBe null
        sourceMangaMetadata(stored, remote, true).title shouldBe "Source title"
        sourceMangaMetadata(stored.copy(favorite = false), remote, false).title shouldBe "Source title"
    }

    @Test
    fun `empty source cover and absent title do not erase persisted values`() {
        val remote = SManga.create().apply { thumbnail_url = "" }
        val update = sourceMangaMetadata(Manga.create().copy(id = 7), remote, false)
        update.title shouldBe null
        update.thumbnailUrl shouldBe null
        update.coverLastModified shouldBe null
    }
}
