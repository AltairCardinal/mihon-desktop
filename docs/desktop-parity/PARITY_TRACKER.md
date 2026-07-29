# Mihon Desktop 对齐追踪器

## 目标与边界

本追踪器覆盖 Android 对 Desktop 仍占优的 64 项能力。唯一机器数据源是 `app-desktop/src/test/resources/parity/parity-manifest.json`；本文只解释治理规则，不复制状态表，避免双写漂移。

- 初始状态统一为 `NOT_STARTED`，现有实现或文档不等于已经对齐。
- 合法状态依次为 `NOT_STARTED`、`CHARACTERIZED`、`SHARED`、`WIRED`、`VERIFIED`、`EXEMPT`。
- 每项状态变化都必须先补失败测试，再做最小实现并完成回归验证。
- 导航、DI、HTTP、数据库和后台任务接点必须有集成测试。
- `EXEMPT` 必须提供可审查的平台豁免证据；不得用平台差异掩盖业务能力缺口。

## 设计标签

| 标签 | 含义 |
|---|---|
| `SHARE-DIRECT` | Desktop 直接接入现有共享实现 |
| `SHARE-EXTRACT` | 从平台实现抽取公共能力后由两端复用 |
| `PLATFORM-ADAPTER` | 共享业务语义，Desktop 仅保留平台适配层 |
| `DESKTOP-PRODUCT` | Desktop 独有产品能力，必须保持零回退 |
| `TEMP-COMPAT` | 迁移期兼容层，必须有退出条件 |
| `PLATFORM-EXEMPT` | 无合理桌面等价物，需证据后豁免 |

## 状态推进门槛

1. `CHARACTERIZED`：已有测试锁定固定 `main@6fbf6dfc…` 的原版 Mihon 权威行为，并分别刻画当前 Android consumer 与 Desktop consumer/adapter 的行为；当前 `app/` 不能作为 expected-value 来源。
2. `SHARED`：业务实现已复用或抽取到共享链路，临时兼容层有退出说明。
3. `WIRED`：用户入口、加载、空状态、错误和权限/数据缺失反馈均已接通。
4. `VERIFIED`：相关单元、集成及 Test Mode 场景通过，且保护测试写入 manifest。
5. `EXEMPT`：确无桌面等价需求，manifest 中记录平台证据和边界。

## 全局产品证据池与逐项证据

作者聚合、Upcoming、阅读器双页、Webtoon 自动滚动以及 Test Mode 导航/HTTP 属于全局 Desktop 产品证据池：契约测试独立验证这些真实测试存在，但它们不对应 64 项中的普通对齐条目，因此不会被写入无关条目的 `protectionTests`。

逐项 `protectionTests` 绑定所有已提升条目的真实 production behavior/wiring；`DESKTOP-PRODUCT` 还必须零回退。契约测试、自引用、源码符号扫描或 `MISSING:` 占位均不能替代行为证据。

## Task 14 governance snapshot

`app-desktop/src/test/resources/parity/parity-manifest.json` 继续承载逐项状态与证据；manifest is the only machine-readable status authority。下表仅是 Task 14 的治理交接快照，不是第二份状态源。

| ID | 裁决 | 收口时 manifest 状态 | 后续任务 |
|---:|---|---|---|
| 3 | `extract` | `CHARACTERIZED` | `NONE` |
| 4 | `adapter` | `VERIFIED` | `NONE` |
| 32 | `reuse` | `WIRED` | `NONE` |
| 39 | `adapter` | `VERIFIED` | `NONE` |
| 69 | `extract` | `CHARACTERIZED` | `NONE` |
| 70 | `extract` | `CHARACTERIZED` | `NONE` |
| 87 | `adapter` | `SHARED` | `NONE` |
| 88 | `adapter` | `VERIFIED` | `NONE` |

## 最终终态摘要（2026-07-28）

`parity-manifest.json` 的最终机器结果为 64/64 terminal：

- `VERIFIED`：63；
- `EXEMPT`：1（ID 85 Android Widget）；
- `NOT_STARTED | CHARACTERIZED | SHARED | WIRED`：0；
- `UNCLASSIFIED_DEBT | TEMP-COMPAT` 终态分类：0。

每项都绑定固定原版 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`、当前 Android
consumer、shared 或平台 adapter、Desktop consumer 与 production protection fixture。
最终 `finalParityAudit` 及 manifest governance contract 通过；Windows/macOS 同一版本
`0.11.14.51.19a55d7` 的运行验收均为 13/13 场景族、5/5 永久保护、64/64 capability、
`unmapped=0`。完整命令、产物哈希、失败/跳过和环境限制见
完整逐项状态、保护测试和运行证据以 manifest 为准。

Task 14 表仅保留历史交接含义，不表示当前状态；任何终态查询必须读取 manifest。

## 维护方式

机器状态与 production protection evidence 始终以 manifest 为准；本文件只维护治理规则和带明确收口时点的有限快照。原版 Mihon 仅指固定 `main@6fbf6dfc…`，current Android/shared/Desktop 都只是消费者、迁移输出或平台适配。
