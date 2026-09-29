package mihon.presentation.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import mihon.data.sync.runtime.SyncConnection
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncPanelState
import mihon.data.sync.runtime.SyncProgressDirection
import mihon.data.sync.runtime.SyncProgressFact
import mihon.data.sync.runtime.SyncProgressHold
import mihon.data.sync.runtime.SyncProgressStage
import mihon.data.sync.runtime.SyncRunPhase
import mihon.data.sync.runtime.SyncRunSnapshot
import mihon.data.sync.runtime.SyncRunState
import mihon.domain.sync.auth.GitHubDeviceCode
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.transport.SyncRepository

/** A fixed, in-memory state for reviewing the production sync panel on each platform. */
data class SyncReviewScenario(
    val id: String,
    val label: String,
    val description: String,
    val initialState: SyncPanelState,
)

object SyncReviewScenarios {
    private val connectedState = SyncPanelState(
        visible = true,
        loaded = true,
        connection = SyncConnection(
            spaceId = "review-space",
            generation = 1,
            repository = SyncRepository("review", "private", "mihon-sync"),
            enabled = true,
            protectionMode = "none",
        ),
        startup = true,
        periodMinutes = 60,
        deviceName = "Review device",
    )

    val all: List<SyncReviewScenario> = listOf(
        SyncReviewScenario(
            id = "disconnected",
            label = "未连接",
            description = "主面板尚未连接同步空间",
            initialState = SyncPanelState(visible = true, loaded = true),
        ),
        SyncReviewScenario(
            id = "setup",
            label = "首次设置",
            description = "授权前的设置页面，不连接真实账号",
            initialState = SyncPanelState(visible = true, loaded = true, page = SyncPanelPage.SETUP),
        ),
        SyncReviewScenario(
            id = "connected",
            label = "已连接",
            description = "已连接的主面板",
            initialState = connectedState,
        ),
        SyncReviewScenario(
            id = "settings",
            label = "设置",
            description = "已连接空间的同步设置",
            initialState = connectedState.copy(page = SyncPanelPage.SETTINGS),
        ),
        SyncReviewScenario(
            id = "progress",
            label = "同步进行中",
            description = "固定传输进度，可在预览中暂停和继续",
            initialState = connectedState.copy(
                busy = true,
                run = SyncRunSnapshot(
                    runId = "review-run",
                    spaceId = "review-space",
                    generation = 1,
                    trigger = SyncTrigger.MANUAL,
                    state = SyncRunState.RUNNING,
                    phase = SyncRunPhase.UPLOADING,
                    processed = 6,
                    total = 10,
                    completed = 6,
                    skipped = 0,
                    failed = 0,
                    attemptId = 1,
                    nextRetryAt = 0,
                    lastProgressAt = 1,
                    stopReason = null,
                    ownerSession = "review-session",
                    createdAt = 1,
                    updatedAt = 1,
                ),
                progress = SyncProgressFact(
                    scope = "review-run:upload",
                    stage = SyncProgressStage.TRANSFERRING,
                    direction = SyncProgressDirection.UPLOAD,
                    completedItems = 6,
                    totalItems = 10,
                    effectiveBytes = 60,
                    networkBytes = 60,
                    totalBytes = 100,
                    elapsedSeconds = 38,
                    hold = SyncProgressHold.ACTIVE,
                    stageEtaSeconds = 12,
                    wholeEtaSeconds = null,
                ),
            ),
        ),
    )

    fun find(id: String): SyncReviewScenario? = all.firstOrNull { it.id == id }
}

/** Handles only local presentation changes; external, persistent, and destructive actions are inert. */
class SyncReviewPanel(scenario: SyncReviewScenario) : SyncPanel {
    override val state = MutableStateFlow(scenario.initialState)

    override fun claimDeviceCodeBrowser(code: GitHubDeviceCode): Boolean = false

    override fun dispatch(action: SyncPanelAction) {
        state.update { current ->
            when (action) {
                SyncPanelAction.Open -> current.copy(visible = true, page = SyncPanelPage.MAIN, notice = null)
                SyncPanelAction.Close -> current.copy(visible = false, notice = null, question = null)
                SyncPanelAction.Back -> if (current.page == SyncPanelPage.MAIN) {
                    current.copy(visible = false, notice = null, question = null)
                } else {
                    current.copy(page = SyncPanelPage.MAIN, question = null)
                }
                is SyncPanelAction.Navigate -> current.copy(page = action.page)
                SyncPanelAction.BeginSetup -> current.copy(page = SyncPanelPage.SETUP)
                SyncPanelAction.Synchronize -> if (current.connection?.enabled == true) {
                    current
                } else {
                    current.copy(page = SyncPanelPage.SETUP)
                }
                is SyncPanelAction.SetPeriod -> current.copy(periodMinutes = action.minutes)
                is SyncPanelAction.SetStartup -> current.copy(startup = action.enabled)
                is SyncPanelAction.SetDeviceName -> current.copy(deviceName = action.name.take(80))
                is SyncPanelAction.Ask -> current.copy(question = action.question)
                SyncPanelAction.CancelQuestion -> current.copy(question = null)
                SyncPanelAction.PauseSync -> if (current.run?.state == SyncRunState.RUNNING) {
                    current.copy(
                        run = current.run?.copy(state = SyncRunState.PAUSED_USER),
                        progress = current.progress?.copy(hold = SyncProgressHold.PAUSED),
                        busy = false,
                    )
                } else {
                    current
                }
                SyncPanelAction.ResumeSync -> if (current.run?.state == SyncRunState.PAUSED_USER) {
                    current.copy(
                        run = current.run?.copy(state = SyncRunState.RUNNING),
                        progress = current.progress?.copy(hold = SyncProgressHold.ACTIVE),
                        busy = true,
                    )
                } else {
                    current
                }
                SyncPanelAction.DismissNotice -> current.copy(notice = null)
                else -> current
            }
        }
    }
}
