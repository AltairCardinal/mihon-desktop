package mihon.domain.manga.model

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SourceMangaResultBoundaryContractTest {
    @Test
    fun `actual domain conversion classifies every delayed field without replacing the original cause`() {
        val observed = mutableListOf<String>()
        for (field in listOf(
            "url", "title", "artist", "author", "description", "genres", "status",
            "thumbnail_url", "update_strategy", "initialized", "memo",
        )) {
            val cause = java.io.IOException("returned field", NoSuchMethodError("future $field"))
            val actual = runCatching { FutureSourceManga(field, cause).toDomainManga(42L) }.exceptionOrNull()
            observed += "$field:${actual?.javaClass?.simpleName}"
            if (actual is SourceCompatibilityException) {
                assertSame(cause, actual.cause)
                assertEquals(SourceOperationPhase.METADATA, actual.phase)
            }
        }
        assertEquals(
            listOf(
                "url", "title", "artist", "author", "description", "genres", "status",
                "thumbnail_url", "update_strategy", "initialized", "memo",
            ).map {
                "$it:SourceCompatibilityException"
            },
            observed,
        )
    }

    @Test
    fun `domain conversion preserves controls ordinary IO and unrelated host Errors`() {
        val fatal = object : VirtualMachineError("controlled") {}
        val cancel = kotlinx.coroutines.CancellationException("cancelled")
        val io = java.io.IOException("ordinary")
        val host = AssertionError("host")
        for ((thrown, expected) in listOf(
            java.io.IOException("wrapped", fatal) to fatal,
            ExceptionInInitializerError(cancel) to cancel,
            io to io,
            host to host,
        )) {
            assertSame(expected, runCatching { FutureSourceManga("memo", thrown).toDomainManga(42L) }.exceptionOrNull())
        }
        val healthy = SManga.create().apply {
            url = "/healthy"
            title = "Healthy"
        }
        assertEquals("Healthy", healthy.toDomainManga(42L).title)
        assertEquals(42L, healthy.toDomainManga(42L).source)
    }
}

internal class FutureSourceManga(private val site: String, private val failure: Throwable) : SManga {
    private fun hit(field: String) {
        if (site == field) throw failure
    }
    private var urlValue: String = "/work"
    override var url: String
        get() {
            hit("url")
            return urlValue
        }
        set(value) {
            urlValue = value
        }
    private var titleValue: String = "Work"
    override var title: String
        get() {
            hit("title")
            return titleValue
        }
        set(value) {
            titleValue = value
        }
    private var artistValue: String? = "Artist"
    override var artist: String?
        get() {
            hit("artist")
            return artistValue
        }
        set(value) {
            artistValue = value
        }
    private var authorValue: String? = "Author"
    override var author: String?
        get() {
            hit("author")
            return authorValue
        }
        set(value) {
            authorValue = value
        }
    private var descriptionValue: String? = "Description"
    override var description: String?
        get() {
            hit("description")
            return descriptionValue
        }
        set(value) {
            descriptionValue = value
        }
    private var genreValue: String? = "Action"
    override var genre: String?
        get() {
            hit("genre")
            return genreValue
        }
        set(value) {
            genreValue = value
        }
    private var statusValue: Int = 1
    override var status: Int
        get() {
            hit("status")
            return statusValue
        }
        set(value) {
            statusValue = value
        }
    private var thumbnailUrlValue: String? = "/cover"
    override var thumbnail_url: String?
        get() {
            hit("thumbnail_url")
            return thumbnailUrlValue
        }
        set(value) {
            thumbnailUrlValue = value
        }
    private var updateStrategyValue: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
    override var update_strategy: UpdateStrategy
        get() {
            hit("update_strategy")
            return updateStrategyValue
        }
        set(value) {
            updateStrategyValue = value
        }
    private var initializedValue: Boolean = true
    override var initialized: Boolean
        get() {
            hit("initialized")
            return initializedValue
        }
        set(value) {
            initializedValue = value
        }
    private var memoValue: JsonObject = JsonObject(emptyMap())
    override var memo: JsonObject
        get() {
            hit("memo")
            return memoValue
        }
        set(value) {
            memoValue = value
        }
    override fun getGenres(): List<String>? {
        hit("genres")
        return listOf("Action")
    }
    override fun copy(): SManga {
        hit("copy")
        return super.copy()
    }
}
