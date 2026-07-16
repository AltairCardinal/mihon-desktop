# Subagent Progress

- Change: `align-sources-extensions`
- Plan: `docs/superpowers/plans/2026-07-15-mihon-source-extension-shared-core.md`
- Review mode: `thorough`

## Completed

- Task 1: complete (`15ef2c3e6`; RED 5/2 expected failures; GREEN 19/19; spotlessCheck PASS; authority evidence corrected by `8ba3d4fa1`)
- Task 2: complete (`2f1d4317a..19675b443`; shared query/reducer, Android/Desktop production wiring, API 36 UI recovery 2/2, independent gate re-review CLEAN 0/0/0, 48/48 focused tests PASS)
- Task 3: complete (`0502b755fb`, fixes `3ba6171f0` + `b6345f989`; final independent thorough review APPROVED/APPROVED, 0 Critical/Important/Minor; final forced matrix 70/70 PASS; spotless/diff checks PASS)
- Task 4A: complete (`5b18fc74b`, fixes `b2e299657` + `ce81eec65` + `7cd8a609c`; final independent thorough review APPROVED/APPROVED, 0 Critical/Important/Minor; forced 39/39 PASS; Spotless/diff/platform checks PASS)
- Task 4B: complete (`2ee4ac8bf`, fixes `663b1e686` + `fe4da6f57` + `d3543c933`; final independent thorough review APPROVED/APPROVED, 0 Critical/Important/Minor; forced 90/90 PASS; root Spotless/diff/platform checks PASS)

## Task 4A Review History

- Reviewed task: `Task 4A：共享安装事务状态机`
- Plan checkbox: complete
- OpenSpec mappings:
  - `1.3 为 JAR、APK→JAR、损坏产物、版本替换、回滚与不兼容 API 写 RED 测试`（事务/回滚部分）
  - `2.2 提取扩展发现、版本、安全、安装/更新事务与回滚状态到共享层`（事务/回滚部分）
  - `2.3 定义共享模型、状态机、port 和平台 adapter 边界`
  - `3.1 对 Android/Desktop 安装更新建立一致的事务状态`
  - `3.2 实现仓库身份/摘要信任、下载校验与 reload 失败原子回滚`（原子回滚部分）
- Stage: `complete`
- Task base: `209439e8769c983beec244a6fef4e93d247a2ac8`
- Implementer: `/root/align_sources_task4a_impl`
- Implementation commit: `5b18fc74bc10d2b963525d972392fe0d6d1e73a0`
- Changed files: 3 plan-listed files only.
- RED evidence: `ExtensionInstallCoordinatorTest` compile failed only because coordinator/port/state API was absent.
- GREEN evidence: Task 4A 9/9 + shared extension regression 19/19 PASS; `:domain:spotlessCheck` and staged diff check PASS.
- Review package: `.superpowers/sdd/align-sources-task-4a-review.diff`
- Reviewer: `/root/align_reader_plan` (independent read-only fallback; fresh-thread dispatch was blocked by platform thread limit)
- Review result: `CHANGES_REQUIRED` for spec compliance and code quality; Critical 2, Important 3, Minor 0. Full findings: `.superpowers/sdd/align-sources-task-4a-review.md`.
- Required fixes: pre-commit rollback ownership; retain package flight until compensation/cleanup ends; make cleanup completion non-cancellable and retry-safe; linearize terminal removal; cover commit/cancel/cleanup/recovery/multi-collector races.
- Dispatch fallback: platform thread limit prevented a fresh reviewer/fixer thread; reviewer remained distinct from implementer, and repair is returned to the original Task 4A implementer per the base Subagent-Driven repair loop.
- Fix commit: `b2e299657867d71b6188e2d4f6085c2767608a50`
- Fix evidence: Task 4A 17/17 + shared contract 19/19 PASS; domain Spotless, diff check, platform-boundary scan PASS.
- Re-review package: `.superpowers/sdd/align-sources-task-4a-rereview.diff`
- Re-review result: `CHANGES_REQUIRED`; original 2 Critical + 3 Important closed, new Critical 0 / Important 1 / Minor 1. Full findings: `.superpowers/sdd/align-sources-task-4a-rereview.md`.
- Round 2 required fix: a cancelled injected scope must not leave a never-started LAZY worker/dead package flight; termination/removal/completion must be exactly-once even if worker body never starts.
- Round 2 minor: document idempotent cleanup, missing-target success, and rollback snapshot release responsibility in the port contract.
- Round 2 fix commit: `ce81eec65a5d104d208adad40ba6421f0630e65e`
- Round 2 evidence: Task 4A 19/19 + shared contract 19/19 PASS; domain Spotless, diff check, and platform-boundary scan PASS.
- Final review package: `.superpowers/sdd/align-sources-task-4a-final-review.diff`
- Final review result: `CHANGES_REQUIRED`; previous dead-flight Important and cleanup Minor closed, but a new Important remains. Full report: `.superpowers/sdd/align-sources-task-4a-final-review.md`.
- Blocking finding: after the worker body has started, external scope cancellation lets the body `finally` win exactly-once finalization with `terminalState == null`; the later completion handler cannot publish `Failed(AppError.Cancelled)`, so an independent active collector ends without a terminal state.
- User override: explicitly authorized one additional repair/re-review round because the remaining cancellation race is narrowly scoped and assessed as fixable without replanning.
- Round 3 required fix: add a deterministic RED case where the worker body has entered a suspending port call, the coordinator scope is cancelled while an independent collector remains active, and the collector must receive exactly one `Failed(AppError.Cancelled)` before finite completion; then make cancellation participate in the exactly-once terminal decision.
- Round 3 fix commit: `7cd8a609c3b032164edc2055440082aa58fb82f8` (`fix(extension): emit cancelled install terminal`).
- Round 3 evidence: deterministic RED 1/1 failed with only `[Preparing, Validating]`; GREEN Task 4A 20/20 + shared contract 19/19 = 39/39 PASS; domain Spotless, diff check, and platform-boundary scan PASS.
- Extra re-review package: `.superpowers/sdd/align-sources-task-4a-extra-review.diff` (base `33ca9aa49`, head `7cd8a609c`).
- Extra re-review result: Spec compliance APPROVED; Code quality APPROVED; Critical/Important/Minor = 0/0/0. The prior remaining Important is CLOSED. Report: `.superpowers/sdd/align-sources-task-4a-extra-review.md`.
- Coordinator forced verification: `:domain:spotlessCheck` plus Task 4A 20/20 and shared contract 19/19 passed with `--rerun-tasks` (39/39 total).
- Review/fix round: 3/3

## Task 4B Review History

- Reviewed task: `Task 4B: Desktop install port 与 reload 回滚`
- Plan checkbox: complete
- Stage: `complete`
- Task base: `1ba46fd3005f85a15e296ad7ef2dc760c8bdfe4f`
- Implementer: `/root/task4b_impl`
- Brief: `.superpowers/sdd/align-sources-task-4b-brief.md`
- Report: `.superpowers/sdd/align-sources-task-4b-report.md`
- Implementation commit: `2ee4ac8bfd18c60e5948ab6fc88316cb57759a9a` (`refactor(desktop): adapt transactional extension install`).
- Changed files: 5 plan-listed files; +580/-141, net +439. Plan records a concrete Split waiver for the inseparable production port and seven-scenario transaction integration test.
- RED evidence: production MockWebServer/API path rejected expectation failed because the old implementation returned Success for a wrong-package JVM JAR (1/1 expected failure).
- GREEN evidence: transaction 7/7; brief matrix 13/13; related API/Manager/Loader regression 45/45; root Spotless, diff check, and platform-boundary check PASS.
- Review package: `.superpowers/sdd/align-sources-task-4b-review.diff` (base `1ba46fd30`, head `2ee4ac8bf`).
- Reviewer: `/root/task4b_review` (independent, read-only).
- Review result: Spec compliance CHANGES_REQUIRED; Code quality CHANGES_REQUIRED; Critical 3 / Important 3 / Minor 1. Report: `.superpowers/sdd/align-sources-task-4b-review.md`.
- Repair scope decision: the brief's API-only-provider boundary cannot be satisfied while the production UI continues to call API installation and reload the DI Manager outside the transaction. Under the user's standing no-pause authorization, keep the same change/risk axis/platform boundary and expand Task 4B only to the necessary Desktop UI/DI wiring and integration protection; do not create a new change.
- Repair requirements: reuse the real long-lived DI Manager/Coordinator; preserve same-package single-flight; close candidate/old ClassLoaders safely; contain all package-derived paths; reject non-loadable/wrong-provider JAR/APK; make two-file commit/rollback retry-safe; preserve structured errors; replace false-positive tests with real production-wiring, concurrency, cancellation, path, failure-injection, and mutation coverage.
- Review/fix round: 1/2
- Fix commit: `663b1e6863b5e3445d136e874afd185d59ed2145` (`fix(desktop): harden transactional extension installs`).
- Fix scope: 11 approved Desktop production/test files, +852/-190 for the repair; cumulative Task 4B +1297/-196, net +1101. The plan and brief record the concrete review-repair Split waiver.
- Fix evidence: 5 deterministic Critical RED failures plus DI compile RED; Critical GREEN 6/6; transaction/replacement/DI 24/24; expanded related regression 56/56; product baseline 5/5; three reversible mutations were killed and fully restored; root Spotless, diff check, platform-boundary check PASS.
- Re-review package: `.superpowers/sdd/align-sources-task-4b-rereview.diff` (cumulative base `1ba46fd30`, head `663b1e686`).
- Re-review result: Spec compliance CHANGES_REQUIRED; Code quality CHANGES_REQUIRED; Critical 1 / Important 3 / Minor 1. C2, C3, and original M1 are CLOSED; C1, I1, I2, and I3 remain partially open. Report: `.superpowers/sdd/align-sources-task-4b-rereview.md`.
- Coordinator forced verification after repair 1: the original command outlived its 184-second shell timeout but its Gradle child completed and produced 10 fresh XML suites with 66/66 tests, 0 failures/errors/skipped; a separate root Spotless invocation exited 0.
- Round 2 requirements: serialize install file/runtime lifecycle against public reload/remove/close; release the newly loaded runtime before rollback after cleanup failure; make cleanup/prepare journal recovery non-consuming and retry-safe; distinguish network from local Storage and map hash/runtime rejection precisely; add real coroutine cancellation, cleanup-after-reload, external reload race, partial-delete, and mutation coverage; reject unsupported atomic move rather than silently weakening atomicity.
- Review/fix round: 2/2
- Round 2 fix commit: `fe4da6f578a22751f9126e764f0e56e2292a881e` (`fix(desktop): complete extension transaction recovery`).
- Round 2 evidence: 13 target RED cases, 12 expected failures and one already-passing prepare-cancel regression; close-during-prepare RED 1/1 failed then GREEN; final transaction/replacement and extended production matrix 81/81 PASS; five targeted mutations were killed and fully restored; root Spotless, diff check, and platform-boundary check PASS.
- Final review package: `.superpowers/sdd/align-sources-task-4b-final-review.diff` (cumulative base `1ba46fd30`, head `fe4da6f57`).
- Final review result after repair 2: Spec compliance CHANGES_REQUIRED; Code quality CHANGES_REQUIRED; Critical 1 / Important 1 / Minor 1. I1, I2, and M1-new are CLOSED; C1 and I3 remain narrowly open because the install permit is acquired after the old-state snapshot; new Important: public load/reload/remove remain allowed after close; Minor: residue assertions omit `.recovery-*`. Startup crash recovery is explicitly outside this Task's blocker scope. Report: `.superpowers/sdd/align-sources-task-4b-final-review.md`.
- Coordinator forced verification after repair 2: root Spotless plus 10 related classes completed BUILD SUCCESSFUL; 81/81 tests, 0 failures/errors/skipped; diff check PASS.
- User-authorized extra round: apply engineering judgment rather than stop mechanically at 2/2. The remaining findings are a narrow lifecycle linearization fix plus terminal-state/residue tests and are assessed as closable in one round.
- Round 3 requirements: acquire and retain the install lifecycle window before reading old-state existence or writing the recovery archive; reject queued/new public load/reload/remove after close while preserving a dedicated shutdown resource-release path; include `.recovery-*` in residue assertions and make archive-delete failure observable/cleanable within the in-process contract.
- Review/fix round: 3/3
- Round 3 fix commit: `d3543c9332a612412d7621283b398c630f2dd86e` (`fix(desktop): serialize extension snapshots and shutdown`).
- Round 3 evidence: snapshot/remove RED 1/1 failed; close terminal 4/5 expected failures with install-after-close already correct; recovery residue mutation RED 1/1 failed; GREEN transaction 40/40 and full related matrix 90/90; three mutations killed and restored; root Spotless, diff and platform checks PASS.
- Extra final review package: `.superpowers/sdd/align-sources-task-4b-extra-final-review.diff` (cumulative base `1ba46fd30`, head `d3543c933`).
- Extra final review result: Spec compliance APPROVED; Code quality APPROVED; Critical/Important/Minor = 0/0/0. C1, N1, and M1 are CLOSED. Report: `.superpowers/sdd/align-sources-task-4b-extra-final-review.md`.
- Coordinator forced verification after repair 3: root Spotless plus 10 related classes completed BUILD SUCCESSFUL; 90/90 tests, 0 failures/errors/skipped; diff check PASS.
- Final cumulative scope: 11 Desktop files, +2178/-205 (2383 changed lines, net +1973). The concrete Split waiver remains applicable; startup crash recovery is an explicit non-blocking future hardening boundary.

## Current

- Current task: `Task 4C: Android PackageInstaller adapter wiring`
- Plan checkbox: pending
- Stage: `ready`
