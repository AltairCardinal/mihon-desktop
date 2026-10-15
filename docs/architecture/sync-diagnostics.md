# Persistent synchronization diagnostics

Android and Desktop inject one `SyncDiagnosticJournal` per application profile in a persistent
files/logs directory. `SyncRuntime`, its run store, onboarding and transport share that instance.
The HTTP context is passed explicitly to each configured production-client wrapper; there is
no global journal sink. An omitted journal preserves the previous behavior and creates no files.

Android uses `Application.filesDir/sync-diagnostics-journal/journal.json`; Desktop uses
`DesktopPaths.logsDir/sync-diagnostics-journal/journal.json`. Export files remain in the
existing diagnostic export directory. Each application profile must use its own journal path.

The journal observes admission and skip reasons, run creation, semantic phase changes, retry
deadlines and terminal state. Authorization, space verification, recovery transitions and old
unverified scopes are observations, not instructions to retry, authorize or alter user data.
HTTP events contain only fixed operation stages, request aliases, failure phase and status.
The application's proxy/TLS configuration, request gates and body progress observers remain
the production path. Network exceptions retain their underlying causes for local diagnostics.

Records exclude URLs, repositories, account identifiers, credentials, headers, bodies, work
titles and exception messages. Exception graphs are bounded to eight nodes with identity-cycle
detection, including causes and suppressed errors, and at most 24 frames per node. Class,
method and source-file names are validated and bounded; source directory paths are discarded.
Only explicitly allowlisted fixed failure reasons may become safe error codes. Unknown errors
retain code locations without exposing their messages.

Run and request identifiers are exported as short SHA-256 aliases derived with a random,
profile-local persisted salt. The salt and raw IDs are never exported. This alias survives
restarts and is independent of the optional, expiring panel-observation session. Logs describe
the version that wrote them through platform/version/source/identity environment information.

`journal.json` is atomically replaced after each accepted observation. A record is at most
16 KiB, there are at most 256 records and the persisted journal is at most 128 KiB.
Successful phase/request noise is evicted before failure evidence. Older
failures eventually rotate if the failure-only history exceeds the limits. `truncated` and
`droppedEvents` expose loss; success never clears earlier failures. Temporary files are removed
after normal write failures. Corrupt or oversized input is read with a strict bound, rejected,
and reported through `readState`; the next write starts a new bounded history with truncation
visible. `writeState` reports write failure while keeping the bounded in-memory observations.

Journal I/O is isolated from exchange results, cancellation, retry policy and business writes.
It is best effort local evidence, not a recovery database or a complete network trace. A process
crash before atomic replacement may lose the newest event. Multiple application profiles must
use separate directories; sharing one directory between concurrent processes is unsupported.
The existing diagnostic JSON export carries a snapshot through the existing user-controlled
sharing path. Automatic local persistence does not automatically send diagnostics anywhere.

Open the synchronization panel, select diagnostics, capture a snapshot, then save its JSON or
copy the diagnostic text. The additive `journal` field carries persisted observations,
per-event build information and `readState`/`writeState`/`truncated`/`droppedEvents` status.
The current/latest run's `journalRunAlias` joins panel facts with historical run evidence;
request aliases join request and response/failure events, and `attemptId` distinguishes retries.
Ending the optional 24-hour panel session clears only that session's association cache and
does not erase the durable journal. Previously lost exceptions cannot be reconstructed.

Validation uses the shared `SyncDiagnosticJournalContract` on JVM and Android, plus focused
JVM tests against real HTTP wrappers, the durable run store, exchange and recovery workflow.
Platform DI/export tests cover the final application graph separately. A browser prototype,
standalone HTTP client or source-text assertion is not evidence for production wiring.
