package mihon.feedback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import tachiyomi.core.common.feedback.DiagnosticCategory
import tachiyomi.core.common.feedback.DiagnosticEvent
import tachiyomi.core.common.feedback.DiagnosticEvents
import tachiyomi.core.common.feedback.DiagnosticOperation
import tachiyomi.core.common.feedback.DiagnosticPersistence
import tachiyomi.core.common.feedback.DiagnosticSession
import tachiyomi.core.common.feedback.DiagnosticStage
import tachiyomi.core.common.feedback.DiagnosticStore
import tachiyomi.core.common.feedback.SafeCrashReportCodec
import java.nio.file.Files
import java.time.LocalDate

internal suspend fun withDiagnosticRecording(category: DiagnosticCategory, block: suspend () -> Unit): String {
    val directory = Files.createTempDirectory("fictional-operation-diagnostics").toFile()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store = DiagnosticStore(
        directory,
        object : DiagnosticPersistence {
            var value: DiagnosticSession? = null
            override fun load() = value
            override fun save(session: DiagnosticSession?) {
                value = session
            }
        },
        scope,
    )
    DiagnosticEvents.install(store)
    return try {
        check(store.start(category))
        block()
        store.flush()
        store.read(category, LocalDate.now().toString())?.content.orEmpty()
    } finally {
        DiagnosticEvents.install(null)
        scope.cancel()
        directory.deleteRecursively()
    }
}
