package tachiyomi.domain.creator.model

/** The date meaning is part of the quality key; chapter dates never certify work dates. */
enum class SourceDateField {
    WORK_PUBLISHED,
    CHAPTER_UPDATED,
}

enum class SourceDateQualityStatus {
    UNKNOWN,
    TRUSTED,
    SUSPECT,
}

enum class SourceDatePrecision {
    UNKNOWN,
    YEAR,
    MONTH,
    DAY,
}

data class SourceDateExtensionIdentity(
    val packageName: String,
    val version: String,
) {
    init {
        require(packageName.isNotBlank())
        require(version.isNotBlank())
    }
}

/** A date value explicitly exposed by a source adapter; arbitrary page scraping is out of scope. */
data class SourceDateValue(
    val rawValue: String,
    val valueAt: Long,
    val precision: SourceDatePrecision,
    val semanticConfirmed: Boolean,
) {
    init {
        require(rawValue.isNotBlank())
        require(valueAt > 0L)
    }
}

data class SourceDateQualityIdentity(
    val extensionPackage: String,
    val extensionVersion: String,
    val sourceId: Long,
    val field: SourceDateField,
) {
    init {
        require(extensionPackage.isNotBlank())
        require(extensionVersion.isNotBlank())
        require(sourceId >= 0L)
    }
}

data class SourceDateObservation(
    val identity: SourceDateQualityIdentity,
    val workNaturalKey: String,
    val chapterNaturalKey: String? = null,
    val rawValue: String? = null,
    val valueAt: Long? = null,
    val precision: SourceDatePrecision = SourceDatePrecision.UNKNOWN,
    val semanticConfirmed: Boolean = false,
    val observedAt: Long,
    val reason: String? = null,
    val networkFailure: Boolean = false,
) {
    init {
        require(workNaturalKey.isNotBlank())
        require(observedAt > 0L)
        if (identity.field == SourceDateField.CHAPTER_UPDATED) {
            require(!chapterNaturalKey.isNullOrBlank())
        } else {
            require(chapterNaturalKey == null)
        }
    }
}

data class SourceDateQualitySnapshot(
    val identity: SourceDateQualityIdentity,
    val status: SourceDateQualityStatus,
    val strategyVersion: Long,
    val sampleCount: Int,
    val observedWorkCount: Int,
    val stableChapterCount: Int,
    val distinctHistoryDateCount: Int,
    val firstObservedAt: Long?,
    val lastObservedAt: Long?,
    val projectedDateAt: Long?,
    val lastReason: String?,
)

/**
 * Conservative, bounded evidence policy for source supplied dates.
 *
 * The policy is deliberately independent from source and extension identity resolution. A new
 * extension version therefore starts with a fresh UNKNOWN bucket instead of inheriting trust from
 * an older parser. Network failures are not observations and cannot demote an existing result.
 */
object SourceDateQualityPolicy {
    const val STRATEGY_VERSION = 1L
    const val MAX_WORK_SAMPLES = 20
    const val MAX_CHAPTER_SAMPLES_PER_WORK = 20
    const val MAX_OBSERVATIONS_PER_NATURAL_KEY = 2
    const val MAX_DIAGNOSTIC_SAMPLES = MAX_WORK_SAMPLES * MAX_CHAPTER_SAMPLES_PER_WORK
    const val DIAGNOSTIC_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1_000L
    const val MIN_WORK_SAMPLES = 3
    const val MIN_CHAPTERS_PER_WORK = 3
    const val MIN_HISTORY_DATES = 3
    const val MIN_OBSERVATION_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L

    fun retain(
        observations: List<SourceDateObservation>,
        now: Long,
    ): List<SourceDateObservation> {
        val cutoff = now - DIAGNOSTIC_RETENTION_MILLIS
        val recent = observations.distinct().filter { it.observedAt >= cutoff }
        val retainedValid = recent
            .filter { isQualityEvidence(it, now) }
            .groupBy(SourceDateObservation::identity)
            .values
            .flatMap { identitySamples ->
                val perNaturalKey = identitySamples
                    .groupBy { it.workNaturalKey to it.chapterNaturalKey }
                    .values
                    .map { values ->
                        retainQualityAnchors(values)
                    }
                    .flatten()
                perNaturalKey
                    .groupBy(SourceDateObservation::workNaturalKey)
                    .entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, List<SourceDateObservation>>> { entry ->
                            entry.value.groupBy { it.chapterNaturalKey }.values.any(::hasStableObservation)
                        }.thenByDescending { entry ->
                            entry.value.maxOfOrNull(SourceDateObservation::observedAt) ?: 0L
                        },
                    )
                    .take(MAX_WORK_SAMPLES)
                    .flatMap { (_, values) ->
                        values
                            .groupBy { it.chapterNaturalKey }
                            .entries
                            .sortedWith(
                                compareByDescending<Map.Entry<String?, List<SourceDateObservation>>> { entry ->
                                    hasStableObservation(entry.value)
                                }.thenByDescending { entry ->
                                    entry.value.maxOfOrNull(SourceDateObservation::observedAt) ?: 0L
                                }.thenBy { it.key },
                            )
                            .take(MAX_CHAPTER_SAMPLES_PER_WORK)
                            .flatMap { (_, samples) ->
                                samples.sortedByDescending(SourceDateObservation::observedAt)
                                    .take(MAX_OBSERVATIONS_PER_NATURAL_KEY)
                            }
                    }
            }
        val retainedDiagnostics = recent
            .filterNot { isQualityEvidence(it, now) }
            .groupBy(SourceDateObservation::identity)
            .values
            .flatMap { identitySamples ->
                identitySamples
                    .groupBy { it.workNaturalKey to it.chapterNaturalKey }
                    .values
                    .flatMap { values ->
                        values.sortedByDescending(SourceDateObservation::observedAt)
                            .take(MAX_OBSERVATIONS_PER_NATURAL_KEY)
                    }
                    .sortedByDescending(SourceDateObservation::observedAt)
                    .take(MAX_DIAGNOSTIC_SAMPLES)
            }
        return retainedValid + retainedDiagnostics
    }

    fun evaluate(
        identity: SourceDateQualityIdentity,
        observations: List<SourceDateObservation>,
        now: Long,
    ): SourceDateQualitySnapshot {
        val matching = observations
            .filter { it.identity == identity }
            .filterNot(SourceDateObservation::networkFailure)
        val valid = matching.filter { observation ->
            val value = observation.valueAt ?: return@filter false
            value in 1L..now && observation.precision != SourceDatePrecision.UNKNOWN
        }
        val qualityValid = when (identity.field) {
            SourceDateField.CHAPTER_UPDATED -> valid
            SourceDateField.WORK_PUBLISHED -> valid.filter(SourceDateObservation::semanticConfirmed)
        }
        val status = when (identity.field) {
            SourceDateField.CHAPTER_UPDATED -> evaluateChapterStatus(qualityValid, now)
            SourceDateField.WORK_PUBLISHED -> evaluateWorkStatus(qualityValid, now)
        }
        val projectedDateAt = if (status == SourceDateQualityStatus.TRUSTED) {
            when (identity.field) {
                SourceDateField.CHAPTER_UPDATED -> qualityValid.maxOfOrNull { it.valueAt ?: 0L }
                SourceDateField.WORK_PUBLISHED -> qualityValid.minOfOrNull { it.valueAt ?: Long.MAX_VALUE }
            }
        } else {
            null
        }
        val allObservedAt = matching.map(SourceDateObservation::observedAt)
        return SourceDateQualitySnapshot(
            identity = identity,
            status = status,
            strategyVersion = STRATEGY_VERSION,
            sampleCount = matching.size,
            observedWorkCount = qualityValid.map(SourceDateObservation::workNaturalKey).distinct().size,
            stableChapterCount = stableChapterCount(qualityValid),
            distinctHistoryDateCount = qualityValid.mapNotNull(SourceDateObservation::valueAt).distinct().size,
            firstObservedAt = allObservedAt.minOrNull(),
            lastObservedAt = allObservedAt.maxOrNull(),
            projectedDateAt = projectedDateAt,
            lastReason = when (status) {
                SourceDateQualityStatus.TRUSTED -> "sample support"
                SourceDateQualityStatus.SUSPECT -> "historic dates drifted to observation time"
                SourceDateQualityStatus.UNKNOWN -> if (matching.isEmpty()) {
                    "no date evidence"
                } else {
                    "insufficient stable history"
                }
            },
        )
    }

    private fun evaluateChapterStatus(
        valid: List<SourceDateObservation>,
        now: Long,
    ): SourceDateQualityStatus {
        val groups = valid.groupBy { it.workNaturalKey to it.chapterNaturalKey }
        val stableGroups = groups.values.filter(::hasStableObservation)
        val byWork = stableGroups.groupBy { it.first().workNaturalKey }
        val enough = byWork.size >= MIN_WORK_SAMPLES &&
            byWork.values.all { groupsForWork ->
                groupsForWork.size >= MIN_CHAPTERS_PER_WORK &&
                    groupsForWork
                        .flatMap { it.mapNotNull(SourceDateObservation::valueAt) }
                        .distinct()
                        .size >= MIN_HISTORY_DATES
            } &&
            observationSpan(valid) >= MIN_OBSERVATION_INTERVAL_MILLIS
        return when {
            hasHistoricDrift(groups.values, now) -> SourceDateQualityStatus.SUSPECT
            enough -> SourceDateQualityStatus.TRUSTED
            else -> SourceDateQualityStatus.UNKNOWN
        }
    }

    private fun evaluateWorkStatus(
        valid: List<SourceDateObservation>,
        now: Long,
    ): SourceDateQualityStatus {
        val groups = valid.groupBy(SourceDateObservation::workNaturalKey)
        val enough = groups.size >= MIN_WORK_SAMPLES &&
            groups.values.all(::hasStableObservation) &&
            valid.mapNotNull(SourceDateObservation::valueAt).distinct().size >= MIN_HISTORY_DATES &&
            observationSpan(valid) >= MIN_OBSERVATION_INTERVAL_MILLIS
        return when {
            hasHistoricDrift(groups.values, now) -> SourceDateQualityStatus.SUSPECT
            enough -> SourceDateQualityStatus.TRUSTED
            else -> SourceDateQualityStatus.UNKNOWN
        }
    }

    private fun hasStableObservation(samples: List<SourceDateObservation>): Boolean {
        val ordered = samples.sortedBy(SourceDateObservation::observedAt)
        if (ordered.size < 2) return false
        val first = ordered.first()
        val last = ordered.last()
        return first.valueAt != null && first.valueAt == last.valueAt &&
            last.observedAt - first.observedAt >= MIN_OBSERVATION_INTERVAL_MILLIS
    }

    private fun retainQualityAnchors(values: List<SourceDateObservation>): List<SourceDateObservation> {
        val ordered = values.sortedBy(SourceDateObservation::observedAt)
        if (ordered.size <= MAX_OBSERVATIONS_PER_NATURAL_KEY) return ordered
        return listOf(ordered.first(), ordered.last()).distinctBy(SourceDateObservation::observedAt)
    }

    private fun hasHistoricDrift(
        groups: Collection<List<SourceDateObservation>>,
        now: Long,
    ): Boolean {
        val drifted = groups.count { samples ->
            val ordered = samples.sortedBy(SourceDateObservation::observedAt)
            if (ordered.size < 2) return@count false
            val previous = ordered[ordered.lastIndex - 1].valueAt
            val latest = ordered.last().valueAt
            previous != null && latest != null && previous != latest && latest >= now - MIN_OBSERVATION_INTERVAL_MILLIS
        }
        return drifted >= MIN_CHAPTERS_PER_WORK
    }

    private fun stableChapterCount(observations: List<SourceDateObservation>): Int = observations
        .filter { it.identity.field == SourceDateField.CHAPTER_UPDATED }
        .groupBy { it.workNaturalKey to it.chapterNaturalKey }
        .values
        .count(::hasStableObservation)

    private fun isQualityEvidence(
        observation: SourceDateObservation,
        now: Long,
    ): Boolean {
        val value = observation.valueAt ?: return false
        if (observation.networkFailure || observation.precision == SourceDatePrecision.UNKNOWN) return false
        if (value !in 1L..now) return false
        return observation.identity.field != SourceDateField.WORK_PUBLISHED || observation.semanticConfirmed
    }

    private fun observationSpan(observations: List<SourceDateObservation>): Long {
        val values = observations.map(SourceDateObservation::observedAt)
        return (values.maxOrNull() ?: 0L) - (values.minOrNull() ?: 0L)
    }
}
