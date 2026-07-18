# Migration authority and reliability boundary

## Authority

The only authority for original Mihon migration behavior is
`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`. The current shared migration code is a
migration output, while `app/` and `app-desktop/` are its current Android and Desktop consumers.
Neither may be cited as evidence that a behavior existed in original Mihon.

## Fixed-main-compatible core

Single-manga migration preserves the fixed-main rules:

- chapter matching uses the first source chapter with the same recognized chapter number;
- the maximum read source chapter is calculated from all read source chapters, including `NaN`;
- a target chapter receives only a nullable `read = true` patch when needed, so existing target
  read state is never cleared;
- bookmark and fetch date are copied from the matching source chapter;
- category membership, chapter/viewer flags, date added, notes, and replace-versus-copy library
  membership follow the fixed-main use case.

Batch migration preserves the fixed-main-compatible sequencing contract: process input in order,
continue after an ordinary item failure, and propagate cancellation. Original Mihon did not have a
durable batch queue, resumable checkpoint, explicit user-decision pause, per-item failure event, or
retry workflow.

## Retained enhancements and adapters

| Layer | Responsibility | Authority classification |
|---|---|---|
| `BatchMigrationOrchestrator` | `startIndex`, `Completed(nextIndex)`, `Failed`, and `WaitingForUser` | Cross-platform reliability enhancement |
| Android runner and failure dialog | Connect shared events to Android job cancellation, progress, failure feedback, and targeted retry | Android platform adapter |
| Desktop controller and queue UI | Persist queue targets/options/status/errors, recover interrupted work, pause/resume/cancel/retry, and expose Test Mode/UI feedback | Desktop product persistence/UI enhancement |

The enhancements are intentionally retained. They must not be described as an extraction of an
original-Mihon checkpoint, waiting-for-user, or retry mechanism.

## Failure and maintenance boundary

`CancellationException` always propagates. The Android batch callback converts non-cancellation
single-migration failures into a `Failed` event for user feedback and targeted retry; standalone
single-manga callers intentionally do not unwrap that result, preserving the pre-existing
non-crashing UI boundary. A newly found difference in the fixed-main-compatible core is a separate
strict-TDD behavior task, not an authority-classification change.
