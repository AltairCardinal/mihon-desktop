---
status: active
parent: docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md
parent-task: Task 9
task-base: 511c811e91574dc524f450a66a13d0ed7c084fd6
---

# Task 9R status-D replan

## Failure record

Task 9 首审发现 ID 47/51 的 production wiring 只由源码扫描声称保护。唯一修复复审又发现 ID 51 扫描方法仍残留于 manifest 顶层 `behaviorMethods` 和契约 `task3BehaviorMethods`；原修复链停止，WIP 不得提交。

## S1：audit stabilization

**Budget:** 最多 5 文件、400 行；当前 contract、manifest、父计划、Task 9A child 与本 replan。

清除 ID 51 顶层及 Task 3 历史扫描 evidence；ID 47/51 保持 `WIRED`，final gate 保持 45，任何 scan test 不再充当 evidence。审计防回归 mutation 必须证明：临时把 ID 51 scan 方法重新加入顶层 `behaviorMethods` 或 `task3BehaviorMethods` 时，focused/ordinary contract 精确 RED；S1 不声称移除 production wiring。一个实现代理、一个独立审查代理，单独提交。

## S2：Task 9A Stage A

**Budget:** 仅产品/测试文件，最多 8 文件、400 行；不修改审计计划。

执行 Android pager/webtoon holder `sharedStateFlow` 与 Desktop mounted Compose/Modifier/render chain 的真实 wiring fixture；两端移除 production wiring 的 mutation 必须 RED，禁止源码扫描。一个实现代理、一个独立审查代理，单独提交。

## S3：Task 9A closeout

**Budget:** 最多 5 文件、280 行；child、manifest、contract、父计划与 replan。

记录 S1/S2 hash、RED/GREEN/mutation 与零跳过证据，按实际重判 47/51 并把控制权返回父 Task 9。不得写入 S3 自身 hash。一个实现代理、一个独立审查代理，单独提交。

## S4：父 Task 9 closeout

**Budget:** 最多 3 文件、160 行；contract、父计划与 replan。

勾选父 Task 9、推进 `active-task: Task 10`、标记 replan completed；S4 自身 hash 仅在交付报告中提供。一个实现代理、一个独立审查代理，单独提交。

任一阶段测试或独立审查失败时停止；每阶段最多一次修复复审，不得跨阶段顺手修改。
