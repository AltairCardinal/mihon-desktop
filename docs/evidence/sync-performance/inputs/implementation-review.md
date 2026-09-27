# 同步设计审阅说明

**当前实现基线：**

- 提交：`96dbb69d7a`
- 设计文档：[2026-09-21-sync-repair-design.md]\(D:/Codex/worktrees/85be/mihon/docs/2026-09-21-sync-repair-design.md)
- Roadmap：[2026-09-21-sync-repair-roadmap.md]\(D:/Codex/worktrees/85be/mihon/docs/roadmap/2026-09-21-sync-repair-roadmap.md)
- 当前已实现 F1～F4；F5、F6 仍需要正式设备和发布验收。

本说明描述的是当前代码实际采用的设计，不是未来优化方案。

---

## 1. 整体架构

同步由共享 Kotlin 逻辑统一实现，Android 和 Desktop 只负责平台调度与界面适配。

| 层主要职责               |                                         |
| ------------------- | --------------------------------------- |
| `domain`            | 协议、事件模型、加密接口、冲突归并、运行状态                  |
| `data`              | 本地同步日志、上传队列、下载收件箱、SQLDelight、GitHub API |
| `presentation-sync` | Android/Desktop 共用同步面板、进度和日志            |
| Android adapter     | WorkManager、前台恢复、设备生命周期                 |
| Desktop adapter     | 启动恢复、定时同步、网络恢复                          |

关键入口：

- [SyncCoordinator.kt]\(D:/Codex/worktrees/85be/mihon/domain/src/commonMain/kotlin/mihon/domain/sync/runtime/SyncCoordinator.kt)
- [SyncRuntime.kt]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncRuntime.kt)
- [SyncDatabaseExchange.kt]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncDatabaseExchange.kt)
- [SyncTransactionJournal.kt]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/journal/SyncTransactionJournal.kt)
- [GitHubGitDatabaseClient.kt]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/transport/GitHubGitDatabaseClient.kt)

---

## 2. 核心数据模型

同步不是直接上传整个数据库，而是上传本地发生过的变更事件。

### 2.1 本地事件

用户执行收藏、取消收藏、关注、阅读、修改阅读进度等操作时，会在原业务数据库事务中追加同步事件。

事件包含：

- `spaceId`
- `generation`
- `actorId`
- `epoch`
- `seq`
- 事件类别：收藏、关注、阅读
- 对象类型：漫画、章节、作者
- 修改字段
- 修改动作
- 因果父节点 `parents`
- 操作来源：用户操作、初始导入、远程导入
- 对象描述信息
- 内容摘要

事件具有单调递增的 actor 序列号，并通过因果父节点形成事件图。

### 2.2 本地表

主要表包括：

- `sync_spaces`：同步空间、仓库和分支绑定
- `sync_actors`：本设备身份、epoch、序列号
- `sync_batches`：本地事件批次
- `sync_events`：不可变事件
- `sync_outbox`：待上传事件
- `sync_object_heads`：每个对象字段的因果头
- `sync_inbox_*`：远程批次、远程事件、索引和投影状态
- `sync_runtime_runs`：同步运行记录
- `sync_runtime_logs`：同步条目日志
- `sync_import_*`：首次连接时的初始导入

---

## 3. 批次规则

一个批次包含多个事件，当前协议限制为：

- 每批最多 **256 个事件**
- 明文序列化大小最多 **512 KiB**
- 每个事件最多 **32 个 effect**
- 每个 effect 最多 **64 个因果父节点**

超过事件数量或大小限制时，当前批次被封存，后续事件进入新批次。

这些限制是应用协议限制，用于控制：

- 内存占用
- JSON 解析时间
- 加密和重试成本
- 单次恢复工作量
- 旧版本兼容性
- GitHub tree、blob 和 API 请求大小

它们不是 Git 本身的限制。

**当前实现没有“一条事件一个 Git 提交”的行为。**
一个逻辑批次对应一次远程发布提交。1000 个事件在事件大小允许时至少会分成 4 个批次。

---

## 4. 首次连接和初始导入

首次创建或加入同步空间时：

1. 先在本地保存持久化 setup intent。
2. 创建或验证同步空间描述。
3. 创建或验证 GitHub 私有仓库。
4. 初始化同步分支和远程 bootstrap。
5. 验证账号、仓库私有状态、分支和空间身份。
6. 建立本地 actor。
7. 冻结本地收藏、关注、阅读状态和阅读记录。
8. 将冻结数据分块转换为 `INITIAL_IMPORT` 事件。
9. 逐批处理初始导入。
10. 所有导入完成且首次同步成功后，才清除 pending setup。

初始导入中的对象会携带描述信息，使另一台设备在缺少本地对象时可以尝试重建漫画、章节或作者。

---

## 5. 远程 GitHub 存储结构

同步使用 GitHub Git Data API，不调用本地 Git CLI。

远程分支中主要包含：

```
.mihon-sync/
├── space.json
├── batches/
│   └── {actorId}/{epoch}/{batchId}.json
├── index/
│   ├── bootstrap/0/bootstrap.bin
│   └── {actorId}/{epoch}/{batchId}.bin
└── heads/
    └── {actorId}/{epoch}.bin
```

### 5.1 `space.json`

描述同步空间：

- `spaceId`
- `generation`
- `spaceFormatVersion`
- `eventProtocolVersion`
- 密码保护模式
- 加密参数

### 5.2 `batches`

保存加密后的事件批次。

### 5.3 `index`

每个批次有一个索引 shard，包含：

- actor
- epoch
- 起止序号
- 批次路径
- 前一个索引 shard 路径
- 批次元数据

### 5.4 `heads`

每个 actor/epoch 有一个 head，指向最新索引 shard。

通过 `previousPath` 和序列号可以验证 actor 链是否连续，防止缺批次、分叉、回滚或篡改。

---

## 6. 加密设计

同步内容使用 AES-256-GCM。

加密材料包括：

- 32 字节同步密钥
- 随机 nonce
- GCM authentication tag
- AAD

AAD 绑定：

- 协议版本
- `spaceId`
- `generation`
- `batchId`
- 存储路径

因此，同一批密文不能被安全地替换到另一个空间或路径。

密码保护模式下：

1. 使用 PBKDF2-HMAC-SHA256 派生密钥。
2. 派生密钥使用 AES-GCM 包裹同步密钥。
3. 批次、索引和 head 使用同步密钥加密。

无密码模式仍然要求仓库为私有仓库，并会验证仓库权限。

---

# 7. 上传流程

上传方向是：

```
本地业务数据库
→ 本地同步事件
→ sync_outbox
→ 加密批次
→ GitHub Git Data API
→ 远程提交
```

## 7.1 生成本地事件

用户修改数据时：

1. 进入业务数据库事务。
2. 读取当前 actor、epoch 和序列号。
3. 读取对象字段当前 causal heads。
4. 创建同步事件及其 effects。
5. 写入 `sync_events`。
6. 写入或更新当前开放批次。
7. 写入 `sync_outbox`。
8. 更新 `sync_object_heads`。
9. 与业务数据一起提交。

业务数据和同步事件在同一个数据库事务中提交，避免“业务已修改但同步事件丢失”。

## 7.2 选择待上传批次

`SyncDatabaseExchange` 先读取远程快照，然后调用 outbox 上传逻辑：

1. 找到一个待上传的本地批次。
2. 如果批次仍然开放，则按事件数量或大小封存。
3. 如果已有持久化的 prepared artifact，则复用它。
4. 否则根据当前远程 actor head 创建新的上传准备物。

一个 prepared artifact 会持久化：

- 基础远程 head
- 加密批次正文
- 前置索引路径
- 前置序列号
- 加密索引 shard
- 加密 actor head

这样进程中断后可以继续使用原来准备好的内容，而不用重新生成不同的加密结果。

## 7.3 发布前检查

发布前会验证：

- 当前同步空间仍然 active
- 仓库、账号和分支未改变
- 仓库仍是私有仓库
- 批次空间身份和 generation 正确
- 本地批次确实接在远程 actor 链末尾
- `firstSeq = previousLastSeq + 1`
- 远程快照没有被回滚或篡改
- prepared artifact 与当前远程 predecessor 一致

## 7.4 远程发布

单个批次发布包含：

1. 上传批次 blob。
2. 上传 index shard blob。
3. 上传 actor head blob。
4. 创建新的 tree。
5. 创建新的 commit。
6. 更新分支 ref。
7. 重新读取远程快照确认发布结果。

因此，一次批次上传对应：

```
3 个 blob
+ 1 个 tree
+ 1 个 commit
+ 1 次 ref 更新
+ 发布后的快照确认
```

GitHub API 返回超时但远程实际已成功时，代码会先检查同一批次 ID：

- 如果远程已有完全相同的批次，视为幂等成功。
- 如果同一批次 ID对应不同内容，拒绝并报告冲突。
- 如果 actor predecessor 已被其他设备推进，返回冲突，重新读取快照后再决定是否重试。

ref 更新冲突最多自动尝试 3 次，每次刷新远程快照。

## 7.5 上传成功后的本地处理

远程确认成功后：

1. 对应批次标记为 `PUBLISHED`。
2. outbox 中的事件标记为已发布。
3. 更新运行记录中的 uploaded 数量。
4. 添加“已确认上传”的条目日志。
5. 重新读取远程快照。
6. 继续处理下一个批次。

当前上传策略是**串行处理批次**，每次只发布一个本地批次。

---

# 8. 下载流程

下载方向是：

```
GitHub 远程提交
→ 远程快照
→ 加密批次
→ 解密与验证
→ sync_inbox
→ 因果归并
→ 本地业务数据库
```

## 8.1 读取远程快照

每次同步循环首先读取：

1. 分支 ref
2. 当前 commit
3. commit 对应的 tree
4. `space.json`
5. bootstrap
6. 所有 actor head
7. 所有索引 shard
8. 对应的批次文件

读取过程中检查：

- tree 是否截断
- tree 条目数量是否超过限制
- 索引条目数量是否超过限制
- `spaceId` 和 generation 是否一致
- 是否存在且仅存在一个 bootstrap
- actor 链是否连续
- 是否存在序列缺口、分叉、循环
- 每个索引批次是否确实存在
- 存储路径是否与元数据一致

## 8.2 远程快照保护

`SyncRemoteSnapshotGuard` 会记录远程快照和已知对象指纹。

首次观察时建立锚点。之后如果发现：

- 已知 head 回退
- 已知对象内容被修改
- 远程索引不完整
- 已确认对象消失
- 快照发生未经允许的回滚

同步会 fail closed，停止继续导入或上传。

## 8.3 选择需要下载的批次

遍历远程索引中的批次：

- 本地已收到的批次跳过。
- 本地已发布且本地已知的批次跳过。
- 当前运行已经尝试过且有记录的批次跳过。
- 其他批次进入 inbox 接收流程。

批次级别去重，不按单个事件重复提交。

## 8.4 读取、解密和验证

每个待下载批次：

1. 读取加密 batch blob。
2. 校验路径和批次 ID。
3. 解密。
4. 校验明文摘要。
5. 使用 `SyncBatchCodec` 解码。
6. 检查协议版本。
7. 检查空间、generation、actor、序列号。
8. 检查事件数量、大小、对象描述。
9. 使用 `SyncValidator` 检查事件、effects、父节点和因果图。

无效批次不会直接写入业务数据库，而会记录为 invalid data 并写入失败日志。

## 8.5 写入 inbox

通过事务写入：

- 远程批次记录
- 批次描述
- 不可变事件
- effect 字段索引
- 因果依赖索引
- 受影响字段的 dirty 标记

如果本地已有相同批次：

- 内容完全相同：按幂等重复接收处理。
- 相同批次 ID但内容不同：标记冲突并拒绝。

远程事件只进入 inbox，不直接修改最终业务表。

## 8.6 归并和投影

`SyncInboxProjector` 以有限批量处理 dirty 字段，每次最多处理约 50 个字段。

每个字段的处理过程：

1. 读取该字段的事件和父节点。
2. 构造所需的因果闭包。
3. 调用 `SyncReducer.reduce`。
4. 检查缺少父节点、循环和冲突。
5. 计算新的 causal heads。
6. 计算最终业务状态。
7. 写入本地业务数据库。
8. 更新 field state、revision 和 applied heads。

收藏和关注：

- ADD head 存在时加入。
- 只有 REMOVE head 时移除。
- ADD 与 REMOVE 同时存在时标记冲突。
- 远程移除本地已有成员时，不直接静默删除，而是生成待确认的 cancellation decision。

阅读状态：

- 同步已读/未读
- 恢复阅读位置
- 阅读历史
- 阅读摘要

远程导入的结果会标记为不可回传，避免“远程修改被重新当作本地修改上传”形成回声。

---

# 9. 上传与下载的循环顺序

当前 `SyncDatabaseExchange` 的核心循环是：

```
1. 处理待完成的初始导入
2. 读取远程快照
3. 下载远程新批次
4. 写入 inbox
5. 投影到本地业务数据
6. 读取一个本地待上传批次
7. 加密、准备并发布
8. 确认远程结果
9. 更新进度和日志
10. 重新读取远程快照
11. 继续下一轮
```

这样可以在上传前先吸收其他设备的新提交，减少基于过期远程 head 上传造成的冲突。

当前设计没有把多个批次合并为一个远程提交。每个逻辑批次都会单独推进一次 GitHub commit。

---

# 10. 冲突和数据安全语义

## 10.1 因果优先

同步不按“最后写入时间”简单覆盖，而是按 causal heads 归并。

用户操作产生的 head 优先于初始导入 head。

## 10.2 收藏和关注冲突

- 只有 ADD：保留。
- 只有 REMOVE：移除。
- ADD 和 REMOVE 并存：保留冲突状态。
- 远程删除本地内容：进入待确认操作。

用户可以选择：

- 接受远程结果
- 保留本地结果

## 10.3 阅读冲突

阅读状态、阅读位置和阅读历史分别按字段处理，避免一个字段的冲突覆盖其他字段。

## 10.4 缺少对象或来源

如果远程事件引用的漫画、章节或作者本地不存在：

- 尝试使用批次中的对象描述创建对象。
- 需要来源或身份信息时进行验证。
- 来源暂不可用时保留 retryable 状态。
- 不创建无法确认身份的错误对象。

---

# 11. 运行状态、重试和恢复

`SyncRunStore` 会持久化：

- 当前阶段
- 已处理、总数、完成、跳过、失败
- uploaded/downloaded
- 本次运行开始时的基线
- 当前 attempt
- 下一次重试时间
- 所有者 session
- 停止原因
- 最近日志

运行记录支持进程重启后恢复。

## 11.1 进程中断

如果同步因进程、系统或生命周期中断：

- 不把它当成用户暂停。
- 当前运行转为 `WAITING_SYSTEM`。
- 自动网络 attempt 回退。
- 下次恢复继续使用持久化进度和 prepared artifact。

## 11.2 用户暂停

用户暂停是明确的用户意图：

- 持久化为暂停状态。
- 周期任务和自动恢复不会覆盖用户暂停。
- 手动继续才会恢复。

## 11.3 网络失败

当前代码中的：

```
MAX_AUTOMATIC_ATTEMPTS = 3
```

按实现和测试表示总网络尝试次数为 3 次：

```
第 1 次失败 → 等待 10 秒
第 2 次失败 → 等待 30 秒
第 3 次失败 → retry_exhausted
```

这里存在文档与实现的不一致：

- roadmap 的文字仍描述为“最多 3 次自动重试，第 4 次失败耗尽”
- 实际代码和测试按 3 次总尝试处理

这是审阅时必须先确认的行为。

## 11.4 Android 调度

Android 使用 WorkManager：

- 网络约束
- 唯一恢复任务
- 延迟执行
- 前台通知
- `resumeIfNeeded`
- WorkManager 自身的 run attempt 重试

因此 Android 具备持久化恢复能力，但真实设备从待机唤醒后是否立即恢复，仍取决于系统调度、电池策略和厂商限制。

## 11.5 Desktop 调度

Desktop：

- 启动时检查未完成运行
- 网络恢复时延迟恢复
- 每分钟检查周期同步
- 进程仍运行时可以恢复

Desktop 进程已完全退出时，应用自身没有操作系统级后台唤醒能力。

---

# 12. 用户界面和可观察性

共享 UI 位于：

- [SyncPanelContent.kt]\(D:/Codex/worktrees/85be/mihon/presentation-sync/src/commonMain/kotlin/mihon/presentation/sync/SyncPanelContent.kt)

面板显示：

- 当前同步设备
- 当前同步状态
- 当前阶段
- 已处理/总数
- 完成、跳过、失败、剩余数量
- 上传和下载方向
- 最近条目日志
- 暂停、继续、重试、处理剩余项等操作
- 等待网络、等待系统恢复、阻塞等状态

日志策略：

- 每个运行最多保留 500 条日志。
- 默认显示最近 5 条。
- 用户可以展开加载更多。
- 终止运行历史默认保留最近 20 次。

---

# 13. 当前性能特征

当前实现的瓶颈主要来自：

1. 每个批次单独创建一次 Git 提交。
2. 每次上传需要多个 GitHub API 请求。
3. 上传前后都会重新读取远程快照。
4. 下载和投影按小批量处理以控制内存和事务大小。
5. 单次最多处理 256 个事件或 512 KiB 明文。
6. 冲突和因果图验证会增加 CPU 成本。

当前没有实现：

- 多个批次合并为一个 commit
- 并行上传多个批次
- Git packfile 优化
- 远程服务端增量 API
- 完全后台常驻同步进程

因此，1000 多条事件不是逐条 Git 提交，但也不是全部打包成一个提交。它们会按批次串行发布。

---

# 14. 失败处理和安全边界

以下情况会阻止继续同步：

- 同步空间身份不一致
- generation 不一致
- 仓库不是私有仓库
- actor 序列出现缺口
- actor 链出现分叉或循环
- 远程 head 回滚
- 已知对象内容改变
- 批次摘要不匹配
- 批次内容与已知批次 ID 不一致
- 事件协议版本不支持
- 父节点跨字段或跨空间
- 因果图存在循环
- 对象身份无法确认

失败类型分为：

- **网络失败**：等待重试
- **系统中断**：等待恢复，不消耗网络预算
- **用户暂停**：保持暂停
- **数据/安全错误**：进入阻塞，需要人工处理
- **冲突**：保留冲突状态或生成用户决策
- **部分完成**：记录已上传、已下载和剩余项

---

# 15. 交给审阅 agent 的重点问题

请重点审阅以下项目：

1. `MAX_AUTOMATIC_ATTEMPTS = 3` 与 roadmap 中“第 4 次失败耗尽”的描述是否统一。
2. Android WorkManager 的重试次数与 `SyncRuntime` 的内部重试次数是否会叠加，导致实际请求次数超过预期。
3. `SyncRemoteSnapshotGuard` 是否覆盖所有下载和上传入口。
4. actor head、索引链和 batch 文件是否始终保持原子一致。
5. 上传失败后 prepared artifact 是否能在进程重启后安全复用。
6. 同一批次重复发布和 GitHub 超时后的幂等处理是否完整。
7. 下载重复批次和相同 ID 不同内容是否都能正确区分。
8. 远程删除收藏/关注时，是否始终进入待确认决策而不是静默覆盖。
9. 远程阅读事件是否会错误地产生本地上传回声。
10. 初始导入是否只能在 setup intent 完成后清理。
11. 数据库事务中业务修改与同步事件是否始终同时提交。
12. Android 待机唤醒后是否有真实设备证据证明能恢复。
13. Desktop 进程退出后的恢复边界是否在 UI 和文档中明确。
14. 单批次单提交的吞吐是否满足大规模历史数据首次导入需求。
15. 旧 aex.11 客户端对当前协议版本、批次大小和加密格式是否兼容。

当前最明确的实现边界是：

- 上传和下载均按批次执行。
- 单个批次不会拆成单事件 Git 提交。
- 多个批次不会合并为单个 Git 提交。
- Android 具备持久化恢复机制，但待机后立即恢复仍需真机验收。
- Desktop 只有应用进程存活时才能执行定时或恢复同步。
