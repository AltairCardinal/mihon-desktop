package mihon.data.sync.runtime

import kotlinx.coroutines.flow.StateFlow
import mihon.data.sync.auth.DiscoveredSyncSpace
import mihon.data.sync.auth.SyncAppInstallation
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.inbox.SyncPendingItem
import mihon.domain.sync.SyncCancellationDecision
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mihon.domain.sync.auth.GitHubDeviceCode
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunResult
import mihon.domain.sync.transport.SyncRepository

enum class SyncPanelPage { MAIN, SETTINGS, HISTORY, SETUP, DIAGNOSTICS, RECOVERY }

@kotlinx.serialization.Serializable
enum class SyncSpaceRecoveryReason { AUTHORIZATION_REQUIRED, SPACE_UNAVAILABLE, SPACE_DATA_INVALID, SWITCH_PENDING }
data class SyncSpaceRecovery(
    val reason: SyncSpaceRecoveryReason,
    val busy: Boolean = false,
    val lastCheckedAtMillis: Long? = null,
    val lastCheckProblem: SyncRunProblem? = null,
    val lastCheckReason: SyncSpaceRecoveryReason? = null,
    val lastCheckSucceeded: Boolean? = null,
    val authorizationConfirmedAtMillis: Long? = null,
)

@kotlinx.serialization.Serializable
enum class SyncRecoveryAuthorization { IDLE, CHECKING, WAITING, VERIFYING, CONFIRMED, CANCELLED, FAILED }

enum class SyncRecoveryContinuation { CONNECT, CREATE }
enum class SyncDiagnosticFeedback {
    CAPTURED,
    READ_FAILED,
    INCONSISTENT,
    EXPORTED,
    SAVE_FAILED,
    SESSION_STARTED,
    SESSION_ENDED,
}
enum class SyncPanelRunSource { ACTIVE, LATEST }
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
    PREPARE_REPOSITORY,
}

@kotlinx.serialization.Serializable
enum class SyncInitializationFailureReason {
    SPACE_IDENTITY_CHANGED,
    ATTEMPT_INVALID,
    REPOSITORY_IDENTITY_CHANGED,
    DEFAULT_BRANCH_CHANGED,
    NOT_EMPTY,
    BOOTSTRAP_MISSING,
    BOOTSTRAP_CHANGED,
    BOOTSTRAP_UNCONFIRMED,
    UNRECOGNIZED_DATA,
    EXISTING_SPACE_REQUIRES_JOIN,
    REQUEST_UNCONFIRMED,
    UNKNOWN,
}

@kotlinx.serialization.Serializable
data class SyncInitializationFailure(
    val stage: mihon.domain.sync.transport.SyncInitializationStage,
    val reason: SyncInitializationFailureReason,
    val requiresExplicitAction: Boolean,
)

enum class SyncPasswordProblem { INCORRECT, TOO_LONG, INVALID }
enum class SyncDecisionScope { ITEM, SELECTED, ALL }
enum class SyncPanelQuestion {
    DISCONNECT,
    SWITCH_SPACE,
    ABANDON_LEGACY,
    CREATE_NEW_SPACE,
    CONNECT_SPACE,
    CANCEL_RECOVERY_SWITCH,
    CREATE_REPOSITORY,
    REPAIR_REPOSITORY_PROPERTIES,
    AUTHORIZE_REPOSITORY_SCOPE,
    CONNECT_MANUAL_REPOSITORY,
}

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
    val spaceAddressUpdated: Boolean = false,
)

/** Read-only durable outcome details for one selected run. */
data class SyncTerminalSummary(
    val runId: String,
    val pendingDownloadBatches: Long,
    val pendingDownloadEvents: Long,
    val sourceUnavailableFields: Long,
)

sealed interface SyncFailureLogStatus {
    val runId: String
    val failedEntries: Long

    data class Ready(override val runId: String, val path: String, override val failedEntries: Long) :
        SyncFailureLogStatus

    data class SaveFailed(override val runId: String, override val failedEntries: Long) : SyncFailureLogStatus
}

data class SyncPanelState(
    val visible: Boolean = false,
    val page: SyncPanelPage = SyncPanelPage.MAIN,
    val loaded: Boolean = false,
    val connection: SyncConnection? = null,
    val recovery: SyncSpaceRecovery? = null,
    val recoveryBusy: Boolean = false,
    val recoveryStep: SyncRecoveryFlowStep = SyncRecoveryFlowStep.CHECK_CONDITIONS,
    val recoveryOutcome: SyncRecoveryOutcome? = null,
    val recoveryFailure: SyncRecoveryFailure? = null,
    val recoveryStepFailure: SyncRecoveryFailure? = null,
    val recoveryPlatformResult: SyncRecoveryPlatformResult? = null,
    val recoveryBindingStatus: SyncBindingDecode = SyncBindingDecode.UNKNOWN,
    val recoveryCredentialAvailable: Boolean = false,
    val recoveryOfficialAction: SyncRecoveryAction? = null,
    val recoveryOfficialCheckAttempted: Boolean = false,
    val recoveryConditionsVerified: Boolean = false,
    val recoveryExternalScopes: List<SyncRecoveryExternalScope> = emptyList(),
    val recoveryRepairMadeNoProgress: Boolean = false,
    val recoveryRepairReport: mihon.data.sync.inbox.SyncRepairReport? = null,
    val recoveryRepairOffset: Long = 0,
    val recoveryOldScopes: List<SyncRecoveryScopeSummary> = emptyList(),
    val externalRecoveryOrigin: String? = null,
    val recoveryPlatformRequest: SyncRecoveryPlatformRequest? = null,
    val recoveryPlatformLaunchPending: Boolean = false,
    val recoveryPersistenceFailed: Boolean = false,
    val recoveryRestartRequired: Boolean = false,
    val recoveryAuthorization: SyncRecoveryAuthorization = SyncRecoveryAuthorization.IDLE,
    val pendingRecoveryPurpose: SyncRecoveryContinuation? = null,
    val canCancelRecoverySwitch: Boolean = false,
    val canChangeSpace: Boolean = false,
    val recoveryReturnPage: SyncPanelPage? = null,
    val switchTargetRepository: SyncRepository? = null,
    val switchPendingDecisions: Long = 0,
    val busy: Boolean = false,
    val problem: SyncRunProblem? = null,
    val notice: SyncPanelNotice? = null,
    val queuedMembership: Long = 0,
    val queuedFavorites: Long = 0,
    val queuedFollows: Long = 0,
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
    /** Selection source, independent of a run state that may still have recoverable work. */
    val runSource: SyncPanelRunSource? = null,
    /** Volatile observations for the active run; omitted after process recovery until remeasured. */
    val progress: SyncProgressFact? = null,
    val terminalSummary: SyncTerminalSummary? = null,
    val failureLog: SyncFailureLogStatus? = null,
    val logs: List<SyncRunLog> = emptyList(),
    val logsHasMore: Boolean = false,
    val setupStep: SyncSetupStep = SyncSetupStep.SIGN_IN,
    val setupBusy: Boolean = false,
    val setupRetryAttempted: Boolean = false,
    val setupRetryFailed: Boolean = false,
    val authRequestStartedAtMillis: Long? = null,
    val deviceCode: GitHubDeviceCode? = null,
    val authFailure: GitHubAuthFailureReason? = null,
    val authRetryAtMillis: Long = 0,
    val setupProblem: SyncDiscoveryProblem? = null,
    val initializationFailure: SyncInitializationFailure? = null,
    val passwordProblem: SyncPasswordProblem? = null,
    val spaces: List<DiscoveredSyncSpace> = emptyList(),
    val setupAccountLogin: String? = null,
    val setupInstallation: SyncAppInstallation? = null,
    val setupRepository: SyncRepository? = null,
    val repositoryCreationName: String = mihon.data.sync.auth.GitHubSyncSpaceClient.REPOSITORY_NAME,
    /** A user statement that a named repository is ready; never a verified repository identity. */
    val repositoryPreparedName: String? = null,
    val repositoryPreparationEditing: Boolean = false,
    val creationRepositoryId: Long? = null,
    val creationSubmitted: Boolean = false,
    val creationPermissionProblem: SyncDiscoveryProblem? = null,
    val repairMakePrivate: Boolean = false,
    val repairUnarchive: Boolean = false,
    val legacyRecoveryAvailable: Boolean = false,
    val diagnosticBusy: Boolean = false,
    val diagnosticSnapshot: SyncDiagnosticSnapshot? = null,
    val diagnosticPath: String? = null,
    val diagnosticFeedback: SyncDiagnosticFeedback? = null,
) {
    val queuedTotal: Long get() = queuedMembership + queuedReading
    val canOpenRecovery: Boolean get() = true
    val recoveryPrimaryAction: SyncRecoveryActionDecision get() = recoveryDecision()
    val recoveryAlternativeActions: List<SyncRecoveryActionDecision> get() = recoveryAlternatives()
    val needsRepositoryPreparation: Boolean get() = repositoryPreparationEditing ||
        (
            repositoryPreparedName == null && setupRepository == null &&
                (
                    (
                        creationPermissionProblem == SyncDiscoveryProblem.NEEDS_INSTALLATION &&
                            setupStep == SyncSetupStep.PREPARE_REPOSITORY
                        ) ||
                        (
                            setupProblem == SyncDiscoveryProblem.NEEDS_INSTALLATION &&
                                (connection == null || pendingRecoveryPurpose == SyncRecoveryContinuation.CREATE)
                            )
                    )
            )

    // Device codes and transient authentication details must never be logged.
    override fun toString(): String = "SyncPanelState(page=$page, visible=$visible, busy=$busy)"
}

sealed interface SyncPanelAction {
    data class ExecuteRecoveryAction(val action: SyncRecoveryAction) : SyncPanelAction
    data class RecoveryPlatformCompleted(
        val requestId: String,
        val result: SyncRecoveryPlatformResult,
    ) : SyncPanelAction
    data class RecoveryOfficialOpened(val action: SyncRecoveryAction) : SyncPanelAction
    data object RecoveryOfficialReturned : SyncPanelAction
    data class PrepareBrowserRepository(val name: String) : SyncPanelAction
    data class ConfirmRepositoryPrepared(val name: String) : SyncPanelAction
    data object EditRepositoryPreparation : SyncPanelAction
    data object Open : SyncPanelAction
    data object OpenRecovery : SyncPanelAction
    data object VerifyRecovery : SyncPanelAction
    data class RepairData(val offset: Long = 0) : SyncPanelAction
    data object LoadMoreRecoveryFailures : SyncPanelAction
    data class RetryFailedBulk(val jobId: String) : SyncPanelAction
    data class OpenRecoveryPlatform(
        val action: SyncRecoveryPlatformAction,
        val objectKey: mihon.domain.sync.SyncObjectKey? = null,
    ) : SyncPanelAction
    data class RecoveryPlatformReturned(val requestId: String, val restartRequired: Boolean = false) : SyncPanelAction
    data class RecoveryPlatformFailed(val requestId: String) : SyncPanelAction
    data object CheckRepositoryCreationPermission : SyncPanelAction
    data class PrepareRepositoryCreation(val name: String) : SyncPanelAction
    data class PrepareManualRepository(val name: String) : SyncPanelAction
    data class RepairRepositoryProperties(val makePrivate: Boolean, val unarchive: Boolean) : SyncPanelAction
    data object AuthorizeRepositoryScope : SyncPanelAction
    data object RecheckSpace : SyncPanelAction
    data object CheckAuthorization : SyncPanelAction
    data object ManageAuthorization : SyncPanelAction
    data object ContinueRecovery : SyncPanelAction
    data object ConnectOtherSpace : SyncPanelAction
    data object CreateNewSpace : SyncPanelAction
    data object Close : SyncPanelAction
    data object Back : SyncPanelAction
    data class Navigate(val page: SyncPanelPage) : SyncPanelAction
    data object CaptureDiagnostics : SyncPanelAction
    data object ExportDiagnostics : SyncPanelAction
    data object BeginDiagnosticSession : SyncPanelAction
    data object EndDiagnosticSession : SyncPanelAction
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
    data object AbandonLegacyPending : SyncPanelAction
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
    val diagnosticDirectory: String? get() = null
    fun dispatch(action: SyncPanelAction)

    /** Claims the one automatic browser launch for this authorization code across sheet remounts. */
    fun claimDeviceCodeBrowser(code: GitHubDeviceCode): Boolean

    fun claimRecoveryPlatform(requestId: String): Boolean = false
}
