package mihon.desktop.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.awt.ComposeWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import mihon.desktop.diagnostics.ReaderRenderTrace
import mihon.desktop.diagnostics.ReaderRenderTracing

internal val LocalReaderRenderTrace = staticCompositionLocalOf<ReaderRenderTrace?> { null }

internal fun readerRenderTraceImage(
    command: ReaderBaseImageDraw,
    identity: mihon.desktop.reader.DesktopReaderPresentationImageSlotIdentity,
) = buildJsonObject {
    put("pageIndex", identity.pageId.sourcePageIndex)
    put("chapterId", identity.pageId.chapterId.value)
    put("generation", identity.generation)
    put("attemptGeneration", identity.attemptGeneration)
    put("command", Json.encodeToJsonElement(command))
}

@Composable
internal fun rememberReaderRenderTrace(window: ComposeWindow?, state: ReaderState): ReaderRenderTrace? {
    val trace = remember(window) { ReaderRenderTracing.create(window) }
    DisposableEffect(trace) {
        trace?.guard("attach") { trace.attach() }
        onDispose { trace?.guard("detach") { trace.close() } }
    }
    SideEffect {
        trace?.guard("reader-state") {
            trace.readerState(
                buildJsonObject {
                    put("generation", state.session.generation)
                    put("chapterId", state.context.chapterId)
                    put("pageIndex", state.currentPage)
                    put("readingMode", state.readingMode.name)
                    put("automaticLayout", state.automaticLayout)
                    put("dualPageMode", state.dualPageMode)
                    put("geometryRevision", state.dualGeometryRevision)
                    put("fullscreen", state.isFullscreen)
                    put("showUI", state.showUI)
                    put("scaleType", state.scaleType.name)
                    put("zoom", state.zoomState.scale)
                    put("panX", state.zoomState.offsetX)
                    put("panY", state.zoomState.offsetY)
                    put("cropBorders", state.cropBordersPager)
                },
            )
        }
    }
    return trace
}
