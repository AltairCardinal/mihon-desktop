# Mihon Desktop 原版对齐路线图概念混淆清单

> 审查对象：`docs/superpowers/plans/2026-07-12-mihon-desktop-upstream-parity-roadmap.md`
>
> 审查日期：2026-07-18
>
> 原版基线：`main` 提交 `6fbf6dfca203d99d6dd32137f2df97ced40c81b8`

## 1. 判定口径

本次审查严格区分四种代码身份：

1. **原版 Mihon**：固定 `main` 提交中的代码和行为，只能通过 `git show <original-ref>:<path>`、该提交构建的真实产物或可追溯 fixture 取证。
2. **当前 Android 构建版**：当前重构分支中的 `app/`。它是 Mihon Desktop fork 保留的 Android 构建目标和共享核心消费者，不是原版行为的自证来源。
3. **Desktop JVM 实现**：当前重构分支中的 `app-desktop/` 生产代码。
4. **Desktop Android 兼容 shim**：`app-desktop/src/main/kotlin/android/`，用于让 Android 扩展在 Desktop 运行的 API 模拟层；它既不是原版 Mihon，也不是当前 Android 构建版。

迁移后的 common/shared 实现是当前分支的新生产实现。它必须用原版 fixture 证明一致，不能因 Android 与 Desktop 都消费它就反向成为“原版证据”。

## 2. 已确认的概念混淆

| 原文行 | 混淆内容 | 必须采用的纠正口径 |
|---:|---|---|
| 5 | “收敛到原版/共享实现”把原版来源与迁移后共享实现合并为一个身份。 | 以固定原版行为为契约，将 Desktop 简化规则迁入或替换为当前分支共享实现。 |
| 7 | “Android 与 Desktop”没有说明 Android 是当前分支消费者，容易被理解为原版本身。 | 明确写成“当前 Android 构建版与 Desktop JVM 实现”。 |
| 36 | 当前分支 `app/` 直接称为 Android UI，没有与 `main:app/` 区分。 | 标注为当前 Android 构建版的平台适配与生产 wiring。 |
| 40 | “原版与 Desktop 共用测试”暗示 `main` 会直接运行当前 shared 测试。 | 从原版提取 fixture，当前 Android 构建版与 Desktop JVM 实现共同接受契约验证。 |
| 79–85 | 同一段中“原版”是来源，“Android”是当前消费者，但未区分；当前 Android 测试可能被误作原版证据。 | 分别声明原版来源、迁移后 shared 实现、当前 Android 消费端和 Desktop 消费端。 |
| 139 | 追踪矩阵只有“当前权威类”，会把当前 Android/shared 类覆盖成原版权威。 | 分列原版 ref/符号、共享实现、当前 Android 消费路径、Desktop 消费路径和有意偏差。 |
| 216、221、227–240 | Backup Task 中“Android”同时表示原版备份格式、原版 fixture、当前 Android writer 和当前 Android 测试。 | 原版备份必须来自固定 `original-ref`；当前 Android writer/reader/test 必须明确标为当前分支消费者。 |
| 231 | 同一句“Desktop 读 Android、Android/shared 读 Desktop”中的两个 Android 指代不同。 | 改成“Desktop 读取原版 fixture；当前 Android 构建版和 shared codec 读取 Desktop 转换结果”。 |
| 246、264 | 修改当前 `app/.../download`，同时从“原版”提取规则，却未规定从 `main` 读取。 | 原始规则从固定原版快照提取，当前 Android 构建版仅作为迁移目标和回归消费者。 |
| 308、317–325 | 当前 Android tracker 与原版 tracker/API/domain 没有分开命名。 | 原版 tracker 来源绑定 `original-ref`；当前 `app/` 是 shared tracker 的 Android adapter/consumer。 |
| 335、348–357 | Reader Task 未区分原版页面算法、当前分支 Android viewer 和当前 Android tests。 | 只从固定原版快照建立默认向量；仅存在于当前分支的 Android 双页等行为按 fork/Desktop 增强或独立 bugfix 管理。 |
| 354 | “Android 与 Desktop viewer”可能被理解为原版与 Desktop。 | 明确为当前 Android 构建版 viewer 和 Desktop JVM viewer。 |
| 364、379–387 | Source/Extension Task 修改当前 Android 代码并称原版 fixture/共享状态，未固定原版来源。 | index、状态、安全和操作语义从固定原版快照提取；当前 `app/` 只是消费者。 |
| 368 | `app-desktop/src/main/kotlin/android/` 名称可能被当成 Android Mihon 实现。 | 明确它是 Desktop Android API 兼容 shim，只允许保留有真实扩展证据的 API。 |
| 465 | 最终审计只比较“原版权威类”和 Desktop 类，遗漏 shared 及当前 Android 消费端。 | 审计表必须同时记录原版来源、shared 实现、当前 Android consumer 和 Desktop consumer。 |
| 469 | 当前分支 Android 测试可能被当作原版一致性证明。 | 当前 Android 测试只证明消费 wiring；原版一致性必须由有 provenance 的原版 fixture/差分断言证明。 |
| 489 | “Android 原版共享抽取”把平台和产品基线绑定为一个概念。 | 改成“从固定 `main` 原版实现抽取平台无关行为”。 |
| 517 | “两端共同修复”没有说明两端是当前 Android 构建版和 Desktop，而原版是不可变对照。 | 原版 bug 另建 cross-platform bugfix；当前 Android 与 Desktop 共同消费修正，同时保留与原版的有意偏差记录。 |

## 3. 未固定来源的高风险“原版”表述

以下表述未必在字面上错误，但原路线图没有 `original-ref`，执行者可以错误地从当前工作树 `app/` 取证：

| 原文行 | 高风险表述 |
|---:|---|
| 13 | “原版能力完全对齐”没有绑定具体 `main` 提交。 |
| 51 | “原版 protobuf”没有固定 schema 来源和版本。 |
| 67–69 | “从原版现有测试/实现提取”没有规定使用固定 Git tree。 |
| 175 | AppError/TaskState 的原版调用链未绑定 `original-ref`。 |
| 204 | 原版任务定义、约束和幂等规则来源未固定。 |
| 228、233 | 原版 Backup schema、codec、validator 来源未固定。 |
| 264 | 原版下载状态机和 retry/backoff 来源未固定。 |
| 289–294 | 原版 domain use cases、fixture 和结果未绑定 `main`。 |
| 317–325 | 原版 tracker、迁移和自动同步策略未绑定 `main`。 |
| 348 | 原版页面模型和算法未规定必须从 `main` 提取。 |
| 379 | 原版 index、安装包和 source fixture 没有 provenance 要求。 |
| 411 | 原版 URI、安全策略和 release 状态来源未固定。 |
| 424、439–441 | 当前 Android settings 与原版设置/主题来源没有分开。 |
| 515 | “当前版本 fixture”可能再次指向当前 Android 实现，而不是原版快照。 |

## 4. 不属于概念混淆的内容

- WorkManager、Intent、PackageInstaller、Android View 等明确指 Android 平台 API。
- Desktop 通过 OS adapter 提供等价能力而不机械复制 Android API，方向正确。
- 把非平台业务规则迁入共享层，方向正确；共享默认值必须从固定原版事实提取。
- Desktop 独有能力、平台 adapter 和有证据的平台豁免应继续保留。

## 5. 对后续执行的约束

1. 每个子计划必须记录 `original-ref`，并在读取原版实现时使用该 ref，而不是当前工作树路径。
2. 每个原版 fixture 必须记录原版提交、生成路径、生成方式和适用行为；测试自造对象不能冒充原版 fixture。
3. 当前 Android 构建版测试与 Desktop 测试用于证明迁移后 production wiring；它们不能替代原版行为 provenance。
4. 若当前共享行为与原版不同，必须分类为平台 adapter、Desktop 产品增强或独立 cross-platform bugfix，不能继续标记为无差异的原版对齐。
5. 原版基线升级必须作为显式变更执行：更新 `original-ref`、重新生成/核验 fixture，并重跑受影响的 parity contract。

## 6. 迁移权威纠正（2026-07-18）

迁移行为的原版权威固定为 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`。
`MigrationOrchestrator` 是当前共享迁移产物，不是原版 Android Mihon 本身。

- 与固定原版一致的单部迁移核心：使用第一个可识别章节号相同的章节；只写入可空的
  `read = true` patch；保留固定原版的 `NaN` 最大已读行为，以及章节元数据、分类、标志、
  加入日期、备注和替换/复制书库归属语义。
- 与固定原版一致的批量核心：按顺序遍历、普通失败后继续、传播取消。
- 跨平台可靠性增强：`startIndex`、`Completed(nextIndex)`、逐项 `Failed`、
  `WaitingForUser` 和可恢复 checkpoint 协议。
- Android 平台 adapter：`AndroidBatchMigrationRunner` 及进度/失败对话框 wiring。
- Desktop 产品增强：持久任务队列、目标/选项/状态/错误持久化、恢复、暂停/继续/取消/重试，
  以及队列 UI/Test Mode。

不得把 checkpoint、等待用户、重试或 Desktop 队列持久化描述为从原版 Mihon 抽取的行为。
若以后 replay 发现核心规则不一致，应停止分类工作并建立独立的严格 TDD 行为变更。
