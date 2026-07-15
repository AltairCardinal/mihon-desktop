package tachiyomi.domain.source.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File

class SourceMangaSearchCommonBoundaryTest {

    @Test
    fun `common source search service has no java io dependency`() {
        val source = repositoryRoot()
            .resolve("domain/src/commonMain/kotlin/tachiyomi/domain/source/service/SourceMangaSearchService.kt")
            .readText()

        assertFalse(source.contains("java.io."), "commonMain must use a cross-platform IO error type")
    }

    private fun repositoryRoot(): File {
        var current: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (current != null && !current.resolve("settings.gradle.kts").isFile) current = current.parentFile
        return requireNotNull(current) { "Repository root not found from ${System.getProperty("user.dir")}" }
    }
}
