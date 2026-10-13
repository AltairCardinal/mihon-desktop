package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.NewUpdateScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.updater.AppUpdateDownloadJob
import eu.kanade.tachiyomi.data.updater.canInstallForkUpdate
import eu.kanade.tachiyomi.util.system.openInBrowser

class NewUpdateScreen(
    private val versionName: String,
    private val changelogInfo: String,
    private val releaseLink: String,
    private val downloadLink: String,
    private val expectedSha256: String? = null,
    private val expectedSizeBytes: Long? = null,
    private val expectedVersionCode: Long? = null,
    private val infoTruncated: Boolean = false,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val manualOnly = !canInstallForkUpdate()
        val changelogInfoNoChecksum = remember {
            changelogInfo.replace("""---(\R|.)*Checksums(\R|.)*""".toRegex(), "")
        }

        NewUpdateScreen(
            versionName = versionName,
            changelogInfo = changelogInfoNoChecksum,
            manualOnly = manualOnly,
            infoTruncated = infoTruncated,
            onOpenInBrowser = { context.openInBrowser(releaseLink) },
            onRejectUpdate = navigator::pop,
            onAcceptUpdate = {
                if (manualOnly) {
                    context.openInBrowser(releaseLink)
                } else {
                    AppUpdateDownloadJob.start(
                        context = context,
                        url = downloadLink,
                        title = versionName,
                        expectedSha256 = expectedSha256,
                        expectedSizeBytes = expectedSizeBytes,
                        expectedVersionCode = expectedVersionCode,
                    )
                }
                navigator.pop()
            },
        )
    }
}
