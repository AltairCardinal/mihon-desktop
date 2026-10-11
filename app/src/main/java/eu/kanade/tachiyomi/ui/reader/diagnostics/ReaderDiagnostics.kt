package eu.kanade.tachiyomi.ui.reader.diagnostics

import android.content.Context
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import java.io.File
import java.util.UUID

/** Local diagnostics only. Field names are allowlisted and values never accept free-form text. */
internal object ReaderDiagnostics {
    @Volatile var sink: ReaderDiagnosticSink? = null
    private val process = UUID.randomUUID().toString()
    private val holders = java.util.concurrent.atomic.AtomicLong()
    fun nextHolderId(): Long = holders.incrementAndGet()
    private val safeToken = Regex("[A-Za-z0-9_,:.=-]{1,2048}")
    private val fields = setOf(
        "reason", "state", "loader", "error", "elapsedMs", "generation", "attachment", "visible",
        "sequence", "ownerSequence", "context", "serial", "observedHint", "submittedHint", "storedHint",
        "sample", "matching", "sideEnabled", "crop", "bounded", "animated", "actual", "proposed",
        "proposedBefore", "reasonBefore", "lockedBefore", "locked", "accepted", "prepared", "outcome",
        "neighbors", "storage", "position", "forward", "target", "rendered", "layoutRevision", "factState",
        "gesture", "idle", "holder",
    )

    fun initialize(context: Context) {
        if (sink != null) return
        var root: File? = null
        sink = ReaderDiagnosticSink(directory = {
            root ?: (context.getExternalFilesDir("reader-diagnostics") ?: File(context.filesDir, "reader-diagnostics"))
                .also {
                    root = it
                    val storage = if (it.parentFile == context.filesDir) "INTERNAL" else "EXTERNAL"
                    record("storage", fields = mapOf("storage" to storage))
                    android.util.Log.i("ReaderDiagnostics", "storage=$storage")
                }
        }, onFailure = { count ->
            if (count == 1L || count % 100 == 0L) android.util.Log.w("ReaderDiagnostics", "write_failure count=$count")
        })
        record("process_start")
    }

    fun record(event: String, page: ReaderPage, session: String, vararg values: Pair<String, Any?>) =
        record(event, page.chapter.chapter.id, page.index, values.toMap(), session)

    fun record(
        event: String,
        chapter: Long? = null,
        source: Int? = null,
        fields: Map<String, Any?> = emptyMap(),
        session: String = "none",
    ) {
        val destination = sink ?: return
        fun token(value: Any?): String = when (value) {
            null -> "none"
            is Number, is Boolean -> value.toString()
            is Enum<*> -> value.name
            is String -> if (safeToken.matches(value)) value else "REDACTED"
            else -> "REDACTED"
        }
        val details = fields.filterKeys {
            it in this.fields
        }.entries.joinToString(" ") { "${it.key}=${token(it.value)}" }
        destination.offer(
            "wallMs=${System.currentTimeMillis()} monoMs=${System.nanoTime() / 1_000_000} " +
                "process=$process session=$session version=${BuildConfig.VERSION_CODE} " +
                "chapter=${chapter ?: -1} sourceIndex=${source ?: -1} event=${token(event)} $details",
        )
    }
}
