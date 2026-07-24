---
status: completed
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

**Evidence:** S1 提交 `c04721f44b5c490c541f05c868c77975142e87e2`，范围 5 文件、388 touched；focused/ordinary 对两种 scan evidence 回灌均精确 RED，恢复后 final gate 保持 45，独立审查 `APPROVED 0/0/0`。

## S2：Task 9A Stage A

**Budget:** 仅产品/测试文件，最多 8 文件、400 行；不修改审计计划。

执行 Android pager/webtoon holder `sharedStateFlow` 与 Desktop mounted Compose/Modifier/render chain 的真实 wiring fixture；两端移除 production wiring 的 mutation 必须 RED，禁止源码扫描。一个实现代理、一个独立审查代理，单独提交。

**Evidence:** S2 提交 `d0311eb381a45d323bc28cd1ee4ac010e312fc2d`，范围 5 文件、251 touched；Android `14/14`、Desktop `21/21`、均 0 skipped，Pager/Webtoon 一次读取 mutation 与 Desktop transform 移除 mutation 均精确 RED 后恢复，独立审查 `APPROVED 0/0/0`。

## S3：Task 9A closeout

**Budget:** 最多 5 文件、280 行；child、manifest、contract、父计划与 replan。

记录 S1/S2 hash、RED/GREEN/mutation 与零跳过证据，按实际重判 47/51 并把控制权返回父 Task 9。不得写入 S3 自身 hash。一个实现代理、一个独立审查代理，单独提交。

**Evidence:** closeout 契约先精确 RED 于 `ID 47 Task 9 status expected VERIFIED but was WIRED`；复核确认 ID 47 的两种 Android holder seam 与 ID 51 的 mounted viewport → layer → transform → matrix 均具备五角色及可杀死断链的 mutation，裁决两项提升为 `VERIFIED`。focused `1/1`、新增 Android `5/5`、新增 Desktop `2/2`、ordinary contract `42/42` 均 GREEN 且 0 skipped；显式 final gate 唯一按设计 RED 于精确 43 个非终态 ID，且不含 47/51。控制权返回父 `Task 9`，S3 自身 hash 不写入计划。

## S4：父 Task 9 closeout

**Budget:** 最多 3 文件、160 行；contract、父计划与 replan。

勾选父 Task 9、推进 `active-task: Task 10`、标记 replan completed；S4 自身 hash 仅在交付报告中提供。一个实现代理、一个独立审查代理，单独提交。

**Evidence:** S1 `c04721f44b5c490c541f05c868c77975142e87e2`、S2 `d0311eb381a45d323bc28cd1ee4ac010e312fc2d`、S3 `84add84daad5606a20ac9793d39349b7bbb0a744` 已闭合。最终 ID 39 保持 `WIRED` 并交 Task 14，ID 40/43/44/45/47/49/51 保持 `VERIFIED`；父 Task 9 已勾选，`active-task` 推进到未勾选的 Task 10。S4 focused 先精确 RED 于 `Task 9 closeout must advance to Task 10 ==> expected Task 10 but was Task 9`（1 test/1 failed），再 GREEN `1/1`；ordinary contract `42/42`、0 skip，显式 final gate 唯一按设计 RED 于精确 43 个非终态 ID 且不含 47/51，`spotlessCheck`、JSON、plan、diff、range guards 均 PASS。范围为 3 files/21 touched；S4 自身 hash 不写入计划。

任一阶段测试或独立审查失败时停止；每阶段最多一次修复复审，不得跨阶段顺手修改。
