# Migration authority and reliability boundary

## Authority

The only authority for original Mihon migration behavior is
`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`. The current shared migration code is a
migration output, while `app/` and `app-desktop/` are its current Android and Desktop consumers.
Neither may be cited as evidence that a behavior existed in original Mihon.

## Verified shared plan core and unresolved Desktop debt

The shared `MigrationOrchestrator` plan layer preserves these fixed-main rules:

- chapter matching uses the first source chapter with the same recognized chapter number;
- the maximum read source chapter is calculated from all read source chapters, including `NaN`;
- a target chapter receives only a nullable `read = true` patch when needed, so existing target
  read state is never cleared;
- bookmark and fetch date are copied from the matching source chapter;
- `libraryPlan()` calculates category membership, chapter/viewer flags, date added, notes, and
  replace-versus-copy membership from the fixed-main rules.

That plan-level agreement is not proof that every consumer persists the plan correctly. The
current `DesktopMigrateMangaUseCase` has three confirmed `UNCLASSIFIED_DEBT` differences where the
original implementation is better:

| Desktop behavior | Fixed-main behavior to restore |
|---|---|
| `copyCategories = false` supplies an empty category list to the membership update and therefore clears categories already assigned to the target | Omitting category migration must leave the target's existing categories unchanged |
| `libraryPlan()` calculates target chapter/viewer flags, but the Desktop adapter does not write them | Write the source chapter/viewer flags to the target |
| Copying to an already-favorited target preserves the target's old `dateAdded` | A copy migration (`replace = false`) writes the invocation time, as fixed main does |

These are behavior defects, not platform exemptions or retained enhancements. They require one
independent strict red-green-refactor Task before the single-manga Desktop path, parity manifest
ID 67, or the combined migration capability may be described as fully aligned.

Batch migration preserves the fixed-main-compatible sequencing contract: process input in order,
continue after an ordinary item failure, and propagate cancellation. Original Mihon did not have a
durable batch queue, resumable checkpoint, explicit user-decision pause, per-item failure event, or
retry workflow.

## Retained enhancements and adapters

| Layer | Responsibility | Authority classification |
|---|---|---|
| `BatchMigrationOrchestrator` | `startIndex`, `Completed(nextIndex)`, `Failed`, and `WaitingForUser` | Cross-platform reliability enhancement |
| Failure summary and targeted retry | Turn per-item failures into visible summary state and allow retrying selected failures | Cross-platform/product reliability enhancement |
| Android ScreenModel, Compose, and Job wiring | Connect the shared protocol and reliability state to Android lifecycle, cancellation, and UI | Android platform adapter |
| Desktop controller and queue UI | Persist queue targets/options/status/errors, recover interrupted work, pause/resume/cancel/retry, and expose Test Mode/UI feedback | Desktop product persistence/UI enhancement |

The enhancements are intentionally retained. They must not be described as an extraction of an
original-Mihon checkpoint, waiting-for-user, or retry mechanism.

## Failure and maintenance boundary

`CancellationException` always propagates. Converting non-cancellation item failures into `Failed`,
summarizing them, and enabling targeted retry are reliability behavior shared by product flows;
only the ScreenModel/Compose/Job connection is an Android platform adapter. Standalone
single-manga callers intentionally do not unwrap the result, preserving the pre-existing
non-crashing UI boundary.

IDs 67 and 68 must not be marked complete parity while any known migration behavior debt remains.
The three Desktop defects above must first be fixed by the independent strict-TDD Task and replayed
against fixed main. Only then may ID 67 be promoted from `UNCLASSIFIED_DEBT`; ID 68 may be marked
aligned only when its fixed-main core and explicitly retained reliability deviations are recorded
separately.
