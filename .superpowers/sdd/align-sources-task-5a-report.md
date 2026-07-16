# Task 5A Implementation Report

## Scope

- Added the shared `SourceLoginSession` contract and state machine for browser open, controlled Cookie completion, cancellation, timeout, validation, atomic commit, and redacted diagnostics.
- Added the Desktop AWT browser adapter. It only opens the URL; it does not claim access to browser-private Cookie storage. Cookies enter through `DesktopBrowserLoginCompletion` using an opaque session ID.
- Added `DesktopAuthenticatedSessionCommitter`, which converts one complete validated shared session and calls the real `DesktopCookieJar` once.
- Added transactional `DesktopCookieJar.commitAuthenticatedSession`: the complete request-host set is swapped under one lock, serialized to a sibling temp file, and atomically replaces the target. Persistence failure restores the previous in-memory set and leaves the previous target file unchanged.
- Preserved response merge, expiry filtering, legacy persistence loading, manual Cookie operations, and `clearDomains` behavior.
- Did not add challenge UI, preferences, interceptor behavior, or FlareSolverr policy.

Actual product/test scope: 6 files, 794 changed lines (four new files with 622 lines plus tracked `+139/-33`). The estimate was exceeded because the shared state contract, Desktop controlled-completion adapter, atomic persistence implementation, behavior tests, and required mutation guards form one security/atomicity boundary: splitting the state contract from its real jar integration would leave an independently passing task that could not prove zero partial credential writes.

## TDD Evidence

### RED

The brief command was run before production implementation:

```text
./gradlew :domain:jvmTest --tests "tachiyomi.domain.source.service.SourceLoginSessionTest" :app-desktop:jvmTest --tests "mihon.desktop.network.DesktopBrowserLoginAdapterTest" :core:common:jvmTest --tests "eu.kanade.tachiyomi.network.DesktopCookieJarTest" --no-parallel
```

It failed for the intended missing behavior:

- shared request/state/session/browser/committer types were unresolved;
- `DesktopBrowserLoginAdapter` and its controlled completion seam were absent;
- `DesktopCookieJar` lacked the persistence-replacement seam and `commitAuthenticatedSession`.

This was a compile RED caused by absent product capability, not Android SDK or environment setup.

A later TDD cycle added `coroutine cancellation during commit is propagated`: it failed because the initial broad exception mapping converted cancellation into `CommitFailed`, then passed after the minimal cancellation-specific rethrow.

### GREEN and regression

Fresh final command:

```text
./gradlew :domain:jvmTest --tests "tachiyomi.domain.source.service.SourceLoginSessionTest" :app-desktop:jvmTest --tests "mihon.desktop.network.DesktopBrowserLoginAdapterTest" :core:common:jvmTest --tests "eu.kanade.tachiyomi.network.DesktopCookieJarTest" --tests "eu.kanade.tachiyomi.network.DesktopCookieJarPersistenceTest" spotlessCheck --no-parallel --rerun-tasks
```

Result: `BUILD SUCCESSFUL`, 97 tasks executed.

- `SourceLoginSessionTest`: 9 tests, 0 failures/errors/skips.
- `DesktopBrowserLoginAdapterTest`: 5 tests, 0 failures/errors/skips.
- `DesktopCookieJarTest`: 13 tests, 0 failures/errors/skips.
- `DesktopCookieJarPersistenceTest`: 8 tests, 0 failures/errors/skips.
- Total: 35 tests, 0 failures/errors/skips.
- Root `spotlessCheck`: passed.
- `git diff --check`: passed.

The first attempted module-scoped formatting command named `:app-desktop:spotlessCheck`, which does not exist. No tests ran in that invocation. The project-supported root `spotlessCheck` was then used. Its first run found only import ordering in the new domain test; after the mechanical correction, the fresh combined command above passed.

## Mutation Evidence

Each temporary mutation failed a named behavior test and was restored before final verification:

1. Removed required-Cookie validation → `missing required cookie rejects the whole session without commit` failed.
2. Removed host/domain filtering → `unrelated and child-domain cookies reject the whole session` failed.
3. Allowed cancelled completion to commit → `cancelled login performs zero commits` failed.
4. Allowed timeout to commit → `timeout under virtual time cancels browser session and performs zero commits` failed.
5. Split a session into per-Cookie jar replacements → `opens external browser then commits controlled completion through the real jar` failed because only the final Cookie survived.
6. Wrote the persistence target directly instead of invoking atomic replacement → `authenticated session replaces the complete host set with one atomic persistence` failed.

## Remaining Boundary

- Task 5A provides production contracts, the AWT open adapter, controlled Cookie completion, and real jar commit behavior. It intentionally does not wire a user-facing challenge/login UI; that belongs to the later UI/wiring task.
- AWT external browsers cannot expose their private Cookie stores. A later UI/manual/solver adapter must explicitly call the controlled completion seam with a complete session; there is no automatic capture claim or hidden fallback.
- Existing project compiler warnings outside the six Task 5A files remain unchanged and were not modified.
