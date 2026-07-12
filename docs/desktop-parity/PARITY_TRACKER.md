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

1. `CHARACTERIZED`：已有测试锁定 Android 权威行为与 Desktop 当前行为。
2. `SHARED`：业务实现已复用或抽取到共享链路，临时兼容层有退出说明。
3. `WIRED`：用户入口、加载、空状态、错误和权限/数据缺失反馈均已接通。
4. `VERIFIED`：相关单元、集成及 Test Mode 场景通过，且保护测试写入 manifest。
5. `EXEMPT`：确无桌面等价需求，manifest 中记录平台证据和边界。

## 全局产品证据池与逐项证据

作者聚合、Upcoming、阅读器双页、Webtoon 自动滚动以及 Test Mode 导航/HTTP 属于全局 Desktop 产品证据池：契约测试独立验证这些真实测试存在，但它们不对应 64 项中的普通对齐条目，因此不会被写入无关条目的 `protectionTests`。

逐项 `protectionTests` 只保护该条目上明确标记的 `DESKTOP-PRODUCT` 增强。当前 APK→JAR、FlareSolverr 后备、宽页拆分/边缘匹配和双页点击区域分别绑定能在对应能力回退时失败的具体测试；其他条目保留空数组。manifest 不允许自引用契约测试或 `MISSING:` 占位。

## 维护方式

只修改 manifest 中对应条目推进状态；本文仅在治理规则或标签语义变化时更新。比较报告原评分保持不变，直到单项达到 `VERIFIED` 或有充分证据达到 `EXEMPT` 后，再单独评审评分变化。

## 71–74 备份对齐进展

| 编号 | 当前阶段 | 证据与剩余门禁 |
|---:|---|---|
| 71 手动备份 | WIRED | canonical schema 已迁入 common，Android/Desktop writer 共用 codec；Desktop 已采集漫画、章节、分类、历史、tracking、应用/源偏好、来源和扩展仓库。真实迁移前 Android producer fixture 的可复现来源仍是 VERIFIED 门禁。 |
| 72 备份恢复 | WIRED | 设置页已有预览、危险确认、确定进度、取消、逐项失败及权限/存储可重试反馈；恢复器已覆盖 canonical 全部数据段。GUI/E2E 与真实跨端 fixture 恢复仍是 VERIFIED 门禁。 |
| 73 自动备份 | CHARACTERIZED | 既有 scheduler 继续调用统一 writer；退出进程后的平台唤醒能力尚未验证。 |
| 74 跨端兼容 | SHARED | Android/Desktop 解码及 canonical writer 共用 `BackupCodec`；读取兼容首个 Desktop writer（8c6d18c20）的历史 protobuf 与 canonical protobuf，写入仅生成 Android 相同的 canonical gzip+protobuf。历史 Desktop 与迁移前 Android fixture 均由各自旧 serializer 独立生成并以 SHA-256/逐字段测试锁定；仓库无可归因 JSON writer，故不维护 JSON 分支。 |

这些状态只记录本轮可证实进展；在真实历史 fixture、Android/GUI 集成测试与完整构建验收通过前不得提升为 `VERIFIED`。
