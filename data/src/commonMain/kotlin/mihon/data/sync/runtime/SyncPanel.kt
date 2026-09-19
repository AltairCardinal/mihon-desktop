package mihon.data.sync.runtime

import kotlinx.coroutines.flow.StateFlow
import mihon.data.sync.auth.DiscoveredSyncSpace
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.inbox.SyncPendingItem
import mihon.domain.sync.SyncCancellationDecision
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mihon.domain.sync.auth.GitHubDeviceCode
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunResult
import mihon.domain.sync.transport.SyncRepository

enum class SyncPanelPage { MAIN, SETTINGS, HISTORY, SETUP }
enum class SyncSetupStep {
    SIGN_IN,
    DISCOVERING,
    CHOOSE_SPACE,
    NEW_PASSWORD,
    UNLOCK,
    CREATING,
    MERGING,
    COMPLETE,
    ERROR,
}
enum class SyncPasswordProblem { INCORRECT, TOO_LONG, INVALID }
enum class SyncDecisionScope { ITEM, SELECTED, ALL }
enum class SyncPanelQuestion { DISCONNECT, SWITCH_SPACE }

data class SyncBulkConfirmation(
    val jobId: String,
    val decision: SyncCancellationDecision,
    val manga: Long,
    val authors: Long,
) {
    val total: Long get() = manga + authors
}

data class SyncBulkStatus(
    val jobId: String,
    val total: Long,
    val remaining: Long,
    val completed: Long,
    val skipped: Long,
    val failed: Long,
    val running: Boolean = false,
)

/** Only events produced during the current visible session may populate this value. */
data class SyncPanelNotice(
    val exchange: SyncRunResult? = null,
    val bulk: SyncBulkStatus? = null,
    val setupCompleted: Boolean = false,
)

data class SyncPanelState(
    val visible: Boolean = false,
    val page: SyncPanelPage = SyncPanelPage.MAIN,
    val loaded: Boolean = false,
    val connection: SyncConnection? = null,
    val busy: Boolean = false,
    val problem: SyncRunProblem? = null,
    val notice: SyncPanelNotice? = null,
    val queuedMembership: Long = 0,
    val queuedReading: Long = 0,
    val pendingTotal: Long = 0,
    val pending: List<SyncPendingItem> = emptyList(),
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val selecting: Boolean = false,
    val selected: Set<Long> = emptySet(),
    val confirmation: SyncBulkConfirmation? = null,
    val question: SyncPanelQuestion? = null,
    val bulk: SyncBulkStatus? = null,
    val startup: Boolean = true,
    val periodMinutes: Int = 60,
    val nowMillis: Long = 0,
    val nextSyncAtMillis: Long = 0,
    val lastSuccessMillis: Long = 0,
    val deviceName: String = "",
    val importRemaining: Long = 0,
    val importPaused: Boolean = false,
    val records: List<SyncRunRecord> = emptyList(),
    val run: SyncRunSnapshot? = null,
    val logs: List<SyncRunLog> = emptyList(),
    val logsHasMore: Boolean = false,
    val setupStep: SyncSetupStep = SyncSetupStep.SIGN_IN,
    val setupBusy: Boolean = false,
    val deviceCode: GitHubDeviceCode? = null,
    val authFailure: GitHubAuthFailureReason? = null,
    val setupProblem: SyncDiscoveryProblem? = null,
    val passwordProblem: SyncPasswordProblem? = null,
    val spaces: List<DiscoveredSyncSpace> = emptyList(),
    val setupAccountLogin: String? = null,
    val setupRepository: SyncRepository? = null,
) {
    val queuedTotal: Long get() = queuedMembership + queuedReading

    // Device codes and transient authentication details must never be logged.
    override fun toString(): String = "SyncPanelState(page=$page, visible=$visible, busy=$busy)"
}

sealed interface SyncPanelAction {
    data object Open : SyncPanelAction
    data object Close : SyncPanelAction
    data object Back : SyncPanelAction
    data class Navigate(val page: SyncPanelPage) : SyncPanelAction
    data object Synchronize : SyncPanelAction
    data object RetrySync : SyncPanelAction
    data object CancelSync : SyncPanelAction
    data object PauseSync : SyncPanelAction
    data object ResumeSync : SyncPanelAction
    data object PauseImport : SyncPanelAction
    data object ResumeImport : SyncPanelAction
    data class SetPeriod(val minutes: Int) : SyncPanelAction
    data class SetStartup(val enabled: Boolean) : SyncPanelAction
    data class SetDeviceName(val name: String) : SyncPanelAction
    data object BeginSetup : SyncPanelAction
    data object Authorize : SyncPanelAction
    data object CancelAuthorization : SyncPanelAction
    data object RetrySetup : SyncPanelAction
    data class ChooseSpace(val space: DiscoveredSyncSpace) : SyncPanelAction
    data class SubmitPassword(val password: String) : SyncPanelAction {
        override fun toString(): String = "SubmitPassword(<redacted>)"
    }
    data class Ask(val question: SyncPanelQuestion) : SyncPanelAction
    data object CancelQuestion : SyncPanelAction
    data object ConfirmQuestion : SyncPanelAction
    data class SelectionMode(val enabled: Boolean) : SyncPanelAction
    data class ToggleItem(val id: Long, val range: Boolean = false) : SyncPanelAction
    data object SelectAll : SyncPanelAction
    data object InvertSelection : SyncPanelAction
    data class PrepareDecision(
        val decision: SyncCancellationDecision,
        val scope: SyncDecisionScope,
        val itemId: Long? = null,
    ) : SyncPanelAction
    data object CancelDecision : SyncPanelAction
    data object ConfirmDecision : SyncPanelAction
    data object PauseBulk : SyncPanelAction
    data object ResumeBulk : SyncPanelAction
    data object LoadMore : SyncPanelAction
    data object LoadMoreLogs : SyncPanelAction
    data object DismissNotice : SyncPanelAction
}

/** The application owns work; a sheet only observes state and dispatches user intent. */
interface SyncPanel {
    val state: StateFlow<SyncPanelState>
    fun dispatch(action: SyncPanelAction)
}
