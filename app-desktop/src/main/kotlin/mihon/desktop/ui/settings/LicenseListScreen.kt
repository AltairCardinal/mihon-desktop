package mihon.desktop.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import mihon.desktop.LocalDesktopUiDependencies
import mihon.domain.license.model.DependencyNotice
import mihon.domain.license.model.LicenseNoticeResult
import tachiyomi.i18n.MR

class LicenseListScreen : Screen {

    internal fun onOpen(navigator: Navigator, notice: DependencyNotice) {
        navigator.push(licenseDetailDestination(notice))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val result = LocalDesktopUiDependencies.current.dependencyNoticeProvider.getNotices()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(MR.strings.licenses.localized()) },
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, MR.strings.action_bar_up_description.localized())
                        }
                    },
                )
            },
        ) { padding ->
            when (result) {
                is LicenseNoticeResult.Failure -> Text(
                    MR.strings.desktop_license_notices_unavailable.localized(),
                    Modifier.padding(padding).padding(horizontal = 24.dp),
                )
                is LicenseNoticeResult.Success -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = padding,
                ) {
                    itemsIndexed(result.notices, key = { index, notice -> "$index:${notice.name}" }) { _, notice ->
                        ListItem(
                            headlineContent = { Text(notice.name) },
                            modifier = Modifier.clickable { onOpen(navigator, notice) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

internal fun licenseListDestination() = LicenseListScreen()

internal fun licenseDetailDestination(notice: DependencyNotice) =
    LicenseDetailScreen(notice.name, notice.website, notice.license)
