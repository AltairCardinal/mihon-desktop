package mihon.desktop.feedback

import tachiyomi.core.common.feedback.DiagnosticCategory
import tachiyomi.core.common.feedback.DiagnosticEvent
import tachiyomi.core.common.feedback.DiagnosticOperation
import tachiyomi.core.common.feedback.DiagnosticStage
import tachiyomi.core.common.feedback.DiagnosticStore
import tachiyomi.domain.feedback.FeedbackDraftMemory

class DesktopFeedbackRuntime(val store: DiagnosticStore) {
    val memory = FeedbackDraftMemory()
    init {
        store.record(
            DiagnosticEvent(
                DiagnosticCategory.STARTUP_CRASH,
                DiagnosticOperation.APPLICATION,
                DiagnosticStage.START,
            ),
        )
    }
}
