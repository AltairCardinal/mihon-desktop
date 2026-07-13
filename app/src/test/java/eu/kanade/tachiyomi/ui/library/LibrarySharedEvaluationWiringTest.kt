package eu.kanade.tachiyomi.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.interactor.EvaluateLibrary

class LibrarySharedEvaluationWiringTest {
    @Test
    fun `Android library production model owns the shared evaluator`() {
        val evaluatorFields = LibraryScreenModel::class.java.declaredFields.filter {
            it.type == EvaluateLibrary::class.java
        }

        assertEquals(
            1,
            evaluatorFields.size,
            "Android LibraryScreenModel must keep one production dependency on the common evaluator",
        )
    }
}
