---
parent-plan: docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md
parent-task: Task 6
capability-id: 12
status: completed
task-base: 0cec5ed06a96dde60e63a9c882a42c9cbbaf1ba4
---

# Task 6A：Desktop crash log 写入失败边界

## 目标

让全局 uncaught-exception handler 在 crash log 目录不可创建、文件不可写或轮转失败时仍能完成失败报告：原始异常与持久化失败必须输出到 stderr，handler 本身不得再次抛出。完成后返回父计划 Task 6 重新核验 ID 12；本子计划不预先承诺 `VERIFIED` 或 `EXEMPT`。

## 已确认的生产缺口

- 固定原版 `CrashLogUtil.dumpLogs()` 用 `try/catch` 包围日志生成和分享，并向用户报告失败。
- Desktop `CrashHandler.uncaughtException()` 直接调用 `appendCrashReport()`；目录、轮转或追加写入抛出的异常会逃逸 handler，后续 stderr 报告也不会执行。
- `Main.kt` 已在应用初始化早期调用 `CrashHandler.install()`，因此这是实际 production wiring，不是孤立工具函数。
- 现有成功与轮转测试没有覆盖不可写路径。

## 用户入口、反馈与边界

- 触发入口：应用任意线程发生未捕获异常时自动触发；该系统级能力不新增按钮。
- 可见诊断入口保持为“设置 → 高级 → 打开崩溃日志目录”；成功/失败继续通过现有 Snackbar 反馈。
- crash log 持久化失败时 UI 可能已不可用，可靠反馈边界是 stderr：必须同时包含原始异常与明确的日志写入失败。
- 不增加遥测、上传、重启或崩溃恢复，不把写入失败伪装成成功，也不吞掉原始异常。
- “日志目录无法写入”不是危险操作，不需要确认对话框。

## 复用与实现边界

复用现有 `CrashHandler`、`DesktopPlatformPaths`、日志轮转和高级设置入口；不另建 crash service。把 `uncaughtException()` 的真实处理链提取为可注入目标文件、可同步验证的内部函数，默认 production 路径仍使用 `crashLogFile()`。失败隔离只包围持久化步骤，stderr 报告无论持久化结果如何都必须执行。

## 文件范围

- Modify: `app-desktop/src/test/kotlin/mihon/desktop/CrashHandlerTest.kt`
- Modify: `app-desktop/src/main/kotlin/mihon/desktop/CrashHandler.kt`
- Modify: `app-desktop/src/test/resources/parity/parity-manifest.json`（修复完成后仅更新 ID 12 的真实证据；是否升终态由父 Task 6 决定）
- Modify: `docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md`（回收 active-task 与父任务状态）
- Modify: `docs/superpowers/plans/2026-07-24-task-6a-desktop-crash-log-failure-boundary.md`（持久化本计划完成状态与执行证据）
- Modify: `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`（把 Task 6A completed/returned 与父 active-task 状态纳入结构化契约）

预计产品实现仍为 2 个文件、220 行以内；回收证据后总范围不超过 6 个文件、300 行。原计划先漏列 child plan 自身，随后又遗漏父回收时必须同步的状态契约；两次修正都只持久化计划状态，不扩大产品实现范围或其他 ID 的状态裁决。

## TDD 固定步骤

1. **RED — 写入失败边界：** 在 `CrashHandlerTest` 创建“父路径是普通文件”的不可写目标，调用 production crash handling chain，断言调用不抛出，stderr 同时包含原始异常与持久化失败。先运行单测并确认失败来自 `appendCrashReport()` 异常逃逸。
2. **CHARACTERIZE — production wiring：** 断言既有 `install()` 将 `CrashHandler` 注册为默认 uncaught exception handler；测试结束必须恢复原 handler，避免污染其他测试。该回归保护不冒充 RED，唯一预期 RED 是写入失败逃逸。
3. **GREEN：** 在 `CrashHandler` 中做最小失败隔离；保留现有目录创建、轮转、追加和 stack-trace 截断语义。测试不得复制 production 写入逻辑。
4. **REFACTOR：** 合并重复 stderr 输出，保持异常处理顺序清晰；再次运行全部 focused tests。
5. **父任务回收：** 更新 ID 12 的行为方法与五类角色证据，恢复父计划 `active-task: Task 6`，由 Task 6 根据 production wiring、成功/失败测试及剩余语义差异决定状态。

## 验证

```bash
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.CrashHandlerTest" --no-daemon
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.parity.DesktopProductCapabilityContractTest.task 6 status batch keeps gaps and promotes only complete production evidence" --no-daemon
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.parity.DesktopProductCapabilityContractTest" --no-daemon
./gradlew spotlessCheck --no-daemon
```

还需运行父 Task 6 的真实行为矩阵和显式 final gate；final gate 仅用于确认剩余 ID 准确，在 Task 18 前预期保持 RED。若实现需要改变高级设置入口或反馈，则该变化超出本计划，必须先暂停并修正规模与 UI 测试范围。

## 执行证据

不可写父路径通过真实 `CrashHandler.uncaughtException()` 首次精确 RED 于 `FileNotFoundException` 逃逸；最小 failure boundary 后，原始异常、明确持久化失败与截断标记均保留在 stderr，handler 不再抛出。`install()` 仅作为既有 production wiring characterization，成功写入与轮转语义继续由原测试保护。控制权已返回父 Task 6，ID 12 保持非终态，等待父审计裁决。
