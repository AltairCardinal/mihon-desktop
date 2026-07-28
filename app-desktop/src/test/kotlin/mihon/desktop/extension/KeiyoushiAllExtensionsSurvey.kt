package mihon.desktop.extension

import kotlinx.serialization.Serializable

@Serializable
internal data class KeiyoushiSurveyEntry(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int = 0,
)

@Serializable
internal enum class KeiyoushiSurveyStatus {
    COMPATIBLE,
    DOWNLOAD_FAILED,
    CONVERSION_FAILED,
    LOAD_FAILED,
}

@Serializable
internal data class KeiyoushiSurveyResult(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val status: KeiyoushiSurveyStatus,
    val sourcesLoaded: Int = 0,
    val detail: String = "",
) {
    val artifactId: String
        get() = artifactId(pkg, code, apk)

    val isCompatible: Boolean
        get() = status == KeiyoushiSurveyStatus.COMPATIBLE && sourcesLoaded > 0

    companion object {
        fun success(
            entry: KeiyoushiSurveyEntry,
            sourcesLoaded: Int,
            detail: String = "",
        ): KeiyoushiSurveyResult = from(
            entry = entry,
            status = if (sourcesLoaded > 0) {
                KeiyoushiSurveyStatus.COMPATIBLE
            } else {
                KeiyoushiSurveyStatus.LOAD_FAILED
            },
            sourcesLoaded = sourcesLoaded,
            detail = detail.ifBlank {
                if (sourcesLoaded > 0) {
                    "Loaded $sourcesLoaded source(s)"
                } else {
                    "Production loader returned no sources"
                }
            },
        )

        fun failure(
            entry: KeiyoushiSurveyEntry,
            status: KeiyoushiSurveyStatus,
            detail: String,
        ): KeiyoushiSurveyResult {
            require(status != KeiyoushiSurveyStatus.COMPATIBLE)
            return from(entry, status, sourcesLoaded = 0, detail = detail)
        }

        private fun from(
            entry: KeiyoushiSurveyEntry,
            status: KeiyoushiSurveyStatus,
            sourcesLoaded: Int,
            detail: String,
        ) = KeiyoushiSurveyResult(
            name = entry.name,
            pkg = entry.pkg,
            apk = entry.apk,
            lang = entry.lang,
            code = entry.code,
            version = entry.version,
            status = status,
            sourcesLoaded = sourcesLoaded,
            detail = detail,
        )
    }
}

@Serializable
internal data class KeiyoushiSurveyReport(
    val schemaVersion: Int = 1,
    val indexUrl: String,
    val indexSha256: String,
    val testedGitHash: String,
    val operatingSystem: String,
    val javaVersion: String,
    val totalEntries: Int,
    val results: List<KeiyoushiSurveyResult>,
)

internal object KeiyoushiAllExtensionsSurvey {
    fun plan(entries: List<KeiyoushiSurveyEntry>): List<KeiyoushiSurveyEntry> = entries.toList()

    fun hasCompleteCoverage(
        planned: List<KeiyoushiSurveyEntry>,
        results: List<KeiyoushiSurveyResult>,
    ): Boolean {
        val expectedIds = planned.map { artifactId(it.pkg, it.code, it.apk) }
        val actualIds = results.map(KeiyoushiSurveyResult::artifactId)
        return expectedIds.size == expectedIds.toSet().size &&
            actualIds.size == actualIds.toSet().size &&
            expectedIds.toSet() == actualIds.toSet()
    }
}

private fun artifactId(pkg: String, code: Long, apk: String): String = "$pkg:$code:$apk"
