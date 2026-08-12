package eu.kanade.tachiyomi.data.library

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.creator.model.ArchiveDiscovery
import tachiyomi.domain.creator.model.DiscoveryKind
import tachiyomi.domain.creator.service.CreatorDiscoveryDeliveryResult
import tachiyomi.domain.creator.service.CreatorDiscoveryNotificationPort
import tachiyomi.i18n.MR

class AndroidCreatorDiscoveryNotifier(private val context: Context) : CreatorDiscoveryNotificationPort {
    override suspend fun deliver(discovery: ArchiveDiscovery): CreatorDiscoveryDeliveryResult {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = Constants.SHORTCUT_UPDATES
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            discovery.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val kind = when (discovery.kind) {
            DiscoveryKind.NEW_WORK_CANDIDATE -> context.stringResource(MR.strings.desktop_ui_new_work_candidate)
            DiscoveryKind.NEW_SOURCE_VERSION -> context.stringResource(MR.strings.desktop_ui_new_source_version)
        }
        context.notify(
            Notifications.ID_AUTHOR_DISCOVERY + discovery.id.toInt(),
            context.notificationBuilder(Notifications.CHANNEL_NEW_CHAPTERS) {
                setSmallIcon(R.drawable.ic_mihon)
                setContentTitle(kind)
                setContentText(discovery.title)
                setContentIntent(pendingIntent)
                setAutoCancel(true)
            }.build(),
        )
        return CreatorDiscoveryDeliveryResult.Delivered
    }
}
