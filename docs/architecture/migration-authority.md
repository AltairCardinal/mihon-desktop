# Migration authority and reliability boundary

## Authority

The only authority for original Mihon migration behavior is
`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`. The current shared migration code is a
migration output, while `app/` and `app-desktop/` are its current Android and Desktop consumers.
Neither may be cited as evidence that a behavior existed in original Mihon.

## Shared plan and atomic storage boundary

`MigrationOrchestrator` preserves the fixed-main matching and metadata rules: recognized
chapter numbers (including fractional numbers) use the first matching source chapter; the
maximum read chapter includes the shared unknown/NaN behavior; read patches never clear an
already-read target; bookmark and fetch date come from the matched chapter. `libraryPlan()`
provides flags, categories, notes, date added and copy-versus-replace membership.

Both production consumers call `MangaRepository.commitMigration`. Its single SQLDelight
transaction rechecks source/target ID, source ID and URL, reads current unfiltered chapters
and memberships, and applies the shared plan together with chapter/favorite synchronization
journals and creator membership indexing. Target manga and chapters keep independent IDs.
Omitted categories and notes retain the target's latest values. Source chapter/viewer flags
are persisted; copy uses the accepted invocation time while replace preserves source date
added. A rejected chapter or membership write rolls back all selected SQL changes.

The target's source directory is fetched outside this transaction through the existing
source-save/directory core. Self migration is rejected before target saving and again in the
transaction. Network failure cannot remove the original favorite. SQLite cannot store NaN
chapter numbers; the shared plan contract protects that rule separately from actual storage.

The former three Desktop defects (cleared unselected categories, omitted flags and stale copy
date added) are resolved by this storage consumer. This reliability transaction is a project
enhancement; it is not a claim that fixed main used the same transaction.

Batch migration retains ordered processing, continuation after ordinary failure and propagated
cancellation. The durable queue, user-decision pause, per-item errors and targeted retries are
retained project enhancements rather than original-Mihon mechanisms.

## Retained enhancements and adapters

| Layer | Responsibility | Authority classification |
|---|---|---|
| `BatchMigrationOrchestrator` | `startIndex`, `Completed(nextIndex)`, `Failed`, and `WaitingForUser` | Cross-platform reliability enhancement |
| Failure summary and targeted retry | Turn per-item failures into visible summary state and allow retrying selected failures | Cross-platform/product reliability enhancement |
| Android ScreenModel, Compose, and Job wiring | Connect the shared protocol and reliability state to Android lifecycle, cancellation, and UI | Android platform adapter |
| Desktop controller and queue UI | Persist queue targets/options/status/errors, recover interrupted work, pause/resume/cancel/retry, and expose Test Mode/UI feedback | Desktop product persistence/UI enhancement |

The enhancements are intentionally retained. They must not be described as an extraction of an
original-Mihon checkpoint, waiting-for-user, or retry mechanism.

## Desktop finite file and checkpoint boundary

Desktop accepts one immutable confirmation before target HTTP: original manga identity,
options, cover bytes, existing bounded canonical/legacy download artifacts and process-local
attempt markers. Late files are not added. A queued/active/retiring affected chapter, later
accepted generation, active reading lease or conflicting file causes a visible rejection,
without cancelling producers or deleting downloads. Unselected file options do not reserve
unrelated work.

`MigrationFileStaging` stores only this operation's finite sidecar and private staged objects.
The optional `MigrationReceipt` extends the existing local directory phase; it is not a global
outbox, backup format or synchronization protocol. The prepared receipt is durable before file
moves. The committed flag and original result/date are written with the SQL transaction.
Ordinary directory finish/ACK paths cannot consume a migration receipt.

Moves must be no-replace. Windows uses `MoveFileW` with the Windows calling convention;
macOS uses `renamex_np(RENAME_EXCL)`. Unsupported/nonatomic moves are rejected rather than
silently replaced by destructive copy/delete. macOS ABI source verification does not substitute
for the formal platform runtime acceptance. Custom cover preparation uses the actual cover
store's reservation; rollback never overwrites a later file or cover. SQL rejection restores the
original finite files. Postcommit cleanup only removes the operation's private objects.

A cancellation after durable SQL reads the committed receipt in `NonCancellable` and preserves
installed files instead of rolling back an already-committed result. A prepared restart restores
its original finite files and aborts that confirmation; it cannot recapture newer generations.
Missing or conflicting recovery lists keep the receipt and expose an error. A committed cleanup
failure can be closed while retaining the receipt, and retry finishes the original operation
without another HTTP request, SQL copy or date reassignment.

For batch work, an accepted operation belongs to the original task/item/target. The actual
persisted SUCCESS cursor must match that operation before ACK. An idempotent unchanged cursor
is accepted by readback, rather than treating the scheduler's `changed = false` as refusal.
Cancelled tasks remain Cancelled even when an already-committed item is truthfully recorded as
SUCCESS. Failed checkpoint writes retain the committed receipt; retry does not repeat migration.
A cancelled queue exposes Continue cleanup for the original accepted operation. The same
finite callback also retries a rejected precommit rollback; its recovery feedback therefore
does not claim that an uncommitted migration succeeded. It never reopens a cancelled task.
Private cleanup runs only after committed/filesComplete guards and before SQL ACK, so cleanup
or ACK rejection cannot erase the recovery anchor. Successful ACK and precommit cancellation
release private captures; unresolved rollback/cleanup preserves them.

Production DI recovers prepared and committed migration file phases before starting the download
worker. Recovery rejection blocks this initialization rather than allowing destructive work past
an unresolved record. Runtime stop waits for the original noncancellable migration completion
before closing its database. This is a finite startup/confirmation boundary, not a background
recovery scheduler.

## Android and maintenance limits

Android consumes the same atomic migration SQL and shared plan, and propagates directory and
commit failures. Its existing CoverCache/DownloadManager/tracker platform stages are retained.
They do not provide Desktop's finite staging, crash recovery or cross-file/SQL atomic guarantee;
shared storage contract tests must not be cited as proof of Android file atomicity.

`CancellationException` propagates. Desktop ordinary item errors continue the batch and remain
visible/retryable. Original operation identities and checkpoint ownership must be checked on
all recovery/finish/cancel paths. File cleanup never rescans source directories, takes a newer
response, or replaces captured paths with the current queue generation. Keep ordinary directory
and library-update receipt tests when changing this local phase extension.
