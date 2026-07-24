# 治理成本降低执行计划

日期：2026-07-25  
状态：执行中

## 目标

降低计划、状态、审查和验证本身的维护成本，同时保留 TDD、上游对齐、独立审查、集成测试和最终平台验收。

## 状态权威

- 父 roadmap 只保存宏观 Task 与唯一 `active-child-plan`。
- 最终审计计划保存唯一 `active-task`。
- 产品 child plan 只保存任务规范和 checkbox，不再保存 `active-task`。
- `parity-manifest.json` 只负责 64 项 capability 状态与证据。
- tracker/report 是人类可读快照，不得成为第二个机器状态源。
- checkbox 只在实现、审查、验证和提交全部完成后勾选。

## Task G1：将文件/行数限制改为软提示

- [x] 完成

修改：

- `scripts/comet-project-guard.sh`
- `scripts/tests/comet-project-guard-test.sh`
- `app-desktop/src/test/kotlin/mihon/desktop/architecture/DesktopArchitectureGuardTest.kt`

RED：

1. 新增“超过 8 files/400 lines 且没有 waiver 仍通过，但输出 WARNING”的 fixture。
2. 先运行 guard 测试，预期旧实现因退出码 1 失败。

GREEN：

1. `Estimated scope` 保持必填，但只作为审查提示。
2. 超限输出 warning，不阻塞。
3. 删除以源码行数增长作为失败条件的 Desktop UI 大文件基线测试。
4. guard 全部测试通过。

成功标准：

- Spotless 导致行数增长不会要求压缩或重规划。
- 超限仍可见，但不会成为完成门禁。

## Task G2：收敛为单一活动任务权威

- [x] 完成

修改：

- `scripts/roadmap-state-guard.py`
- `scripts/tests/roadmap-state-guard-test.py`
- 当前五份产品 child plan
- 最终 parity contract 中对应 plan 断言

RED：

1. 写 fixture：child plan 含 `active-task` 时失败。
2. 写 fixture：父计划没有唯一 child pointer 时失败。
3. 写 fixture：合法父计划 + 无活动字段 child plan 时通过。
4. 在脚本尚不存在时运行，确认 RED。

GREEN：

1. 最终审计计划成为唯一 `active-task` 权威。
2. child plan 删除 `active-task`。
3. Task 144 在审查/提交前恢复未勾选。
4. 状态 guard 及相关 parity contract 通过。

成功标准：

- 任一 child plan 再加入 `active-task` 时 guard 失败。
- 日常任务不再同时更新六个活动任务字段。

## Task G3：将计划治理测试与普通产品测试分层

- [x] 完成

修改：

- `app-desktop/src/test/kotlin/mihon/desktop/parity/DesktopProductCapabilityContractTest.kt`
- `app-desktop/build.gradle.kts`

RED：

1. 先运行不存在的 `parityGovernanceCheck`，预期 Gradle 报 task not found。

GREEN：

1. Task 2–16D 的计划/状态迁移合同标记为 `parity-governance`。
2. 普通 `jvmTest` 排除该 tag。
3. 新增显式 `parityGovernanceCheck` 运行该 tag。
4. `finalParityAudit` 保持单独显式门禁。

成功标准：

- 普通产品测试不再因 `active-task`/checkbox 变化重复执行历史治理合同。
- 显式治理任务仍能验证计划与 manifest 一致性。

## Task G4：批次级实现、审查、提交与验证

- [x] 完成

修改：

- `AGENTS.md`

验证设计：

- 本项是工作流文档，不改变产品行为。
- 通过只读规则检查确认包含：功能批次、一次独立审查、实现/审查/验证/提交后勾选、禁止纯状态推进提交、分层验证。

成功标准：

- 一个功能批次原则上只有一个实现提交和至多一个审查修复提交。
- 不再按微任务创建 close/checkoff 提交。
- full test 只在里程碑和最终收口运行。

## Task G5：组合验证

- [x] 完成

验证：

- guard 单元测试；
- roadmap state guard 单元测试；
- `parityGovernanceCheck`；
- 普通 parity contract；
- `DesktopArchitectureGuardTest`；
- `spotlessCheck`。

## 执行结果

### G1

- RED：将超限 fixture 期望改为退出码 0 后，旧 guard 仍以缺少 waiver 返回失败。
- GREEN：`scripts/tests/comet-project-guard-test.sh` 为 `27 passed, 0 failed`；9 files/401 lines 在无 waiver、空 waiver和有说明三种情况下均只警告并通过。
- 删除了 `DesktopArchitectureGuardTest` 的 UI 文件源码行数硬失败基线；职责/依赖/平台边界保护保持。

### G2

- RED：`roadmap-state-guard-test.py` 首轮 `3/3` 因实现脚本不存在失败。
- GREEN：单元测试 `3/3` 通过；真实父计划、最终执行计划和五份 child plan 检查通过。
- 五份 child plan 均删除 `active-task`；最终审计计划保留唯一 `active-task: Task 17`；Task 144 因尚未审查/提交恢复未勾选。

### G3

- RED：`:app-desktop:parityGovernanceCheck` 首轮为 `task not found`。
- GREEN：显式治理任务 `20/20` 通过。首轮 GREEN 曾捕获 Task 14B 只扫描未勾选 overview 的历史耦合，修正为扫描全部 checkbox 后通过。
- 普通 `jvmTest` 中 parity contract 为 `34/34`、architecture guard 为 `6/6`，均 0 failure/0 skipped；历史治理合同不再进入普通产品测试。

### G4

- `AGENTS.md` 已写入功能批次、软范围提示、单一活动任务、完成后勾选、禁止纯状态推进提交和分层验证规则。
- 只读规则检查已确认关键条款存在。

### G5

- guard：`27/27`。
- roadmap state guard：fixture `3/3`，真实计划 PASS。
- parity governance：`20/20`。
- 普通 parity + architecture：`34/34 + 6/6`。
- 根 `spotlessCheck`：GREEN。

结论：方案一的可验证修改均已取得预期 GREEN，计划治理已从普通产品测试分离，范围估算不再触发强制压缩循环。
