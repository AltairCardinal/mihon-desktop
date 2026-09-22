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

## 未完成与限制

- 本轮没有实现磁盘 L2、持久 manifest/delta discovery、分层 tree、SQL/日志批处理、统一限流、inline tree、raw pipeline、正式构建、真实 GitHub 写入或 Android/Desktop 设备验收。
- 目前没有真实网络耗时、请求配额、设备内存或 P9 规模门禁数据；不能据此宣称整体同步已达到 roadmap 的 50% 耗时目标。
- 独立审查结论为 `PASS_WITH_ADVISORY`：P2 仍缺并发 single-flight 与 `createBlob` 返回 OID 的本地复算，缓存键的 account/connection revision 也尚未进入；因此 roadmap 的 P2/P3/P4 保持 `PARTIAL`，P5–P10 保持 `NOT_STARTED`。
