package mihon.desktop.ui.tutorial

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import kotlin.math.roundToInt

/** Non-modal overlay. [anchorBounds] is the real target rectangle in this overlay's coordinates. */
@Composable
fun DesktopTutorialBubble(
    tutorial: DesktopTutorial,
    anchorBounds: Rect,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.secondaryContainer
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            Surface(
                modifier = Modifier.testTag("desktop-tutorial-${tutorial.id}"),
                shape = RoundedCornerShape(20.dp),
                color = color,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shadowElevation = 6.dp,
            ) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
                    Text(tutorial.title.localized(), style = MaterialTheme.typography.titleSmall)
                    Text(
                        tutorial.message.localized(),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = onConfirm,
                        modifier = Modifier.align(Alignment.End).testTag("desktop-tutorial-confirm"),
                    ) {
                        Text(MR.strings.desktop_tutorial_got_it.localized())
                    }
                }
            }
            Canvas(Modifier.testTag("desktop-tutorial-tip")) {
                drawPath(
                    Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width / 2f, size.height)
                        close()
                    },
                    color,
                )
            }
        },
    ) { measurables, constraints ->
        val margin = 16.dp.roundToPx()
        val gap = 4.dp.roundToPx()
        val tip = measurables[1].measure(Constraints.fixed(18.dp.roundToPx(), 10.dp.roundToPx()))
        val body = measurables[0].measure(
            Constraints(
                maxWidth = minOf(336.dp.roundToPx(), (constraints.maxWidth - margin * 2).coerceAtLeast(0)),
                maxHeight = (anchorBounds.top.roundToInt() - gap - tip.height - margin).coerceAtLeast(0),
            ),
        )
        val center = anchorBounds.center.x.roundToInt()
        val left = (center - body.width / 2).coerceIn(
            margin,
            (constraints.maxWidth - margin - body.width).coerceAtLeast(margin),
        )
        val tipTop = anchorBounds.top.roundToInt() - gap - tip.height
        layout(constraints.maxWidth, constraints.maxHeight) {
            body.place(left, tipTop - body.height)
            tip.place(center - tip.width / 2, tipTop)
        }
    }
}
