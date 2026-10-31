package mihon.domain.reader

import mihon.domain.reader.session.ReaderPageId

object ReaderPairingPrefetchPlan {
    data class ClassifiedPlan(val realMissing: List<ReaderPageId>, val speculative: List<ReaderPageId>)

    fun classify(
        certification: ReaderPairingCertification,
        pageIds: List<ReaderPageId>,
        analysisStatus: (ReaderPageId) -> ReaderPageAnalysisEvidence,
        lookaheadK: Int,
        failedPages: Set<ReaderPageId> = emptySet(),
    ): ClassifiedPlan = when (certification) {
        is ReaderPairingCertification.NeedFacts -> {
            val real = certification.facts
                .filter { it.kind != ReaderPairingFactKind.PAIRING_RULES }
                .flatMap { need ->
                    val endpoints = listOfNotNull(need.pageId, need.secondPageId)
                    if (need.kind != ReaderPairingFactKind.EDGE_SAMPLES) {
                        endpoints
                    } else {
                        val uncomputed = endpoints.filter {
                            analysisStatus(it) !is ReaderPageAnalysisEvidence.Computed
                        }
                        uncomputed.ifEmpty { endpoints }
                    }
                }
                .distinct()
            val realSet = real.toSet()
            val first = certification.continuationFirstIndex
            val speculative = mutableListOf<ReaderPageId>()
            if (lookaheadK > 0) {
                for (step in 1..lookaheadK) {
                    val candidateIndex = first - step
                    if (candidateIndex < 0) break
                    if (candidateIndex >= pageIds.size) continue
                    val id = pageIds[candidateIndex]
                    if (id in realSet || id in failedPages) continue
                    if (analysisStatus(id) is ReaderPageAnalysisEvidence.NotAnalyzed) {
                        speculative.add(id)
                    }
                }
            }
            ClassifiedPlan(real, speculative)
        }
        is ReaderPairingCertification.RetryableBlocked -> {
            val real = certification.facts.filter { need ->
                need.kind == ReaderPairingFactKind.DIMENSIONS &&
                    analysisStatus(need.pageId) is ReaderPageAnalysisEvidence.NotAnalyzed &&
                    need.pageId !in failedPages
            }.flatMap { listOfNotNull(it.pageId, it.secondPageId) }.distinct()
            ClassifiedPlan(real, emptyList())
        }
        is ReaderPairingCertification.Certified -> ClassifiedPlan(emptyList(), emptyList())
    }

    fun plan(
        certification: ReaderPairingCertification,
        pageIds: List<ReaderPageId>,
        analysisStatus: (ReaderPageId) -> ReaderPageAnalysisEvidence,
        lookaheadK: Int,
        failedPages: Set<ReaderPageId> = emptySet(),
    ): List<ReaderPageId> = classify(certification, pageIds, analysisStatus, lookaheadK, failedPages).let {
        it.realMissing + it.speculative
    }
}
