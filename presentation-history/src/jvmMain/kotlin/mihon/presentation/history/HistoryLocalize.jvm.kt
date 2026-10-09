package mihon.presentation.history

import androidx.compose.runtime.Composable
import dev.icerock.moko.resources.StringResource
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal actual fun historyString(resource: StringResource, vararg args: Any): String =
    resource.localized(Locale.getDefault(), *args)

internal actual fun historyDate(timeMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timeMillis))

@Composable
internal actual fun historyPlural(
    resource: dev.icerock.moko.resources.PluralsResource,
    count: Int,
    vararg args: Any,
): String = resource.localized(Locale.getDefault(), count, *args)
