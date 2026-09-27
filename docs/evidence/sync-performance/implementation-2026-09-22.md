# 同步性能优化实施证据（2026-09-22）

## 已实施

- `GitHubSyncTransport` 为不可变 Git blob 增加按仓库/OID 隔离的 transport 生命周期 L1 LRU 缓存，预算 16 MiB；batch 正文仍按请求读取，不把大正文挤出索引/头缓存。
- 读取 blob 时校验 Git 对象响应 SHA，并按 40 位 SHA-1 或 64 位 SHA-256 复算 `blob <size>\0<bytes>`；不匹配直接失败。
- 发布成功只有在当前 ref 已重新读取、完整解析并确认包含相同制品时才携带 `confirmedSnapshot`；交换循环复用该快照。旧 transport 或未知确认结果仍走完整重新读取回退。
- 保留每轮 ref、commit、tree 读取、远端 guard、scope 检查、加密/链校验和 durable prepared artifact。

## 验证命令与结果

以下命令均在当前 worktree 由 Gradle 协调器串行执行，退出码为 0：

```text
./gradlew.bat :data:jvmTest --tests "mihon.data.sync.SyncGitSafetyContractTest"
./gradlew.bat :data:jvmTest --tests "mihon.data.sync.SyncSpaceTransportContractTest"
./gradlew.bat :data:jvmTest --tests "mihon.data.sync.JvmSyncRuntimeStorageContractTest"
```

覆盖证据：

- 相同 ref 的第二次快照读取仅保留 ref/commit/tree 三次请求，旧 index/head blob 不再重复请求。
- 257 条基线形成两批上传时，确认快照复用使快照读取从 5 次降为 3 次（`B + 1`）；未提供确认快照的 transport 保守回退到 5 次。
- 未知发布响应、竞争 ref、原制品重试、截断/篡改/回滚、空间隔离、payload 模式和取消语义保持通过。
- 错误 blob 响应的对象身份会被拒绝；缓存命中不会绕过 tree 完整性检查。

## 审查 advisory 后续核对

- `SyncBlobCache` 已改为按 `SyncBlobCacheKey` 管理：键包含 API origin、仓库、branch、Git 对象格式/OID、validation scope 和 connection revision；缓存访问与 LRU 更新由 `Mutex` 保护，同一键的并发 miss 共享一个 `CompletableDeferred`。
- `createBlob` 返回的 SHA 会按 Git blob 对象格式对上传字节重新计算并校验；响应 OID 不匹配时发布返回失败，ref 不推进。
- cache 命中只返回原始字节；`readSnapshot` 仍按 space/generation 完整执行 descriptor、AEAD、索引链和 guard 校验。原始字节复用不等于跨 scope 复用已验证快照。
- focused 红测试已证明实现前缺少 `SyncBlobCache`/key；后续 GREEN 及 scope/revision 隔离、单飞、OID 复算测试见 `.gradle-coordinator` 的阶段日志。当前集成仍等待本轮树容量与真实文件迁移验收以及复审。

## P0–P5 后续核查记录（2026-09-24）

- P0 对 R1 的 `getFieldEvents` 误报作了核对：R1 所贴 SQL 本身已经含 `space_id/generation`，当前生产版 `getFieldEvents`、`getAffectedFields` 及 published/remote totals 查询也都按 scope 过滤。设计文档和 roadmap 已记录输入报告内部矛盾，原始输入副本未改写。
- P2 的持久对象缓存、context 隔离、损坏重读/淘汰与 tree 边界 focused 验证已扩展；tree cache poisoning、嵌套路径共享累计器、深度256/257、tree/index/HTTP limit±1、多OID LRU 和缓存损坏回退的组合 GREEN 日志为 `.gradle-coordinator/p2-tree-cache-limits-green-20260924.log`。共 23 个测试通过。P2 尚需文件数据库历史谱系迁移及独立审查。
- P5 秒级倒计时曾每秒排队重读数据库。`JvmSyncPanelStorageContractTest` 的 RED 为 `.gradle-coordinator/p5-panel-ticker-red-20260924.log`，GREEN 为 `.gradle-coordinator/p5-panel-ticker-green-20260924.log`；改为只更新时钟，不重新刷新持久状态。
- P5 首次运行重复执行 published/remote totals 对账。`SyncRuntimeWiringTest` 的 RED 明确失败为 fresh run 预期0次、实测1次，见 [red](results/2026-09-22/p5-totals-red-20260924.log)；GREEN 验证新 run 跳过重复对账、失败后 recovery 恰好对持久状态对账一次，见 [green](results/2026-09-22/p5-totals-green-20260924.log)。P5 其他SQL计划、批量日志/导入及全量门禁仍待做。
- 本轮尝试的初次全 `:data:jvmTest`（307 tests）有4项失败，不能记为通过。当前详细原日志在 `.gradle-coordinator/sync-perf-p9-data-sync-full-r1.log`；后续须重跑并关闭 `SyncBaselineStorageContract`、`SyncGitSafetyContract` 和 `SyncScaleAcceptanceTest` 的失败。

## 未完成与限制

- P3 仍缺 normalized/delta manifest 以避免每个新 head 重写完整 `manifest_json`，也缺 owner token + guard revision 条件写/竞争重算闭环；现有 guard/discovery 原子回滚与持久发现队列不是这两项退出证据。
- P5 计数重复、panel 秒级刷新成本已分别修正并有 focused 证据；SQL plan、批次事务/日志完整计数、导入限额和完整 P5 阶段退出仍未齐备。
- P7 的单批发布与当前 GraphQL 适配器尚未有真实生产网络请求/全协议 fixture 门禁；P8 的 pipeline/raw 默认决策及资源门禁尚未完成。
- 尚无正式构建、真实 GitHub 用户隔离仓库写入授权、Android/Desktop 设备验收。
- 目前没有真实网络耗时、请求配额、设备内存或 P9 规模门禁数据；不能据此宣称整体同步已达到 roadmap 的 50% 耗时目标。
- 早期独立审查为 `FAIL`，发现的历史head回退已通过“同一 sync tree、外部文件head前进后 ref回退”production guard测试修复。2026-09-24新增的缓存/tree/P5 focused GREEN 还未经过更新后的独立审查；因此 P2–P5 仍是局部实现，不能提前勾选，P6–P10 依据实际覆盖逐项更新。
