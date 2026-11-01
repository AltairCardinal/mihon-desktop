package tachiyomi.presentation.feedback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.icerock.moko.resources.StringResource
import tachiyomi.domain.feedback.FeedbackCategory
import tachiyomi.domain.feedback.FeedbackImageStage
import tachiyomi.domain.feedback.FeedbackLanguage
import tachiyomi.domain.feedback.FeedbackScreenshot
import tachiyomi.domain.feedback.NativeFeedbackContent
import tachiyomi.domain.feedback.NativeFeedbackController
import tachiyomi.domain.feedback.NativeFeedbackFailure
import tachiyomi.domain.feedback.NativeFeedbackPhase
import tachiyomi.i18n.MR

@Composable
fun NativeFeedbackDialog(
    controller: NativeFeedbackController,
    summary: String,
    onClose: () -> Unit,
    copySummary: () -> Unit,
    text: @Composable (StringResource) -> String,
    language: FeedbackLanguage = FeedbackLanguage.ENGLISH,
    onPickScreenshot: () -> Unit = {},
) {
    val state by controller.state.collectAsState()
    val draft = state.draft
    val diagnosticState = controller.diagnostics?.state?.collectAsState()?.value
    val titleFocus = remember { FocusRequester() }
    val statusFocus = remember { FocusRequester() }
    var categoryExpanded by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<FeedbackScreenshot?>(null) }
    var copyFailed by remember { mutableStateOf<Boolean?>(null) }
    val close = {
        controller.cancel()
        onClose()
    }
    DisposableEffect(controller) { onDispose { controller.cancel() } }
    LaunchedEffect(controller) {
        controller.refreshCapabilities()
        withFrameNanos { }
        titleFocus.requestFocus()
    }
    LaunchedEffect(state.phase) {
        if (state.phase == NativeFeedbackPhase.FAILED || state.phase == NativeFeedbackPhase.RECEIVED) {
            withFrameNanos { }
            statusFocus.requestFocus()
        }
    }
    AlertDialog(
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(0.95f).composed {
            val focusManager = LocalFocusManager.current
            onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                    close()
                    true
                } else if (it.key == Key.Tab && it.type == KeyEventType.KeyDown) {
                    focusManager.moveFocus(if (it.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                    true
                } else {
                    false
                }
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = close,
        title = { Text(text(MR.strings.feedback_report_problem)) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).testTag("feedback-scroll"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text(MR.strings.feedback_category_privacy), style = MaterialTheme.typography.bodySmall)
                Box {
                    TextButton(
                        onClick = { categoryExpanded = true },
                        enabled = !state.locked,
                        modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).testTag("feedback-category"),
                    ) { Text(draft.category?.label(language) ?: text(MR.strings.feedback_category_choose)) }
                    DropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                        FeedbackCategory.entries.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.label(language)) },
                                onClick = {
                                    categoryExpanded = false
                                    controller.category(category)
                                },
                                modifier = Modifier.testTag("feedback-category-${category.id}"),
                            )
                        }
                    }
                }
                Text(text(MR.strings.feedback_template_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = draft.description,
                    onValueChange = controller::description,
                    enabled = !state.locked,
                    label = { Text(text(MR.strings.feedback_native_description)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().testTag("feedback-description"),
                )
                ConsentRow(
                    draft.includeSummary,
                    !state.locked,
                    controller::includeSummary,
                    text(MR.strings.feedback_native_include_summary),
                    "feedback-include-summary",
                )
                SelectionContainer { Text(summary, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = {
                    copyFailed = runCatching(copySummary).isFailure
                }, enabled = !state.busy, modifier = Modifier.testTag("feedback-copy-summary")) {
                    Text(text(MR.strings.feedback_copy_summary))
                }
                copyFailed?.let {
                    Text(text(if (it) MR.strings.feedback_copy_failed else MR.strings.feedback_summary_copied))
                }
                controller.diagnostics?.let { diagnostics ->
                    val logState by diagnostics.state.collectAsState()
                    val recording by diagnostics.recording.collectAsState()
                    if (logState.reading) Text(text(MR.strings.feedback_native_reading))
                    if (logState.readFailed) Text(text(MR.strings.feedback_diagnostic_read_failed))
                    if (logState.noToday) Text(text(MR.strings.feedback_diagnostic_no_today))
                    if (recording.session != null) {
                        val category = FeedbackCategory.entries.firstOrNull { it.id == recording.session?.category?.id }
                        Text(text(MR.strings.feedback_diagnostic_active) + " " + category?.label(language).orEmpty())
                        if (recording.session?.paused == true) {
                            Text(text(MR.strings.feedback_diagnostic_paused), color = MaterialTheme.colorScheme.error)
                        } else if (recording.error != null) {
                            Text(
                                text(MR.strings.feedback_diagnostic_storage_active),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        TextButton(
                            onClick = diagnostics::stop,
                            modifier = Modifier.testTag("feedback-diagnostics-stop"),
                        ) {
                            Text(text(MR.strings.feedback_diagnostic_stop))
                        }
                    } else if (recording.error != null) {
                        Text(
                            text(MR.strings.feedback_diagnostic_storage_inactive),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(text(MR.strings.feedback_diagnostic_notice), style = MaterialTheme.typography.bodySmall)
                    TextButton(
                        onClick = diagnostics::start,
                        enabled = !state.locked && draft.category != null,
                        modifier = Modifier.testTag("feedback-diagnostics-start"),
                    ) {
                        Text(text(MR.strings.feedback_diagnostic_start))
                    }
                    if (recording.session != null) {
                        TextButton(
                            onClick = close,
                            enabled = !state.busy,
                            modifier = Modifier.testTag("feedback-diagnostics-leave"),
                        ) {
                            Text(text(MR.strings.feedback_diagnostic_leave))
                        }
                    }
                    logState.dates.forEach { date ->
                        TextButton(
                            onClick = { diagnostics.refresh(date, explicit = true) },
                            enabled = !state.locked,
                            modifier = Modifier.testTag("feedback-diagnostics-date-$date"),
                        ) { Text(date) }
                    }
                    if (logState.pendingRecording != null) {
                        AlertDialog(
                            onDismissRequest = { diagnostics.confirmStart(false) },
                            title = { Text(text(MR.strings.feedback_diagnostic_switch)) },
                            text = { Text(text(MR.strings.feedback_diagnostic_switch_notice)) },
                            confirmButton = {
                                TextButton(
                                    onClick = { diagnostics.confirmStart(true) },
                                    modifier = Modifier.testTag("feedback-diagnostics-replace"),
                                ) {
                                    Text(text(MR.strings.action_ok))
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { diagnostics.confirmStart(false) },
                                    modifier = Modifier.testTag("feedback-diagnostics-keep"),
                                ) {
                                    Text(text(MR.strings.action_cancel))
                                }
                            },
                        )
                    }
                }
                ConsentRow(
                    draft.logConsent,
                    !state.locked,
                    controller::logConsent,
                    text(MR.strings.feedback_attach_log),
                    "feedback-log-consent",
                )
                Text(text(MR.strings.feedback_native_log_notice), style = MaterialTheme.typography.bodySmall)
                TextButton(
                    onClick = controller::readLog,
                    enabled = !state.locked,
                    modifier = Modifier.testTag("feedback-read-log"),
                ) {
                    Text(text(MR.strings.feedback_native_read_log))
                }
                if (draft.crashLog.isNotEmpty()) {
                    OutlinedTextField(
                        value = draft.crashLog,
                        onValueChange = controller::log,
                        enabled = !state.locked,
                        label = { Text(text(MR.strings.feedback_native_log_preview)) },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth().testTag("feedback-log"),
                    )
                    val bytes = NativeFeedbackContent.logBytes(draft.crashLog)
                    Text(
                        "${text(
                            MR.strings.feedback_native_log_bytes,
                        )}: $bytes / ${NativeFeedbackContent.MAX_LOG_BYTES}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (bytes >
                        NativeFeedbackContent.MAX_LOG_BYTES
                    ) {
                        Text(text(MR.strings.feedback_native_log_large), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(
                        onClick = controller::removeLog,
                        enabled = !state.locked,
                        modifier = Modifier.testTag("feedback-remove-log"),
                    ) {
                        Text(text(MR.strings.feedback_native_remove_log))
                    }
                }
                Text("${text(MR.strings.feedback_screenshots)} (${draft.screenshots.size}/2)")
                Text(text(MR.strings.feedback_screenshot_private), style = MaterialTheme.typography.bodySmall)
                TextButton(
                    onClick = onPickScreenshot,
                    enabled = !state.locked && state.capabilities?.accepting == true &&
                        state.capabilities?.screenshots == true && draft.screenshots.size < 2,
                    modifier = Modifier.testTag("feedback-add-screenshot"),
                ) { Text(text(MR.strings.feedback_screenshot_pick)) }
                state.screenshotProgress?.let { progress ->
                    val resource = when (progress.stage) {
                        FeedbackImageStage.READ -> MR.strings.feedback_image_read
                        FeedbackImageStage.DECODE -> MR.strings.feedback_image_decode
                        FeedbackImageStage.SCALE -> MR.strings.feedback_image_scale
                        FeedbackImageStage.ENCODE -> MR.strings.feedback_image_encode
                        FeedbackImageStage.CHECK -> MR.strings.feedback_image_check
                        FeedbackImageStage.COMPLETE -> MR.strings.feedback_image_complete
                    }
                    Text(
                        "${progress.index}/${progress.total} · ${text(resource)}",
                        modifier = Modifier.testTag("feedback-image-progress"),
                    )
                }
                draft.screenshots.forEachIndexed { index, image ->
                    Column {
                        FeedbackScreenshotPreview(
                            image,
                            Modifier.fillMaxWidth().heightIn(max = 180.dp).clickable(enabled = !state.busy) {
                                preview =
                                    image
                            }
                                .testTag("feedback-screenshot-${image.id}"),
                        )
                        Text("#${index + 1} · ${image.width} × ${image.height} · ${image.byteLength} B · JPEG")
                        TextButton(
                            onClick = { controller.removeScreenshot(image.id) },
                            enabled = !state.locked,
                            modifier = Modifier.testTag("feedback-remove-screenshot-${image.id}"),
                        ) {
                            Text(text(MR.strings.feedback_screenshot_remove))
                        }
                    }
                }
                ConsentRow(
                    draft.publicConsent,
                    !state.locked,
                    controller::publicConsent,
                    text(MR.strings.feedback_native_public_consent),
                    "feedback-public-consent",
                )
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.configLoading) Text(text(MR.strings.feedback_config_loading))
                if (state.capabilityFailure != null) {
                    TextButton(
                        onClick = controller::refreshCapabilities,
                        enabled = !state.locked,
                        modifier = Modifier.testTag("feedback-config-retry"),
                    ) {
                        Text(text(MR.strings.feedback_config_retry))
                    }
                }
                val status = (state.failure ?: state.capabilityFailure)?.resource() ?: when (state.phase) {
                    NativeFeedbackPhase.READING_LOG -> MR.strings.feedback_native_reading
                    NativeFeedbackPhase.CREATING_SESSION -> MR.strings.feedback_native_preparing
                    NativeFeedbackPhase.WAITING_VERIFICATION -> MR.strings.feedback_native_waiting
                    NativeFeedbackPhase.SENDING -> MR.strings.feedback_native_sending
                    NativeFeedbackPhase.RECEIVED -> MR.strings.feedback_native_received
                    else -> null
                }
                if (status != null) {
                    Column(Modifier.focusRequester(statusFocus).focusable().testTag("feedback-status")) {
                        Text(
                            text(status),
                            color = if (state.failure !=
                                null
                            ) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        state.receipt?.let { SelectionContainer { Text(it) } }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = controller::submit,
                enabled =
                !state.locked && diagnosticState?.reading != true && state.capabilities?.accepting == true &&
                    state.capabilities?.nativeV2 == true,
                modifier = Modifier.testTag("feedback-submit"),
            ) {
                Text(text(MR.strings.feedback_native_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (state.busy) controller.cancel() else close()
            }, modifier = Modifier.testTag("feedback-cancel")) {
                Text(text(if (state.busy) MR.strings.feedback_native_cancel else MR.strings.action_close))
            }
        },
    )
    state.pendingCategory?.let {
        AlertDialog(
            onDismissRequest = { controller.confirmCategory(false) },
            title = { Text(text(MR.strings.feedback_category_replace)) },
            text = { Text(text(MR.strings.feedback_category_replace_help)) },
            confirmButton = {
                TextButton(
                    onClick = { controller.confirmCategory(true) },
                    modifier = Modifier.testTag("feedback-category-replace"),
                ) {
                    Text(text(MR.strings.feedback_category_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { controller.confirmCategory(false) },
                    modifier = Modifier.testTag("feedback-category-keep"),
                ) { Text(text(MR.strings.feedback_category_keep)) }
            },
        )
    }
    preview?.let { image ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(text(MR.strings.feedback_screenshot_preview)) },
            text = {
                FeedbackScreenshotPreview(
                    image,
                    Modifier.fillMaxWidth().heightIn(max = 600.dp)
                        .testTag("feedback-final-preview"),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    preview = null
                }, modifier = Modifier.testTag("feedback-preview-close")) { Text(text(MR.strings.action_close)) }
            },
        )
    }
}

@Composable
private fun ConsentRow(checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit, label: String, tag: String) {
    Row(
        Modifier.fillMaxWidth().toggleable(
            checked,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = change,
        ).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}

private fun NativeFeedbackFailure.resource(): StringResource = when (this) {
    NativeFeedbackFailure.INVALID -> MR.strings.feedback_native_invalid
    NativeFeedbackFailure.LOG_INVALID -> MR.strings.feedback_native_log_invalid
    NativeFeedbackFailure.LOG_TOO_LARGE -> MR.strings.feedback_native_log_large
    NativeFeedbackFailure.LOG_READ_TOO_LARGE -> MR.strings.feedback_native_log_read_large
    NativeFeedbackFailure.LOG_UNAVAILABLE -> MR.strings.feedback_native_log_unavailable
    NativeFeedbackFailure.PUBLIC_CONSENT -> MR.strings.feedback_native_need_public_consent
    NativeFeedbackFailure.LOG_CONSENT -> MR.strings.feedback_native_need_log_consent
    NativeFeedbackFailure.NETWORK -> MR.strings.feedback_native_network
    NativeFeedbackFailure.RATE_LIMIT -> MR.strings.feedback_native_rate_limit
    NativeFeedbackFailure.EXPIRED -> MR.strings.feedback_native_expired
    NativeFeedbackFailure.CONFLICT -> MR.strings.feedback_native_conflict
    NativeFeedbackFailure.BROWSER -> MR.strings.feedback_native_browser
    NativeFeedbackFailure.CANCELLED -> MR.strings.feedback_native_cancelled
    NativeFeedbackFailure.IMAGE_INVALID -> MR.strings.feedback_image_invalid
    NativeFeedbackFailure.IMAGE_TOO_LARGE -> MR.strings.feedback_image_large
    NativeFeedbackFailure.IMAGE_LIMIT -> MR.strings.feedback_image_limit
    NativeFeedbackFailure.V2_UNAVAILABLE -> MR.strings.feedback_v2_unavailable
    NativeFeedbackFailure.ATTACHMENT_CAPACITY -> MR.strings.feedback_attachment_capacity
    NativeFeedbackFailure.SCREENSHOTS_UNAVAILABLE -> MR.strings.feedback_screenshots_unavailable
}
