package tachiyomi.data.creator

import java.time.Instant
import java.time.ZoneOffset

internal const val ARCHIVE_DATE_ZONE = "UTC"

internal fun frozenArchiveDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate().toString()
