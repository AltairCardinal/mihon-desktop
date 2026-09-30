package mihon.desktop.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.selectableAppThemes
import eu.kanade.presentation.theme.colorscheme.AppThemeColorScheme
import tachiyomi.i18n.MR

/** Desktop adapter of the frozen Android preview: illustrative cover ratio is 2:3. */
@Composable
internal fun AppearanceThemeCards(
    current: AppTheme,
    isDark: Boolean,
    amoled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (AppTheme) -> Unit,
) {
    LazyRow(
        modifier = modifier.testTag("appearance-theme-cards"),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(selectableAppThemes(false), key = { it.name }) { theme ->
            Column(Modifier.width(114.dp).padding(top = 8.dp).testTag("theme-card-${theme.name}")) {
                MaterialTheme(colorScheme = AppThemeColorScheme.colorScheme(theme, isDark, amoled)) {
                    AppearanceThemePreview(current == theme) { onSelect(theme) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    requireNotNull(theme.titleRes).localized(),
                    modifier = Modifier.fillMaxWidth().alpha(0.78f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    minLines = 2,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
internal fun AppearanceThemePreview(selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .border(
                width = 4.dp,
                color =
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    DividerDefaults.color
                },
                shape = RoundedCornerShape(17.dp),
            ).padding(4.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick),
    ) {
        // App Bar
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                Modifier
                    .fillMaxHeight(0.8f)
                    .weight(0.7f)
                    .padding(end = 4.dp)
                    .background(color = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.small),
            )

            Box(modifier = Modifier.weight(0.3f), contentAlignment = Alignment.CenterEnd) {
                if (selected) {
                    Icon(
                        imageVector = SettingsDirectoryIcons.checkCircle,
                        contentDescription = MR.strings.selected.localized(),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Cover
        Box(
            modifier =
            Modifier
                .padding(start = 8.dp, top = 2.dp)
                .background(color = DividerDefaults.color, shape = MaterialTheme.shapes.small).fillMaxWidth(0.5f)
                .aspectRatio(2f / 3f),
        ) {
            Row(
                modifier =
                Modifier
                    .padding(4.dp)
                    .size(width = 24.dp, height = 16.dp)
                    .clip(RoundedCornerShape(5.dp)),
            ) {
                Box(modifier = Modifier.fillMaxHeight().width(12.dp).background(MaterialTheme.colorScheme.tertiary))
                Box(modifier = Modifier.fillMaxHeight().width(12.dp).background(MaterialTheme.colorScheme.secondary))
            }
        }

        // Bottom bar
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    modifier =
                    Modifier
                        .height(32.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier =
                        Modifier
                            .size(17.dp)
                            .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape),
                    )
                    Box(
                        modifier =
                        Modifier
                            .padding(start = 8.dp)
                            .alpha(0.6f)
                            .height(17.dp)
                            .weight(1f)
                            .background(
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = MaterialTheme.shapes.small,
                            ),
                    )
                }
            }
        }
    }
}
