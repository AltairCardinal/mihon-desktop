---
parent-plan: docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md
parent-task: Task 16B
task-base: c15f31897d0b736653ba6b11c4bdf732748fc1f4
original-ref: main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8
status: planned
---

# Background task state-machine closure

## 固定边界与任务总览

本计划只关闭 parity ID10 的重复业务规则：任务幂等注册、合法状态转换、终态一次性结果和约束词汇进入一个 shared core；Android WorkManager 与 Desktop checkpoint runtime 继续拥有各自平台副作用和持久化 writer。不得创建第二套 scheduler、repository 或 UI 状态链。

- 固定原版语义只取自 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8` 的 `LibraryUpdateJob`。
- Android 保留 WorkManager constraints、unique work、foreground 与 notification；Desktop 保留原子 checkpoint store、workset recovery、结构化 partial failure 与 runtime teardown。
- 顺序固定为 161 → 162 → 163；每项单独完成红绿重构、mutation、focused tests 和提交，后项才能消费前项 shared contract。
- ID10 只有在 Android 与 Desktop production consumer 都会因断开 shared core 而测试失败后，才能从 `WIRED` 提升为 `VERIFIED`。
- 任一任务超过预算或发现相邻产品缺陷时停止并修订本计划，不把新 capability、UI redesign 或架构治理静默并入。

- [ ] Task 161：shared task lifecycle core
- [ ] Task 162：Android WorkManager consumer
- [ ] Task 163：Desktop scheduler consumer and closeout

### Task 161 shared task lifecycle core

**Risk axis:** shared-task-lifecycle

**Platform boundary:** shared

**Estimated scope:** 2 files, 260 lines

**Verification:** shared contract 对 Pending/Running/Completed/Failed/Cancelled 的合法转换、幂等 key、checkpoint 与单终态结果执行参数化行为测试；没有平台 API。

**Files:**
- Modify: `domain/src/commonMain/kotlin/mihon/domain/task/BackgroundTask.kt`
- Modify: `domain/src/commonTest/kotlin/mihon/domain/task/BackgroundTaskContractTest.kt`

**RED:** 先为 register/start/checkpoint/complete/fail/cancel 的状态矩阵、重复 idempotency key 和终态不可重写写失败测试；当前只有 data class/enum，测试须因缺少 production reducer 精确失败。

**GREEN:** 在既有 `mihon.domain.task` 模型旁增加最小纯函数 lifecycle policy；它只决定合法 next state/result，不执行 WorkManager、文件 IO、coroutine 或通知。

**Mutation:** 暂时允许 Completed → Running、重复 terminal event 或同 key 新建 occurrence，确认 shared contract 分别失败后恢复。

**User entry:** 内部基础设施无独立入口；真实入口仍是 Library/Updates refresh 与 Desktop library update。

**Feedback:** shared core 输出 typed transition/result；Android 与 Desktop 在后续任务保持各自 already-running、progress、success、failure 与 cancelled 反馈。

**Desktop zero-regression:** 本任务不改 Desktop；checkpoint、workset、partial failure、startup recovery 和 teardown 行为全部保持现状。

### Task 162 Android WorkManager consumer

**Risk axis:** android-library-update-lifecycle

**Platform boundary:** shared+android

**Estimated scope:** 5 files, 380 lines

**Verification:** WorkManager 测试执行 `LibraryUpdateJob.setupTask/startNow/stop` 的真实 request、constraints、unique policy 和 shared transition policy；另有 production caller 测试覆盖 refresh 结果反馈。

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJob.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJobSharedLifecycleIntegrationTest.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/ui/updates/UpdatesScreenModelLibraryUpdateWiringTest.kt`
- Modify: `app/build.gradle.kts`
- Modify: `domain/src/commonTest/kotlin/mihon/domain/task/BackgroundTaskContractTest.kt`

**RED:** 先运行真实 WorkManager test driver，断言 scheduled/manual/cancel 调用映射到 Task 161 policy；再从 `UpdatesScreenModel.updateLibrary()` 验证 started/already-running 反馈。未消费 shared policy 时必须失败。

**GREEN:** `LibraryUpdateJob` 仅把 shared decision 映射为 WorkManager enqueue/cancel 与 worker result；保留 fixed-original interval、Wi-Fi/unmetered、charging、battery、linear backoff、KEEP/UPDATE 和自动任务恢复语义。

**Mutation:** 分别绕过 shared decision、把 KEEP 改为替换、漏掉 stop 后自动任务恢复，确认 integration/caller 测试精确失败后恢复。

**User entry:** Android Library → Refresh；Updates → Refresh；Settings → Library update interval/restrictions。

**Feedback:** 已开始或已在运行的可见结果不变；worker progress/success/failure/cancel 通知继续由 Android adapter 呈现。

**Desktop zero-regression:** 本任务不改 Desktop；Task 161 的 API 不能要求 Android-only Context、WorkerParameters 或 WorkInfo。

### Task 163 Desktop scheduler consumer and closeout

**Risk axis:** desktop-library-update-lifecycle

**Platform boundary:** shared+desktop

**Estimated scope:** 7 files, 400 lines

**Verification:** Desktop scheduler、library recovery 与 DI tests 执行 shared lifecycle policy和真实 production wiring；ordinary parity contract 与 ID10 focused closeout 全绿。

**Files:**
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/task/DesktopTaskScheduler.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/domain/LibraryUpdateScheduler.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/task/DesktopTaskSchedulerIntegrationTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/domain/LibraryUpdateRecoveryIntegrationTest.kt`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/di/DesktopDiWiringTest.kt`
- Modify: `app-desktop/src/test/resources/parity/parity-manifest.json`
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`

**RED:** 先让 scheduler transition test 要求 production `DesktopTaskScheduler` 委托 Task 161 policy；断开委托时 checkpoint/cancel/idempotency 和 recovery tests 必须失败。

**GREEN:** Desktop adapter 保留 `FileTaskCheckpointStore` writer、atomic replace、workset/completed IDs、structured failure、startup recovery 与 coroutine ownership，只删除其重复的合法转换判断；证据闭合后将 ID10 提升为 `VERIFIED`。

**Mutation:** 绕过 shared policy、重复发送 terminal notification、丢弃 completed IDs 或 DI 使用第二个 scheduler，确认对应行为测试失败后恢复。

**User entry:** Desktop Library → Refresh 与应用启动后的未完成更新恢复。

**Feedback:** progress、one terminal success/failure/cancel、恢复后的累计进度与 failure detail 保持可见；already-running 调用共享同一 occurrence。

**Desktop zero-regression:** checkpoint 文件兼容、corrupt quarantine、concurrent writer、partial failure、creator discovery 与 shutdown join 全部必须继续通过。
