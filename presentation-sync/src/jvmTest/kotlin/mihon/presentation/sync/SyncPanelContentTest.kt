package mihon.presentation.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.data.sync.auth.DiscoveredSyncSpace
import mihon.data.sync.auth.SyncAppInstallation
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.auth.SyncGitHubAccount
import mihon.data.sync.auth.SyncInstallationAccountType
import mihon.data.sync.auth.SyncRepositorySelection
import mihon.data.sync.inbox.SyncPendingItem
import mihon.data.sync.runtime.SyncBulkConfirmation
import mihon.data.sync.runtime.SyncBulkStatus
import mihon.data.sync.runtime.SyncConnection
import mihon.data.sync.runtime.SyncFailureLogStatus
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
import mihon.data.sync.runtime.SyncRecoveryAuthorization
import mihon.data.sync.runtime.SyncRecoveryContinuation
import mihon.data.sync.runtime.SyncRunLog
import mihon.data.sync.runtime.SyncRunLogStatus
import mihon.data.sync.runtime.SyncRunPhase
import mihon.data.sync.runtime.SyncRunSnapshot
import mihon.data.sync.runtime.SyncRunState
import mihon.data.sync.runtime.SyncSetupStep
import mihon.data.sync.runtime.SyncSpaceRecovery
import mihon.data.sync.runtime.SyncSpaceRecoveryReason
import mihon.data.sync.runtime.SyncTerminalSummary
import mihon.domain.sync.SyncCancellationDecision
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mihon.domain.sync.auth.GitHubDeviceCode
import mihon.domain.sync.crypto.SyncSpaceDescriptor
import mihon.domain.sync.crypto.SyncSpaceProtection
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunResult
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.transport.SyncRepository
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import java.io.File
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
class SyncPanelContentTest {
    @Test
    fun `recovery method recommends the observed next step before auxiliary recheck`() {
        val cases = listOf(
            Triple(SyncSpaceRecoveryReason.SPACE_UNAVAILABLE, "sync-recovery-create", SyncPanelAction.CreateNewSpace),
            Triple(
                SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED,
                "sync-recovery-authorization",
                SyncPanelAction.CheckAuthorization,
            ),
            Triple(
                SyncSpaceRecoveryReason.SPACE_DATA_INVALID,
                "sync-recovery-details",
                SyncPanelAction.Navigate(SyncPanelPage.DIAGNOSTICS),
            ),
        )
        for ((reason, tag, action) in cases) {
            renderedEnglish(connected().copy(page = SyncPanelPage.RECOVERY, recovery = SyncSpaceRecovery(reason))) {
                awaitTag(tag)
                assertTrue(node(tag).boundsInRoot.top < node("sync-recovery-recheck").boundsInRoot.top)
                click(tag)
                assertEquals(listOf(action), actions)
            }
        }
    }

    @Test
    fun `invalid space data recommends inspecting the reason instead of replacing the space`() = renderedEnglish(
        connected().copy(recovery = SyncSpaceRecovery(SyncSpaceRecoveryReason.SPACE_DATA_INVALID)),
    ) {
        awaitTag("sync-recovery-card")
        assertFalse(hasTag("sync-recovery-create"))
        click("sync-recovery-details")
        assertEquals(listOf(SyncPanelAction.Navigate(SyncPanelPage.DIAGNOSTICS)), actions)
    }

    @Test
    fun `pending recovery continues the selected purpose and cancel uses a separate confirmation`() {
        for (purpose in SyncRecoveryContinuation.entries) {
            renderedEnglish(
                connected().copy(
                    recovery = SyncSpaceRecovery(SyncSpaceRecoveryReason.SWITCH_PENDING),
                    pendingRecoveryPurpose = purpose,
                    canCancelRecoverySwitch = true,
                ),
            ) {
                awaitTag("sync-recovery-continue")
                val label = if (purpose ==
                    SyncRecoveryContinuation.CREATE
                ) {
                    "Continue creating"
                } else {
                    "Continue connecting"
                }
                assertTrue(texts().contains(label), texts().toString())
                click("sync-recovery-continue")
                assertEquals(listOf(SyncPanelAction.ContinueRecovery), actions)
                click("sync-recovery-cancel-switch")
                assertEquals(SyncPanelAction.Ask(SyncPanelQuestion.CANCEL_RECOVERY_SWITCH), actions.last())
                panel.state.value = panel.state.value.copy(question = SyncPanelQuestion.CANCEL_RECOVERY_SWITCH)
                render()
                click("sync-cancel-question")
                assertEquals(SyncPanelAction.CancelQuestion, actions.last())
                panel.state.value = panel.state.value.copy(question = SyncPanelQuestion.CANCEL_RECOVERY_SWITCH)
                render()
                click("sync-confirm-question")
                assertEquals(SyncPanelAction.ConfirmQuestion, actions.last())
            }
        }
    }

    @Test
    fun `confirmed recovery authorization becomes explicit management instead of another check`() = renderedEnglish(
        connected().copy(
            page = SyncPanelPage.RECOVERY,
            recovery = SyncSpaceRecovery(
                SyncSpaceRecoveryReason.SPACE_UNAVAILABLE,
                authorizationConfirmedAtMillis = 1000,
            ),
            recoveryAuthorization = SyncRecoveryAuthorization.CONFIRMED,
        ),
    ) {
        awaitTag("sync-recovery-manage-authorization")
        assertFalse(hasTag("sync-recovery-authorization"))
        click("sync-recovery-manage-authorization")
        assertEquals(listOf(SyncPanelAction.ManageAuthorization), actions)
    }

    @Test
    fun `recovery separates authorization and unavailable space facts`() = renderedEnglish(
        connected().copy(
            recovery = SyncSpaceRecovery(
                SyncSpaceRecoveryReason.SPACE_UNAVAILABLE,
                authorizationConfirmedAtMillis = 9_000,
                lastCheckedAtMillis = 10_000,
                lastCheckReason = SyncSpaceRecoveryReason.SPACE_UNAVAILABLE,
                lastCheckSucceeded = true,
            ),
            recoveryAuthorization = SyncRecoveryAuthorization.CONFIRMED,
        ),
    ) {
        awaitTag("sync-recovery-card")
        assertTrue(hasTag("sync-recovery-authorization-fact"))
        assertTrue(hasTag("sync-recovery-space-fact"))
        assertTrue(hasTag("sync-recovery-last-check"))
        assertFalse(hasTag("sync-recovery-authorization"))
        click("sync-recovery-create")
        assertEquals(listOf(SyncPanelAction.CreateNewSpace), actions)
        assertTrue(hasTag("sync-recovery-recheck"))
    }

    @Test
    fun `failed recovery check retains prior space conclusion and disables repeat while checking`() = renderedEnglish(
        connected().copy(
            recovery = SyncSpaceRecovery(
                SyncSpaceRecoveryReason.SPACE_UNAVAILABLE,
                lastCheckedAtMillis = 10_000,
                lastCheckProblem = SyncRunProblem.NETWORK,
                lastCheckReason = SyncSpaceRecoveryReason.SPACE_UNAVAILABLE,
                lastCheckSucceeded = false,
                authorizationConfirmedAtMillis = 9_000,
            ),
            recoveryAuthorization = SyncRecoveryAuthorization.FAILED,
        ),
    ) {
        awaitTag("sync-recovery-card")
        assertTrue(hasTag("sync-recovery-space-fact"))
        assertTrue(hasTag("sync-recovery-check-incomplete"))
        assertTrue(texts().contains("Authorization confirmed"), texts().toString())
        click("sync-recovery-recheck")
        assertEquals(listOf(SyncPanelAction.RecheckSpace), actions)
        panel.state.value = panel.state.value.copy(recovery = panel.state.value.recovery!!.copy(busy = true))
        render()
        assertTrue(node("sync-recovery-recheck").config.contains(SemanticsProperties.Disabled))
    }

    @Test
    fun `counting track animates only while active and time begins when the plan freezes`() = renderedEnglish(
        connected().copy(
            run = visualRun(SyncRunPhase.IMPORTING).copy(plannedItems = null),
            nowMillis = 50_000,
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(hasTag("sync-counting-track"))
        assertEquals(
            androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate,
            node("sync-counting-track").config[SemanticsProperties.ProgressBarRangeInfo],
        )
        assertFalse(hasTag("sync-round-time"))
        click("sync-pause-run")
        assertEquals(listOf(SyncPanelAction.PauseSync), actions)
        panel.state.value = panel.state.value.copy(
            run = panel.state.value.run!!.copy(
                state = SyncRunState.PAUSED_USER,
                pausedAt = 50_000,
            ),
        )
        render()
        assertFalse(hasTag("sync-counting-track"))
        assertTrue(hasTag("sync-counting-paused-track"))
        assertFalse(hasTag("sync-round-time"))
        val resume = node("sync-resume-run").boundsInRoot
        assertTrue(resume.height >= 48f)
        assertEquals(node("sync-progress-card").boundsInRoot.right - 18f, resume.right, 1f)
        assertEquals(24f, unmergedNode("sync-pause-resume-icon").boundsInRoot.width, 1f)
        click("sync-resume-run")
        assertTrue(actions.contains(SyncPanelAction.ResumeSync))
        panel.state.value = panel.state.value.copy(
            run = panel.state.value.run!!.copy(
                state = SyncRunState.RUNNING,
                plannedItems = 100,
                planStartedAt = 50_000,
                pausedAt = null,
            ),
        )
        render()
        assertFalse(hasTag("sync-counting-track"))
        assertTrue(texts().contains("Syncing, completed 0/100 items"), texts().toString())
        assertTrue(texts().contains("Elapsed 00:00"), texts().toString())
        assertEquals(0f, node("sync-progress-track").config[SemanticsProperties.ProgressBarRangeInfo].current)
    }

    @Test
    fun `pending switch primary and recovery method both continue the existing setup`() {
        for (page in listOf(SyncPanelPage.MAIN, SyncPanelPage.RECOVERY)) {
            renderedEnglish(
                connected().copy(page = page, recovery = SyncSpaceRecovery(SyncSpaceRecoveryReason.SWITCH_PENDING)),
            ) {
                awaitTag("sync-recovery-continue")
                assertTrue(texts().contains(MR.strings.sync_recovery_continue.localized(Locale.US)))
                click("sync-recovery-continue")
                assertEquals(listOf(SyncPanelAction.ContinueRecovery), actions)
                assertTrue(opened.isEmpty())
            }
        }
    }

    @Test
    fun `long target connection confirmation stays readable and clickable at 200 percent`(): Unit = runBlocking {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val fixture = Fixture(
            connected().copy(
                question = SyncPanelQuestion.CONNECT_SPACE,
                switchTargetRepository = SyncRepository(
                    "reader-with-a-long-github-account-name",
                    "private-mihon-sync-space-with-a-long-name",
                    "mihon-sync-v1",
                ),
                switchPendingDecisions = 109,
            ),
            ImageComposeScene(320, 800, coroutineContext = currentCoroutineContext()) {},
            fontScale = 2f,
            dark = false,
        )
        try {
            fixture.setContent()
            fixture.awaitTag("sync-confirm-question")
            val text = fixture.node("sync-question-text")
            val pending = fixture.node("sync-switch-pending-count")
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            requireNotNull(pending.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts)
            if (layouts.any { it.hasVisualOverflow } || pending.boundsInRoot.height < pending.size.height - 1f ||
                pending.size.height <= 0
            ) {
                assertTrue(text.config.contains(SemanticsActions.ScrollBy), "clipped confirmation must remain readable")
                requireNotNull(text.config[SemanticsActions.ScrollBy].action).invoke(0f, 10_000f)
                fixture.render()
            }
            fixture.assertTextFits("sync-switch-pending-count")
            assertTrue(fixture.node("sync-switch-pending-count").boundsInRoot.height > 0f)
            for (tag in listOf("sync-confirm-question", "sync-cancel-question")) {
                val bounds = fixture.geometry(tag)
                assertTrue(bounds.height >= 48f && bounds.top >= 0f && bounds.bottom <= 800f, tag)
                assertTrue(bounds.left >= 0f && bounds.right <= 320f, tag)
                fixture.click(tag)
            }
            assertEquals(listOf(SyncPanelAction.ConfirmQuestion, SyncPanelAction.CancelQuestion), fixture.actions)
            if (text.config.contains(SemanticsActions.ScrollBy)) {
                requireNotNull(text.config[SemanticsActions.ScrollBy].action).invoke(0f, -10_000f)
            }
            fixture.render()
            System.getProperty("mihon.sync.visualDir")?.let(::File)?.let { directory ->
                directory.mkdirs()
                fixture.scene.render().use { image ->
                    requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                        File(directory, "space-connection-confirm-320-2.0-zh.png").writeBytes(data.bytes)
                    }
                }
            }
        } finally {
            fixture.scene.close()
            Locale.setDefault(original)
        }
    }

    @Test
    fun `connection confirmation names the verified target and retains zero or multiple old decisions`() {
        val target = SyncRepository("reader", "new-private-space", "mihon-sync-v1")
        for (count in listOf(0L, 7L)) {
            renderedEnglish(
                connected().copy(
                    question = SyncPanelQuestion.CONNECT_SPACE,
                    switchTargetRepository = target,
                    switchPendingDecisions = count,
                ),
            ) {
                awaitTag("sync-confirm-question")
                assertTrue(
                    texts().contains(MR.strings.sync_recovery_connect_target.localized(Locale.US, target.fullName)),
                )
                assertTrue(texts().contains(MR.strings.sync_recovery_old_pending.localized(Locale.US, count)))
                click("sync-cancel-question")
                assertEquals(SyncPanelAction.CancelQuestion, actions.last())
                click("sync-confirm-question")
                assertEquals(SyncPanelAction.ConfirmQuestion, actions.last())
                assertTrue(opened.isEmpty())
            }
        }
    }

    @Test
    fun `new space initial confirmation reports actual old unapplied decisions`() = renderedEnglish(
        connected().copy(question = SyncPanelQuestion.CREATE_NEW_SPACE, switchPendingDecisions = 9),
    ) {
        awaitTag("sync-confirm-question")
        assertTrue(texts().contains(MR.strings.sync_recovery_old_pending.localized(Locale.US, 9L)))
        assertTrue(texts().contains(MR.strings.sync_recovery_create_body.localized(Locale.US)))
        click("sync-cancel-question")
        assertEquals(listOf(SyncPanelAction.CancelQuestion), actions)
    }

    @Test
    fun `unfinished space change explains preparation without claiming a deleted space`() {
        for (page in listOf(SyncPanelPage.MAIN, SyncPanelPage.RECOVERY)) {
            renderedEnglish(
                connected().copy(page = page, recovery = SyncSpaceRecovery(SyncSpaceRecoveryReason.SWITCH_PENDING)),
            ) {
                awaitTag(if (page == SyncPanelPage.MAIN) "sync-recovery-card" else "sync-recovery-page")
                render()
                assertTrue(texts().contains(MR.strings.sync_recovery_switch_pending_title.localized(Locale.US)))
                assertTrue(texts().contains(MR.strings.sync_recovery_switch_pending_body.localized(Locale.US)))
                assertTrue(texts().contains(MR.strings.sync_recovery_switch_pending_waiting.localized(Locale.US)))
                assertFalse(texts().contains(MR.strings.sync_recovery_preserved.localized(Locale.US)))
                assertTrue(
                    hasTag(
                        if (page ==
                            SyncPanelPage.MAIN
                        ) {
                            "sync-recovery-continue"
                        } else {
                            "sync-recovery-recheck"
                        },
                    ),
                )
            }
        }
    }

    @Test
    fun `safe rename result appears as a dismissible session notice`() = renderedEnglish(
        connected().copy(notice = SyncPanelNotice(spaceAddressUpdated = true)),
    ) {
        awaitTag("sync-notice-address-updated")
        assertTrue(texts().contains(MR.strings.sync_recovery_address_updated.localized(Locale.US)))
        click("sync-dismiss-notice")
        assertEquals(SyncPanelAction.DismissNotice, actions.last())
        panel.state.value = panel.state.value.copy(notice = null)
        render()
        assertFalse(hasTag("sync-notice-address-updated"))
    }

    @Test
    fun `main space recovery gives explicit checking feedback`() = renderedEnglish(
        connected().copy(recovery = SyncSpaceRecovery(SyncSpaceRecoveryReason.SPACE_UNAVAILABLE, busy = true)),
    ) {
        awaitTag("sync-recovery-checking")
        assertTrue(texts().contains(MR.strings.sync_recovery_checking.localized(Locale.US)))
        assertTrue(node("sync-recovery-open").config.contains(SemanticsProperties.Disabled))
        assertTrue(hasTag("sync-recovery-card"))
    }

    @Test
    fun `recovery methods give explicit checking feedback`() = renderedEnglish(
        connected().copy(
            page = SyncPanelPage.RECOVERY,
            recovery = SyncSpaceRecovery(SyncSpaceRecoveryReason.SPACE_UNAVAILABLE, busy = true),
        ),
    ) {
        awaitTag("sync-recovery-checking")
        assertTrue(texts().contains(MR.strings.sync_recovery_checking.localized(Locale.US)))
        assertTrue(hasTag("sync-recovery-page"))
    }

    @Test
    fun `incomplete recovery check explains current failure and preserves recovery paths`() {
        for (page in listOf(SyncPanelPage.MAIN, SyncPanelPage.RECOVERY)) {
            for ((problem, reason) in listOf(
                SyncRunProblem.NETWORK to MR.strings.sync_problem_network,
                SyncRunProblem.STORAGE to MR.strings.sync_problem_storage,
            )) {
                renderedEnglish(
                    connected().copy(
                        page = page,
                        recovery = SyncSpaceRecovery(SyncSpaceRecoveryReason.SPACE_UNAVAILABLE),
                        problem = problem,
                    ),
                ) {
                    awaitTag("sync-recovery-check-incomplete")
                    assertTrue(texts().contains(MR.strings.sync_recovery_check_incomplete.localized(Locale.US)))
                    assertTrue(texts().contains(reason.localized(Locale.US)))
                    assertTrue(hasTag(if (page == SyncPanelPage.MAIN) "sync-recovery-card" else "sync-recovery-page"))
                    if (page == SyncPanelPage.MAIN) {
                        click("sync-recovery-open")
                        assertEquals(SyncPanelAction.OpenRecovery, actions.last())
                    } else {
                        scroll("sync-recovery-page", 2)
                        awaitTag("sync-recovery-recheck")
                        click("sync-recovery-recheck")
                        assertEquals(SyncPanelAction.RecheckSpace, actions.last())
                    }
                    panel.state.value = panel.state.value.copy(problem = SyncRunProblem.SPACE_UNAVAILABLE)
                    render()
                    assertFalse(hasTag("sync-recovery-check-incomplete"))
                }
            }
        }
    }

    @Test
    fun `empty recovery candidates keep authorization and creation available`() = renderedEnglish(
        connected().copy(page = SyncPanelPage.SETUP, setupStep = SyncSetupStep.CHOOSE_SPACE),
    ) {
        awaitTag("sync-recovery-no-spaces")
        click("sync-recovery-authorization")
        assertEquals(SyncPanelAction.CheckAuthorization, actions.last())
        click("sync-recovery-create")
        assertEquals(SyncPanelAction.CreateNewSpace, actions.last())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `multiple recovery candidates dispatch only the explicitly selected repository`() {
        val candidates = listOf(71L, 72L).map { id ->
            DiscoveredSyncSpace(
                SyncGitHubAccount(42, "owner"),
                id,
                SyncRepository("owner", "space-$id", "main"),
                SyncSpaceDescriptor("space-$id", 1, SyncSpaceProtection.None),
                "head-$id",
            )
        }
        renderedEnglish(
            connected().copy(
                page = SyncPanelPage.SETUP,
                setupStep = SyncSetupStep.CHOOSE_SPACE,
                spaces = candidates,
            ),
        ) {
            awaitTag("sync-space-72")
            assertTrue(actions.isEmpty())
            click("sync-space-72")
            assertEquals(listOf(SyncPanelAction.ChooseSpace(candidates.last())), actions)
            assertTrue(opened.isEmpty())
        }
    }

    @Test
    fun `space recovery replaces stale progress and dispatches one primary recovery action`() = renderedEnglish(
        connected().copy(
            recovery = SyncSpaceRecovery(SyncSpaceRecoveryReason.SPACE_UNAVAILABLE),
            run = visualRun(SyncRunPhase.UPLOADING),
            nextSyncAtMillis = 60_000,
        ),
    ) {
        awaitTag("sync-recovery-card")
        assertFalse(hasTag("sync-progress-card"))
        assertFalse(hasTag("sync-next-auto"))
        assertFalse(hasTag("sync-now"))
        assertTrue(texts().contains(MR.strings.sync_recovery_preserved.localized(Locale.US)))
        click("sync-recovery-open")
        assertEquals(SyncPanelAction.OpenRecovery, actions.last())
        click("sync-recovery-recheck")
        assertEquals(SyncPanelAction.RecheckSpace, actions.last())
    }

    @Test
    fun `authorization recovery presents reconnect and does not offer sync`() = renderedEnglish(
        connected().copy(recovery = SyncSpaceRecovery(SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED)),
    ) {
        awaitTag("sync-recovery-authorization")
        assertTrue(texts().contains(MR.strings.sync_recovery_auth_expired.localized(Locale.US)))
        assertFalse(hasTag("sync-now"))
        click("sync-recovery-authorization")
        assertEquals(SyncPanelAction.CheckAuthorization, actions.last())
    }

    @Test
    fun `recovery methods use production panel actions and preserve back and close`() = renderedEnglish(
        connected().copy(
            page = SyncPanelPage.RECOVERY,
            recovery = SyncSpaceRecovery(SyncSpaceRecoveryReason.SPACE_UNAVAILABLE),
        ),
    ) {
        awaitTag("sync-recovery-page")
        for ((index, entry) in listOf(
            "sync-recovery-recheck" to SyncPanelAction.RecheckSpace,
            "sync-recovery-authorization" to SyncPanelAction.CheckAuthorization,
            "sync-recovery-connect-other" to SyncPanelAction.ConnectOtherSpace,
            "sync-recovery-create" to SyncPanelAction.CreateNewSpace,
        ).withIndex()) {
            val (tag, action) = entry
            scroll("sync-recovery-page", index + 1)
            awaitTag(tag)
            click(tag)
            assertEquals(action, actions.last())
        }
        click("sync-back")
        assertEquals(SyncPanelAction.Back, actions.last())
        click("sync-close")
        assertEquals(SyncPanelAction.Close, actions.last())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `new space preparation opens browser while explicit continue uses existing recheck`() = renderedEnglish(
        connected().copy(
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.PREPARE_REPOSITORY,
            setupAccountLogin = "owner&other=a/b",
        ),
    ) {
        awaitTag("sync-recovery-prepare")
        assertTrue(texts().contains(MR.strings.sync_recovery_prepare_body.localized(Locale.US)))
        click("sync-create-private-repo")
        assertEquals(
            "https://github.com/new?name=mihon-sync&visibility=private&owner=owner%26other%3Da%2Fb",
            opened.single(),
        )
        assertTrue(actions.isEmpty(), "opening the browser must not initialize or replace a space")
        click("sync-recovery-repository-created")
        assertEquals(SyncPanelAction.RetrySetup, actions.last())
        click("sync-back")
        assertEquals(SyncPanelAction.Back, actions.last())
    }

    @Test
    fun `new space confirmation states local recovery boundary and cancellation stays explicit`() = renderedEnglish(
        connected().copy(question = SyncPanelQuestion.CREATE_NEW_SPACE),
    ) {
        awaitTag("sync-cancel-question")
        assertTrue(texts().contains(MR.strings.sync_recovery_create_body.localized(Locale.US)))
        click("sync-cancel-question")
        assertEquals(listOf(SyncPanelAction.CancelQuestion), actions)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `busy recovery disables repeated checks and switching without losing the page`() = renderedEnglish(
        connected().copy(
            page = SyncPanelPage.RECOVERY,
            recovery = SyncSpaceRecovery(SyncSpaceRecoveryReason.SPACE_UNAVAILABLE, busy = true),
        ),
    ) {
        awaitTag("sync-recovery-page")
        for ((index, tag) in listOf(
            "sync-recovery-create",
            "sync-recovery-connect-other",
            "sync-recovery-authorization",
            "sync-recovery-recheck",
        ).withIndex()) {
            scroll("sync-recovery-page", index + 1)
            awaitTag(tag)
            assertTrue(node(tag).config.contains(SemanticsProperties.Disabled), tag)
        }
        assertTrue(hasTag("sync-close"))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `recovery actions remain reachable at 200 percent with English dark and Chinese light themes`() = runBlocking {
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.SIMPLIFIED_CHINESE)) {
                Locale.setDefault(locale)
                val fixture = Fixture(
                    connected().copy(
                        page = SyncPanelPage.RECOVERY,
                        recovery = SyncSpaceRecovery(SyncSpaceRecoveryReason.SPACE_DATA_INVALID),
                    ),
                    ImageComposeScene(320, 1100, coroutineContext = currentCoroutineContext()) {},
                    fontScale = 2f,
                    dark = locale == Locale.US,
                )
                try {
                    fixture.setContent()
                    fixture.awaitTag("sync-recovery-page")
                    for ((index, tag) in listOf(
                        1 to "sync-recovery-details",
                        2 to "sync-recovery-connect-other",
                        3 to "sync-recovery-authorization",
                        4 to "sync-recovery-create",
                        5 to "sync-recovery-recheck",
                    )) {
                        fixture.scroll("sync-recovery-page", index)
                        fixture.awaitTag(tag)
                        val bounds = fixture.geometry(tag)
                        assertTrue(bounds.height >= 48f && bounds.right <= 320 && bounds.left >= 0, tag)
                    }
                    assertEquals(SyncPanelPage.RECOVERY, fixture.panel.state.value.page)
                    assertTrue(fixture.actions.isEmpty())
                    System.getProperty("mihon.sync.visualDir")?.let(::File)?.let { directory ->
                        directory.mkdirs()
                        fixture.scene.render().use { image ->
                            requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                                File(directory, "space-recovery-320-2.0-${locale.language}.png").writeBytes(data.bytes)
                            }
                        }
                    }
                } finally {
                    fixture.scene.close()
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `main unknown failure keeps recovery accessible with and without a failed run`() {
        for (run in listOf(null, visualRun(SyncRunPhase.UPLOADING).copy(state = SyncRunState.FAILED))) {
            renderedEnglish(connected().copy(problem = SyncRunProblem.UNKNOWN, run = run, canChangeSpace = true)) {
                awaitTag("sync-main-recovery-open")
                click("sync-main-recovery-open")
                assertTrue(actions.contains(SyncPanelAction.OpenRecovery))
            }
        }
    }

    @Test
    fun `setup unknown failure offers an immediate safe recovery exit and diagnostics`() = renderedEnglish(
        connected().copy(
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.ERROR,
            setupProblem = SyncDiscoveryProblem.RETRYABLE,
            canChangeSpace = true,
        ),
    ) {
        awaitTag("sync-setup-recovery-open")
        assertTrue(texts().any { it.contains("deleted or can no longer be used") })
        click("sync-setup-recovery-open")
        assertTrue(actions.contains(SyncPanelAction.OpenRecovery))
        click("sync-setup-error-details")
        assertTrue(actions.contains(SyncPanelAction.Navigate(SyncPanelPage.DIAGNOSTICS)))
        click("sync-setup-retry")
        assertTrue(actions.contains(SyncPanelAction.RetrySetup))
    }

    @Test
    fun `first setup failure configures space while unreadable existing binding cannot switch`() {
        renderedEnglish(
            connected().copy(
                connection = null,
                page = SyncPanelPage.SETUP,
                setupStep = SyncSetupStep.ERROR,
                setupProblem = SyncDiscoveryProblem.RETRYABLE,
                canChangeSpace = false,
            ),
        ) {
            awaitTag("sync-setup-configure-space")
            assertFalse(hasTag("sync-setup-recovery-open"))
            click("sync-setup-configure-space")
            assertTrue(actions.contains(SyncPanelAction.BeginSetup))
        }
        renderedEnglish(
            connected().copy(
                page = SyncPanelPage.SETUP,
                setupStep = SyncSetupStep.ERROR,
                setupProblem = SyncDiscoveryProblem.INCOMPATIBLE,
                canChangeSpace = false,
            ),
        ) {
            awaitTag("sync-setup-error-details")
            assertFalse(hasTag("sync-setup-recovery-open"))
            assertFalse(hasTag("sync-setup-configure-space"))
        }
    }

    @Test
    fun `unknown recovery uses a neutral choice rather than claiming the old space failed`() = renderedEnglish(
        connected().copy(page = SyncPanelPage.RECOVERY, canChangeSpace = true),
    ) {
        awaitTag("sync-recovery-neutral")
        assertTrue(texts().contains("Choose how to change your sync space."))
        click("sync-recovery-create")
        assertTrue(actions.contains(SyncPanelAction.CreateNewSpace))
        click("sync-recovery-connect-other")
        assertTrue(actions.contains(SyncPanelAction.ConnectOtherSpace))
        assertFalse(hasTag("sync-recovery-recheck"))
    }

    @Test
    fun `failed setup retry has explicit feedback and busy setup disables recovery exit`() = renderedEnglish(
        connected().copy(
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.ERROR,
            setupProblem = SyncDiscoveryProblem.RETRYABLE,
            setupRetryFailed = true,
            canChangeSpace = true,
        ),
    ) {
        awaitTag("sync-setup-retry-failed")
        assertTrue(texts().contains("Retry was unsuccessful."))
        panel.state.value = panel.state.value.copy(setupBusy = true)
        render()
        assertTrue(node("sync-setup-recovery-open").config.contains(SemanticsProperties.Disabled))
        assertTrue(node("sync-setup-retry").config.contains(SemanticsProperties.Disabled))
    }

    @Test
    fun `setup retryable error remains visible beside an old nonterminal run`() {
        renderedEnglish(
            connected().copy(
                page = SyncPanelPage.SETUP,
                setupStep = SyncSetupStep.ERROR,
                setupProblem = SyncDiscoveryProblem.RETRYABLE,
                run = visualRun(SyncRunPhase.UPLOADING),
            ),
        ) {
            awaitTag("sync-setup-retry")
            assertTrue(hasTag("sync-setup-error"), "current discovery error must not be hidden by an older run")
            click("sync-setup-retry")
            assertTrue(actions.contains(SyncPanelAction.RetrySetup))
            panel.state.value = panel.state.value.copy(
                setupInstallation = SyncAppInstallation(7, SyncRepositorySelection.SELECTED, 2),
            )
            render()
            assertTrue(hasTag("sync-installation-scope-warning"))
            panel.state.value = panel.state.value.copy(setupStep = SyncSetupStep.MERGING)
            awaitTag("sync-progress-card")
            assertFalse(hasTag("sync-setup-error"))
            assertFalse(hasTag("sync-installation-scope-warning"))
        }
    }

    @Test
    fun `modern settings groups and frequency chips stay reachable at 200 percent`() = runBlocking {
        val original = Locale.getDefault()
        val directory = System.getProperty("mihon.sync.visualDir")?.let(::File)
        try {
            for (locale in listOf(Locale.US, Locale.SIMPLIFIED_CHINESE)) {
                Locale.setDefault(locale)
                val fixture = Fixture(
                    connected().copy(page = SyncPanelPage.SETTINGS),
                    ImageComposeScene(320, 1100, coroutineContext = currentCoroutineContext()) {},
                    fontScale = 2f,
                    dark = locale == Locale.US,
                )
                try {
                    fixture.setContent()
                    fixture.awaitTag("sync-settings-auto-group")
                    for (minutes in listOf(0, 15, 60, 360, 1440)) {
                        val tag = "sync-period-$minutes"
                        val bounds = fixture.geometry(tag)
                        assertTrue(bounds.height >= 48f && bounds.right <= 320 && bounds.left >= 0)
                        fixture.click(tag)
                        assertEquals(SyncPanelAction.SetPeriod(minutes), fixture.actions.last())
                    }
                    suspend fun snapshot(name: String) {
                        directory?.let {
                            it.mkdirs()
                            fixture.scene.render().use { image ->
                                requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                                    File(
                                        it,
                                        "modern-settings-320-2.0-${locale.language}-$name.png",
                                    ).writeBytes(data.bytes)
                                }
                            }
                        }
                    }
                    snapshot("auto")
                    fixture.scroll("sync-settings-list", 1)
                    fixture.awaitTag("sync-device-name")
                    assertTrue(fixture.geometry("sync-device-name").right <= 320)
                    snapshot("account")
                    fixture.scroll("sync-settings-list", 2)
                    fixture.awaitTag("sync-settings-diagnostics")
                    fixture.click("sync-settings-diagnostics")
                    assertEquals(SyncPanelAction.Navigate(SyncPanelPage.DIAGNOSTICS), fixture.actions.last())
                    for ((index, tag, question) in listOf(
                        Triple(3, "sync-disconnect", SyncPanelQuestion.DISCONNECT),
                        Triple(4, "sync-switch", SyncPanelQuestion.SWITCH_SPACE),
                    )) {
                        fixture.scroll("sync-settings-list", index)
                        fixture.click(tag)
                        assertEquals(SyncPanelAction.Ask(question), fixture.actions.last())
                    }
                } finally {
                    fixture.scene.close()
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `modern failed schedule reflects network and immediate frequency changes`() = renderedEnglish(
        connected().copy(
            run = visualRun(SyncRunPhase.COMPLETE).copy(state = SyncRunState.FAILED),
            problem = SyncRunProblem.NETWORK,
            periodMinutes = 60,
            nowMillis = 100_000,
            nextSyncAtMillis = 3_700_000,
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(hasTag("sync-next-auto"))
        assertTrue(texts().any { it.contains("waiting for the connection") })
        panel.state.value = panel.state.value.copy(problem = null, periodMinutes = 360, nextSyncAtMillis = 21_700_000)
        render()
        assertTrue(texts().any { it.contains(syncScheduleDateTime(21_700_000)) && it.contains("6") })
        panel.state.value = panel.state.value.copy(periodMinutes = 0)
        render()
        assertTrue(texts().contains("Automatic sync is off"))
    }

    @Test
    fun `modern pending actions wrap at large text and retain the safe decision snapshot`() = runBlocking {
        val fixture = Fixture(
            connected().copy(pendingTotal = 1, pending = listOf(item(1))),
            ImageComposeScene(320, 1200, coroutineContext = currentCoroutineContext()) {},
            fontScale = 2f,
        )
        try {
            fixture.setContent()
            fixture.awaitTag("sync-keep-1")
            for (tag in listOf("sync-keep-1", "sync-remove-1")) {
                val bounds = fixture.geometry(tag)
                assertTrue(bounds.height >= 48f)
                assertTrue(bounds.left >= 0 && bounds.right <= 320, "$tag $bounds")
            }
            assertTrue(
                fixture.geometry("sync-remove-1").top >= fixture.geometry("sync-keep-1").bottom,
                "Large-text decisions each have enough width",
            )
            fixture.click("sync-keep-1")
            assertEquals(
                SyncPanelAction.PrepareDecision(
                    SyncCancellationDecision.KEEP_LOCAL,
                    mihon.data.sync.runtime.SyncDecisionScope.ITEM,
                    1,
                ),
                fixture.actions.last(),
            )
        } finally {
            fixture.scene.close()
        }
    }

    @Test
    fun `modern completed card is compact and details remain actionable`() = renderedEnglish(
        connected().copy(
            run = visualRun(
                SyncRunPhase.COMPLETE,
            ).copy(state = SyncRunState.SUCCEEDED, confirmedItems = 14434, plannedItems = 14434),
            periodMinutes = 60,
            nowMillis = 100_000,
            nextSyncAtMillis = 3_700_000,
        ),
    ) {
        awaitTag("sync-progress-card")
        assertFalse(hasTag("sync-progress"), "A completed result has no running track")
        assertFalse(hasTag("sync-whole-eta-value"))
        assertTrue(texts().contains("Sync completed"))
        assertTrue(geometry("sync-progress-card").height < 240f)
        assertTrue(hasTag("sync-next-auto"))
        assertFalse(hasTag("sync-queue-summary"))
        click("sync-progress-details-toggle")
        render()
        assertTrue(hasTag("sync-queue-summary"))
        click("sync-now")
        assertEquals(SyncPanelAction.Synchronize, actions.last())
    }

    @Test
    fun `modern idle queue is folded and schedule uses the durable deadline`() = renderedEnglish(
        connected().copy(periodMinutes = 60, nowMillis = 100_000, nextSyncAtMillis = 3_700_000),
    ) {
        awaitTag("sync-now")
        assertFalse(hasTag("sync-queue-summary"))
        assertTrue(hasTag("sync-next-auto"))
        val original = node("sync-next-auto").config[SemanticsProperties.Text].toString()
        click("sync-progress-details-toggle")
        render()
        assertTrue(hasTag("sync-queue-summary"))
        assertEquals(original, node("sync-next-auto").config[SemanticsProperties.Text].toString())
        panel.state.value = panel.state.value.copy(nowMillis = 160_000)
        render()
        assertNotEquals(original, node("sync-next-auto").config[SemanticsProperties.Text].toString())
    }

    @Test
    fun `modern diagnostics technical controls are folded and survive feedback`() = renderedEnglish(
        connected().copy(page = SyncPanelPage.DIAGNOSTICS),
    ) {
        awaitTag("sync-diagnostic-capture")
        assertFalse(hasTag("sync-diagnostic-session"))
        click("sync-diagnostic-details-toggle")
        render()
        click("sync-diagnostic-session")
        assertEquals(SyncPanelAction.BeginDiagnosticSession, actions.last())
        panel.state.value = panel.state.value.copy(diagnosticBusy = true)
        render()
        assertTrue(hasTag("sync-diagnostic-session"))
        assertTrue(node("sync-diagnostic-session").config.contains(SemanticsProperties.Disabled))
    }

    @Test
    fun `modern settings group frequency and preserve every original action`() = renderedEnglish(
        connected().copy(page = SyncPanelPage.SETTINGS),
    ) {
        awaitTag("sync-settings-auto-group")
        assertTrue(hasTag("sync-settings-auto-group"))
        assertTrue(texts().contains("Periodic sync"))
        click("sync-period-360")
        assertEquals(SyncPanelAction.SetPeriod(360), actions.last())
        scroll("sync-settings-list", 1)
        awaitTag("sync-device-name")
        assertTrue(hasTag("sync-settings-account-group"))
        scroll("sync-settings-list", 2)
        awaitTag("sync-settings-diagnostics")
        assertTrue(hasTag("sync-settings-more-group"))
        click("sync-settings-diagnostics")
        assertEquals(SyncPanelAction.Navigate(SyncPanelPage.DIAGNOSTICS), actions.last())
    }

    @Test
    fun `modern completed layout fits narrow large text and preserves schedule after expiry`() = runBlocking {
        val originalLocale = Locale.getDefault()
        val directory = System.getProperty("mihon.sync.visualDir")?.let(::File)
        try {
            for (locale in listOf(Locale.US, Locale.SIMPLIFIED_CHINESE)) {
                Locale.setDefault(locale)
                for (width in listOf(320, 390, 560)) {
                    val fixture = Fixture(
                        connected().copy(
                            run = visualRun(
                                SyncRunPhase.COMPLETE,
                            ).copy(state = SyncRunState.SUCCEEDED, confirmedItems = 1280, updatedAt = 62_000),
                            periodMinutes = 60,
                            nowMillis = 100_000,
                            nextSyncAtMillis = 3_700_000,
                        ),
                        ImageComposeScene(width, 900, coroutineContext = currentCoroutineContext()) {},
                        fontScale = 2f,
                        dark = locale == Locale.US,
                    )
                    try {
                        fixture.setContent()
                        fixture.awaitTag("sync-progress-card")
                        fixture.assertTextFits("sync-progress-status")
                        fixture.assertTextFits("sync-confirmed-count")
                        assertTrue(fixture.geometry("sync-now").height >= 48f)
                        assertTrue(fixture.geometry("sync-progress-card").width <= width)
                        fixture.reveal("sync-next-auto", 900)
                        fixture.assertTextFits("sync-next-auto")
                        directory?.let {
                            it.mkdirs()
                            fixture.scene.render().use { image ->
                                requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                                    File(it, "modern-$width-2.0-${locale.language}.png").writeBytes(data.bytes)
                                }
                            }
                        }
                        fixture.panel.state.value = fixture.panel.state.value.copy(nowMillis = 3_800_000)
                        fixture.render()
                        val text = fixture.node("sync-next-auto").config[SemanticsProperties.Text].joinToString {
                            it.text
                        }
                        assertTrue(text.contains("system") || text.contains("系统"))
                        assertEquals(3_700_000, fixture.panel.state.value.nextSyncAtMillis)
                    } finally {
                        fixture.scene.close()
                    }
                }
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `compact running card shows only whole run count time and real fraction`() = renderedEnglish(
        connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING).copy(plannedItems = 100, confirmedItems = 40),
            progress = compactFact().copy(confirmedThisRun = 999, effectiveBytes = 80, totalBytes = 100),
            queuedMembership = 9,
            nowMillis = 11_000,
            pendingTotal = 1,
            pending = listOf(item(1)),
            logs = listOf(SyncRunLog("run-visual", "hidden", "PRIVATE LOG", "detail", SyncRunLogStatus.COMPLETED, 0)),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().contains("Syncing, completed 40/100 items"), texts().toString())
        assertTrue(texts().contains("Elapsed 00:10"), texts().toString())
        assertEquals(
            androidx.compose.ui.semantics.ProgressBarRangeInfo(0.4f, 0f..1f),
            node("sync-progress-track").config[SemanticsProperties.ProgressBarRangeInfo],
        )
        for (tag in listOf(
            "sync-progress-action", "sync-confirmed-count", "sync-progress-explanation", "sync-details-toggle",
            "sync-progress-details", "sync-queue-summary", "sync-history", "sync-selection-bar", "sync-log-hidden",
        )) {
            assertFalse(hasTag(tag), tag)
        }
        assertFalse(texts().any { it.contains("PRIVATE LOG") || it.contains("Manga 1") })
        click("sync-pause-run")
        assertTrue(actions.contains(SyncPanelAction.PauseSync))
        assertTrue(hasTag("sync-close"))
    }

    @Test
    fun `compact setup merging shares the same counting and paused feedback`() = renderedEnglish(
        connected().copy(
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.MERGING,
            setupBusy = true,
            run = visualRun(SyncRunPhase.IMPORTING).copy(plannedItems = null),
            progress = compactFact(),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().contains("Counting data"), texts().toString())
        assertFalse(hasTag("sync-details-toggle"))
        panel.state.value = panel.state.value.copy(
            run = panel.state.value.run!!.copy(
                state = SyncRunState.PAUSED_USER,
                plannedItems = 100,
                confirmedItems = 40,
            ),
        )
        render()
        assertTrue(texts().contains("Sync paused, completed 40/100 items"), texts().toString())
        assertTrue(hasTag("sync-resume-run"))
        click("sync-resume-run")
        assertTrue(actions.contains(SyncPanelAction.ResumeSync))
    }

    @Test
    fun `initial statistics never promote prepared rows to safe confirmations`() = rendered(
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
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-stage-count"))
        assertFalse(hasTag("sync-active-body-progress"))
        assertTrue(texts().contains("正在统计数据"))
        assertFalse(texts().any { it.contains("50 / 10300") })
        assertFalse(hasTag("sync-progress-track"))
    }

    @Test
    fun `compact whole plan excludes work totals body percentages and local ETA`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING).copy(plannedItems = 20_000),
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
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-stage-count"))
        assertFalse(hasTag("sync-active-body-progress"))
        assertTrue(texts().contains("同步中，已完成0/20000条"))
        assertTrue(texts().any { it.startsWith("已用 ") })
        assertFalse(texts().any { it.contains("剩余估时") })
        assertFalse(texts().any { it.contains("6144") || it.contains("62%") || it.contains("12 秒") })
    }

    @Test
    fun `transfer card keeps separately confirmed count visible`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING).copy(uploaded = 4, confirmedItems = 4, plannedItems = 10),
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
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-stage-count"))
        assertFalse(hasTag("sync-active-body-progress"))
        assertTrue(texts().contains("同步中，已完成4/10条"))
        assertEquals(0.4f, node("sync-progress-track").config[SemanticsProperties.ProgressBarRangeInfo].current)
        assertFalse(texts().any { it.contains("6 / 10") })
    }

    @Test
    fun `recovered preparation keeps the durable confirmed count visible`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.CHECKING).copy(uploaded = 7, confirmedItems = 7, plannedItems = 10),
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
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-stage-count"))
        assertFalse(hasTag("sync-active-body-progress"))
        assertTrue(texts().any { it.contains("正在恢复并核对进度") && it.contains("7/10条") })
        assertEquals(0.7f, node("sync-progress-track").config[SemanticsProperties.ProgressBarRangeInfo].current)
    }

    @Test
    fun `received download does not claim confirmation before projection`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.DOWNLOADING).copy(downloaded = 5, plannedItems = 10),
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
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-stage-count"))
        assertFalse(hasTag("sync-active-body-progress"))
        assertTrue(texts().contains("同步中，已完成0/10条"))
        assertEquals(0f, node("sync-progress-track").config[SemanticsProperties.ProgressBarRangeInfo].current)
        assertFalse(texts().any { it.contains("5 / 10") })
    }

    @Test
    fun `ongoing body never invents a whole plan percentage or ETA`() = rendered(
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
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-stage-count"))
        assertFalse(hasTag("sync-active-body-progress"))
        assertTrue(texts().contains("正在统计数据"))
        assertFalse(hasTag("sync-progress-track"))
        assertFalse(texts().any { it.contains("50%") || it.contains("7 秒") })
    }

    @Test
    fun `English time ignores local ETA and uses the shared clock format`() = renderedEnglish(
        connected().copy(
            run = visualRun(SyncRunPhase.DOWNLOADING).copy(plannedItems = 2),
            progress = SyncProgressFact(
                "run-visual:3", SyncProgressStage.TRANSFERRING, SyncProgressDirection.DOWNLOAD,
                1, 2, 10, 10, 20, 10, SyncProgressHold.ACTIVE, 1, 60,
            ),
            nowMillis = 11_000,
        ),
    ) {
        awaitTag("sync-round-time")
        assertTrue(texts().contains("Elapsed 00:10"))
        assertTrue(texts().contains("Syncing, completed 0/2 items"))
        assertFalse(texts().any { it.contains("1 minute") || it.contains("1 second") })
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
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-stage-count"))
        assertFalse(hasTag("sync-active-body-progress"))
        assertTrue(texts().contains("正在统计数据"))
        assertFalse(hasTag("sync-round-time"))
        assertTrue(hasTag("sync-counting-track"))
        assertFalse(hasTag("sync-progress-track"))
        assertFalse(texts().any { it.contains("0/0") || it.contains("NaN") })
    }

    @Test
    fun `partial terminal run shows durable outcome instead of stale live stage`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING).copy(
                state = SyncRunState.PARTIAL,
                stopReason = "projection_pending",
                confirmedItems = 26_778,
                plannedItems = 30_000,
                updatedAt = 6_000,
            ),
            progress = SyncProgressFact(
                scope = "run-visual:empty-download",
                stage = SyncProgressStage.CONFIRMING,
                direction = SyncProgressDirection.DOWNLOAD,
                completedItems = 0,
                totalItems = 0,
                effectiveBytes = 0,
                networkBytes = 0,
                totalBytes = null,
                elapsedSeconds = 10,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
            ),
            terminalSummary = SyncTerminalSummary("run-visual", 2, 512, 10),
            nowMillis = 600_000,
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().contains("本次同步已结束，部分数据待处理"))
        assertTrue(texts().any { it.contains("已完成26778条") })
        assertTrue(texts().contains("2 个下载批次待确认，涉及 512 条变动"))
        assertTrue(texts().contains("批次内变动仍待核对，不代表这些变动都已失败"))
        assertTrue(texts().contains("上次有 10 项核对内容因漫画源不可用而未能恢复"))
        assertTrue(texts().contains("请重试同步，重新检查并恢复已保存的数据"))
        assertTrue(texts().any { it.contains("用时00:05") })
        assertFalse(hasTag("sync-progress"))
        assertFalse(texts().contains("正在接收并校验数据"))
        assertFalse(texts().any { it.contains("已确认 0 / 0") })
        click("sync-retry-run")
        assertEquals(SyncPanelAction.RetrySync, actions.last())
    }

    @Test
    fun `failure report exposes complete file path and opens only selected run`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.CONFIRMING).copy(state = SyncRunState.PARTIAL),
            failureLog = SyncFailureLogStatus.Ready("run-visual", "C:/reports/sync-run.txt", 521),
        ),
    ) {
        awaitTag("sync-failure-log-open")
        click("sync-progress-details-toggle")
        render()
        assertTrue(texts().any { it.contains("521") })
        assertTrue(texts().contains("C:/reports/sync-run.txt"))
        click("sync-failure-log-open")
        assertEquals(listOf("C:/reports/sync-run.txt"), openedFailureLogs)
        panel.state.value = panel.state.value.copy(
            failureLog = SyncFailureLogStatus.Ready("another-run", "C:/reports/other.txt", 2),
        )
        render()
        assertFalse(hasTag("sync-failure-log-open"))
        assertFalse(texts().contains("C:/reports/other.txt"))
        panel.state.value = panel.state.value.copy(
            failureLog = SyncFailureLogStatus.SaveFailed("run-visual", 521),
        )
        render()
        assertFalse(hasTag("sync-failure-log-open"))
        assertTrue(texts().any { it.contains("失败日志保存失败") })
    }

    @Test
    fun `setup and other terminal states do not retain progress animation`() = rendered(
        connected().copy(
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.MERGING,
            setupBusy = true,
            run = visualRun(SyncRunPhase.CONFIRMING).copy(
                state = SyncRunState.PARTIAL,
                stopReason = "projection_pending",
            ),
            progress = SyncProgressFact(
                scope = "run-visual:stale",
                stage = SyncProgressStage.CONFIRMING,
                direction = SyncProgressDirection.DOWNLOAD,
                completedItems = 0,
                totalItems = null,
                effectiveBytes = 0,
                networkBytes = 0,
                totalBytes = null,
                elapsedSeconds = 30,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertFalse(hasTag("sync-progress"))
        assertFalse(texts().contains("正在合并数据"))
        assertEquals(0, nodes().count { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
        for (terminal in listOf(
            SyncRunState.SUCCEEDED,
            SyncRunState.FAILED,
            SyncRunState.BLOCKED,
            SyncRunState.CANCELLED,
        )) {
            panel.state.value = panel.state.value.copy(
                page = SyncPanelPage.MAIN,
                run = panel.state.value.run?.copy(state = terminal),
            )
            render()
            assertFalse(hasTag("sync-progress"), terminal.name)
            assertFalse(texts().contains("正在接收并校验数据"), terminal.name)
        }
    }

    @Test
    fun `runtime receipt details stay hidden and paused status remains actionable`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.MERGING),
            progress = SyncProgressFact(
                scope = "run-visual:download",
                stage = SyncProgressStage.CONFIRMING,
                direction = SyncProgressDirection.DOWNLOAD,
                completedItems = 0,
                totalItems = 10,
                effectiveBytes = 0,
                networkBytes = 0,
                totalBytes = null,
                elapsedSeconds = 20,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = 5,
                wholeEtaSeconds = null,
                receivedItems = 4,
                receivedTotalItems = 10,
                checkedFields = 3,
                unavailableFields = 1,
                mergingReceivedData = true,
                secondsSinceLastProgress = 10,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-stage-count"))
        assertFalse(hasTag("sync-active-body-progress"))
        assertTrue(texts().contains("正在统计数据"))
        assertFalse(texts().any { it.contains("已接收") || it.contains("已检查") || it.contains("4 / 10") })
        panel.state.value =
            panel.state.value.copy(progress = panel.state.value.progress!!.copy(secondsSinceLastProgress = 60))
        render()
        assertFalse(texts().any { it.contains("1 分钟") })
        panel.state.value = panel.state.value.copy(run = panel.state.value.run!!.copy(state = SyncRunState.PAUSED_USER))
        render()
        assertTrue(texts().any { it.contains("已暂停") })
        click("sync-resume-run")
        assertEquals(SyncPanelAction.ResumeSync, actions.last())
    }

    @Test
    fun `receiving unconfirmed batches stays statistical without claiming merge completion`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.MERGING),
            progress = SyncProgressFact(
                scope = "run-visual:download",
                stage = SyncProgressStage.CONFIRMING,
                direction = SyncProgressDirection.DOWNLOAD,
                completedItems = 0,
                totalItems = 10,
                effectiveBytes = 0,
                networkBytes = 0,
                totalBytes = null,
                elapsedSeconds = 12,
                hold = SyncProgressHold.ACTIVE,
                stageEtaSeconds = null,
                wholeEtaSeconds = null,
                receivedItems = 4,
                secondsWithoutProgress = 10,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-stage-count"))
        assertFalse(hasTag("sync-active-body-progress"))
        assertTrue(texts().contains("正在统计数据"))
        assertFalse(texts().any { it.contains("正在合并已接收的数据") || it.contains("已接收") })
        panel.state.value =
            panel.state.value.copy(progress = panel.state.value.progress!!.copy(stage = SyncProgressStage.PREPARING))
        render()
        assertTrue(texts().contains("正在统计数据"))
    }

    @Test
    fun `completed run freezes elapsed time at its durable finish`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.COMPLETE).copy(
                state = SyncRunState.SUCCEEDED,
                plannedItems = 1,
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
        assertTrue(texts().any { it.contains("用时00:30") })
        assertFalse(texts().contains("已用 01:59"))
    }

    @Test
    fun `recovered run keeps stage and explicitly checks saved progress`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.CHECKING).copy(state = SyncRunState.WAITING_SYSTEM, plannedItems = 10),
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
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-stage-count"))
        assertFalse(hasTag("sync-active-body-progress"))
        assertTrue(texts().any { it.contains(MR.strings.sync_waiting_system.localized(Locale.getDefault())) })
        assertTrue(texts().any { it.startsWith("已用 ") })
        assertFalse(texts().any { it.contains("剩余估时") })
    }

    @Test
    fun `pause request keeps progress and explains that saving is in progress`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING).copy(plannedItems = 10),
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
        awaitTag("sync-progress-card")
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-stage-count"))
        assertFalse(hasTag("sync-active-body-progress"))
        assertTrue(texts().any { it.contains("正在暂停，正在保存进度") })
        assertTrue(texts().any { it.startsWith("已用 ") })
        assertFalse(texts().any { it.contains("剩余估时") })
        assertTrue(node("sync-wait").config.contains(SemanticsProperties.Disabled))
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
        awaitTag("sync-progress-card")
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-stage-count"))
        assertFalse(hasTag("sync-active-body-progress"))
        assertTrue(texts().contains("正在统计数据"))
        assertFalse(texts().any { it.contains("正在生成上传数据") || it.contains("正在上传变动") })
    }

    @Test
    fun `work scope changes never alter the fixed running summary`() = rendered(
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
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-stage-count"))
        assertFalse(hasTag("sync-active-body-progress"))
        assertTrue(texts().contains("正在统计数据"))
        panel.state.value =
            panel.state.value.copy(
                progress = panel.state.value.progress!!.copy(scope = "run-visual:next", totalItems = 100),
            )
        render()
        assertTrue(texts().contains("正在统计数据"))
        assertFalse(texts().any { it.contains("发现新增数据") })
    }

    @Test
    fun `safe whole plan count fits the narrow Android panel`() = runBlocking {
        val state = connected().copy(
            run = visualRun(SyncRunPhase.DOWNLOADING).copy(plannedItems = 10_300, confirmedItems = 10_299),
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
            fixture.awaitTag("sync-progress-card")
            fixture.assertTextFits("sync-progress-status")
            assertTrue(fixture.texts().contains("同步中，已完成10299/10300条"))
            assertTrue(fixture.node("sync-progress-status").boundsInRoot.right <= 400f)
        } finally {
            fixture.scene.close()
        }
    }

    @Test
    fun `large text keeps the terminal result and pending status inside the Android panel`() = runBlocking {
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
            fixture.awaitTag("sync-progress-card")
            assertTrue(fixture.node("sync-progress-card").boundsInRoot.right <= 400f)
            assertFalse(fixture.hasTag("sync-stage-count"))
            assertTrue(fixture.texts().contains("本次同步已结束，部分数据待处理"))
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
                if (fixture.hasTag("sync-pause-run") &&
                    fixture.node("sync-pause-run").boundsInRoot.top >= 0f &&
                    fixture.node("sync-pause-run").boundsInRoot.bottom <= 600f
                ) {
                    break
                }
                scroll.invoke(0f, 160f)
                fixture.render()
            }
            assertTrue(fixture.hasTag("sync-pause-run"))
            assertTrue(fixture.node("sync-pause-run").boundsInRoot.top >= 0f)
            assertTrue(fixture.node("sync-pause-run").boundsInRoot.bottom <= 600f)
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
        assertFalse(hasTag("sync-queue-summary"))
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-progress-detail"))
        assertFalse(texts().any { it.contains("1804 项") || it.contains("失败 3 项") })
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
    fun `setup completion does not show a session notice`() = rendered(
        connected().copy(notice = SyncPanelNotice(setupCompleted = true)),
    ) {
        assertFalse(hasTag("sync-setup-complete"))
        assertFalse(hasTag("sync-dismiss-notice"))
    }

    @Test
    fun `setup page omits normal target status and stale exchange errors`() = rendered(
        SyncPanelState(
            visible = true,
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.NEW_PASSWORD,
            setupRepository = SyncRepository("owner", "private", "sync"),
            problem = SyncRunProblem.REMOTE_CHANGED,
        ),
    ) {
        awaitTag("sync-password-input")
        assertFalse(hasTag("sync-setup-target"))
        assertFalse(texts().contains(MR.strings.sync_problem_remote.localized(Locale.getDefault())))
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
    fun `legacy setup recovery requires confirmation and explains local-only cleanup`() = rendered(
        SyncPanelState(
            visible = true,
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.ERROR,
            setupProblem = SyncDiscoveryProblem.CREATION_UNCONFIRMED,
            legacyRecoveryAvailable = true,
        ),
    ) {
        awaitTag("sync-abandon-legacy")
        click("sync-abandon-legacy")
        assertEquals(SyncPanelAction.Ask(SyncPanelQuestion.ABANDON_LEGACY), actions.last())

        panel.state.value = panel.state.value.copy(question = SyncPanelQuestion.ABANDON_LEGACY)
        render()
        assertTrue(texts().contains(MR.strings.sync_setup_abandon_legacy_title.localized(Locale.getDefault())))
        assertTrue(texts().contains(MR.strings.sync_setup_abandon_legacy_body.localized(Locale.getDefault())))
        click("sync-confirm-question")
        assertEquals(SyncPanelAction.ConfirmQuestion, actions.last())
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
        scroll("sync-settings-list", 1)
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
            setupProblem = SyncDiscoveryProblem.AUTHORIZATION_REQUIRED,
        )
        awaitTag("sync-setup-recovery-authorization")
        click("sync-setup-recovery-authorization")
        assertEquals(SyncPanelAction.CheckAuthorization, actions.last())
        click("sync-setup-recovery-open")
        assertEquals(SyncPanelAction.OpenRecovery, actions.last())
    }

    @Test
    fun `unconfigured settings disable space actions and show unavailable protection`() = rendered(
        SyncPanelState(visible = true, page = SyncPanelPage.SETTINGS),
    ) {
        awaitTag("sync-settings-list")
        scroll("sync-settings-list", 1)
        assertTrue(hasTag("sync-password-status"))
        for ((index, tag) in listOf(3 to "sync-disconnect", 4 to "sync-switch")) {
            scroll("sync-settings-list", index)
            assertTrue(node(tag).config.contains(SemanticsProperties.Disabled), tag)
        }
        assertFalse(hasTag("sync-show-recovery"))
    }

    @Test
    fun `empty pending list omits the selection toolbar`() = rendered(connected()) {
        awaitTag("sync-history")
        assertFalse(hasTag("sync-selection-bar"))
    }

    @Test
    fun `failed authorization offers reauthorization without confusing App installation`() = rendered(
        SyncPanelState(
            visible = true,
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.ERROR,
            setupProblem = SyncDiscoveryProblem.AUTHORIZATION_REQUIRED,
        ),
    ) {
        awaitTag("sync-repo-reconnect")
        click("sync-repo-reconnect")
        assertEquals(SyncPanelAction.Authorize, actions.last())
        assertTrue(opened.isEmpty())
        assertFalse(hasTag("sync-install-app"))
        assertFalse(hasTag("sync-create-repo"))
        click("sync-setup-retry")
        assertEquals(SyncPanelAction.RetrySetup, actions.last())
    }

    @Test
    fun `repository guidance encodes owner and links to the verified installation scope`() = rendered(
        SyncPanelState(
            visible = true,
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.ERROR,
            setupProblem = SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS,
            setupAccountLogin = "owner&other=a/b",
            setupInstallation = SyncAppInstallation(
                7,
                SyncRepositorySelection.SELECTED,
                authorizedRepositoryCount = 2,
            ),
        ),
    ) {
        awaitTag("sync-install-app")
        assertTrue(hasTag("sync-installation-scope-warning"))
        assertTrue(texts().any { it == MR.strings.sync_setup_manage_installation.localized(Locale.getDefault()) })
        click("sync-create-private-repo")
        assertEquals(
            "https://github.com/new?name=mihon-sync&visibility=private&owner=owner%26other%3Da%2Fb",
            opened.last(),
        )
        click("sync-install-app")
        assertEquals("https://github.com/settings/installations/7", opened.last())
        assertFalse(opened.last().contains("/settings/installations?"))

        panel.state.value = panel.state.value.copy(
            setupInstallation = SyncAppInstallation(
                9,
                SyncRepositorySelection.ALL,
                authorizedRepositoryCount = 1,
            ),
        )
        render()
        assertTrue(hasTag("sync-installation-scope-warning"))
        assertTrue(texts().any { it == MR.strings.sync_setup_scope_all.localized(Locale.getDefault()) })

        panel.state.value = panel.state.value.copy(
            setupInstallation = SyncAppInstallation(
                11,
                SyncRepositorySelection.SELECTED,
                authorizedRepositoryCount = 1,
                accountType = SyncInstallationAccountType.ORGANIZATION,
            ),
        )
        render()
        click("sync-install-app")
        assertEquals(
            "https://github.com/organizations/owner%26other%3Da%2Fb/settings/installations/11",
            opened.last(),
        )
        assertFalse(texts().any { it == MR.strings.sync_setup_scope_multiple.localized(Locale.getDefault()) })

        panel.state.value = panel.state.value.copy(
            setupProblem = SyncDiscoveryProblem.REPOSITORY_NOT_WRITABLE,
            setupInstallation = SyncAppInstallation(13, SyncRepositorySelection.SELECTED, 1),
        )
        render()
        awaitTag("sync-install-app")
        click("sync-install-app")
        assertEquals("https://github.com/settings/installations/13", opened.last())
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
    fun `R01 R03 disconnected terminal results retain an executable connection route`() = rendered(
        connected().copy(
            connection = connected().connection!!.copy(enabled = false),
            run = visualRun(SyncRunPhase.COMPLETE).copy(state = SyncRunState.CANCELLED, confirmedItems = 1536),
        ),
    ) {
        for (terminal in listOf(
            SyncRunState.CANCELLED,
            SyncRunState.SUCCEEDED,
            SyncRunState.FAILED,
            SyncRunState.PARTIAL,
            SyncRunState.BLOCKED,
        )) {
            panel.state.value = panel.state.value.copy(run = panel.state.value.run!!.copy(state = terminal))
            render()
            awaitTag("sync-now")
            assertFalse(node("sync-now").config.contains(SemanticsProperties.Disabled), terminal.name)
            assertTrue(texts().contains("连接同步空间"), terminal.name)
            click("sync-now")
            assertEquals(SyncPanelAction.BeginSetup, actions.last(), terminal.name)
        }
        panel.state.value = panel.state.value.copy(connection = null)
        render()
        click("sync-now")
        assertEquals(SyncPanelAction.BeginSetup, actions.last())
        assertFalse(actions.contains(SyncPanelAction.Synchronize))
        assertFalse(actions.contains(SyncPanelAction.RetrySync))
    }

    @Test
    fun `R04 unsupported connection with cancelled history opens the guarded reason`() = rendered(
        connected().copy(
            connection = connected().connection!!.copy(enabled = false, unsupportedFormat = true),
            run = visualRun(SyncRunPhase.COMPLETE).copy(state = SyncRunState.CANCELLED),
            setupProblem = SyncDiscoveryProblem.INCOMPATIBLE,
            problem = SyncRunProblem.INVALID_DATA,
        ),
    ) {
        awaitTag("sync-view-reason")
        click("sync-view-reason")
        assertEquals(SyncPanelAction.BeginSetup, actions.last())
        assertFalse(actions.contains(SyncPanelAction.Synchronize))
        assertFalse(actions.contains(SyncPanelAction.Authorize))
    }

    @Test
    fun `R05 connection changes do not replace the owned pause retry or recovery operation`() = rendered(
        connected().copy(
            connection = connected().connection!!.copy(enabled = false),
            run = visualRun(SyncRunPhase.CHECKING).copy(state = SyncRunState.PAUSED_USER),
        ),
    ) {
        awaitTag("sync-resume-run")
        assertFalse(hasTag("sync-now"))
        assertTrue(node("sync-resume-run").config.contains(SemanticsProperties.Disabled))
        for (owned in listOf(SyncRunState.WAITING_RETRY, SyncRunState.RUNNING)) {
            panel.state.value = panel.state.value.copy(run = panel.state.value.run!!.copy(state = owned))
            render()
            assertFalse(hasTag("sync-now"))
            assertFalse(hasTag("sync-retry-run"))
        }
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `R06 historical cancelled result freezes elapsed and has no active eta`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.COMPLETE).copy(
                state = SyncRunState.CANCELLED,
                confirmedItems = 1536,
                plannedItems = 2048,
                updatedAt = 17_515_000,
            ),
            nowMillis = 20_000_000,
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().contains("上次同步已取消"))
        assertFalse(texts().contains("已确认结果会保留"))
        assertTrue(texts().any { it.contains("用时291:54") })
        assertFalse(hasTag("sync-whole-eta-value"))
        assertFalse(hasTag("sync-progress"))
        panel.state.value = panel.state.value.copy(nowMillis = 20_060_000)
        displayMillis = 60_000
        render()
        assertTrue(texts().any { it.contains("用时291:54") })
        assertFalse(texts().contains("已用 291:54"))
    }

    @Test
    fun `unknown connection waits for checking and a failed check has an explicit retry`() = rendered(
        connected().copy(loaded = false, connection = null),
    ) {
        awaitTag("sync-now")
        assertTrue(node("sync-now").config.contains(SemanticsProperties.Disabled))
        assertTrue(texts().contains("正在检查连接"))
        panel.state.value = panel.state.value.copy(problem = SyncRunProblem.STORAGE)
        render()
        assertFalse(node("sync-now").config.contains(SemanticsProperties.Disabled))
        assertTrue(texts().contains("重试检查连接"))
        click("sync-now")
        assertEquals(SyncPanelAction.Open, actions.last())
        assertFalse(actions.contains(SyncPanelAction.BeginSetup))
    }

    @Test
    fun `initial import running card exposes only whole run pause and resume`() = rendered(
        connected().copy(
            importRemaining = 9,
            busy = true,
            run = visualRun(SyncRunPhase.IMPORTING),
            progress = compactFact(),
        ),
    ) {
        awaitTag("sync-pause-run")
        assertFalse(hasTag("sync-pause-import"))
        assertFalse(hasTag("sync-resume-import"))
        click("sync-pause-run")
        assertEquals(SyncPanelAction.PauseSync, actions.last())
        panel.state.value = panel.state.value.copy(
            busy = false,
            run = visualRun(SyncRunPhase.IMPORTING).copy(state = SyncRunState.PAUSED_USER),
            importPaused = true,
        )
        render()
        assertFalse(hasTag("sync-pause-import"))
        assertFalse(hasTag("sync-resume-import"))
        click("sync-resume-run")
        assertEquals(SyncPanelAction.ResumeSync, actions.last())
    }

    @Test
    fun `baseline pause and resume remain available only in expanded idle details`() = rendered(
        connected().copy(importRemaining = 9),
    ) {
        awaitTag("sync-progress-details-toggle")
        assertFalse(hasTag("sync-pause-import"))
        click("sync-progress-details-toggle")
        render()
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
        awaitTag("sync-progress-card")
        assertFalse(hasTag("sync-notice-counts"))
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
        assertTrue(texts().any { it.contains("已完成0条") })
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
    fun `compact run hides phases and logs while pause and resume remain actionable`() = rendered(
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
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-stage-count"))
        assertFalse(hasTag("sync-active-body-progress"))
        assertFalse(hasTag("sync-log-item-1"))
        click("sync-pause-run")
        assertEquals(SyncPanelAction.PauseSync, actions.last())
        panel.state.value = panel.state.value.copy(run = panel.state.value.run!!.copy(state = SyncRunState.PAUSED_USER))
        render()
        assertFalse(hasTag("sync-now"))
        click("sync-resume-run")
        assertEquals(SyncPanelAction.ResumeSync, actions.last())
    }

    @Test
    fun `setup merging shares the counting summary and hides item logs`() = rendered(
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
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-stage-count"))
        assertFalse(hasTag("sync-active-body-progress"))
        assertTrue(texts().contains("正在统计数据"))
        assertFalse(hasTag("sync-log-item-1"))
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
    fun `retry waiting retains truthful status and disables the main operation`() = rendered(
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
        awaitTag("sync-progress-card")
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-stage-count"))
        assertFalse(hasTag("sync-active-body-progress"))
        assertTrue(texts().any { it.contains(MR.strings.sync_waiting_retry.localized(Locale.getDefault())) })
        assertFalse(hasTag("sync-retry-countdown"))
        assertTrue(node("sync-wait").config.contains(SemanticsProperties.Disabled))
    }

    @Test
    fun `rate limit waiting keeps truthful status without runtime countdown details`() = rendered(
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
        awaitTag("sync-progress-card")
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-stage-count"))
        assertFalse(hasTag("sync-active-body-progress"))
        assertTrue(texts().any { it.contains(MR.strings.sync_waiting_rate_limit.localized(Locale.getDefault())) })
        assertFalse(hasTag("sync-retry-countdown"))
        assertTrue(node("sync-wait").config.contains(SemanticsProperties.Disabled))
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
        scroll("sync-settings-list", 1)
        assertTrue(hasTag("sync-password-status"))
        assertFalse(hasTag("sync-show-recovery"))
        scroll("sync-settings-list", 3)
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
            setupBusy = true,
            deviceCode = GitHubDeviceCode("secret-device", "ABCD-EFGH", "https://github.com/login/device", 900, 5),
        ),
    ) {
        awaitTag("sync-copy-open")
        assertTrue(texts().contains("等待你在浏览器完成授权…"))
        assertEquals(listOf("ABCD-EFGH"), copied)
        assertEquals(listOf("https://github.com/login/device"), opened)
        render()
        assertEquals(1, opened.size)
        click("sync-copy-open")
        assertEquals(listOf("ABCD-EFGH", "ABCD-EFGH"), copied)
        assertEquals(listOf("https://github.com/login/device", "https://github.com/login/device"), opened)
        assertFalse(texts().any { it.contains("secret-device") })
        click("sync-cancel-auth")
        assertEquals(SyncPanelAction.CancelAuthorization, actions.last())
    }

    @Test
    fun `browser opens when delayed device code replaces waiting state`() = rendered(
        SyncPanelState(visible = true, page = SyncPanelPage.SETUP, setupBusy = true),
    ) {
        render()
        assertTrue(opened.isEmpty())
        panel.state.value = panel.state.value.copy(
            deviceCode = GitHubDeviceCode("secret-device", "ABCD-EFGH", "https://github.com/login/device", 900, 5),
        )
        awaitTag("sync-copy-open")
        assertEquals(listOf("ABCD-EFGH"), copied)
        assertEquals(listOf("https://github.com/login/device"), opened)
        render()
        assertEquals(1, opened.size)
    }

    @Test
    fun `failed GitHub connection replaces the code spinner with retry guidance`() = rendered(
        SyncPanelState(
            visible = true,
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.SIGN_IN,
            setupBusy = false,
            authFailure = GitHubAuthFailureReason.HTTP,
        ),
    ) {
        awaitTag("sync-authorize")
        assertTrue(texts().contains("无法完成 GitHub 授权请求，请检查网络或代理后重试。"))
        assertTrue(opened.isEmpty())
        click("sync-authorize")
        assertEquals(SyncPanelAction.Authorize, actions.last())
    }

    @Test
    fun `device code request names its work then offers network guidance after five seconds`() = rendered(
        SyncPanelState(
            visible = true,
            page = SyncPanelPage.SETUP,
            setupStep = SyncSetupStep.SIGN_IN,
            setupBusy = true,
            authRequestStartedAtMillis = 1_000,
            nowMillis = 1_000,
        ),
    ) {
        awaitTag("sync-authorize")
        assertTrue(texts().contains("正在获取 GitHub 验证码…"))
        assertFalse(texts().contains("如果持续无法获取，请检查您的网络是否能够访问 GitHub。"))
        panel.state.value = panel.state.value.copy(nowMillis = 5_999)
        render()
        assertFalse(texts().contains("如果持续无法获取，请检查您的网络是否能够访问 GitHub。"))
        panel.state.value = panel.state.value.copy(nowMillis = 6_000)
        render()
        assertTrue(texts().contains("如果持续无法获取，请检查您的网络是否能够访问 GitHub。"))
        panel.state.value = panel.state.value.copy(
            deviceCode = GitHubDeviceCode("secret-device", "ABCD-EFGH", "https://github.com/login/device", 900, 5),
        )
        render()
        assertFalse(texts().contains("正在获取 GitHub 验证码…"))
        assertFalse(texts().contains("如果持续无法获取，请检查您的网络是否能够访问 GitHub。"))
        panel.state.value = panel.state.value.copy(
            deviceCode = null,
            setupBusy = false,
            authFailure = GitHubAuthFailureReason.HTTP,
        )
        render()
        assertFalse(texts().contains("正在获取 GitHub 验证码…"))
        panel.state.value = panel.state.value.copy(
            setupBusy = true,
            authFailure = null,
            authRequestStartedAtMillis = 10_000,
            nowMillis = 10_000,
        )
        render()
        assertTrue(texts().contains("正在获取 GitHub 验证码…"))
        assertFalse(texts().contains("如果持续无法获取，请检查您的网络是否能够访问 GitHub。"))
    }

    @Test
    fun `remounting setup never reopens the same code but a new code opens`() = rendered(
        SyncPanelState(
            visible = true,
            page = SyncPanelPage.SETUP,
            deviceCode = GitHubDeviceCode("first-secret", "FIRST-CODE", "https://github.com/login/device", 900, 5),
        ),
    ) {
        awaitTag("sync-copy-open")
        assertEquals(1, opened.size)
        scene.setContent {}
        render()
        setContent()
        awaitTag("sync-copy-open")
        assertEquals(1, opened.size)
        panel.state.value = panel.state.value.copy(
            deviceCode = GitHubDeviceCode("next-secret", "NEXT-CODE", "https://github.com/login/device", 900, 5),
        )
        render()
        assertEquals(listOf("FIRST-CODE", "NEXT-CODE"), copied)
        assertEquals(2, opened.size)
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

    @Test
    fun `running summary omits details and exposes one fixed plan track`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING).copy(plannedItems = 10, confirmedItems = 2),
            progress = SyncProgressFact(
                "run-visual:1", SyncProgressStage.TRANSFERRING,
                SyncProgressDirection.UPLOAD, 6, 10, 64, 64, 100, 12,
                SyncProgressHold.ACTIVE, 5, null, confirmedThisRun = 2,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-stage-count"))
        assertFalse(hasTag("sync-active-body-progress"))
        assertEquals(1, nodes().count { tag(it) == "sync-progress-track" })
    }

    @Test
    fun `D09 fixed summary geometry and status live region survive progress changes`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING).copy(plannedItems = 1000, confirmedItems = 999),
            progress = SyncProgressFact(
                "run-visual:a", SyncProgressStage.TRANSFERRING,
                SyncProgressDirection.UPLOAD, 999, 1000, 64, 64, 100, 12,
                SyncProgressHold.ACTIVE, 5, 20, confirmedThisRun = 999, secondsWithoutProgress = 0,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        val bottom = geometry("sync-progress-card").bottom
        val button = geometry("sync-pause-run")
        val live = nodes().single { it.config.contains(SemanticsProperties.LiveRegion) }
        assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Polite, live.config[SemanticsProperties.LiveRegion])
        assertFalse(live.config.contains(SemanticsProperties.Text), "Numeric changes must not be announced")
        assertEquals("同步中", live.config[SemanticsProperties.StateDescription])
        for ((time, progress) in listOf(
            800L to panel.state.value.progress!!.copy(completedItems = 1000, confirmedThisRun = 1000),
            2000L to panel.state.value.progress!!.copy(wholeEtaSeconds = 18),
            2001L to panel.state.value.progress!!.copy(stage = SyncProgressStage.CONFIRMING, wholeEtaSeconds = null),
            2801L to
                panel.state.value.progress!!.copy(
                    direction = SyncProgressDirection.DOWNLOAD,
                    mergingReceivedData = true,
                ),
        )) {
            displayMillis = time
            panel.state.value = panel.state.value.copy(progress = progress)
            render()
            assertEquals(bottom, geometry("sync-progress-card").bottom, 1f)
            assertEquals(button, geometry("sync-pause-run"))
            assertFalse(hasTag("sync-active-body-progress"))
            assertTrue(texts().contains("同步中，已完成999/1000条"))
        }
    }

    @Test
    fun `D06 same session preserves details across pages and new run or close resets`() = rendered(
        connected().copy(run = visualRun(SyncRunPhase.UPLOADING).copy(state = SyncRunState.FAILED)),
    ) {
        awaitTag("sync-progress-card")
        click("sync-progress-details-toggle")
        render()
        assertTrue(hasTag("sync-progress-details"))
        panel.state.value = panel.state.value.copy(run = panel.state.value.run!!.copy(state = SyncRunState.RUNNING))
        render()
        assertFalse(hasTag("sync-progress-details"))
        assertFalse(hasTag("sync-progress-details-toggle"))
        panel.state.value = panel.state.value.copy(run = panel.state.value.run!!.copy(state = SyncRunState.FAILED))
        render()
        assertTrue(hasTag("sync-progress-details"))
        panel.state.value = panel.state.value.copy(page = SyncPanelPage.SETTINGS)
        render()
        panel.state.value = panel.state.value.copy(page = SyncPanelPage.MAIN)
        render()
        assertTrue(hasTag("sync-progress-details"))
        panel.state.value = panel.state.value.copy(run = panel.state.value.run!!.copy(runId = "next"))
        render()
        assertFalse(hasTag("sync-progress-details"))
        click("sync-progress-details-toggle")
        render()
        panel.state.value = panel.state.value.copy(visible = false)
        render()
        panel.state.value = panel.state.value.copy(visible = true)
        render()
        assertFalse(hasTag("sync-progress-details"))
        assertTrue(actions.none { it == SyncPanelAction.PauseSync || it == SyncPanelAction.RetrySync })
    }

    @Test
    fun `persisted terminal problems remain readable without an empty notice`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING).copy(state = SyncRunState.FAILED),
            problem = SyncRunProblem.NETWORK,
            notice = SyncPanelNotice(
                exchange = mihon.domain.sync.runtime.SyncRunResult(
                    SyncRunStatus.FAILED,
                    problem = SyncRunProblem.NETWORK,
                ),
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertTrue(texts().contains(MR.strings.sync_problem_network.localized(Locale.getDefault())))
        assertFalse(hasTag("sync-dismiss-notice"))
        click("sync-progress-details-toggle")
        render()
        assertTrue(hasTag("sync-full-reason"))
        panel.state.value = panel.state.value.copy(run = panel.state.value.run!!.copy(state = SyncRunState.PARTIAL))
        render()
        assertTrue(hasTag("sync-full-reason"))
    }

    @Test
    fun `incompatible terminal storage cannot enter password repair`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.CHECKING).copy(state = SyncRunState.BLOCKED),
            problem = SyncRunProblem.STORAGE,
            setupProblem = SyncDiscoveryProblem.INCOMPATIBLE,
        ),
    ) {
        awaitTag("sync-progress-card")
        assertFalse(hasTag("sync-reenter-password"))
        click("sync-view-reason")
        render()
        assertTrue(hasTag("sync-full-reason"))
        assertTrue(actions.none { it == SyncPanelAction.BeginSetup })
    }

    @Test
    fun `known zero received and checked counts remain distinct from unknown`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.DOWNLOADING).copy(plannedItems = 10),
            progress = SyncProgressFact(
                "run-visual:a", SyncProgressStage.CONFIRMING, SyncProgressDirection.DOWNLOAD,
                0, null, 0, 0, null, 0, SyncProgressHold.ACTIVE, null, null,
                receivedItems = 0, receivedTotalItems = 0, checkedFields = 0,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        assertFalse(hasTag("sync-progress-details-toggle"))
        assertFalse(hasTag("sync-received-count"))
        assertFalse(hasTag("sync-checked-count"))
        assertTrue(texts().contains("同步中，已完成0/10条"))
        assertEquals(0L, panel.state.value.progress!!.receivedItems)
        panel.state.value = panel.state.value.copy(progress = panel.state.value.progress!!.copy(receivedItems = null))
        render()
        assertNull(panel.state.value.progress!!.receivedItems)
        assertTrue(texts().contains("同步中，已完成0/10条"))
        assertEquals(0f, node("sync-progress-track").config[SemanticsProperties.ProgressBarRangeInfo].current)
    }

    @Test
    fun `D01 deadlines advance elapsed and invalidate stale whole ETA without another fact`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING).copy(plannedItems = 100),
            progress = SyncProgressFact(
                "run-visual:a", SyncProgressStage.TRANSFERRING,
                SyncProgressDirection.UPLOAD, 0, 10, 0, 0, 100, 0, SyncProgressHold.ACTIVE, 12, 20,
                secondsWithoutProgress = 0,
            ),
        ),
    ) {
        awaitTag("sync-round-time")
        assertFalse(texts().any { it.contains("剩余估时") })
        val initial = texts().single { it.startsWith("已用 ") }
        displayMillis = 1000
        withTimeout(3000) {
            while (texts().single { it.startsWith("已用 ") } == initial) {
                render()
                delay(10)
            }
        }
        assertTrue(texts().any { it.contains("已用 00:01") })
        for (increment in 1..3) {
            displayMillis = increment * 1000L
            panel.state.value =
                panel.state.value.copy(run = panel.state.value.run!!.copy(confirmedItems = increment * 10L))
            render()
        }
        assertTrue(texts().any { it.contains("剩余估时00:07") })
        displayMillis = 13000
        withTimeout(3000) {
            while (texts().any { it.contains("剩余估时") }) {
                render()
                delay(10)
            }
        }
        assertTrue(texts().any { it.startsWith("已用 ") })
        panel.state.value = panel.state.value.copy(visible = false)
        render()
        val calls = clockCalls
        displayMillis = 60000
        delay(1100)
        render()
        assertEquals(calls, clockCalls)
        panel.state.value =
            panel.state.value.copy(
                visible = true,
                run = panel.state.value.run!!.copy(state = SyncRunState.SUCCEEDED, confirmedItems = 7),
            )
        render()
        assertTrue(texts().contains("同步已完成"))
        assertFalse(hasTag("sync-round-time"))
    }

    @Test
    fun `D10 hidden panel cancels visual wakes and reopen projects current terminal`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING),
            progress = SyncProgressFact(
                "run-visual:a", SyncProgressStage.TRANSFERRING,
                SyncProgressDirection.UPLOAD, 0, 10, 0, 0, 100, 0, SyncProgressHold.ACTIVE, null, null,
            ),
        ),
    ) {
        awaitTag("sync-progress-card")
        panel.state.value = panel.state.value.copy(visible = false)
        render()
        val calls = clockCalls
        repeat(20) {
            panel.state.value =
                panel.state.value.copy(progress = panel.state.value.progress!!.copy(completedItems = it.toLong()))
            render()
        }
        assertEquals(calls, clockCalls)
        assertFalse(hasTag("sync-progress-card"))
        panel.state.value =
            panel.state.value.copy(
                visible = true,
                run = panel.state.value.run!!.copy(state = SyncRunState.SUCCEEDED, confirmedItems = 7),
            )
        render()
        assertTrue(texts().contains("同步已完成"))
        assertTrue(texts().any { it.contains("已完成7条") })
        assertFalse(hasTag("sync-progress-details"))
    }

    @Test
    fun `D09 explicit wait changes remain readable and preserve disabled operation height`() = rendered(
        connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING),
            progress = SyncProgressFact(
                "run-visual:a", SyncProgressStage.TRANSFERRING, SyncProgressDirection.UPLOAD,
                0, 10, 0, 0, 100, 0, SyncProgressHold.ACTIVE, null, null,
            ),
        ),
    ) {
        awaitTag("sync-pause-run")
        val original = node("sync-pause-run").boundsInRoot
        panel.state.value =
            panel.state.value.copy(run = panel.state.value.run!!.copy(state = SyncRunState.WAITING_RETRY))
        render()
        assertEquals(original.height, geometry("sync-wait").height, 1f)
        assertTrue(node("sync-wait").config.contains(SemanticsProperties.Disabled))
        assertTextFits("sync-progress-status")
        panel.state.value = panel.state.value.copy(
            run = panel.state.value.run!!.copy(state = SyncRunState.RUNNING),
            progress = panel.state.value.progress!!.copy(hold = SyncProgressHold.PAUSING),
        )
        render()
        assertEquals(original.height, geometry("sync-wait").height, 1f)
        assertTrue(node("sync-wait").config.contains(SemanticsProperties.Disabled))
        assertTextFits("sync-progress-status")
    }

    @Test
    fun `P13 terminal detail focus survives updates and collapse returns focus to trigger`() = rendered(
        connected().copy(run = visualRun(SyncRunPhase.UPLOADING).copy(state = SyncRunState.FAILED), logsHasMore = true),
    ) {
        awaitTag("sync-progress-card")
        click("sync-progress-details-toggle")
        render()
        requireNotNull(node("sync-log-more").config[SemanticsActions.RequestFocus].action).invoke()
        render()
        assertTrue(node("sync-log-more").config[SemanticsProperties.Focused])
        panel.state.value = panel.state.value.copy(nowMillis = 9999)
        render()
        assertTrue(node("sync-log-more").config[SemanticsProperties.Focused])
        click("sync-progress-details-toggle")
        render()
        assertTrue(node("sync-progress-details-toggle").config[SemanticsProperties.Focused])
    }

    @Test
    fun `P13 running operation retains keyboard focus across safe confirmation updates`() = rendered(
        connected().copy(run = visualRun(SyncRunPhase.UPLOADING).copy(plannedItems = 1280)),
    ) {
        awaitTag("sync-pause-run")
        requireNotNull(node("sync-pause-run").config[SemanticsActions.RequestFocus].action).invoke()
        render()
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod("access\$getKeyDown\$cp").invoke(null)
        val factory = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").declaredMethods
            .single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
        for (count in listOf(999L, 1000L, 1280L)) {
            panel.state.value = panel.state.value.copy(run = panel.state.value.run!!.copy(confirmedItems = count))
            render()
            assertTrue(node("sync-pause-run").config[SemanticsProperties.Focused])
        }
        val native = factory.invoke(
            null, androidx.compose.ui.input.key.Key.Enter.keyCode, eventType, 0, false, false, false, false, null,
        )
        scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(native))
        val eventUp = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod("access\$getKeyUp\$cp").invoke(null)
        scene.sendKeyEvent(
            androidx.compose.ui.input.key.KeyEvent(
                factory.invoke(
                    null, androidx.compose.ui.input.key.Key.Enter.keyCode, eventUp, 0, false, false, false, false, null,
                ),
            ),
        )
        render()
        assertTrue(actions.contains(SyncPanelAction.PauseSync))
    }

    @Test
    fun `P13 reduced motion retains a static track and readable running state`() = runBlocking {
        val motion = object : androidx.compose.ui.MotionDurationScale {
            override val scaleFactor = 0f
        }
        val state = connected().copy(
            run = visualRun(SyncRunPhase.UPLOADING).copy(plannedItems = 10),
            progress = SyncProgressFact(
                "run-visual:a", SyncProgressStage.TRANSFERRING, SyncProgressDirection.UPLOAD,
                0, 10, 0, 0, null, 0, SyncProgressHold.ACTIVE, null, null,
            ),
        )
        val fixture = Fixture(state, ImageComposeScene(320, 680, coroutineContext = coroutineContext + motion) {})
        try {
            fixture.setContent()
            fixture.awaitTag("sync-progress-track")
            assertNotEquals(
                androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate,
                fixture.node("sync-progress-track").config[SemanticsProperties.ProgressBarRangeInfo],
            )
            assertTrue(fixture.texts().contains("同步中，已完成0/10条"))
        } finally {
            fixture.scene.close()
        }
    }

    @Test
    fun `compact native matrix keeps safe counts ETA and operation geometry readable`() = runBlocking {
        val originalLocale = Locale.getDefault()
        val directory = System.getProperty("mihon.sync.visualDir")?.let(::File)
        val configurations = listOf(
            Triple(320, 1f, "zh-light"),
            Triple(320, 2f, "zh-dark"),
            Triple(320, 1f, "en-dark"),
            Triple(320, 2f, "en-light"),
            Triple(400, 1f, "zh-light"),
            Triple(560, 1f, "zh-dark"),
            Triple(400, 1f, "en-dark"),
            Triple(560, 1f, "en-light"),
        )
        try {
            for ((width, scale, languageTheme) in configurations) {
                Locale.setDefault(if (languageTheme.startsWith("zh")) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH)
                val state = connected().copy(
                    run = visualRun(SyncRunPhase.UPLOADING).copy(plannedItems = 1280),
                    nowMillis = 1000,
                    progress = compactFact(),
                )
                val fixture = Fixture(
                    state,
                    ImageComposeScene(width, 680, coroutineContext = coroutineContext) {},
                    scale,
                    dark = languageTheme.endsWith("dark"),
                )
                try {
                    fixture.setContent()
                    fixture.awaitTag("sync-progress-card")
                    fun readable(tag: String) {
                        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                        requireNotNull(fixture.node(tag).config[SemanticsActions.GetTextLayoutResult].action)
                            .invoke(results)
                        assertTrue(results.isNotEmpty())
                        assertTrue(results.none { it.hasVisualOverflow }, "$languageTheme/$width/$scale $tag $results")
                    }
                    val originalButton = fixture.geometry("sync-pause-run")
                    val originalBottom = fixture.geometry("sync-progress-card").bottom
                    for ((time, count) in listOf(1000L to 999L, 2000L to 1000L, 3000L to 1280L)) {
                        fixture.displayMillis = time
                        fixture.panel.state.value = fixture.panel.state.value.copy(
                            run = state.run!!.copy(confirmedItems = count),
                        )
                        fixture.render()
                        assertEquals(originalButton.top, fixture.geometry("sync-pause-run").top, 1f)
                        assertEquals(originalBottom, fixture.geometry("sync-progress-card").bottom, 1f)
                        readable("sync-progress-status")
                        readable("sync-round-time")
                        assertTrue(fixture.texts().any { it.contains("$count/1280") })
                    }
                    val statusLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    requireNotNull(
                        fixture.node("sync-progress-status").config[SemanticsActions.GetTextLayoutResult].action,
                    )
                        .invoke(statusLayouts)
                    val statusRegion = fixture.nodes().single { it.config.contains(SemanticsProperties.LiveRegion) }
                    assertEquals(statusLayouts.single().size.height.toFloat(), statusRegion.size.height.toFloat(), 1f)
                    assertTrue(fixture.texts().any { it.contains("00:00") })
                    assertEquals(
                        1f,
                        fixture.node("sync-progress-track").config[SemanticsProperties.ProgressBarRangeInfo].current,
                    )
                    assertTrue(fixture.node("sync-close").boundsInRoot.right <= width)
                    assertTrue(fixture.node("sync-settings").boundsInRoot.right <= width)
                    assertFalse(fixture.hasTag("sync-progress-details-toggle"))
                    if (directory != null) {
                        directory.mkdirs()
                        fixture.scene.render().use { image ->
                            requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use {
                                File(directory, "compact-$width-$scale-$languageTheme.png").writeBytes(it.bytes)
                            }
                        }
                    }
                    if (directory != null && width == 320 && scale > 1.4f) {
                        fixture.reveal("sync-pause-run", 680)
                        fixture.scene.render().use { image ->
                            requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use {
                                File(directory, "compact-$width-$scale-$languageTheme-end.png").writeBytes(it.bytes)
                            }
                        }
                    }
                    // A new run can reflow, including the largest valid persistent count.
                    fixture.panel.state.value = state.copy(
                        run = state.run!!.copy(
                            runId = "large",
                            plannedItems = Long.MAX_VALUE,
                            confirmedItems = Long.MAX_VALUE,
                        ),
                        progress = null,
                    )
                    fixture.render()
                    readable("sync-progress-status")
                    readable("sync-round-time")
                    assertTrue(fixture.texts().any { it.contains("${Long.MAX_VALUE}/${Long.MAX_VALUE}") })
                    fixture.reveal("sync-pause-run", 680)
                } finally {
                    fixture.scene.close()
                }
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `R08 terminal recovery labels fit a stable native operation slot at 200 percent`() = runBlocking {
        val previous = Locale.getDefault()
        val directory = System.getProperty("mihon.sync.visualDir")?.let(::File)
        try {
            for (width in listOf(320, 560)) {
                for (languageTheme in listOf("zh-light", "zh-dark", "en-light", "en-dark")) {
                    Locale.setDefault(if (languageTheme.startsWith("zh")) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH)
                    val state = connected().copy(
                        connection = connected().connection!!.copy(enabled = false),
                        run = visualRun(SyncRunPhase.COMPLETE).copy(
                            state = SyncRunState.CANCELLED,
                            confirmedItems = 1536,
                            updatedAt = 17_515_000,
                        ),
                        nowMillis = 20_000_000,
                    )
                    val fixture = Fixture(
                        state,
                        ImageComposeScene(width, 900, coroutineContext = coroutineContext) {},
                        fontScale = 2f,
                        dark = languageTheme.endsWith("dark"),
                    )
                    try {
                        fixture.setContent()
                        fixture.awaitTag("sync-now")
                        val original = fixture.geometry("sync-now")
                        assertTrue(original.height >= 48f)
                        assertTrue(original.right <= width)
                        fun readable(tag: String) {
                            fun descendants(node: SemanticsNode): List<SemanticsNode> =
                                listOf(node) + node.children.flatMap(::descendants)
                            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                            descendants(fixture.node(tag)).forEach { node ->
                                if (node.config.contains(SemanticsActions.GetTextLayoutResult)) {
                                    requireNotNull(
                                        node.config[SemanticsActions.GetTextLayoutResult].action,
                                    ).invoke(layouts)
                                }
                            }
                            assertTrue(layouts.isNotEmpty())
                            assertTrue(
                                layouts.none { it.hasVisualOverflow },
                                "$width $languageTheme $tag " +
                                    layouts.map {
                                        "${it.layoutInput.text} ${it.size} overflow=${it.hasVisualOverflow}"
                                    },
                            )
                        }
                        readable("sync-now")
                        if (directory != null && width == 320 && languageTheme in listOf("zh-light", "en-dark")) {
                            directory.mkdirs()
                            fixture.scene.render().use { image ->
                                requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use {
                                    File(
                                        directory,
                                        "terminal-connect-$width-2.0-$languageTheme.png",
                                    ).writeBytes(it.bytes)
                                }
                            }
                        }
                        fixture.panel.state.value = state.copy(
                            connection = state.connection!!.copy(unsupportedFormat = true),
                            setupProblem = SyncDiscoveryProblem.INCOMPATIBLE,
                        )
                        fixture.render()
                        fixture.awaitTag("sync-view-reason")
                        readable("sync-view-reason")
                        assertTrue(fixture.geometry("sync-view-reason").height >= 48f)
                        assertTrue(fixture.geometry("sync-view-reason").right <= width)
                        if (directory != null && languageTheme in listOf("zh-light", "en-dark")) {
                            directory.mkdirs()
                            fixture.scene.render().use { image ->
                                requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use {
                                    File(
                                        directory,
                                        "terminal-recovery-$width-2.0-$languageTheme.png",
                                    ).writeBytes(it.bytes)
                                }
                            }
                        }
                    } finally {
                        fixture.scene.close()
                    }
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun compactFact() = SyncProgressFact(
        "run-visual:upload", SyncProgressStage.TRANSFERRING, SyncProgressDirection.UPLOAD,
        5, 10, 80, 80, 100, 10, SyncProgressHold.ACTIVE, 5, 20,
        confirmedThisRun = 999, secondsWithoutProgress = 0,
    )

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

    @Test
    fun `stale unavailable recovery cannot offer replacement after local read failure`() = renderedEnglish(
        connected().copy(
            recovery = SyncSpaceRecovery(SyncSpaceRecoveryReason.SPACE_UNAVAILABLE),
            canChangeSpace = false,
            problem = SyncRunProblem.STORAGE,
        ),
    ) {
        awaitTag("sync-recovery-card")
        assertFalse(hasTag("sync-recovery-open"))
        assertFalse(hasTag("sync-recovery-create"))
        awaitTag("sync-recovery-details")
    }

    @Test
    fun `installation guide keeps diagnostics after setup actions without restarting configuration`() = runBlocking {
        for (problem in listOf(SyncDiscoveryProblem.NEEDS_INSTALLATION, SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS)) {
            val fixture = Fixture(
                SyncPanelState(
                    visible = true,
                    loaded = true,
                    page = SyncPanelPage.SETUP,
                    setupStep = SyncSetupStep.ERROR,
                    setupProblem = problem,
                    setupAccountLogin = "owner",
                    setupInstallation = if (problem == SyncDiscoveryProblem.NEEDS_INSTALLATION) {
                        null
                    } else {
                        SyncAppInstallation(7, SyncRepositorySelection.SELECTED, 1)
                    },
                ),
                ImageComposeScene(700, 1600, coroutineContext = coroutineContext) {},
            )
            try {
                fixture.setContent()
                fixture.awaitTag("sync-recheck-installation")
                assertFalse(fixture.hasTag("sync-setup-configure-space"))
                fixture.awaitTag("sync-setup-error-details")
                assertTrue(
                    fixture.node("sync-setup-error-details").boundsInRoot.top >
                        fixture.node("sync-recheck-installation").boundsInRoot.bottom,
                )
                assertFalse(fixture.texts().contains(MR.strings.sync_recovery_details.localized(Locale.getDefault())))
                assertTrue(
                    fixture.texts().contains(MR.strings.sync_diagnostic_information.localized(Locale.getDefault())),
                )
                fixture.click("sync-setup-error-details")
                assertEquals(SyncPanelAction.Navigate(SyncPanelPage.DIAGNOSTICS), fixture.actions.last())
                fixture.click("sync-recheck-installation")
                assertEquals(SyncPanelAction.RetrySetup, fixture.actions.last())
            } finally {
                fixture.scene.close()
            }
        }
    }

    @Test
    fun `resuming unfinished counting restores waiting animation in main and setup`() {
        for (page in listOf(SyncPanelPage.MAIN, SyncPanelPage.SETUP)) {
            renderedEnglish(
                connected().copy(
                    page = page,
                    setupStep = SyncSetupStep.MERGING,
                    run = visualRun(SyncRunPhase.IMPORTING).copy(
                        plannedItems = null,
                        state = SyncRunState.PAUSED_USER,
                    ),
                    progress = SyncProgressFact(
                        "run-visual", SyncProgressStage.PREPARING, SyncProgressDirection.UPLOAD,
                        0, null, 0, 0, null, 0, SyncProgressHold.PAUSED, null, null,
                    ),
                ),
            ) {
                awaitTag("sync-counting-paused-track")
                click("sync-resume-run")
                assertEquals(SyncPanelAction.ResumeSync, actions.last())
                panel.state.value = panel.state.value.copy(
                    run = panel.state.value.run!!.copy(state = SyncRunState.RUNNING),
                    progress = panel.state.value.progress!!.copy(hold = SyncProgressHold.RECOVERING),
                )
                awaitTag("sync-counting-track")
                assertFalse(hasTag("sync-counting-paused-track"))
                assertFalse(hasTag("sync-round-time"))
                assertFalse(hasTag("sync-progress-track"))
                assertTrue(texts().contains(MR.strings.sync_round_counting.localized(Locale.US)))
                assertEquals(
                    androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate,
                    node("sync-counting-track").config[SemanticsProperties.ProgressBarRangeInfo],
                )
                panel.state.value = panel.state.value.copy(
                    progress = panel.state.value.progress!!.copy(hold = SyncProgressHold.OFFLINE),
                )
                awaitTag("sync-counting-paused-track")
                assertFalse(hasTag("sync-counting-track"))
            }
        }
    }

    @Test
    fun `determinate sync track has no bright endpoint marker at zero or partial completion`() {
        for (completed in listOf(0L, 25L)) {
            rendered(
                connected().copy(
                    run = visualRun(SyncRunPhase.UPLOADING).copy(
                        plannedItems = 100,
                        confirmedItems = completed,
                    ),
                ),
            ) {
                awaitTag("sync-progress-track")
                val bounds = node("sync-progress-track").boundsInRoot
                scene.render().use { image ->
                    image.toComposeImageBitmap().asSkiaBitmap().use { bitmap ->
                        assertEquals(
                            bitmap.getColor(bounds.center.x.toInt(), bounds.center.y.toInt()),
                            bitmap.getColor(bounds.right.toInt() - 3, bounds.center.y.toInt()),
                            "unfinished endpoint must have the same color as the unfilled track",
                        )
                    }
                }
                assertEquals(
                    completed / 100f,
                    node("sync-progress-track").config[SemanticsProperties.ProgressBarRangeInfo].current,
                )
            }
        }
    }

    private fun connected() = SyncPanelState(
        visible = true,
        loaded = true,
        canChangeSpace = true,
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

    private fun renderedEnglish(state: SyncPanelState, block: suspend Fixture.() -> Unit) {
        val originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            rendered(state, block)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    private fun rendered(state: SyncPanelState, block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture(state, ImageComposeScene(560, 720, coroutineContext = coroutineContext) {})
        try {
            fixture.setContent()
            fixture.block()
        } finally {
            fixture.scene.close()
        }
    }

    private class Fixture(
        initial: SyncPanelState,
        val scene: ImageComposeScene,
        private val fontScale: Float = 1f,
        private val dark: Boolean = true,
    ) {
        val actions = mutableListOf<SyncPanelAction>()
        val opened = mutableListOf<String>()
        val openedFailureLogs = mutableListOf<String>()
        val copied = mutableListOf<String>()
        val panel = TestPanel(initial, actions)
        var displayMillis by mutableStateOf(0L)
        var clockCalls = 0
        fun setContent() {
            scene.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        val state by panel.state.collectAsState()
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                            Column {
                                SyncToolbarButton(state) { actions += SyncPanelAction.Open }
                                SyncPanelContent(
                                    panel,
                                    onOpenBrowser = opened::add,
                                    onCopyCode = copied::add,
                                    onOpenFailureLog = openedFailureLogs::add,
                                    displayMonotonicMillis = {
                                        clockCalls++
                                        displayMillis
                                    },
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
                scene.render(System.nanoTime())
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
        fun unmergedNode(value: String) = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
            .first { tag(it) == value }
        fun hasTag(value: String) = nodes().any { tag(it) == value }
        fun node(value: String) = nodes().first { tag(it) == value }
        suspend fun reveal(value: String, viewportHeight: Int) {
            val scroll = node("sync-pending-list").config[SemanticsActions.ScrollByOffset]
            withTimeout(5000) {
                while (node(value).boundsInRoot.height < node(value).size.height - 1f) {
                    withContext(object : MonotonicFrameClock {
                        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                            delay(16)
                            return onFrame(System.nanoTime())
                        }
                    }) { scroll.invoke(androidx.compose.ui.geometry.Offset(0f, 100f)) }
                    delay(30)
                    render()
                }
            }
            assertTrue(node(value).boundsInRoot.height >= node(value).size.height - 1f)
            assertTrue(node(value).boundsInRoot.top >= 0)
            assertTrue(node(value).boundsInRoot.bottom <= viewportHeight)
        }
        fun assertTextFits(value: String) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(requireNotNull(node(value).config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts))
            assertTrue(layouts.isNotEmpty())
            assertFalse(layouts.any { it.hasVisualOverflow }, "Clipped $value")
        }
        fun geometry(value: String): androidx.compose.ui.geometry.Rect {
            val node = node(value)
            val position = node.positionInRoot
            return androidx.compose.ui.geometry.Rect(
                position.x,
                position.y,
                position.x + node.size.width,
                position.y + node.size.height,
            )
        }
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
        private val openedDeviceCodes = mutableSetOf<String>()
        override fun claimDeviceCodeBrowser(code: GitHubDeviceCode): Boolean = openedDeviceCodes.add(code.deviceCode)
        override fun dispatch(action: SyncPanelAction) {
            actions += action
        }
    }
}
