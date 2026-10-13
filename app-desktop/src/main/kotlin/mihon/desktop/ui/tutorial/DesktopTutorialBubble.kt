package mihon.desktop.ui.tutorial

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR

/** Non-modal coach bubble. Its host owns eligibility, action and focus restoration. */
@Composable
fun DesktopTutorialBubble(
    tutorial: DesktopTutorial,
    actionLabel: String,
    onAction: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.secondaryContainer
    Column(
        modifier = modifier.widthIn(max = 336.dp).testTag("desktop-tutorial-${tutorial.id}"),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
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
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = onConfirm, modifier = Modifier.testTag("desktop-tutorial-confirm")) {
                        Text(MR.strings.desktop_tutorial_got_it.localized())
                    }
                }
            }
        }
        Canvas(Modifier.padding(end = 32.dp).size(18.dp, 10.dp)) {
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
        Button(onClick = onAction, modifier = Modifier.testTag("desktop-tutorial-action")) {
            Text(actionLabel)
        }
    }
}
