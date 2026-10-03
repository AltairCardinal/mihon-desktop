package mihon.presentation.history

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

/** Original Mihon chapter number format, shared by history and the Android callers. */
fun formatChapterNumber(chapterNumber: Double): String = DecimalFormat(
    "#.###",
    DecimalFormatSymbols().apply { decimalSeparator = '.' },
).format(chapterNumber)
