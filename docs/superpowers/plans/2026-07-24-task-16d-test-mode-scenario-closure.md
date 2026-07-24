---
parent-plan: docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md
parent-task: Task 16D
original-ref: main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8
status: planned
active-task: Task 171
---
# Test Mode scenario closure
本计划只关闭 Task16D inventory 中 9 个 Desktop TestMode wiring/runner gap 和缺失的最终固定 EXE runner；ID3 产品闭环仍归 Task141/142，本计划只消费其完成产物，并按 171→177 串行执行。
- [ ] Task 171：Final fixed-EXE runner
- [ ] Task 172：Library and manga-detail actions
- [ ] Task 173：Browse search and source login
- [ ] Task 174：Downloads, updates and history actions
- [ ] Task 175：Backup and settings actions
- [ ] Task 176：Tracking HTTP runner
- [ ] Task 177：Scenario inventory closeout
### Task 171 Final fixed-EXE runner
**Risk axis:** final-testmode-runner
**Platform boundary:** verification
**Estimated scope:** 5 files, 360 lines
**Verification:** `scripts/desktop-final-parity-test.sh` uses the current build script's fixed unpacked EXE, fails on stale/missing artifact, and reports exact 13/13, 5/5 and unmapped=0.
**Files:** final runner, test client summary model, runner contract, automation guide, tracker.
**TDD:** first make missing/stale EXE and incomplete family/protection summaries RED; minimally add deterministic startup, polling, teardown and exact result aggregation.
**User/feedback:** one CLI entry displays per-family status, permanent protections, unmapped IDs and actionable startup/timeout failures; it never opens system UI.
### Task 172 Library and manga-detail actions
**Risk axis:** testmode-library-detail
**Platform boundary:** desktop
**Estimated scope:** 8 files, 400 lines
**Verification:** library search/filter/sort/select and detail membership/category/chapter/cover/download actions cross HTTP into production screen-model/use-case ports and expose resulting rows or typed failures.
**Files:** TestHttpServer dispatcher, library/detail observation ports, owner wiring, HTTP integration tests and runner assertions.
**TDD:** each current no-op action first REDs on unchanged production state; wire the smallest owner-scoped port and prove closed/unavailable behavior.
**User/feedback:** library/detail scenario reports visible rows, navigation, mutations, partial failure and unavailable owner rather than unconditional success.
### Task 173 Browse search and source login
**Risk axis:** testmode-browse-login
**Platform boundary:** desktop
**Estimated scope:** 5 files, 320 lines
**Verification:** depends on completed Task141 and Task142 artifacts; global search, result selection, source login start/complete/cancel and Cloudflare recovery traverse existing Desktop owners through TestMode adapters with typed HTTP feedback.
**Files:** Desktop TestMode/HTTP code under `app-desktop/src/main/kotlin/mihon/desktop/test/**`, plus HTTP/coverage tests under `app-desktop/src/test/kotlin/mihon/desktop/test/**` and `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopTestModeCoverageContractTest.kt` only.
**TDD:** after Task141/142 complete, first RED on `UNSUPPORTED_ACTION` and cancel-only HTTP evidence; add only Desktop TestMode dispatch/observation wiring and preserve stale-token and owner-close mutations.
**User/feedback:** scenario exposes loading/results/navigation plus login success, rejection, cancellation and recovery errors.
### Task 174 Downloads, updates and history actions
**Risk axis:** testmode-queue-history
**Platform boundary:** desktop
**Estimated scope:** 8 files, 400 lines
**Verification:** download queue controls, updates/upcoming actions and history search/remove/clear call DI-owned production handlers and publish queue/row/error state.
**Files:** three owner ports, TestHttpServer dispatcher/state projection, DI lifecycle wiring and HTTP tests.
**TDD:** existing TestState-only/no-op branches RED against unchanged managers/models; wire one owner-scoped port per context and reject calls after close.
**User/feedback:** scenario reports queue progress/failures, update rows/read state, upcoming navigation and history removal outcomes.
### Task 175 Backup and settings actions
**Risk axis:** testmode-backup-settings
**Platform boundary:** desktop
**Estimated scope:** 8 files, 400 lines
**Verification:** backup create/restore and settings search/security/platform maintenance actions execute production workflows with confirmation, progress, cancellation and typed failure feedback.
**Files:** backup workflow port, settings/platform action port, owner wiring, TestHttpServer dispatcher and HTTP tests.
**TDD:** unconditional success branches RED first; then bind production workflows and mutation-test unavailable owner, cancellation and rejected dangerous actions.
**User/feedback:** scenario exposes backup path/progress/partial failure and settings result/navigation/security/platform capability feedback.
### Task 176 Tracking HTTP runner
**Risk axis:** testmode-tracking-http
**Platform boundary:** desktop
**Estimated scope:** 4 files, 260 lines
**Verification:** tracking login/search/bind/update/logout traverse `/test/action/tracking_*`, DI controller and serialized state; missing controller and invalid tracker return typed HTTP failures.
**Files:** TestHttpServer tracking error mapping, controller lifecycle wiring, HTTP integration test, test client.
**TDD:** direct controller coverage is insufficient; first RED on missing HTTP state/error assertions, then reuse the existing controller without a second tracking implementation.
**User/feedback:** scenario exposes login state, result count, binding/progress and actionable invalid/unavailable errors.
### Task 177 Scenario inventory closeout
**Risk axis:** testmode-inventory-closeout
**Platform boundary:** verification
**Estimated scope:** 6 files, 340 lines
**Verification:** all 13 family rows become covered, ID3 has a real production consumer or approved gap, 5 protections stay covered, 64 IDs remain mapped once and the final runner reports exact zero gaps.
**Files:** coverage inventory/contract, parity manifest/contract, parent plan and runner summary test.
**TDD:** expected gaps and unmapped sets remain RED until 171–176 close; rerun removal/duplicate/unknown/disconnected-handler/missing-protection mutations before handoff.
**User/feedback:** no new UI; the final report truthfully distinguishes covered, non-UI, unsupported and failed runtime evidence.
