package mihon.presentation.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.inbox.SyncPendingItem
import mihon.data.sync.runtime.SyncBulkConfirmation
import mihon.data.sync.runtime.SyncBulkStatus
import mihon.data.sync.runtime.SyncConnection
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelNotice
import mihon.data.sync.runtime.SyncPanelPage
import mihon.data.sync.runtime.SyncPanelQuestion
import mihon.data.sync.runtime.SyncPanelState
import mihon.data.sync.runtime.SyncPasswordProblem
import mihon.data.sync.runtime.SyncProgressDirection
import mihon.data.sync.runtime.SyncProgressFact
import mihon.data.sync.runtime.SyncProgressHold
import mihon.data.sync.runtime.SyncProgressStage
import mihon.data.sync.runtime.SyncRunLog
import mihon.data.sync.runtime.SyncRunLogStatus
import mihon.data.sync.runtime.SyncRunPhase
import mihon.data.sync.runtime.SyncRunSnapshot
import mihon.data.sync.runtime.SyncRunState
import mihon.data.sync.runtime.SyncSetupStep
import mihon.domain.sync.SyncCancellationDecision
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.auth.GitHubDeviceCode
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunResult
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.transport.SyncRepository
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import java.io.File
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
class SyncPanelContentTest {
    @Test
    fun `first import shows committed entries as preparation detail without finishing preparation`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.IMPORTING),
            progress = SyncProgressFact(
                scope = "run-visual:import",
                stage = SyncProgressStage.PREPARING,
                direction = SyncProgressDirection.UPLOAD,
                completedItems = 0,
                totalItems = null,
                effectiveBytes = 0,
                networkBytes = 0,
                totalBytes = null,
                elapsedSeconds = 12,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
                importCompletedItems = 50,
                importTotalItems = 10_300,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().contains("正在写入已保存的数据 50 / 10300 条"))
        assertTrue(texts().contains("已准备 0 条"))
        assertFalse(texts().contains("已准备 50 / 10300 条"))
    }

    @Test
    fun `transfer card separates item count from body percentage and whole eta`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING),
            progress = SyncProgressFact(
                scope = "run-visual:1",
                stage = SyncProgressStage.TRANSFERRING,
                direction = SyncProgressDirection.UPLOAD,
                completedItems = 6_144,
                totalItems = 10_300,
                effectiveBytes = 62,
                networkBytes = 62,
                totalBytes = 100,
                elapsedSeconds = 38,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = 12,
                wholeEtaSeconds = null,
            ),
            nowMillis = 39_000,
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().any { it.contains("准备数据") })
        assertTrue(texts().any { it.contains("传输数据") })
        assertTrue(texts().any { it.contains("确认结果") })
        assertTrue(texts().contains("已传输 6144 / 10300 条"))
        assertTrue(texts().contains("传输进度 62%"))
        assertTrue(node("sync-progress").config[SemanticsProperties.StateDescription].contains("传输进度 62%"))
        assertTrue(texts().contains("已用 00:38"))
        assertTrue(texts().contains("当前阶段预计还需约 12 秒"))
        assertTrue(texts().contains("全部剩余时间正在估算"))
    }

    @Test
    fun `transfer card keeps separately confirmed count visible`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING).copy(uploaded = 4),
            progress = SyncProgressFact(
                scope = "run-visual:2",
                stage = SyncProgressStage.TRANSFERRING,
                direction = SyncProgressDirection.UPLOAD,
                completedItems = 6,
                totalItems = 10,
                effectiveBytes = 20,
                networkBytes = 20,
                totalBytes = 30,
                elapsedSeconds = 10,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
                confirmedThisRun = 4,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().contains("已传输 6 / 10 条"))
        assertTrue(texts().contains("本次已确认 4 条"))
    }

    @Test
    fun `recovered preparation keeps the durable confirmed count visible`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.CHECKING).copy(uploaded = 7),
            progress = SyncProgressFact(
                scope = "run-visual:recovered",
                stage = SyncProgressStage.PREPARING,
                direction = SyncProgressDirection.UPLOAD,
                completedItems = 0,
                totalItems = null,
                effectiveBytes = 0,
                networkBytes = 0,
                totalBytes = null,
                elapsedSeconds = 38,
                hold = SyncProgressHold.RECOVERING,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
                confirmedThisRun = 7,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().contains("本次已确认 7 条"))
        assertTrue(texts().contains("正在恢复并核对进度"))
        assertFalse(texts().contains("本次已确认 0 条"))
    }

    @Test
    fun `received download does not claim confirmation before projection`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.DOWNLOADING).copy(downloaded = 5),
            progress = SyncProgressFact(
                scope = "run-visual:received-only",
                stage = SyncProgressStage.TRANSFERRING,
                direction = SyncProgressDirection.DOWNLOAD,
                completedItems = 5,
                totalItems = 10,
                effectiveBytes = 100,
                networkBytes = 100,
                totalBytes = null,
                elapsedSeconds = 10,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().contains("已传输 5 / 10 条"))
        assertFalse(texts().any { it.startsWith("本次已确认") })
    }

    @Test
    fun `ongoing body reports its own percentage without inventing whole stage percentage`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING),
            progress = SyncProgressFact(
                scope = "run-visual:body",
                stage = SyncProgressStage.TRANSFERRING,
                direction = SyncProgressDirection.UPLOAD,
                completedItems = 0,
                totalItems = 10,
                effectiveBytes = 8_192,
                networkBytes = 8_192,
                totalBytes = null,
                elapsedSeconds = 8,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
                activeBodyBytes = 8_192,
                activeBodyTotal = 16_384,
                activeBodyEtaSeconds = 7,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().contains("当前正文传输 50%"))
        assertTrue(texts().contains("当前正文预计还需约 7 秒"))
        assertTrue(hasTag("sync-active-body-progress"))
        assertFalse(texts().any { it.startsWith("传输进度 ") })
        assertFalse(texts().any { it.startsWith("当前阶段预计还需") })
    }

    @Test
    fun `English eta uses singular seconds and minutes`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            rendered(
                connected().copy(
                    run = visualRun(SyncRunPhase.DOWNLOADING),
                    progress = SyncProgressFact(
                        scope = "run-visual:3",
                        stage = SyncProgressStage.TRANSFERRING,
                        direction = SyncProgressDirection.DOWNLOAD,
                        completedItems = 1,
                        totalItems = 2,
                        effectiveBytes = 10,
                        networkBytes = 10,
                        totalBytes = 20,
                        elapsedSeconds = 10,
                        hold = SyncProgressHold.ACTIVE,
                        stageEtaSeconds = 1,
                        wholeEtaSeconds = 60,
                    ),
                ),
            ) {
                awaitTag("sync-progress-card")
                assertTrue(texts().contains("Estimated time remaining: about 1 minute"))
                panel.state.value = panel.state.value.copy(
                    progress = panel.state.value.progress?.copy(wholeEtaSeconds = null),
                )
                render()
                assertTrue(texts().contains("Current stage: about 1 second remaining"))
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `unknown total and atomic confirmation never invent a percentage or eta`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.CONFIRMING),
            progress = SyncProgressFact(
                scope = "run-visual:1",
                stage = SyncProgressStage.CONFIRMING,
                direction = SyncProgressDirection.UPLOAD,
                completedItems = 0,
                totalItems = null,
                effectiveBytes = 0,
                networkBytes = 0,
                totalBytes = null,
                elapsedSeconds = 7,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().contains("正在统计数据"))
        assertTrue(texts().contains("剩余时间暂无法估算"))
        assertFalse(texts().any { it.contains("0 / 0") || it.contains("NaN") })
    }

    @Test
    fun `completed run freezes elapsed time at its durable finish`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.COMPLETE).copy(
                state = SyncRunState.SUCCEEDED,
                updatedAt = 31_000,
            ),
            progress = SyncProgressFact(
                scope = "run-visual:finished",
                stage = SyncProgressStage.CONFIRMING,
                direction = SyncProgressDirection.UPLOAD,
                completedItems = 1,
                totalItems = 1,
                effectiveBytes = 0,
                networkBytes = 0,
                totalBytes = null,
                elapsedSeconds = 30,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
            ),
            nowMillis = 120_000,
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().contains("已用 00:30"))
        assertFalse(texts().contains("已用 01:59"))
    }

    @Test
    fun `recovered run keeps stage and explicitly checks saved progress`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.CHECKING).copy(state = SyncRunState.WAITING_SYSTEM),
            progress = SyncProgressFact(
                scope = "run-visual:1",
                stage = SyncProgressStage.PREPARING,
                direction = SyncProgressDirection.UPLOAD,
                completedItems = 0,
                totalItems = null,
                effectiveBytes = 0,
                networkBytes = 0,
                totalBytes = null,
                elapsedSeconds = 15,
                hold = SyncProgressHold.RECOVERING,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().contains("正在恢复并核对进度"))
        assertFalse(texts().any { it.contains("预计还需约") })
    }

    @Test
    fun `pause request keeps progress and explains that saving is in progress`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING),
            progress = SyncProgressFact(
                scope = "run-visual:1",
                stage = SyncProgressStage.TRANSFERRING,
                direction = SyncProgressDirection.UPLOAD,
                completedItems = 6,
                totalItems = 10,
                effectiveBytes = 100,
                networkBytes = 100,
                totalBytes = null,
                elapsedSeconds = 18,
                hold = SyncProgressHold.PAUSING,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
            ),
        ),
    ) {
        awaitTag("sync-stage-count")
        assertTrue(texts().contains("已传输 6 / 10 条"))
        assertTrue(texts().contains("正在暂停，正在保存进度"))
        assertFalse(texts().any { it.contains("预计还需约") })
    }

    @Test
    fun `upload artifact preparation does not claim data is already uploading`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING),
            progress = SyncProgressFact(
                scope = "run-visual:1",
                stage = SyncProgressStage.PREPARING,
                direction = SyncProgressDirection.UPLOAD,
                completedItems = 0,
                totalItems = 10,
                effectiveBytes = 0,
                networkBytes = 0,
                totalBytes = null,
                elapsedSeconds = 2,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
            ),
        ),
    ) {
        awaitTag("sync-stage-count")
        assertTrue(texts().contains("正在生成上传数据"))
        assertFalse(texts().contains("正在上传变动"))
    }

    @Test
    fun `new work round is announced when the scope changes`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING),
            progress = SyncProgressFact(
                scope = "run-visual:upload-round-1",
                stage = SyncProgressStage.TRANSFERRING,
                direction = SyncProgressDirection.UPLOAD,
                completedItems = 0,
                totalItems = 5,
                effectiveBytes = 0,
                networkBytes = 0,
                totalBytes = null,
                elapsedSeconds = 30,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
                additionalWork = true,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().contains("发现新增数据，继续同步"))
    }

    @Test
    fun `stage count stays within the Android panel at a narrow viewport`() = runBlocking {
        val state = connected().copy(
            run = visualRun(SyncRunPhase.DOWNLOADING),
            progress = SyncProgressFact(
                scope = "run-visual:1",
                stage = SyncProgressStage.TRANSFERRING,
                direction = SyncProgressDirection.DOWNLOAD,
                completedItems = 10_299,
                totalItems = 10_300,
                effectiveBytes = 12,
                networkBytes = 12,
                totalBytes = null,
                elapsedSeconds = 60,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
            ),
        )
        val fixture = Fixture(state, ImageComposeScene(400, 800, coroutineContext = coroutineContext) {})
        try {
            fixture.setContent()
            fixture.awaitTag("sync-stage-count")
            assertTrue(fixture.node("sync-stage-count").boundsInRoot.right <= 400f)
        } finally {
            fixture.scene.close()
        }
    }

    @Test
    fun `large text keeps the stage count and pending status inside the Android panel`() = runBlocking {
        val state = connected().copy(
            run = visualRun(SyncRunPhase.CONFIRMING).copy(state = SyncRunState.PARTIAL),
            pendingTotal = 10_300,
            progress = SyncProgressFact(
                scope = "run-visual:large-font",
                stage = SyncProgressStage.CONFIRMING,
                direction = SyncProgressDirection.DOWNLOAD,
                completedItems = 6_144,
                totalItems = 10_300,
                effectiveBytes = 0,
                networkBytes = 0,
                totalBytes = null,
                elapsedSeconds = 38,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
            ),
        )
        val fixture =
            Fixture(state, ImageComposeScene(400, 800, coroutineContext = coroutineContext) {}, fontScale = 2f)
        try {
            fixture.setContent()
            fixture.awaitTag("sync-stage-count")
            assertTrue(fixture.node("sync-stage-count").boundsInRoot.right <= 400f)
            assertTrue(fixture.texts().contains("仍有 10300 条待处理，请在下方决定如何保留"))
        } finally {
            fixture.scene.close()
        }
    }

    @Test
    fun `short Android panel can scroll to the full progress card at large text size`() = runBlocking {
        val state = connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING),
            progress = SyncProgressFact(
                scope = "run-visual:short-panel",
                stage = SyncProgressStage.TRANSFERRING,
                direction = SyncProgressDirection.UPLOAD,
                completedItems = 50,
                totalItems = 10_300,
                effectiveBytes = 8_192,
                networkBytes = 8_192,
                totalBytes = null,
                elapsedSeconds = 38,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = 12,
                wholeEtaSeconds = null,
            ),
        )
        val fixture =
            Fixture(state, ImageComposeScene(400, 600, coroutineContext = coroutineContext) {}, fontScale = 2f)
        try {
            fixture.setContent()
            fixture.render()
            val scroll = requireNotNull(fixture.node("sync-pending-list").config[SemanticsActions.ScrollBy].action)
            for (attempt in 0..5) {
                if (fixture.hasTag("sync-stage-count") &&
                    fixture.node("sync-stage-count").boundsInRoot.top >= 0f &&
                    fixture.node("sync-stage-count").boundsInRoot.bottom <= 600f
                ) {
                    break
                }
                scroll.invoke(0f, 160f)
                fixture.render()
            }
            assertTrue(fixture.hasTag("sync-stage-count"))
            assertTrue(fixture.node("sync-stage-count").boundsInRoot.top >= 0f)
            assertTrue(fixture.node("sync-stage-count").boundsInRoot.bottom <= 600f)
        } finally {
            fixture.scene.close()
        }
    }

    @Test
    fun `main panel follows approved status queue and progress hierarchy`() = rendered(
        connected().copy(
            deviceName = "手机 A",
            queuedMembership = 4,
            queuedFavorites = 3,
            queuedFollows = 1,
            queuedReading = 1_800,
            run = SyncRunSnapshot(
                runId = "run-visual",
                spaceId = "space",
                generation = 1,
                trigger = mihon.domain.sync.runtime.SyncTrigger.MANUAL,
                state = SyncRunState.RUNNING,
                phase = SyncRunPhase.MERGING,
                processed = 75,
                total = 120,
                completed = 70,
                skipped = 2,
                failed = 3,
                attemptId = 1,
                nextRetryAt = 0,
                lastProgressAt = 1,
                stopReason = null,
                ownerSession = "session",
                createdAt = 1,
                updatedAt = 1,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(hasTag("sync-space-subtitle"))
        assertTrue(texts().contains("手机 A · 书架"))
        assertTrue(hasTag("sync-status-icon"))
        assertTrue(hasTag("sync-queue-summary"))
        assertTrue(texts().contains("1804 项"))
        assertTrue(texts().contains("3 条收藏 · 1 条关注"))
        assertTrue(texts().contains("1800 条阅读记录"))
        assertTrue(hasTag("sync-progress-detail"))
        assertTrue(texts().contains("已完成 70 项 · 跳过 2 项 · 失败 3 项 · 剩余 45 项待处理"))
    }

    @Test
    fun `unsupported binding is clearly identified in the main panel before any exchange`() = rendered(
        connected().copy(
            connection = connected().connection!!.copy(enabled = false, protectionMode = null),
            setupProblem = SyncDiscoveryProblem.INCOMPATIBLE,
            problem = SyncRunProblem.INVALID_DATA,
        ),
    ) {
        awaitTag("sync-now")
        assertTrue(texts().contains(MR.strings.sync_setup_incompatible.localized(Locale.getDefault())))
        assertFalse(hasTag("sync-reenter-password"))
        click("sync-now")
        assertEquals(SyncPanelAction.BeginSetup, actions.last())
    }

    @Test
    fun `setup completion is a dismissible session notice`() = rendered(
        connected().copy(notice = SyncPanelNotice(setupCompleted = true)),
    ) {
        awaitTag("sync-setup-complete")
        click("sync-dismiss-notice")
        assertEquals(SyncPanelAction.DismissNotice, actions.last())
        panel.state.value = connected()
        render()
        assertFalse(hasTag("sync-setup-complete"))
    }

    @Test
    fun `unfinished setup offers continue and key problems offer verified reconnection`() = rendered(
        SyncPanelState(visible = true, loaded = true, setupStep = SyncSetupStep.NEW_PASSWORD),
    ) {
        awaitTag("sync-now")
        assertTrue(texts().contains(MR.strings.sync_setup_continue.localized(Locale.getDefault())))
        click("sync-now")
        assertEquals(SyncPanelAction.BeginSetup, actions.last())
        panel.state.value = connected().copy(problem = SyncRunProblem.STORAGE)
        awaitTag("sync-reenter-password")
        click("sync-reenter-password")
        assertEquals(SyncPanelAction.BeginSetup, actions.last())
    }

    @Test
    fun `new password input changes action and visibility preserves selection`() = rendered(
        SyncPanelState(visible = true, page = SyncPanelPage.SETUP, setupStep = SyncSetupStep.NEW_PASSWORD),
    ) {
        awaitTag("sync-password-input")
        captureVisuals("password")
        assertTrue(texts().contains(MR.strings.sync_password_skip.localized(Locale.getDefault())))
        assertFalse(node("sync-password-submit").config.contains(SemanticsProperties.Disabled))
        click("sync-password-submit")
        assertEquals(SyncPanelAction.SubmitPassword(""), actions.last())
        enterPassword(" 密碼 🔒 ")
        assertTrue(texts().contains(MR.strings.sync_password_confirm.localized(Locale.getDefault())))
        val input = node("sync-password-input")
        assertTrue(requireNotNull(input.config[SemanticsActions.RequestFocus].action).invoke())
        render()
        assertTrue(node("sync-password-input").config[SemanticsProperties.Focused])
        assertTrue(
            requireNotNull(node("sync-password-input").config[SemanticsActions.SetSelection].action).invoke(1, 3, true),
        )
        render()
        assertEquals(TextRange(1, 3), node("sync-password-input").config[SemanticsProperties.TextSelectionRange])
        click("sync-password-visibility")
        render()
        assertEquals(TextRange(1, 3), node("sync-password-input").config[SemanticsProperties.TextSelectionRange])
        assertTrue(node("sync-password-input").config[SemanticsProperties.Focused])
        click("sync-password-submit")
        assertEquals(SyncPanelAction.SubmitPassword(" 密碼 🔒 "), actions.last())
        render()
        enterPassword("temporary")
        enterPassword("")
        assertTrue(texts().contains(MR.strings.sync_password_skip.localized(Locale.getDefault())))
        click("sync-password-submit")
        assertEquals(SyncPanelAction.SubmitPassword(""), actions.last())
        assertFalse(hasTag("sync-save-recovery"))
        assertFalse(hasTag("sync-confirm-merge"))
    }

    @Test
    fun `existing password input cannot skip and close clears unsubmitted text`() = rendered(
        SyncPanelState(visible = true, page = SyncPanelPage.SETUP, setupStep = SyncSetupStep.UNLOCK),
    ) {
        awaitTag("sync-password-input")
        assertTrue(node("sync-password-submit").config.contains(SemanticsProperties.Disabled))
        enterPassword("local secret")
        assertFalse(node("sync-password-submit").config.contains(SemanticsProperties.Disabled))
        panel.state.value = panel.state.value.copy(visible = false)
        render()
        panel.state.value = panel.state.value.copy(visible = true)
        render()
        assertTrue(node("sync-password-submit").config.contains(SemanticsProperties.Disabled))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `settings show actual password protection instead of recovery export`() = rendered(
        connected().copy(page = SyncPanelPage.SETTINGS),
    ) {
        awaitTag("sync-settings-list")
        scroll("sync-settings-list", 5)
        assertFalse(hasTag("sync-show-recovery"))
        assertTrue(hasTag("sync-password-status"))
        assertTrue(texts().contains(MR.strings.sync_password_disabled.localized(Locale.getDefault())))
        panel.state.value = panel.state.value.copy(
            connection = panel.state.value.connection!!.copy(protectionMode = "password"),
        )
        render()
        assertTrue(texts().contains(MR.strings.sync_password_enabled.localized(Locale.getDefault())))
    }

    @Test
    fun `reconnection explicitly authorizes from status settings and failed discovery`() = rendered(
        connected().copy(problem = SyncRunProblem.AUTHORIZATION),
    ) {
        awaitTag("sync-reconnect")
        click("sync-reconnect")
        assertEquals(SyncPanelAction.Authorize, actions.last())
        panel.state.value = connected().copy(page = SyncPanelPage.SETTINGS)
        render()
        click("sync-settings-connect")
        assertEquals(SyncPanelAction.Authorize, actions.last())
        panel.state.value = connected().copy(
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.ERROR,
            problem = SyncRunProblem.UNKNOWN,
        )
        awaitTag("sync-repo-reconnect")
        click("sync-repo-reconnect")
        assertEquals(SyncPanelAction.Authorize, actions.last())
    }

    @Test
    fun `unconfigured settings disable space actions and show unavailable protection`() = rendered(
        SyncPanelState(visible = true, page = SyncPanelPage.SETTINGS),
    ) {
        awaitTag("sync-settings-list")
        scroll("sync-settings-list", 5)
        for (tag in listOf("sync-disconnect", "sync-switch")) {
            assertTrue(node(tag).config.contains(SemanticsProperties.Disabled), tag)
        }
        assertTrue(hasTag("sync-password-status"))
        assertFalse(hasTag("sync-show-recovery"))
    }

    @Test
    fun `empty pending list omits the selection toolbar`() = rendered(connected()) {
        awaitTag("sync-history")
        assertFalse(hasTag("sync-selection-bar"))
    }

    @Test
    fun `failed authorization offers installation and retry without manual repository creation`() = rendered(
        SyncPanelState(
            visible = true,
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.ERROR,
            setupProblem = SyncDiscoveryProblem.AUTHORIZATION_REQUIRED,
        ),
    ) {
        awaitTag("sync-install-app")
        click("sync-install-app")
        assertEquals(listOf("https://github.com/apps/mihon-desktop/installations/new"), opened)
        assertFalse(hasTag("sync-create-repo"))
        click("sync-setup-retry")
        assertEquals(listOf(SyncPanelAction.RetrySetup), actions)
    }

    @Test
    fun `unfinished bulk disables replacement decisions while keeping resume available`() = rendered(
        connected().copy(pendingTotal = 1, pending = listOf(item(1))),
    ) {
        for (running in listOf(false, true)) {
            panel.state.value = panel.state.value.copy(
                selecting = false,
                bulk = SyncBulkStatus("frozen", 3, 2, 1, 0, 0, running),
            )
            awaitTag("sync-keep-1")
            render()
            for (tag in listOf("sync-keep-1", "sync-remove-1", "sync-all-menu")) {
                assertTrue(node(tag).config.contains(SemanticsProperties.Disabled), tag)
            }
            val control = if (running) "sync-pause-bulk" else "sync-resume-bulk"
            assertFalse(node(control).config.contains(SemanticsProperties.Disabled))
            click(control)
            assertEquals(if (running) SyncPanelAction.PauseBulk else SyncPanelAction.ResumeBulk, actions.last())
            panel.state.value = panel.state.value.copy(selecting = true, selected = setOf(1))
            render()
            for (tag in listOf("sync-keep-selected", "sync-remove-selected")) {
                assertTrue(node(tag).config.contains(SemanticsProperties.Disabled), tag)
            }
        }
        panel.state.value = panel.state.value.copy(
            bulk = panel.state.value.bulk!!.copy(remaining = 0, running = false),
        )
        render()
        assertFalse(node("sync-keep-selected").config.contains(SemanticsProperties.Disabled))
    }

    @Test
    fun `disconnected retained space offers connection instead of synchronization`() = rendered(
        connected().copy(connection = connected().connection!!.copy(enabled = false)),
    ) {
        awaitTag("sync-now")
        click("sync-now")
        assertEquals(SyncPanelAction.BeginSetup, actions.last())
        awaitTag("sync-history")
        val disconnectedText = texts()
        panel.state.value = panel.state.value.copy(connection = null)
        render()
        assertEquals(disconnectedText, texts())
    }

    @Test
    fun `baseline pause and resume remain separate from an active exchange`() = rendered(
        connected().copy(importRemaining = 9, busy = true),
    ) {
        awaitTag("sync-pause-import")
        click("sync-pause-import")
        assertEquals(SyncPanelAction.PauseImport, actions.last())
        panel.state.value = panel.state.value.copy(importPaused = true)
        render()
        click("sync-resume-import")
        assertEquals(SyncPanelAction.ResumeImport, actions.last())
        assertFalse(actions.contains(SyncPanelAction.CancelSync))
    }

    @Test
    fun `failed and partial notices report the failure without a false success`() = rendered(
        connected().copy(
            notice = SyncPanelNotice(
                exchange = SyncRunResult(
                    SyncRunStatus.FAILED,
                    problem = SyncRunProblem.NETWORK,
                ),
            ),
        ),
    ) {
        awaitTag("sync-notice-error")
        assertFalse(hasTag("sync-notice-counts"))
        panel.state.value = panel.state.value.copy(
            notice = SyncPanelNotice(
                exchange = SyncRunResult(
                    SyncRunStatus.PARTIAL,
                    uploaded = 1,
                    problem = SyncRunProblem.NETWORK,
                ),
            ),
        )
        render()
        assertTrue(hasTag("sync-notice-error"))
        assertTrue(hasTag("sync-notice-counts"))
    }

    @Test
    fun `partial exchange with manual decisions shows pending action instead of unknown failure`() = rendered(
        connected().copy(
            pendingTotal = 3,
            notice = SyncPanelNotice(
                exchange = SyncRunResult(
                    SyncRunStatus.PARTIAL,
                    uploaded = 4,
                    pending = 3,
                ),
            ),
        ),
    ) {
        awaitTag("sync-notice-counts")
        assertTrue(texts().contains("仍有 3 条待处理，请在下方决定如何保留"))
        assertFalse(hasTag("sync-notice-error"))
    }

    @Test
    fun `partial exchange awaiting projection explains retry in session notice`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.CONFIRMING).copy(
                state = SyncRunState.PARTIAL,
                stopReason = "projection_pending",
            ),
            notice = SyncPanelNotice(
                exchange = SyncRunResult(
                    SyncRunStatus.PARTIAL,
                    downloaded = 1,
                ),
            ),
        ),
    ) {
        awaitTag("sync-notice-counts")
        awaitTag("sync-notice-projection")
        assertTrue(texts().contains("有数据尚未完成核对，请稍后重试同步"))
        assertFalse(hasTag("sync-notice-error"))
    }

    @Test
    fun `partial run awaiting decisions does not invent zero pending while panel loads`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.CONFIRMING).copy(state = SyncRunState.PARTIAL),
            progress = SyncProgressFact(
                scope = "run-visual:pending",
                stage = SyncProgressStage.CONFIRMING,
                direction = SyncProgressDirection.DOWNLOAD,
                completedItems = 4,
                totalItems = null,
                effectiveBytes = 0,
                networkBytes = 0,
                totalBytes = null,
                elapsedSeconds = 5,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().contains("仍有数据待处理，请在下方查看"))
        assertFalse(texts().any { it.contains("仍有 0 条待处理") })
        assertFalse(texts().contains("正在统计数据"))
    }

    @Test
    fun `unresolved projection offers retry without pointing to manual decisions`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.CONFIRMING).copy(
                state = SyncRunState.PARTIAL,
                stopReason = "projection_pending",
            ),
            pendingTotal = 0,
            progress = SyncProgressFact(
                scope = "run-visual:projection-pending",
                stage = SyncProgressStage.CONFIRMING,
                direction = SyncProgressDirection.DOWNLOAD,
                completedItems = 0,
                totalItems = 1,
                effectiveBytes = 0,
                networkBytes = 0,
                totalBytes = null,
                elapsedSeconds = 5,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
                confirmedThisRun = 0,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().contains("有数据尚未完成核对，请稍后重试同步"))
        assertTrue(texts().contains("本次已确认 0 条"))
        assertFalse(texts().contains("仍有数据待处理，请在下方查看"))
    }

    @Test
    fun `toolbar shows busy and bounded cancellation count together`() = rendered(
        connected().copy(busy = true, pendingTotal = 120),
    ) {
        awaitTag("sync-open")
        assertTrue(texts().contains("99+"))
        val countBounds = node("sync-count").boundsInRoot
        val buttonBounds = node("sync-open").boundsInRoot
        assertTrue(countBounds.left >= buttonBounds.left && countBounds.right <= buttonBounds.right)
        assertTrue(countBounds.top >= buttonBounds.top && countBounds.bottom <= buttonBounds.bottom)
        assertTrue(node("sync-open").config[SemanticsProperties.StateDescription].isNotEmpty())
        click("sync-open")
        assertEquals(SyncPanelAction.Open, actions.last())
        panel.state.value = connected().copy(queuedMembership = 120)
        render()
        assertFalse(texts().contains("99+"))
        assertEquals("", node("sync-open").config[SemanticsProperties.StateDescription])
    }

    @Test
    fun `active run exposes phase progress recent logs and pause action`() = rendered(
        connected().copy(
            run = SyncRunSnapshot(
                runId = "run-1",
                spaceId = "space",
                generation = 1,
                trigger = mihon.domain.sync.runtime.SyncTrigger.RECOVERY,
                state = SyncRunState.RUNNING,
                phase = SyncRunPhase.MERGING,
                processed = 2,
                total = 5,
                completed = 2,
                skipped = 0,
                failed = 0,
                attemptId = 1,
                nextRetryAt = 0,
                lastProgressAt = 1,
                stopReason = null,
                ownerSession = "session",
                createdAt = 1,
                updatedAt = 1,
            ),
            logs = listOf(
                SyncRunLog("run-1", "item-1", "作品 A", "阅读记录 · 已合并", SyncRunLogStatus.COMPLETED, 1),
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(hasTag("sync-progress"))
        assertTrue(hasTag("sync-log-item-1"))
        click("sync-pause-run")
        assertEquals(SyncPanelAction.PauseSync, actions.last())

        panel.state.value = panel.state.value.copy(
            run = panel.state.value.run!!.copy(state = SyncRunState.PAUSED_USER),
        )
        render()
        assertTrue(node("sync-now").config.contains(SemanticsProperties.Disabled))
        click("sync-resume-run")
        assertEquals(SyncPanelAction.ResumeSync, actions.last())
    }

    @Test
    fun `setup merge exposes the same progress card and item log`() = rendered(
        connected().copy(
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.MERGING,
            run = SyncRunSnapshot(
                runId = "setup-run",
                spaceId = "space",
                generation = 1,
                trigger = mihon.domain.sync.runtime.SyncTrigger.MANUAL,
                state = SyncRunState.RUNNING,
                phase = SyncRunPhase.IMPORTING,
                processed = 1,
                total = 2,
                completed = 1,
                skipped = 0,
                failed = 0,
                attemptId = 1,
                nextRetryAt = 0,
                lastProgressAt = 1,
                stopReason = null,
                ownerSession = "session",
                createdAt = 1,
                updatedAt = 1,
            ),
            progress = SyncProgressFact(
                scope = "setup-run:1",
                stage = SyncProgressStage.PREPARING,
                direction = SyncProgressDirection.UPLOAD,
                completedItems = 1,
                totalItems = 2,
                effectiveBytes = 0,
                networkBytes = 0,
                totalBytes = null,
                elapsedSeconds = 2,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
            ),
            logs = listOf(
                SyncRunLog("setup-run", "item-1", "作品 A", "已合并", SyncRunLogStatus.COMPLETED, 1),
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().contains("已准备 1 / 2 条"))
        assertTrue(hasTag("sync-log-item-1"))
    }

    @Test
    fun `retry exhausted run remains visible with retained progress and retry action`() = rendered(
        connected().copy(
            problem = SyncRunProblem.NETWORK,
            run = SyncRunSnapshot(
                runId = "run-retry",
                spaceId = "space",
                generation = 1,
                trigger = mihon.domain.sync.runtime.SyncTrigger.RECOVERY,
                state = SyncRunState.FAILED,
                phase = SyncRunPhase.UPLOADING,
                processed = 24,
                total = 60,
                completed = 24,
                skipped = 0,
                failed = 0,
                attemptId = 3,
                nextRetryAt = 0,
                lastProgressAt = 1,
                stopReason = "retry_exhausted",
                ownerSession = null,
                createdAt = 1,
                updatedAt = 1,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().contains(MR.strings.sync_retry_exhausted.localized(Locale.getDefault())))
        click("sync-retry-run")
        assertEquals(SyncPanelAction.RetrySync, actions.last())
    }

    @Test
    fun `blocked run restores its concrete repair reason`() = rendered(
        connected().copy(
            problem = SyncRunProblem.AUTHORIZATION,
            run = SyncRunSnapshot(
                runId = "run-blocked",
                spaceId = "space",
                generation = 1,
                trigger = mihon.domain.sync.runtime.SyncTrigger.MANUAL,
                state = SyncRunState.BLOCKED,
                phase = SyncRunPhase.CHECKING,
                processed = 4,
                total = 10,
                completed = 4,
                skipped = 0,
                failed = 0,
                attemptId = 1,
                nextRetryAt = 0,
                lastProgressAt = 1,
                stopReason = "AUTHORIZATION",
                ownerSession = null,
                createdAt = 1,
                updatedAt = 1,
            ),
        ),
    ) {
        awaitTag("sync-blocked-reason")
        assertTrue(texts().contains(MR.strings.sync_problem_auth.localized(Locale.getDefault())))
        click("sync-reconnect")
        assertEquals(SyncPanelAction.Authorize, actions.last())
    }

    @Test
    fun `waiting retry shows the persisted backoff countdown`() = rendered(
        connected().copy(
            nowMillis = 1_000,
            run = SyncRunSnapshot(
                runId = "run-waiting",
                spaceId = "space",
                generation = 1,
                trigger = mihon.domain.sync.runtime.SyncTrigger.RECOVERY,
                state = SyncRunState.WAITING_RETRY,
                phase = SyncRunPhase.UPLOADING,
                processed = 2,
                total = 5,
                completed = 2,
                skipped = 0,
                failed = 0,
                attemptId = 1,
                nextRetryAt = 6_000,
                lastProgressAt = 1,
                stopReason = "network",
                ownerSession = null,
                createdAt = 1,
                updatedAt = 1,
            ),
        ),
    ) {
        awaitTag("sync-retry-countdown")
        assertTrue(
            texts().contains(
                MR.strings.sync_retry_after_seconds.localized(Locale.getDefault(), 5),
            ),
        )
    }

    @Test
    fun `rate limit wait explains why the persisted countdown is active`() = rendered(
        connected().copy(
            nowMillis = 1_000,
            run = SyncRunSnapshot(
                runId = "run-rate-limit",
                spaceId = "space",
                generation = 1,
                trigger = mihon.domain.sync.runtime.SyncTrigger.RECOVERY,
                state = SyncRunState.WAITING_RETRY,
                phase = SyncRunPhase.CHECKING,
                processed = 0,
                total = 0,
                completed = 0,
                skipped = 0,
                failed = 0,
                attemptId = 1,
                nextRetryAt = 61_000,
                lastProgressAt = 1,
                stopReason = "rate_limit",
                ownerSession = null,
                createdAt = 1,
                updatedAt = 1,
            ),
        ),
    ) {
        awaitTag("sync-retry-countdown")
        assertTrue(texts().contains(MR.strings.sync_waiting_rate_limit.localized(Locale.getDefault())))
        assertTrue(
            texts().contains(
                MR.strings.sync_retry_after_minutes.localized(Locale.getDefault(), 1),
            ),
        )
    }

    @Test
    fun `status keeps one synchronize action and long list selection stays above rows`() = rendered(
        connected().copy(pendingTotal = 120, pending = (1L..120L).map(::item), queuedReading = 2),
    ) {
        awaitTag("sync-now")
        assertEquals(1, nodes().count { tag(it) == "sync-now" })
        click("sync-now")
        assertEquals(SyncPanelAction.Synchronize, actions.last())
        awaitTag("sync-select")
        click("sync-select")
        assertEquals(SyncPanelAction.SelectionMode(true), actions.last())
        panel.state.value = panel.state.value.copy(selecting = true, selected = setOf(1))
        render()
        click("sync-select-all")
        assertEquals(SyncPanelAction.SelectAll, actions.last())
        click("sync-invert")
        assertEquals(SyncPanelAction.InvertSelection, actions.last())
        val row = node("sync-item-1")
        requireNotNull(row.config[SemanticsActions.OnLongClick].action).invoke()
        assertEquals(SyncPanelAction.ToggleItem(1, range = true), actions.last())
        assertTrue(node("sync-selection-bar").boundsInRoot.top < row.boundsInRoot.top)
        captureVisuals("main")
        click("sync-keep-selected")
        assertTrue(actions.last() is SyncPanelAction.PrepareDecision)
    }

    @Test
    fun `settings and records remain in the panel and expose native actions`() = rendered(connected()) {
        awaitTag("sync-settings")
        click("sync-settings")
        assertEquals(SyncPanelAction.Navigate(SyncPanelPage.SETTINGS), actions.last())
        panel.state.value = connected().copy(page = SyncPanelPage.SETTINGS)
        render()
        captureVisuals("settings")
        click("sync-period-0")
        assertEquals(SyncPanelAction.SetPeriod(0), actions.last())
        scroll("sync-settings-list", 5)
        assertTrue(hasTag("sync-password-status"))
        assertFalse(hasTag("sync-show-recovery"))
        click("sync-disconnect")
        assertEquals(SyncPanelAction.Ask(SyncPanelQuestion.DISCONNECT), actions.last())
        click("sync-back")
        assertEquals(SyncPanelAction.Back, actions.last())
        panel.state.value = connected().copy(page = SyncPanelPage.HISTORY)
        render()
        assertTrue(hasTag("sync-records"))
    }

    @Test
    fun `device authorization uses native copy and browser without sharing device secret`() = rendered(
        SyncPanelState(
            visible = true,
            page = SyncPanelPage.SETUP,
            deviceCode = GitHubDeviceCode("secret-device", "ABCD-EFGH", "https://github.com/login/device", 900, 5),
        ),
    ) {
        awaitTag("sync-copy-open")
        click("sync-copy-open")
        assertEquals(listOf("ABCD-EFGH"), copied)
        assertEquals(listOf("https://github.com/login/device"), opened)
        assertFalse(texts().any { it.contains("secret-device") })
        click("sync-cancel-auth")
        assertEquals(SyncPanelAction.CancelAuthorization, actions.last())
    }

    @Test
    fun `password errors stay editable and busy setup prevents duplicate submit`() = rendered(
        SyncPanelState(
            visible = true,
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.UNLOCK,
            passwordProblem = SyncPasswordProblem.INCORRECT,
        ),
    ) {
        awaitTag("sync-password-error")
        enterPassword("corrected")
        click("sync-password-submit")
        assertEquals(SyncPanelAction.SubmitPassword("corrected"), actions.last())
        panel.state.value = panel.state.value.copy(setupBusy = true)
        render()
        assertTrue(node("sync-password-submit").config.contains(SemanticsProperties.Disabled))
        assertTrue(node("sync-password-input").config.contains(SemanticsProperties.Disabled))
        panel.state.value = panel.state.value.copy(setupStep = SyncSetupStep.MERGING, passwordProblem = null)
        render()
        assertFalse(hasTag("sync-confirm-merge"))
        assertFalse(hasTag("sync-password-input"))
    }

    @Test
    fun `bulk confirmation and pause use frozen job actions`() = rendered(
        connected().copy(confirmation = SyncBulkConfirmation("frozen", SyncCancellationDecision.CONFIRM, 80, 40)),
    ) {
        awaitTag("sync-confirm-decision")
        assertTrue(texts().any { it.contains("80") && it.contains("40") })
        click("sync-confirm-decision")
        assertEquals(SyncPanelAction.ConfirmDecision, actions.last())
        panel.state.value = connected().copy(bulk = SyncBulkStatus("frozen", 120, 70, 50, 0, 0, true))
        render()
        click("sync-pause-bulk")
        assertEquals(SyncPanelAction.PauseBulk, actions.last())
        panel.state.value = panel.state.value.copy(bulk = panel.state.value.bulk!!.copy(running = false))
        render()
        click("sync-resume-bulk")
        assertEquals(SyncPanelAction.ResumeBulk, actions.last())
    }

    private fun visualRun(phase: SyncRunPhase) = SyncRunSnapshot(
        runId = "run-visual",
        spaceId = "space",
        generation = 1,
        trigger = mihon.domain.sync.runtime.SyncTrigger.MANUAL,
        state = SyncRunState.RUNNING,
        phase = phase,
        processed = 0,
        total = 0,
        completed = 0,
        skipped = 0,
        failed = 0,
        attemptId = 1,
        nextRetryAt = 0,
        lastProgressAt = 1,
        stopReason = null,
        ownerSession = "session",
        createdAt = 1_000,
        updatedAt = 1_000,
    )

    private fun connected() = SyncPanelState(
        visible = true,
        loaded = true,
        connection = SyncConnection(
            "space",
            1,
            SyncRepository("owner", "private", "sync"),
            true,
            protectionMode = "none",
        ),
    )

    private fun item(id: Long) = SyncPendingItem(
        id,
        "binding-$id",
        SyncObjectKey(SyncObjectType.MANGA, sourceId = "7", originalUrl = "/$id"),
        "Manga $id",
    )

    private fun rendered(state: SyncPanelState, block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture(state, ImageComposeScene(560, 720, coroutineContext = coroutineContext) {})
        try {
            fixture.setContent()
            fixture.block()
        } finally {
            fixture.scene.close()
        }
    }

    private class Fixture(initial: SyncPanelState, val scene: ImageComposeScene, private val fontScale: Float = 1f) {
        val actions = mutableListOf<SyncPanelAction>()
        val opened = mutableListOf<String>()
        val copied = mutableListOf<String>()
        val panel = TestPanel(initial, actions)
        fun setContent() {
            scene.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        val state by panel.state.collectAsState()
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                            Column {
                                SyncToolbarButton(state) { actions += SyncPanelAction.Open }
                                SyncPanelContent(
                                    panel,
                                    onOpenBrowser = opened::add,
                                    onCopyCode = copied::add,
                                )
                            }
                        }
                    }
                }
            }
        }
        suspend fun captureVisuals(name: String) {
            val directory = System.getProperty("mihon.sync.visualDir")?.let(::File) ?: return
            directory.mkdirs()
            for ((platform, size) in listOf("android" to (400 to 800), "desktop" to (560 to 680))) {
                val rendered = Fixture(
                    panel.state.value,
                    ImageComposeScene(size.first, size.second, coroutineContext = currentCoroutineContext()) {},
                )
                try {
                    rendered.setContent()
                    rendered.awaitTag(
                        when (name) {
                            "main" -> "sync-keep-selected"
                            "password" -> "sync-password-input"
                            else -> "sync-settings-list"
                        },
                    )
                    rendered.render()
                    rendered.scene.render().use { image ->
                        requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                            File(directory, "$platform-$name.png").writeBytes(data.bytes)
                        }
                    }
                } finally {
                    rendered.scene.close()
                }
            }
        }
        suspend fun render() {
            repeat(3) {
                scene.render()
                yield()
            }
        }
        suspend fun awaitTag(value: String) = withTimeout(2_000) {
            while (!hasTag(value)) {
                render()
                yield()
            }
        }
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
        fun hasTag(value: String) = nodes().any { tag(it) == value }
        fun node(value: String) = nodes().first { tag(it) == value }
        fun click(value: String) {
            assertTrue(requireNotNull(node(value).config[SemanticsActions.OnClick].action).invoke())
        }
        suspend fun enterPassword(value: String) {
            requireNotNull(node("sync-password-input").config[SemanticsActions.SetText].action)
                .invoke(AnnotatedString(value))
            render()
        }
        suspend fun scroll(value: String, index: Int) {
            requireNotNull(node(value).config[SemanticsActions.ScrollToIndex].action).invoke(index)
            render()
        }
        fun texts() = nodes().flatMap {
            if (it.config.contains(SemanticsProperties.Text)) {
                it.config[SemanticsProperties.Text].map { text -> text.text }
            } else {
                emptyList()
            }
        }
        fun tag(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.TestTag)) {
            node.config[SemanticsProperties.TestTag]
        } else {
            null
        }
        private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    }

    private class TestPanel(initial: SyncPanelState, private val actions: MutableList<SyncPanelAction>) : SyncPanel {
        override val state = MutableStateFlow(initial)
        override fun dispatch(action: SyncPanelAction) {
            actions += action
        }
    }
}
