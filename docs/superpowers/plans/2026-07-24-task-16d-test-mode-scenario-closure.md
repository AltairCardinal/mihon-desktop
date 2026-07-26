---
parent-plan: docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md
parent-task: Task 16D
original-ref: main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8
status: planned
---
# Test Mode scenario closure
本计划只关闭 Task16D inventory 中 9 个 Desktop TestMode wiring/runner gap 和缺失的最终固定 EXE runner；ID3 产品闭环仍归 Task141/142，本计划只消费其完成产物，并按 171→177 串行执行。
- [x] Task 171：Final fixed-EXE runner
- [x] Task 172：Library and manga-detail actions
- [x] Task 173：Browse search and source login
- [x] Task 174：Downloads, updates and history actions
- [x] Task 175：Backup and settings actions
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
**Execution evidence:** runner contract 首先因入口缺失 5/5 RED；GREEN 后 fixed EXE 与 Task151 provenance sidecar/verifier fail-closed，启动前拒绝旧 health owner，ready 后绑定本轮 PID，fake process 轮询/teardown、严格 summary schema 与 missing/rejected/stale/incomplete/timeout 分支共 `10/10`。唯一审查的默认 client、旧 health 冒充、mtime provenance 与 schema traceback 问题均以精确 RED→GREEN 修复；主代理再补 provenance 恢复命令 `./scripts/build-desktop.sh evidence` 的 `2` 项 RED→GREEN。coverage contract `2/2`、Bash/Python 语法、Spotless 与 diff 全绿。runner 从唯一 inventory 汇总 13/13、5/5、64/64 与 `unmapped=0`，当前 9 个场景 gap 诚实返回非终态，交 Tasks 172–176 关闭。
### Task 172 Library and manga-detail actions
**Risk axis:** testmode-library-detail
**Platform boundary:** desktop
**Estimated scope:** 8 files, 400 lines
**Verification:** library search/filter/sort/select and detail membership/category/chapter/cover/download actions cross HTTP into production screen-model/use-case ports and expose resulting rows or typed failures.
**Files:** TestHttpServer dispatcher, library/detail observation ports, owner wiring, HTTP integration tests and runner assertions.
**TDD:** each current no-op action first REDs on unchanged production state; wire the smallest owner-scoped port and prove closed/unavailable behavior.
**User/feedback:** library/detail scenario reports visible rows, navigation, mutations, partial failure and unavailable owner rather than unconditional success.
**Execution evidence:** search 首先以 HTTP `200` 但真实 DI-owned `LibraryScreenModel.searchQuery` 不变精确 RED；独立审查发现 repository rows 与 detail chapters 被测试 setter 掩盖后，分别以真实 `libraryMangaFlow()` 缺行和 production `mangaWithChaptersFlow()` 缺章节 RED，修复为 owner-scoped 持续收集并移除测试手工注入。复审确认原两个 P1 关闭，并发现 READY 后断流 stale owner、失败重选与 close cancellation 边界；主代理以 3 项精确 RED 补齐 load-state 门控、旧 owner 清理和 typed cancellation，controller 最终 `9/9`。`LibraryMangaTestModeHttpTest`、`DesktopDiWiringTest`、coverage contract 与 Spotless 合并门禁通过；inventory 的 library/manga-detail 两族转为 covered，当前为 6/13 covered、7 gap，64 项仍唯一映射且 unmapped=0。
### Task 173 Browse search and source login
**Risk axis:** testmode-browse-login
**Platform boundary:** desktop
**Estimated scope:** 5 files, 320 lines
**Verification:** depends on completed Task141 and Task142 artifacts; global search, result selection, source login start/complete/cancel and Cloudflare recovery traverse existing Desktop owners through TestMode adapters with typed HTTP feedback.
**Files:** Desktop TestMode/HTTP code under `app-desktop/src/main/kotlin/mihon/desktop/test/**`, plus HTTP/coverage tests under `app-desktop/src/test/kotlin/mihon/desktop/test/**` and `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopTestModeCoverageContractTest.kt` only.
**TDD:** after Task141/142 complete, first RED on `UNSUPPORTED_ACTION` and cancel-only HTTP evidence; add only Desktop TestMode dispatch/observation wiring and preserve stale-token and owner-close mutations.
**User/feedback:** scenario exposes loading/results/navigation plus login success, rejection, cancellation and recovery errors.
**Residual repair batch Task 173R:** Task173 的唯一修复复审已确认 generation lease 问题关闭，但发现全局 browse controller 与普通 SourceBrowse login port 共存时缺少路由回退。173R 只允许修改 TestMode HTTP 路由及共存测试：browse controller 没有活动 login port、返回 `null` 时回退 `SourceBrowseTestModeBridge.port`；返回 `STALE_GENERATION` 等 typed 结果时禁止回退。该残余批次独立审查通过后才勾选 Task173。
**Execution evidence:** `browse_search` 首先因旧硬编码 `UNSUPPORTED_ACTION` 取得精确 RED；实现复用 `DesktopGlobalSearchCoordinator`、`SourceBrowseRecoveryController`、`DesktopSourceLoginController` 与 production save/login ports，HTTP 观察 loading/current rows、选择导航、认证 recovery、start/complete/cancel、拒绝和 owner close。保存拒绝从 500 空体以 RED→GREEN 收敛为 typed `OPERATION_REJECTED`。唯一审查发现 login token 未绑定 generation；修复以 G→G+1 旧 complete/cancel 及受控并发发布 RED，最终保证 `STALE_GENERATION`、零提交/零退休重试。修复复审确认 generation P1 关闭后发现双 bridge 共存回退缺口，按本节 Task 173R 最小重规划；173R 先证明 null-result 错误 503、typed stale 已正确 409，再仅增加 null-result fallback，独立审查 `APPROVED`、无 P0/P1/P2。`BrowseSearchTestModeHttpTest`、`SourceLoginTestModeHttpTest`、既有 wiring、DI、coverage 与 Spotless 主门禁通过；inventory 现为 7/13 covered、6 gap，64/64 mapped、unmapped=0。
### Task 174 Downloads, updates and history actions
**Risk axis:** testmode-queue-history
**Platform boundary:** desktop
**Estimated scope:** 8 files, 400 lines
**Verification:** download queue controls, updates/upcoming actions and history search/remove/clear call DI-owned production handlers and publish queue/row/error state.
**Files:** three owner ports, TestHttpServer dispatcher/state projection, DI lifecycle wiring and HTTP tests.
**TDD:** existing TestState-only/no-op branches RED against unchanged managers/models; wire one owner-scoped port per context and reject calls after close.
**User/feedback:** scenario reports queue progress/failures, update rows/read state, upcoming navigation and history removal outcomes.
**Execution evidence:** Downloads、Updates/Upcoming、History 三个 HTTP family 均先以旧 no-op/TestState 行为取得精确 RED，再接入 `DesktopDownloadManager`、`UpdatesScreenModel`、`HistoryScreenModel` 的真实 production owner。`DownloadTestModeHttpTest`、`UpdatesTestModeHttpTest`、`HistoryTestModeHttpTest` 覆盖队列控制/失败/排序边界、更新加载/筛选/已读/下载/Upcoming 与 Reader 导航、历史搜索/删除/清空/Reader 导航及 typed unavailable/closed/rejected/partial failures。初审发现启动水合、破坏性 index 定位和伪 `date_added` 排序问题；唯一修复以 `TestModeTimelineHydrationTest` 及下载 HTTP 测试分别 RED→GREEN，改为 server 暴露前真实水合、仅稳定 `chapterId` 取消，并对无真实语义的 `date_added` 返回 `INVALID_PARAMETER`。唯一修复复审 `APPROVED`、无 P0/P1/P2；组合 focused、DI/coverage、Spotless 与 diff check 通过。inventory 现为 10/13 covered、3 gap，64/64 mapped、unmapped=0。
### Task 175 Backup and settings actions
**Risk axis:** testmode-backup-settings
**Platform boundary:** desktop
**Estimated scope:** 8 files, 400 lines
**Verification:** backup create/restore and settings search/security/platform maintenance actions execute production workflows with confirmation, progress, cancellation and typed failure feedback.
**Files:** backup workflow port, settings/platform action port, owner wiring, TestHttpServer dispatcher and HTTP tests.
**TDD:** unconditional success branches RED first; then bind production workflows and mutation-test unavailable owner, cancellation and rejected dangerous actions.
**User/feedback:** scenario exposes backup path/progress/partial failure and settings result/navigation/security/platform capability feedback.
**Execution evidence:** Backup 与 Settings 旧 unconditional-success 路由分别取得精确 RED，再接入真实 `BackupRestoreScreenModelFactory`/`BackupWorkflow`/`BackupRestoreScreenModel` 与 `DesktopSettingsCatalog`/`SecuritySettingsController`/`DesktopNetworkMaintenancePort`/Advanced platform action seam。`BackupTestModeHttpTest` 覆盖 create/restore preview-confirm/progress/completed/partial/cancel/busy/unavailable/closed；`SettingsTestModeHttpTest` 覆盖搜索与真实 route、认证持久化、危险确认、cookie/cache/crash-log 平台动作、typed port failure、取消与 close。首审的 action-history 密钥泄漏及 active-handle 竞态均以确定性 RED→GREEN 修复：history 按 action/key 脱敏但 dispatch 保留原值，LAZY child 在启动前 CAS 发布且 caller/cancel 路径 `cancelAndJoin`；唯一修复复审的 source-login Cookie 覆盖 P2 又以 test-only mutation RED→GREEN 关闭，最终 `APPROVED`。组合 focused、DI/coverage、Spotless 与 diff check 通过。inventory 现为 12/13 covered、1 gap，64/64 mapped、unmapped=0。
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
