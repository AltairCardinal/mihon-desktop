package mihon.presentation.sync

import androidx.compose.runtime.Composable
import dev.icerock.moko.resources.StringResource

@Composable
internal expect fun syncString(resource: StringResource, vararg args: Any): String

internal expect fun syncDate(timeMillis: Long): String
