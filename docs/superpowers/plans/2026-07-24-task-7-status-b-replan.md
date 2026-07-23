---
parent-plan: docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md
parent-task: Task 7
status: active
attempt: 2
task-base: 68cfae3aa118c242af1e8d427175efe5d4df99da
---

# Task 7R2：状态批次 B 重规划

本计划取代已停止的 Task7R，是新的执行入口；原 Task 7/Task7R WIP 不提交，Task 7 保持未勾选，Task 8 不得启动。
## 复审记录

- 原 Task7 两轮拒绝：重复 `statusDecision`、ID17 反射误判终态、下载/迁移语义错误；修复后又把审计回收混入 8 文件产品范围，并误称 ID17 为 production behavior。
- Task7R 两轮拒绝：首审发现不存在的导航测试路径及 R3 漏算 replan；修复复审发现把 R3 自身 hash 写回提交会形成 self-hash/amend 循环。
## R1：audit stabilization

- 范围：现有 contract、manifest、父计划、Task7A plan 与本 replan，最多 5 files/400 touched。
- 保留 duplicate-property 结构化 guard、ID17 `SHARED`、ID29 `VERIFIED`、final 57；Task 7 decision 只能属于目标八项。
- 父计划改称 Android characterization/behavior `3/3`，明确 ID17 字段反射不构成 terminal evidence；`active-task` 保持 `Task 7R2 replan`。
- focused、ordinary、final 精确 57、Spotless、JSON/diff/range 全绿后独立审查并单独提交。
## R2：Task7A product/TDD

- 范围仅 8 个产品/测试文件、≤400 touched：`LibrarySelectionState.kt`、`LibraryTab.kt`、`LibraryComponents.kt`、`LibraryScreenModel.kt`、`LibraryParityIntegrationTest.kt`、`LibraryScreenModelTest.kt`、`NavigationContractTest.kt`、Android `LibrarySharedEvaluationWiringTest.kt`；不修改任何计划、manifest 或 contract。
- RED：入口/反馈、可见列表反选、下载下一 1/5/10/25/全部未读/书签、跳过已排队/下载中/已下载、空选与部分失败；迁移导航；删除 Android production filter/sort consumer 必须失败。
- GREEN/REFACTOR：复用现有下载队列核心；迁移固定走 `DesktopBatchMigrationController.submit()` → `MigrationBatchQueueScreen` → 逐项 `MigrationSearchScreen`；不另建平行链。
- 完成后单独实现提交与独立审查；只输出 R2 hash/status，不在 R2 持久化计划或审计元数据。
## R3：closeout

- 独立范围 5 files/≤280 touched：Task7A child plan、manifest、contract、父计划与本 replan。
- 文件内仅记录 R1/R2 hash 与 R3 验证 evidence，把本计划 `status` 改为 `completed`；R3 自身 hash 仅在提交后的交付报告记录，禁止 self-hash/amend 循环。随后重新裁决 ID17/19，恢复父 `active-task: Task 7`。
- 重跑 focused、ordinary、相关矩阵、final、Spotless 与 guards，独立审查通过后单独提交。
- 任何阶段若范围、复用入口或测试前置不成立，先停止并更新本计划；不得把后续阶段文件静默并入当前提交。
