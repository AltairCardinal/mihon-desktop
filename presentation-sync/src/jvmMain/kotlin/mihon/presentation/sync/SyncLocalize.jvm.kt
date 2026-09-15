package mihon.presentation.sync

import androidx.compose.runtime.Composable
import dev.icerock.moko.resources.StringResource
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal actual fun syncString(resource: StringResource, vararg args: Any): String =
    resource.localized(Locale.getDefault(), *args)

internal actual fun syncDate(timeMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timeMillis))
