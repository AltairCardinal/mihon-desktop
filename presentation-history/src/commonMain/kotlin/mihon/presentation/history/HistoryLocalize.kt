package mihon.presentation.history

import androidx.compose.runtime.Composable
import dev.icerock.moko.resources.StringResource

@Composable
internal expect fun historyString(resource: StringResource, vararg args: Any): String

internal expect fun historyDate(timeMillis: Long): String

@Composable
internal expect fun historyPlural(
    resource: dev.icerock.moko.resources.PluralsResource,
    count: Int,
    vararg args: Any,
): String
