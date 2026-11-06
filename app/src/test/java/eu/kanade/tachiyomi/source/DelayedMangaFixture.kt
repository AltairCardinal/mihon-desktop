package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonObject

internal class DelayedMangaFixture(
    private val site: String,
    private val failure: Throwable,
    private val healthyReads: Int = 0,
) : SManga {
    val fieldReads = mutableMapOf<String, Int>()
    private var reads = 0
    private fun hit(field: String) {
        fieldReads[field] = (fieldReads[field] ?: 0) + 1
        if (site == field && reads++ >= healthyReads) throw failure
    }
    private var urlValue: String = "/bad"
    override var url: String
        get() {
            hit("url")
            return urlValue
        }
        set(value) {
            urlValue = value
        }
    private var titleValue: String = "Bad"
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
}
