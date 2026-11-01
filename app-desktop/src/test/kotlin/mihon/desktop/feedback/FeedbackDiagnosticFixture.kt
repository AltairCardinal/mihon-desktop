package mihon.desktop.feedback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import tachiyomi.core.common.feedback.DiagnosticCategory
import tachiyomi.core.common.feedback.DiagnosticEvents
import tachiyomi.core.common.feedback.DiagnosticPersistence
import tachiyomi.core.common.feedback.DiagnosticSession
import tachiyomi.core.common.feedback.DiagnosticStore
import java.io.File

internal class FeedbackDiagnosticFixture(root: File) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store = DiagnosticStore(
        root,
        object : DiagnosticPersistence {
            override fun load(): DiagnosticSession? = null
            override fun save(session: DiagnosticSession?) = Unit
        },
        scope,
    )

    init {
        DiagnosticEvents.install(store)
    }

    suspend fun content(category: DiagnosticCategory): String {
        store.flush()
        val contents = mutableListOf<String>()
        for (date in store.dates(category)) contents += requireNotNull(store.read(category, date)).content
        return contents.joinToString("\n")
    }

    override fun close() {
        DiagnosticEvents.install(null)
        scope.cancel()
    }
}
