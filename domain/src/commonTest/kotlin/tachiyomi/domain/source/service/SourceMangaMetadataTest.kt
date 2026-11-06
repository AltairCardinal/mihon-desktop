package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.inspectSourceFailure
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import java.io.IOException

class SourceMangaMetadataTest {
    @Test
    fun `metadata consumer classifies actual returned getters without reading unused source fields`() {
        for (site in listOf("thumbnail", "title", "genres", "memo")) {
            val missing = IOException("private", NoSuchMethodError(site))
            val base = SManga.create().apply { title = "Remote" }
            val remote = object : SManga by base {
                override var thumbnail_url: String?
                    get() {
                        if (site == "thumbnail") throw missing
                        return base.thumbnail_url
                    }
                    set(value) {
                        base.thumbnail_url = value
                    }
                override var title: String
                    get() {
                        if (site == "title") throw missing
                        return base.title
                    }
                    set(value) {
                        base.title = value
                    }
                override fun getGenres(): List<String>? {
                    if (site == "genres") throw missing
                    return base.getGenres()
                }
                override var memo: kotlinx.serialization.json.JsonObject
                    get() {
                        if (site == "memo") throw missing
                        return base.memo
                    }
                    set(value) {
                        base.memo = value
                    }
            }
            val actual = runCatching { sourceMangaMetadata(Manga.create(), remote, true) }.exceptionOrNull()
            val marker = assertInstanceOf(SourceCompatibilityException::class.java, actual, site)
            marker.phase shouldBe SourceOperationPhase.DETAILS
            assertSame(missing.cause, marker.inspectSourceFailure().linkage)
        }
    }

    @Test
    fun `metadata consumer preserves controls and absent title comma genre without probing URL or initialized`() {
        val fatal = object : VirtualMachineError("controlled") {}
        val cancel = kotlinx.coroutines.CancellationException("controlled")
        for (expected in listOf(fatal, cancel)) {
            val base = SManga.create()
            val remote = object : SManga by base {
                override fun getGenres(): List<String>? = throw IOException("wrapper", expected)
            }
            val actual = runCatching { sourceMangaMetadata(Manga.create(), remote, false) }.exceptionOrNull()
            assertSame(expected, actual)
        }
        val base = SManga.create()
        val remote = object : SManga by base {
            override var url: String
                get() = throw AssertionError("unused URL")
                set(value) {
                    base.url = value
                }
            override var initialized: Boolean
                get() = throw AssertionError("unused initialized")
                set(value) {
                    base.initialized = value
                }
            override fun getGenres() = listOf("science,fiction")
        }
        val value = sourceMangaMetadata(Manga.create(), remote, false)
        value.title shouldBe null
        value.genre shouldBe listOf("science,fiction")
    }

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
