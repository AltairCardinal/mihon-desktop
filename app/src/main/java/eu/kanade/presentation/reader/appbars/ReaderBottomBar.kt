package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderBottomBar(
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    modifier: Modifier = Modifier,
    isDualPageMode: Boolean = false,
    isAutomaticMode: Boolean = false,
    isPairingSaving: Boolean = false,
    isPairingUnavailable: Boolean = false,
    onRetryPairing: () -> Unit = {},
    onClickAdjustPairing: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .pointerInput(Unit) {},
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onClickReadingMode) {
                Icon(
                    painter = painterResource(readingMode.iconRes),
                    contentDescription = stringResource(MR.strings.viewer),
                )
            }
            if (isAutomaticMode) {
                val status = if (isDualPageMode) {
                    MR.strings.desktop_reader_default_dual
                } else {
                    MR.strings.desktop_reader_default_single
                }
                Text(
                    text = stringResource(status),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        IconButton(onClick = onClickOrientation) {
            Icon(
                imageVector = orientation.icon,
                contentDescription = stringResource(MR.strings.rotation_type),
            )
        }

        if (isDualPageMode) {
            val savingDescription = stringResource(MR.strings.desktop_reader_pairing_saving)
            IconButton(
                onClick = onClickAdjustPairing,
                enabled = !isPairingSaving && !isPairingUnavailable,
                modifier = if (isPairingSaving) {
                    Modifier.semantics { stateDescription = savingDescription }
                } else {
                    Modifier
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_adjust_pairing_24dp),
                    contentDescription = stringResource(MR.strings.action_adjust_page_pairing),
                )
            }
            if (isPairingUnavailable) {
                IconButton(onClick = onRetryPairing) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = stringResource(MR.strings.action_retry),
                    )
                }
            }
        }

        IconButton(onClick = onClickCropBorder) {
            Icon(
                painter = painterResource(if (cropEnabled) R.drawable.ic_crop_24dp else R.drawable.ic_crop_off_24dp),
                contentDescription = stringResource(MR.strings.pref_crop_borders),
            )
        }

        IconButton(onClick = onClickSettings) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(MR.strings.action_settings),
            )
        }
    }
}
