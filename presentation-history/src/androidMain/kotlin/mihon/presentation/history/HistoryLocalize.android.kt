package mihon.presentation.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.desc.PluralFormatted
import dev.icerock.moko.resources.desc.ResourceFormatted
import dev.icerock.moko.resources.desc.StringDesc
import java.text.DateFormat
import java.util.Date

@Composable
internal actual fun historyString(resource: StringResource, vararg args: Any): String =
    StringDesc.ResourceFormatted(resource, *args).toString(LocalContext.current)

internal actual fun historyDate(timeMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timeMillis))

@Composable
internal actual fun historyPlural(
    resource: dev.icerock.moko.resources.PluralsResource,
    count: Int,
    vararg args: Any,
): String = StringDesc.PluralFormatted(resource, count, *args).toString(LocalContext.current)
