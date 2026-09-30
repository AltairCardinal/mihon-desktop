package eu.kanade.tachiyomi.util.lang

import eu.kanade.domain.ui.model.UiDateFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.time.LocalDate
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RelativeDateContractTest {
    @Test
    fun `Android wrapper consumes the shared local day window and absolute preference`() {
        val context = RuntimeEnvironment.getApplication()
        val today = LocalDate.of(2024, 3, 10)
        val format = UiDateFormat.formatter("yyyy-MM-dd", Locale.ENGLISH)
        for (difference in -8..7) {
            val date = today.minusDays(difference.toLong())
            val expected = when (val days = UiDateFormat.relativeDays(date, today, true)) {
                null -> format.format(date)
                0 -> context.stringResource(MR.strings.relative_time_today)
                in -7..-1 -> context.pluralStringResource(MR.plurals.upcoming_relative_time, -days, -days)
                else -> context.pluralStringResource(MR.plurals.relative_time, days, days)
            }
            assertEquals(
                "local day difference $difference",
                expected,
                date.toRelativeString(context, true, format, today),
            )
            assertEquals(format.format(date), date.toRelativeString(context, false, format, today))
        }
    }
}
