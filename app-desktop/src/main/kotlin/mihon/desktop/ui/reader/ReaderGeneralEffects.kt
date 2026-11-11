package mihon.desktop.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import mihon.desktop.reader.ReaderDisplayOptions
import mihon.desktop.reader.ReaderFlashColor
import mihon.desktop.reader.ReaderRippleStyle

/** Page effects follow settled source-page changes; opening a chapter does not flash. */
internal class ReaderPageEffectCounter {
    private var previous: Triple<Long, Int, Any?>? = null
    private var changes = 0
    var pageChanged = false
        private set
    fun changed(chapter: Long, page: Int, interval: Int, unit: Any? = null): Boolean {
        val old = previous
        previous = Triple(chapter, page, unit)
        pageChanged = old != null && old.first == chapter &&
            (old.second != page || (old.third != null && unit != null && old.third != unit))
        if (old == null || old.first != chapter) changes = 0
        if (!pageChanged) return false
        changes++
        return changes % interval.coerceAtLeast(1) == 0
    }
}

@Composable
internal fun ReaderGeneralEffects(state: ReaderState) {
    val options = state.displayOptions
    val counter = remember { ReaderPageEffectCounter() }
    val ripple = remember { Animatable(1f) }
    var flashColor by remember { mutableStateOf(Color.Transparent) }
    var turnSequence by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var flashDueForTurn by remember { mutableStateOf(false) }
    LaunchedEffect(
        state.context.chapterId,
        state.currentPage,
        state.currentDisplayUnitId,
        options.flash,
        options.ripple,
    ) {
        val flashDue = counter.changed(
            state.context.chapterId,
            state.currentPage,
            options.flashInterval,
            state.currentDisplayUnitId,
        )
        if (counter.pageChanged) {
            flashDueForTurn = flashDue
            turnSequence++
        }
    }
    LaunchedEffect(turnSequence) {
        if (turnSequence == 0) return@LaunchedEffect
        if (options.flash && flashDueForTurn) {
            try {
                flashColor = if (options.flashColor == ReaderFlashColor.BLACK) Color.Black else Color.White
                if (options.flashColor == ReaderFlashColor.WHITE_BLACK) {
                    delay(options.flashMillis / 2L)
                    flashColor = Color.Black
                    delay(options.flashMillis / 2L)
                } else {
                    delay(options.flashMillis.toLong())
                }
            } finally {
                flashColor = Color.Transparent
            }
        }
        if (options.ripple) {
            ripple.snapTo(0f)
            ripple.animateTo(1f, tween(if (options.rippleStyle == ReaderRippleStyle.SMOOTH) 450 else 280))
        }
    }
    if (flashColor !=
        Color.Transparent
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(flashColor))
    }
    if (options.ripple && ripple.value < 1f) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = kotlin.math.hypot(size.width, size.height) * ripple.value
            val alpha = 1f - ripple.value
            drawCircle(Color.White.copy(alpha = alpha * 0.65f), radius, Offset(0f, size.height / 2))
            if (options.rippleStyle ==
                ReaderRippleStyle.SHADOW_LINE
            ) {
                drawCircle(
                    Color.Black.copy(alpha = alpha),
                    radius,
                    Offset(
                        0f,
                        size.height / 2,
                    ),
                    style = Stroke(2.dp.toPx()),
                )
            }
        }
    }
    if (options.showPageNumber && !state.showUI &&
        state.session.activeChapter.pages.isNotEmpty()
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(
                "${state.currentPage + 1}/${state.session.activeChapter.pages.size}",
                color = Color.White,
                modifier = Modifier.padding(
                    8.dp,
                ).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
    ReaderKeepScreenOn(options.keepScreenOn)
}

@Composable
private fun ReaderKeepScreenOn(enabled: Boolean) {
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        if (com.sun.jna.Platform.isWindows()) {
            while (true) {
                com.sun.jna.platform.win32.Kernel32.INSTANCE.SetThreadExecutionState(0x00000003)
                delay(30_000)
            }
        } else if (com.sun.jna.Platform.isMac()) {
            val process = ProcessBuilder("/usr/bin/caffeinate", "-d", "-i").start()
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                process.destroy()
            }
        }
    }
}
