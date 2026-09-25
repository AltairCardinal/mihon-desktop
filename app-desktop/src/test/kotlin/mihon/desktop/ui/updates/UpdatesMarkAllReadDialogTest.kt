package mihon.desktop.ui.updates

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR

@OptIn(ExperimentalComposeUiApi::class)
class UpdatesMarkAllReadDialogTest {
    @Test
    fun `failed mark all read displays feedback and keeps retry available`() = runBlocking {
        var retries = 0
        val scene = ImageComposeScene(640, 480, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                MaterialTheme {
                    UpdatesMarkAllReadDialog(
                        state = mihon.desktop.updates.UpdatesState(
                            showMarkAllReadDialog = true,
                            markAllReadFailed = true,
                        ),
                        onDismiss = {},
                        onConfirm = { retries++ },
                    )
                }
            }
            scene.render()
            val nodes = scene.semanticsOwners.flatMap { it.rootSemanticsNode.flatten() }
            assertTrue(nodes.any {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "updates-mark-all-read-error"
            })
            nodes.single { node ->
                node.config.contains(SemanticsActions.OnClick) &&
                    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                        .any { it.text == MR.strings.desktop_ui_mark_all_read.localized() }
            }.config[SemanticsActions.OnClick].action?.invoke()
            assertEquals(1, retries)
        } finally {
            scene.close()
        }
    }

    private fun SemanticsNode.flatten(): List<SemanticsNode> = listOf(this) + children.flatMap { it.flatten() }
}
