package eu.kanade.tachiyomi.data.sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import mihon.presentation.sync.SyncReviewPanel
import mihon.presentation.sync.SyncReviewScenarios

/** Debug-only entry for inspecting the real Android sync sheet with local sample state. */
class SyncUiReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TachiyomiPreviewTheme {
                SyncUiReviewRoot()
            }
        }
    }
}

@Composable
internal fun SyncUiReviewRoot() {
    var panel by remember { mutableStateOf<SyncReviewPanel?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("同步面板 · 原生 UI 审阅", style = MaterialTheme.typography.headlineSmall)
            Text("以下场景使用产品界面和本地样本状态；切换场景会重置样本。")
            SyncReviewScenarios.all.forEach { scenario ->
                Button(
                    onClick = { panel = SyncReviewPanel(scenario) },
                    modifier = Modifier.fillMaxWidth().testTag("sync-review-scenario-${scenario.id}"),
                ) {
                    Text("${scenario.label} · ${scenario.description}")
                }
            }
        }
    }
    panel?.let { reviewPanel ->
        val state by reviewPanel.state.collectAsState()
        if (state.visible) {
            AndroidSyncPanelSheet(
                reviewPanel,
                onOpenBrowser = {},
                onCopyCode = {},
                onOpenFailureLog = {},
            )
        }
    }
}
