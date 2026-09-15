package mihon.presentation.sync

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.desc.ResourceFormatted
import dev.icerock.moko.resources.desc.StringDesc
import java.text.DateFormat
import java.util.Date

@Composable
internal actual fun syncString(resource: StringResource, vararg args: Any): String =
    StringDesc.ResourceFormatted(resource, *args).toString(LocalContext.current)

internal actual fun syncDate(timeMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timeMillis))
