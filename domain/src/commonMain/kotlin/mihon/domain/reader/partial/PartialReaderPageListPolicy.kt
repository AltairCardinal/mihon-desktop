package mihon.domain.reader.partial

data class PartialReaderPageCandidate(
    val attemptGeneration: Long,
    val readerOrdinal: Int,
    val sourcePageIndex: Int,
    val opaqueLocation: String,
    val committedRevision: Long,
)

data class PartialReaderOnlinePage(
    val readerOrdinal: Int,
    val sourcePageIndex: Int,
    val pageUrl: String,
    val imageUrl: String?,
)

data class PartialReaderPage(
    val readerOrdinal: Int,
    val sourcePageIndex: Int,
    val pageUrl: String,
    val imageUrl: String?,
    val committedCandidate: PartialReaderPageCandidate?,
)

enum class PartialReaderPageListOrigin {
    COMPLETE_METADATA,
    VERIFIED_LEGACY,
    ONLINE_FALLBACK,
}

enum class PartialReaderPageListFallbackReason {
    NO_SNAPSHOT,
    NO_COMMITTED_PAGES,
    INCOMPLETE_METADATA,
    LEGACY_REQUIRES_ONLINE,
    UNSUPPORTED_VERSION,
    INVALID_METADATA,
    AMBIGUOUS_SOURCE_INDEX,
    SOURCE_MISMATCH,
    STALE_SNAPSHOT,
}

data class PartialReaderPageList(
    val pages: List<PartialReaderPage>,
    val origin: PartialReaderPageListOrigin,
    val fallbackReason: PartialReaderPageListFallbackReason?,
)

sealed interface PartialReaderPageListEvaluation {
    data class Ready(val pageList: PartialReaderPageList) : PartialReaderPageListEvaluation
    data class RequiresOnline(val reason: PartialReaderPageListFallbackReason) : PartialReaderPageListEvaluation
}

object PartialReaderPageListPolicy {

    fun evaluate(snapshot: PartialDownloadSnapshot?): PartialReaderPageListEvaluation {
        if (snapshot == null) {
            return PartialReaderPageListEvaluation.RequiresOnline(PartialReaderPageListFallbackReason.NO_SNAPSHOT)
        }
        return when (PartialPageTablePolicy.validate(snapshot.pageTable)) {
            PartialPageTableValidation.COMPLETE -> evaluateComplete(snapshot)
            PartialPageTableValidation.PARTIAL -> PartialReaderPageListEvaluation.RequiresOnline(
                PartialReaderPageListFallbackReason.INCOMPLETE_METADATA,
            )
            PartialPageTableValidation.LEGACY_UNPROVEN -> evaluateLegacyMetadata(snapshot.pageTable)
            PartialPageTableValidation.UNSUPPORTED_VERSION -> PartialReaderPageListEvaluation.RequiresOnline(
                PartialReaderPageListFallbackReason.UNSUPPORTED_VERSION,
            )
            PartialPageTableValidation.INVALID -> PartialReaderPageListEvaluation.RequiresOnline(
                PartialReaderPageListFallbackReason.INVALID_METADATA,
            )
        }
    }

    fun isCurrent(original: PartialDownloadSnapshot, latest: PartialDownloadSnapshot?): Boolean =
        latest != null &&
            original.chapterId == latest.chapterId &&
            original.identity == latest.identity &&
            original.attemptGeneration == latest.attemptGeneration &&
            original.pageTable == latest.pageTable

    fun mergeOnline(
        originalSnapshot: PartialDownloadSnapshot?,
        latestSnapshot: PartialDownloadSnapshot?,
        onlinePages: List<PartialReaderOnlinePage>,
    ): PartialReaderPageList {
        val fallback = fallbackPages(onlinePages)
        if (originalSnapshot == null) {
            return fallback.withReason(PartialReaderPageListFallbackReason.NO_SNAPSHOT)
        }
        if (!isCurrent(originalSnapshot, latestSnapshot)) {
            return fallback.withReason(PartialReaderPageListFallbackReason.STALE_SNAPSHOT)
        }
        val currentSnapshot = checkNotNull(latestSnapshot)
        val evaluation = evaluate(currentSnapshot)
        if (evaluation is PartialReaderPageListEvaluation.Ready) return evaluation.pageList
        val reason = (evaluation as PartialReaderPageListEvaluation.RequiresOnline).reason
        if (reason != PartialReaderPageListFallbackReason.LEGACY_REQUIRES_ONLINE) {
            return fallback.withReason(reason)
        }
        return mergeLegacy(currentSnapshot, onlinePages, fallback)
    }

    private fun evaluateLegacyMetadata(table: PartialPageTable): PartialReaderPageListEvaluation {
        val entries = table.entries.sortedBy(PartialPageTableEntry::readerOrdinal)
        if (
            entries.any { entry ->
                entry.sourcePageIndex < 0 || entry.sourcePageIndex != entry.readerOrdinal
            } ||
            entries.map(PartialPageTableEntry::sourcePageIndex).distinct().size != entries.size
        ) {
            return PartialReaderPageListEvaluation.RequiresOnline(
                PartialReaderPageListFallbackReason.AMBIGUOUS_SOURCE_INDEX,
            )
        }
        return PartialReaderPageListEvaluation.RequiresOnline(
            PartialReaderPageListFallbackReason.LEGACY_REQUIRES_ONLINE,
        )
    }

    private fun evaluateComplete(snapshot: PartialDownloadSnapshot): PartialReaderPageListEvaluation {
        val entries = snapshot.pageTable.entries.sortedBy(PartialPageTableEntry::readerOrdinal)
        if (entries.any { it.sourcePageIndex < 0 || it.resolvedUrl().isBlank() }) {
            return PartialReaderPageListEvaluation.RequiresOnline(
                PartialReaderPageListFallbackReason.INVALID_METADATA,
            )
        }
        if (entries.map(PartialPageTableEntry::sourcePageIndex).distinct().size != entries.size) {
            return PartialReaderPageListEvaluation.RequiresOnline(
                PartialReaderPageListFallbackReason.AMBIGUOUS_SOURCE_INDEX,
            )
        }
        val candidates = completeCandidates(snapshot, entries)
        if (candidates.isEmpty()) {
            return PartialReaderPageListEvaluation.RequiresOnline(
                PartialReaderPageListFallbackReason.NO_COMMITTED_PAGES,
            )
        }
        return PartialReaderPageListEvaluation.Ready(
            PartialReaderPageList(
                pages = entries.map { entry ->
                    PartialReaderPage(
                        readerOrdinal = entry.readerOrdinal,
                        sourcePageIndex = entry.sourcePageIndex,
                        pageUrl = entry.pageUrl,
                        imageUrl = entry.imageUrl,
                        committedCandidate = candidates[entry.readerOrdinal],
                    )
                },
                origin = PartialReaderPageListOrigin.COMPLETE_METADATA,
                fallbackReason = null,
            ),
        )
    }

    private fun mergeLegacy(
        snapshot: PartialDownloadSnapshot,
        onlinePages: List<PartialReaderOnlinePage>,
        fallback: PartialReaderPageList,
    ): PartialReaderPageList {
        val entries = snapshot.pageTable.entries.sortedBy(PartialPageTableEntry::readerOrdinal)
        if (
            onlinePages.size != snapshot.pageTable.totalPageCount ||
            onlinePages.map(PartialReaderOnlinePage::readerOrdinal) != entries.map(PartialPageTableEntry::readerOrdinal)
        ) {
            return fallback.withReason(PartialReaderPageListFallbackReason.SOURCE_MISMATCH)
        }
        if (
            onlinePages.any { it.sourcePageIndex < 0 } ||
            onlinePages.map(PartialReaderOnlinePage::sourcePageIndex).distinct().size != onlinePages.size
        ) {
            return fallback.withReason(PartialReaderPageListFallbackReason.AMBIGUOUS_SOURCE_INDEX)
        }
        val urlsMatch = entries.zip(onlinePages).all { (entry, page) ->
            entry.resolvedUrl().isNotBlank() && entry.resolvedUrl() == page.resolvedUrl()
        }
        if (!urlsMatch) return fallback.withReason(PartialReaderPageListFallbackReason.SOURCE_MISMATCH)

        val candidates = legacyCandidates(snapshot, entries, onlinePages)
        if (candidates.isEmpty()) {
            return fallback.withReason(PartialReaderPageListFallbackReason.NO_COMMITTED_PAGES)
        }
        return PartialReaderPageList(
            pages = onlinePages.map { page ->
                PartialReaderPage(
                    readerOrdinal = page.readerOrdinal,
                    sourcePageIndex = page.sourcePageIndex,
                    pageUrl = page.pageUrl,
                    imageUrl = page.imageUrl,
                    committedCandidate = candidates[page.readerOrdinal],
                )
            },
            origin = PartialReaderPageListOrigin.VERIFIED_LEGACY,
            fallbackReason = null,
        )
    }

    private fun completeCandidates(
        snapshot: PartialDownloadSnapshot,
        entries: List<PartialPageTableEntry>,
    ): Map<Int, PartialReaderPageCandidate> {
        val entryByOrdinal = entries.associateBy(PartialPageTableEntry::readerOrdinal)
        return validCommittedPages(snapshot)
            .mapNotNull { committed ->
                val entry = entryByOrdinal[committed.readerOrdinal] ?: return@mapNotNull null
                committed.takeIf { it.sourcePageIndex == entry.sourcePageIndex }
                    ?.toCandidate(snapshot.attemptGeneration, entry.sourcePageIndex)
            }
            .associateBy(PartialReaderPageCandidate::readerOrdinal)
    }

    private fun legacyCandidates(
        snapshot: PartialDownloadSnapshot,
        entries: List<PartialPageTableEntry>,
        onlinePages: List<PartialReaderOnlinePage>,
    ): Map<Int, PartialReaderPageCandidate> {
        val entryByOrdinal = entries.associateBy(PartialPageTableEntry::readerOrdinal)
        val onlineByOrdinal = onlinePages.associateBy(PartialReaderOnlinePage::readerOrdinal)
        return validCommittedPages(snapshot)
            .mapNotNull { committed ->
                val entry = entryByOrdinal[committed.readerOrdinal] ?: return@mapNotNull null
                val online = onlineByOrdinal[committed.readerOrdinal] ?: return@mapNotNull null
                committed.takeIf { it.sourcePageIndex == entry.sourcePageIndex }
                    ?.toCandidate(snapshot.attemptGeneration, online.sourcePageIndex)
            }
            .associateBy(PartialReaderPageCandidate::readerOrdinal)
    }

    private fun validCommittedPages(snapshot: PartialDownloadSnapshot): List<PartialCommittedPage> =
        snapshot.committedPages
            .groupBy(PartialCommittedPage::readerOrdinal)
            .values
            .mapNotNull { pages -> pages.singleOrNull() }
            .filter { page ->
                page.readerOrdinal >= 0 &&
                    page.sourcePageIndex >= 0 &&
                    page.opaqueLocation.isNotBlank() &&
                    page.committedRevision > 0L
            }

    private fun PartialCommittedPage.toCandidate(
        attemptGeneration: Long,
        provenSourcePageIndex: Int,
    ) = PartialReaderPageCandidate(
        attemptGeneration = attemptGeneration,
        readerOrdinal = readerOrdinal,
        sourcePageIndex = provenSourcePageIndex,
        opaqueLocation = opaqueLocation,
        committedRevision = committedRevision,
    )

    private fun fallbackPages(onlinePages: List<PartialReaderOnlinePage>) = PartialReaderPageList(
        pages = onlinePages.mapIndexed { ordinal, page ->
            PartialReaderPage(
                readerOrdinal = ordinal,
                sourcePageIndex = ordinal,
                pageUrl = page.pageUrl,
                imageUrl = page.imageUrl,
                committedCandidate = null,
            )
        },
        origin = PartialReaderPageListOrigin.ONLINE_FALLBACK,
        fallbackReason = null,
    )

    private fun PartialReaderPageList.withReason(reason: PartialReaderPageListFallbackReason) =
        copy(fallbackReason = reason)

    private fun PartialPageTableEntry.resolvedUrl(): String = imageUrl?.takeIf(String::isNotBlank) ?: pageUrl

    private fun PartialReaderOnlinePage.resolvedUrl(): String = imageUrl?.takeIf(String::isNotBlank) ?: pageUrl
}
