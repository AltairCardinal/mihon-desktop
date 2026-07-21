package eu.kanade.tachiyomi.util.system

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.IntentCompat
import mihon.domain.platform.ExternalShare
import mihon.domain.platform.SharePayload
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.Serializable

fun Uri.toShareIntent(context: Context, type: String = "image/*", message: String? = null): Intent {
    return buildShareIntent(type, message, context.stringResource(MR.strings.action_share))
}

internal fun Uri.buildShareIntent(
    type: String,
    message: String?,
    chooserTitle: String,
): Intent {
    val uri = this
    val payload = ExternalShare.fromUri(uri.toString(), type, message)

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        when (payload) {
            is SharePayload.Text -> putExtra(Intent.EXTRA_TEXT, payload.text)
            is SharePayload.Stream -> {
                payload.message?.let { putExtra(Intent.EXTRA_TEXT, it) }
                putExtra(Intent.EXTRA_STREAM, uri)
            }
            null -> Unit
        }
        clipData = ClipData.newRawUri(null, uri)
        setType(type)
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    }

    return Intent.createChooser(shareIntent, chooserTitle).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
}

inline fun <reified T> Intent.getParcelableExtraCompat(name: String): T? {
    return IntentCompat.getParcelableExtra(this, name, T::class.java)
}

inline fun <reified T : Serializable> Intent.getSerializableExtraCompat(name: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getSerializableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getSerializableExtra(name) as? T
    }
}
