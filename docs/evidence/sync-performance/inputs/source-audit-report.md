# 同步性能源码核查报告

## 核查基线

- Worktree：`D:\Codex\worktrees\85be\mihon`
- 分支：`git branch --show-current` 为空，当前为 detached HEAD
- HEAD：`96dbb69d7aa75acce530d477c3c9e3e23db4d55f`
- 未提交修改：无
- 用户安装包对应提交：未知，本轮没有可靠的安装记录或 ADB 设备证据
- 本轮范围：只读源码核查，没有修改代码、安装 APK、连接真实同步空间或重新运行全量测试

定义：

- `H`：当前远端快照中的全部历史批次数
- `A`：当前远端 actor 数量
- `D`：本轮真正需要下载正文的新批次数
- `B`：本轮需要上传的本地批次数
- `S(H,A)`：一次 `readSnapshot()` 的 HTTP 请求数

---

## 汇总一：生产同步调用链

```
手动 / 启动 / 周期 / 恢复触发
  ↓
SyncCoordinator.synchronize()
  ↓
SyncRuntime.exchange()
  ├─ 读取本地同步连接
  ├─ GET /user
  ├─ GET /repos/{owner}/{repo}
  ├─ claim durable run
  ├─ 处理 INITIAL_IMPORT，每次 50 条
  │   └─ 默认全部处理完后才进入网络交换
  ↓
SyncDatabaseExchange.exchange()
  ├─ 读取本地上传/下载基线
  ├─ retryUnavailable()
  └─ while (true)
      ├─ readSnapshot()
      │   ├─ GET ref
      │   ├─ GET commit
      │   ├─ GET recursive tree
      │   ├─ GET space.json
      │   ├─ GET 所有 index shard
      │   └─ GET 所有 actor head
      ├─ SyncRemoteSnapshotGuard.observe()
      ├─ 遍历 snapshot.batches
      │   ├─ 本地查询是否已收到
      │   ├─ 新批次才 GET batch blob
      │   ├─ 解密、验证
      │   └─ inbox 事务写入
      ├─ projector.project(limit = 50)
      │   └─ 逐字段归并和投影
      ├─ outbox.uploadNext(snapshot)
      │   ├─ 封存一个本地批次
      │   ├─ 复用或生成 prepared artifact
      │   ├─ 加密 batch/index/head
      │   ├─ POST 3 个 blob
      │   ├─ POST tree
      │   ├─ POST commit
      │   ├─ PATCH ref
      │   ├─ readSnapshot() 确认
      │   └─ 本地确认 outbox/batch
      ├─ 写进度和条目日志
      └─ 进入下一轮，重新 readSnapshot()
```

关键位置：

- [SyncCoordinator.kt (line 66)]\(D:/Codex/worktrees/85be/mihon/domain/src/commonMain/kotlin/mihon/domain/sync/runtime/SyncCoordinator.kt:66)
- [SyncRuntime.kt (line 283)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncRuntime.kt:283)
- [SyncDatabaseExchange.kt (line 46)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncDatabaseExchange.kt:46)

源码已确认：

```
val snapshot = transport.readSnapshot(repository, spaceId, generation).getOrThrow()
...
val result = outbox.uploadNext(snapshot) ?: break
...
// The publisher has advanced the durable remote anchor; always obtain a fresh snapshot.
```

发布确认读取、发布成功后的下一轮读取、最终发现没有更多上传批次时的读取，是三个独立调用。发布函数返回的确认快照没有被外层循环复用。

---

## 汇总二：请求数量与历史增长关系

### 一次远端快照

生产路径使用 `spaceMaterial`，因此：

```
S(H, A)
= 1 GET ref
+ 1 GET commit
+ 1 GET recursive tree
+ 1 GET space.json
+ (H + 1) GET index shard
+ A GET actor head
= H + A + 5
```

`H + 1` 中的 `1` 是 bootstrap shard。

如果使用旧的 `indexSecret` 路径、没有 `spaceMaterial`，会少读取一次 `space.json`：

```
Slegacy(H, A) = H + A + 4
```

### 无变化同步

假设 token 不需要刷新，也没有本地上传队列：

```
GET /user
+ GET /repos/{owner}/{repo}
+ S(H, A)
```

也就是：

```
2 + H + A + 5
```

仍然会完整读取当前 tree、所有 index shard 和所有 actor head。

### 仅下载 D 批

```
2
+ S(H, A)
+ D × GET /git/blobs/{batchSha}
```

已收到的批次会在正文下载前跳过，因此旧批次不会因为每次同步都再次下载 batch blob。

### 上传 1 批

正常首次发布包含：

```
初始快照：S(H, A)
发布确认快照：S(H + 1, A)
下一轮快照：S(H + 1, A)
```

另外有：

```
1 × GET /repos/{owner}/{repo}       // publish 再次验证私有仓库
3 × POST /git/blobs
1 × POST /git/trees
1 × POST /git/commits
1 × PATCH /git/refs/heads/{branch}
```

因此一个批次的快照读取次数是：

```
3 次
```

### 连续上传 B 批

在没有其他设备并发提交、每个本地批次使远端批次数增加 1 的情况下：

```
外层快照：
Σ S(H + j, A)，j = 0..B

发布确认快照：
Σ S(H + j, A)，j = 1..B

每批固定发布请求：
B × (1 次仓库私有状态检查 + 6 次 Git Data API 写请求)
```

也就是总快照读取次数：

```
2B + 1
```

ref 冲突重试会增加 tree、commit、ref 请求和确认快照。一次 `publish()` 内部会复用已创建的 blob，但进程重启后会重新执行 blob POST，因为 prepared artifact 没有保存远程 blob SHA。

### 汇总结论

当前小增量同步的工作量同时受两部分影响：

1. 新增数据：新批次正文下载、解密、写入和投影。
2. 全部历史：每次 `readSnapshot()` 都会重新获取和解密全部 index shard、全部 actor head，并遍历全部历史批次元数据。

上传多个批次时，历史成本会被重复放大，因为每批都有发布确认和下一轮快照读取。

---

## 汇总三：结论分类

### 已确认的重复或不必要工作

1. 每个上传批次有发布确认快照，外层下一轮又重新读取一次相同远端 head。
2. 每次快照都重新下载并解密全部 index shard 和 actor head。
3. `SyncRemoteSnapshotGuard.observe()` 在同一快照上多次重新计算 evidence。
4. 每个上传批次都会重复执行仓库私有状态检查。
5. `SyncOutboxStore` 在一个批次上传过程中会多次读取、解码和重新验证同一批次。
6. 下载一个新批次时，事件至少经历多次解码、验证和序列化。
7. 进度计数会重复执行全局 published/remote event 统计查询。
8. 每条条目日志都会独立开启事务，并读取全部日志 key 进行裁剪。

### 已排除的怀疑

1. 当前实现不是一条事件一个 Git commit。
2. 读取快照时不会下载所有历史 batch 正文。
3. 已收到的批次在正文下载前被跳过。
4. tree 创建使用 `base_tree`，不会每次重新上传全部历史文件。
5. projector 没有调用漫画来源或其他外部服务。
6. prepared artifact 在同一次进程内的 ref 冲突重试中会复用 batch/index/head 密文。
7. Android 和 Desktop 使用相同的同步业务链路，没有发现 Android 独立执行了另一套同步算法。

### 仍需实测的成本

1. Android 真机和 Desktop 的真实请求延迟。
2. OkHttp 是否实际复用连接。
3. 代理、DNS、TLS、HTTP/2 对真机耗时的影响。
4. `readSnapshot()` 中 blob 下载、解密、JSON 解析的比例。
5. Android SQLite 事务等待和数据库锁等待。
6. 不同历史规模下的实际请求数、耗时和内存峰值。
7. 真实本地事件的平均批次填充率。
8. GitHub API 对多次 blob/tree/commit 请求的实际吞吐。
9. WorkManager 重试与运行时恢复之间是否产生重复唤醒或重复请求。

---

# 1. 完整同步调用链，以及每批实际触发多少请求

## 结论

源码确认：

- 一个正常同步运行至少执行一次远端快照读取。
- 只要存在本地上传批次，每个批次都会触发：
  - 发布前已有快照
  - `publish()` 内部的确认快照
  - 发布成功后下一轮外层快照
- `publish()` 内部确认得到的快照没有返回给外层复用。
- 上传前会先处理当前快照中的全部下载批次，再处理 dirty 字段，然后才上传本地批次。
- 账号和仓库验证每次运行执行；仓库私有状态在每个上传批次发布时再次执行。

## 关键源码

### 运行入口

[SyncRuntime.kt (line 283)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncRuntime.kt:283)

```
val session = onboarding.session(stored.accountId)
onboarding.verifyRepository(session, connection.repository, stored.repositoryId)
...
SyncDatabaseExchange(
    ...
    onboarding.transport(session.token, material),
).exchange(...)
```

`session()` 会执行：

```
GET /user
```

`verifyRepository()` 会执行：

```
GET /repos/{owner}/{repo}
```

### 下载和上传顺序

[SyncDatabaseExchange.kt (line 89)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncDatabaseExchange.kt:89)

```
while (true) {
    val snapshot = transport.readSnapshot(...)
    ...
    for (entry in snapshot.batches) {
        ...
        val result = inbox.receive(snapshot, entry)
    }
    ...
    while (projector.project(spaceId, generation) == 50) yield()
    ...
    val result = outbox.uploadNext(snapshot) ?: break
}
```

因此，当前正常路径是：

```
完整遍历当前远端批次
→ 下载新批次
→ 投影 dirty 字段
→ 上传一个本地批次
```

### 请求清单

| 调用位置方法和端点触发条件正常次数        |                                    |                           |         |
| ------------------------ | ---------------------------------- | ------------------------- | ------- |
| `SyncOnboarding.session` | `GET /user`                        | 每次运行                      | 1       |
| `verifyRepository`       | `GET /repos/{owner}/{repo}`        | 每次运行                      | 1       |
| `getRef`                 | `GET /git/ref/heads/{branch}`      | 每次快照                      | 1       |
| `getCommit`              | `GET /git/commits/{sha}`           | 每次快照                      | 1       |
| `getTree`                | `GET /git/trees/{sha}?recursive=1` | 每次快照                      | 1       |
| `readSnapshot`           | `GET /git/blobs/{spaceSha}`        | production space material | 1       |
| `readSnapshot`           | `GET /git/blobs/{indexSha}`        | 每个 index shard            | `H + 1` |
| `readSnapshot`           | `GET /git/blobs/{headSha}`         | 每个 actor                  | `A`     |
| `readEncryptedBatch`     | `GET /git/blobs/{batchSha}`        | 批次未被本地跳过                  | `D`     |
| `publish`                | `GET /repos/{owner}/{repo}`        | 每个上传批次                    | `B`     |
| `createBlob`             | `POST /git/blobs`                  | 新批次首次发布                   | `3B`    |
| `createTree`             | `POST /git/trees`                  | 每次发布尝试                    | 至少 `B`  |
| `createCommit`           | `POST /git/commits`                | 每次发布尝试                    | 至少 `B`  |
| `updateRef`              | `PATCH /git/refs/heads/{branch}`   | 每次发布尝试                    | 至少 `B`  |
| `publish` 确认             | 上述快照读取                             | 每次发布尝试                    | 至少 `B`  |

适用条件：

- 以上是 GitHub API 请求次数，不包含 TCP/TLS 内部握手。
- token 即将过期时，`accessToken()` 可能额外调用 OAuth refresh endpoint。
- ref 冲突、网络超时和运行时重试会增加请求。

---

# 2. 远端快照到底读取了什么，是否重复获取历史数据

## 结论

源码确认：

- `readSnapshot()` 会读取 ref、commit、完整递归 tree、space descriptor、全部 index shard、全部 actor head。
- 它不会读取 batch 正文。
- batch 正文在本地判断“已收到/已发布”之后才读取。
- 每次快照都会重新遍历和解密全部 index/head。
- 没有 HTTP 响应、blob 字节、解密结果或解析结果缓存。
- tree 使用递归读取，不做分页。
- 所有文件都通过固定 commit/tree SHA 读取，单次快照内部是一致的。
- `SyncRemoteSnapshotGuard` 只缓存本地验证证据，不缓存远端响应。

## 关键源码

[GitHubGitDatabaseClient.kt (line 73)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/transport/GitHubGitDatabaseClient.kt:73)

```
val ref = getRef(repository)
val commit = getCommit(repository, ref.objectSha)
val tree = getTree(repository, commit.treeSha)
...
val shards = indexEntries.map { entry ->
    decryptShard(getBlob(repository, entry.sha).content, ...)
}
...
val heads = tree.entries
    .filter { it.type == "blob" && it.path.startsWith(".mihon-sync/heads/") }
    .map { entry -> decryptHead(getBlob(repository, entry.sha).content, ...) }
```

读取顺序是：

```
branch ref
→ commit
→ tree
→ tree 中的 space.json
→ 所有 index shard
→ 所有 actor head
→ 内存中验证 index 链
```

### batch 正文读取时机

[SyncDatabaseExchange.kt (line 93)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncDatabaseExchange.kt:93)

```
val alreadyReceived = handler.await {
    sync_inboxQueries.getReceivedBatch(...) != null ||
        localBatch.status == "PUBLISHED"
}
if (alreadyReceived) continue
val result = inbox.receive(snapshot, entry)
```

[GitHubGitDatabaseClient.kt (line 176)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/transport/GitHubGitDatabaseClient.kt:176)

```
val treeEntry = snapshot.tree.entries.firstOrNull { it.path == entry.path }
val stored = decodeStoredBatch(getBlob(snapshot.repository, treeEntry.sha).content)
```

因此：

```
快照读取只拿 batch 路径和 SHA
本地去重判断完成后
才 GET batch blob
```

同一个快照中，已收到批次不会再次下载正文。

### tree 限制

[GitHubGitDatabaseClient.kt (line 637)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/transport/GitHubGitDatabaseClient.kt:637)

```
GET /git/trees/{sha}?recursive=1
...
return SyncGitTree(..., truncated || entries.size > maxTreeEntries)
```

当前行为：

- 单次递归读取完整 tree。
- 不分页。
- tree 被截断或超过 20,000 条时直接失败。
- index 超过 10,000 条时直接失败。

### HTTP 缓存

[SyncHttpClient.kt (line 36)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/http/SyncHttpClient.kt:36)

```
private val client = productionClient.newBuilder().apply {
    interceptors().clear()
    networkInterceptors().clear()
    cookieJar(CookieJar.NO_COOKIES)
    cache(null)
    followRedirects(false)
    followSslRedirects(false)
}.build()
```

源码已确认：

- sync client 禁用 OkHttp cache。
- 没有同步专用内存快照缓存。
- 每次 `GitHubSyncTransport` 创建时都会创建一个新的 `SyncHttpClient` wrapper。
- wrapper 通过 `productionClient.newBuilder()` 继承底层连接池、dispatcher、DNS、TLS 和代理配置，但实际连接复用仍需运行测量确认。

### Snapshot Guard

[SyncRemoteSnapshotGuard.kt (line 23)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/transport/SyncRemoteSnapshotGuard.kt:23)

Guard 持久化：

- 仓库 owner/name/branch
- 最新 head SHA
- 每个远程对象的 fingerprint
- 每个已观察 head 的 snapshot fingerprint
- blocked 状态

即使 head 相同，`evidence(snapshot)` 仍会对传入的 tree、批次元数据和 index 路径重新计算摘要：

```
val evidence = evidence(snapshot)
...
if (guard?.latest_head == snapshot.head && knownHead == evidence.fingerprint) {
    return@await false
}
```

因此 guard 可以省略数据库对象逐项比较，但不能省略本次快照已经发生的网络读取、blob 解密和 evidence 构造。

---

# 3. 批次是否被充分利用，事件生成本身是否昂贵

## 结论

源码确认：

- 批次不会在每次同步请求结束时自动封存。
- 一个开放批次可以跨多个业务事务持续追加。
- 批次在达到 256 事件或超过 512 KiB 明文限制时封存。
- 开始上传时，`SyncOutboxStore.nextBatch()` 会把选中的开放批次封存。
- 事件大小估算不会重新序列化整个已有事件列表，但会重复读取和编码对象描述。
- 上传一个批次时，本地会多次重新读取和解码该批次。
- 当前没有真实业务数据的平均批次填充率统计。

## 关键源码

### 批次封存

[SyncTransactionJournal.kt (line 51)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/journal/SyncTransactionJournal.kt:51)

```
var open = queries.getOpenBatch(...)
...
var encoded = SyncCodec.encode(event)
...
if (open != null && (
    open.event_count >= SyncProtocol.MAX_EVENTS_PER_BATCH ||
        open.plaintext_bytes + eventBytes + 1 + descriptionBytes() >
            SyncProtocol.MAX_PLAINTEXT_BYTES_PER_BATCH
)) {
    queries.sealBatch(...)
    open = null
}
```

[SyncOutboxStore.kt (line 27)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/journal/SyncOutboxStore.kt:27)

```
val stored = getNextUploadBatch(...)
val batch = readBatch(stored)
sync_journalQueries.sealBatch(...)
```

限制定义：

[SyncProtocol.kt (line 9)]\(D:/Codex/worktrees/85be/mihon/domain/src/commonMain/kotlin/mihon/domain/sync/SyncProtocol.kt:9)

```
const val MAX_EVENTS_PER_BATCH = 256
const val MAX_PLAINTEXT_BYTES_PER_BATCH = 512 * 1024
const val MAX_EFFECTS_PER_EVENT = 32
const val MAX_PARENTS_PER_EFFECT = 64
```

### 事件生成成本

每次追加事件会执行：

- `getActiveActor`
- 获取对象字段 causal heads
- 获取开放批次
- 解码已有 `objects_json`
- 合并并重新编码对象描述
- `SyncCodec.encode(event)`
- 写入 event/outbox
- 重新读取每个 effect 的 heads
- 更新 heads
- 更新批次大小和对象描述
- 更新 sequence
- 建立 event field/dependency 索引

[SyncTransactionJournal.kt (line 40)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/journal/SyncTransactionJournal.kt:40)

批次已有事件不会被完整重新序列化，但对象描述 JSON 会被重复解码和编码。

### 业务操作与事件数量

| 业务操作事件数effect 数 |   |                |
| --------------- | - | -------------- |
| 收藏/取消收藏         | 1 | 1              |
| 初始关注导入          | 1 | 1              |
| 初始收藏导入          | 1 | 1              |
| 阅读进度更新，未完成章节    | 1 | 2：恢复位置、阅读摘要    |
| 阅读进度更新，完成章节     | 1 | 3：已读、恢复位置、阅读摘要 |
| 单独标记章节已读/未读     | 1 | 1              |

证据：

- 收藏：[SyncTransactionJournal.kt (line 134)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/journal/SyncTransactionJournal.kt:134)
- 阅读进度：[SyncReadingJournal.kt (line 17)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/journal/SyncReadingJournal.kt:17)
- 阅读状态：[SyncReadingJournal.kt (line 53)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/journal/SyncReadingJournal.kt:53)
- 初始导入：[SyncBaselineStore.kt (line 104)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/journal/SyncBaselineStore.kt:104)

对象描述按批次去重：

```
SyncObjectDescriptions.decode(previousObjects) + descriptions
    .distinctBy { it.objectKey }
```

同一个对象出现在不同批次时，描述会在不同批次中重复保存。

### 上传时重复读取

[SyncOutboxExchange.kt:137-151 (line 137)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/journal/SyncOutboxStore.kt:137)

```
val batch = store.nextBatch(...)
val upload = store.prepared(batch) ?: store.savePrepared(
    batch,
    service.prepare(snapshot, batch, path),
)
...
store.acknowledge(upload, result.publish)
```

在没有 prepared artifact 的首次上传中：

1. `nextBatch()` 读取和解码一次 batch。
2. `prepared()` 再次 `requireBatch()` 并读取、解码一次。
3. `savePrepared()` 事务中再次 `requireBatch()` 并读取、解码一次。
4. `SyncBatchEncryption.encrypt()` 重新编码整个 batch。
5. `acknowledge()` 再次读取和验证 batch。

这属于源码已确认的本地重复工作。

---

# 4. 初始导入是否阻塞首个请求，是否存在重复扫描

## 结论

源码确认：

- 首个网络请求前，默认必须完成全部待处理的初始导入事件生成。
- 初始冻结会扫描本地收藏、关注和公开阅读数据。
- 导入处理按 50 条一个事务执行。
- 每个处理块不会重新读取业务表，而是读取冻结后的 `sync_import_entries`。
- 导入中断后不会重新冻结全量数据，因为 `sync_initial_import` 唯一索引和已有 import 记录会复用。
- 默认路径不允许网络上传和后续 baseline 生成交错执行。
- 设置 `importPaused` 后可以跳过剩余导入进入网络阶段，但 setup 不会完成，pending import 仍然保留。

## 关键源码

### 冻结本地数据

[SyncBaselineStore.kt (line 27)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/journal/SyncBaselineStore.kt:27)

```
bootstrap.awaitReady()
return handler.await(inTransaction = true) {
    SyncLocalJournal(handler).connect(...)
    val existing = sync_importQueries.getInitialImport(...)
    if (existing != null) return@await existing.import_id
    ...
    freezeFavorites(...)
    freezeFollows(...)
    freezeReading(...)
    setImportTotal(...)
}
```

### 首次网络前处理全部 import

[SyncDatabaseExchange.kt (line 61)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncDatabaseExchange.kt:61)

```
while (allowImport()) {
    val importId = getNextPendingImport(...) ?: break
    baseline.process(importId)
    yield()
}
```

直到该循环结束，才会创建 transport 并调用 `readSnapshot()`。

### 分块处理

[SyncBaselineStore.kt (line 58)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/journal/SyncBaselineStore.kt:58)

```
handler.await(inTransaction = true) {
    getImportEntries(importId, limit.toLong()).executeAsList().forEach { row ->
        appendSyncOperation(...)
        removeImportEntry(row.id)
    }
    SyncImportProgress(total, countImportEntries(importId))
}
```

相关 SQL：

[sync_import.sq (line 37)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/sqldelight/tachiyomi/data/sync_import.sq:37)

```
CREATE INDEX sync_import_queue ON sync_import_entries(import_id, id);
```

[sync_import.sq (line 112)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/sqldelight/tachiyomi/data/sync_import.sq:112)

```
getImportEntries:
SELECT * FROM sync_import_entries WHERE import_id = ? ORDER BY id LIMIT :limit;

countImportEntries:
SELECT count(*) FROM sync_import_entries WHERE import_id = ?;
```

适用条件：

- 首次连接或 setup 尚未完成时成本最大。
- 普通增量同步没有待处理 import 时不会执行这些扫描。
- 初始冻结本身是一次全量本地数据扫描，实际耗时需使用真实数据库测量。

---

# 5. 当前上传实现对合并发布有哪些真实约束

## 结论

源码确认：

- 一个 `SyncPreparedUpload` 只描述一个 batch。
- 一个正常发布会把一个 batch、一个 index shard、一个 actor head 放进同一个 tree/commit。
- tree 使用当前 commit 的 `base_tree`，不会重新提交全部历史文件。
- prepared artifact 同时保存：
  - Git 分支基础 head
  - actor 前驱 index path
  - actor 前驱序列号
  - 加密 batch/index/head
- 其他 actor 推进时，通常表现为 ref 更新冲突，然后刷新快照重试。
- 同一 actor 前驱变化时直接返回冲突。
- 同一次 `publish()` 内部会复用 blob；进程重启后会重新创建 blob。
- 读取端没有显式检查“一个 Git commit 只能增加一个 batch”；这是写入端当前行为，不是协议硬性约束。

## 关键源码

### prepared artifact

[GitHubGitDatabaseClient.kt (line 194)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/transport/GitHubGitDatabaseClient.kt:194)

```
return SyncPreparedUpload(
    snapshot.repository,
    snapshot.head,
    encryptedBatch,
    previous?.indexPath,
    previousSeq,
    encryptedIndex,
    encryptedHead,
)
```

其中：

- `snapshot.head` 是 Git 分支 commit SHA。
- `previous?.indexPath` 和 `previousSeq` 是本 actor 的链前驱。

### tree 复用 base tree

[GitHubGitDatabaseClient.kt (line 320)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/transport/GitHubGitDatabaseClient.kt:320)

```
val tree = createTree(repository, current.tree.sha, entries)
createCommit(repository, tree.sha, current.head)
```

[GitHubGitDatabaseClient.kt (line 671)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/transport/GitHubGitDatabaseClient.kt:671)

```
if (baseTree != null) put("base_tree", JsonPrimitive(baseTree))
...
POST /git/trees
```

### actor 前驱检查

[GitHubGitDatabaseClient.kt (line 310)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/transport/GitHubGitDatabaseClient.kt:310)

```
if (previous?.indexPath != upload.previousIndexPath ||
    (previous?.lastSeq ?: 0) != upload.previousLastSeq
) {
    return SyncPublishResult(CONFLICT, ...)
}
```

### ref 冲突恢复

[GitHubGitDatabaseClient.kt (line 340)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/transport/GitHubGitDatabaseClient.kt:340)

```
updateRef(...)
val observed = readSnapshot(...)
if (observed != null && matches(observed, upload)) {
    return PUBLISHED
}
if (refError is SyncHttpException && refError.code in listOf(409, 422)) {
    current = observed
    continue
}
```

### 多批次合并可行性边界

当前接口无法表达多批次原子发布：

- `SyncPreparedUpload` 只保存一个 batch。
- `publish()` 只接受一个 batch artifact。
- `SyncOutboxStore.acknowledge()` 只确认一个 batch。
- `sync_batches.prepared_upload` 每个批次只有一个 artifact 字段。
- 崩溃恢复只知道单个 batch 是否已确认。

因此，多批次一次 commit 至少需要新的：

- 组级 prepared artifact
- 组内 batch/index/head 关联
- 组级崩溃恢复状态
- 组级确认和幂等对账
- 读取端对同一 commit 中多个 batch 的一致性规则

本轮不提出重构方案，只确认当前接口和不变量不能直接支持该优化。

---

# 6. 下载、接收与投影是否串行阻塞，是否重复处理同一字段

## 结论

源码确认：

- 下载批次逐个串行处理。
- 一个批次必须完成读取、解密、验证和 inbox 写入后，才会处理下一个批次。
- 所有当前快照批次接收完成后，才开始 projector。
- `limit = 50` 是字段投影内部的批处理大小，不会触发新的远端快照。
- 同一快照内多个批次影响同一字段时，dirty 标记会合并，通常在全部接收后归并一次。
- 如果新批次在后续外层循环到达，同一字段可能再次归并。
- 缺少来源、描述或身份时，字段会被置为 retryable 状态。
- projector 没有访问漫画源、章节 API 或作者网络服务。
- “批次已接收”和“业务表已投影”是两个独立状态。
- pending cancellation decision 不阻塞上传。

## 关键源码

### 串行下载

[SyncDatabaseExchange.kt (line 93)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncDatabaseExchange.kt:93)

```
for (entry in snapshot.batches) {
    ...
    val result = inbox.receive(snapshot, entry)
    ...
}
while (projector.project(spaceId, generation) == 50) yield()
```

没有 coroutine 并发、批次并行队列或并行 HTTP 下载。

### 接收事务

[SyncInboxStore.kt (line 30)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/inbox/SyncInboxStore.kt:30)

```
val encoded = SyncBatchCodec.rawEncode(batch)
...
return handler.await(inTransaction = true) {
    ...
    batch.events.forEach { event ->
        sync_journalQueries.insertEvent(...)
        indexSyncEvent(event)
    }
    ...
    sync_inboxQueries.saveInboxBatch(..., "RECEIVED", ...)
}
```

`RECEIVED` 表示：

```
密文已下载
→ 已解密
→ 已通过批次验证
→ 事件已写入本地 inbox
```

它不表示已经修改最终业务表。

### 字段投影

[SyncInboxProjector.kt (line 39)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/inbox/SyncInboxProjector.kt:39)

```
val fields = getDirtyFields(..., limit.toLong())
fields.forEach { entry ->
    handler.await(inTransaction = true) {
        if (current.dirty) projectField(current)
    }
}
```

### 缺少来源不会访问网络

[SyncRemoteProjectionWriter.kt (line 44)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/projection/SyncRemoteProjectionWriter.kt:44)

```
suspend fun prepare() = bootstrap.awaitReady()
...
sourceAvailable(sourceId)
```

`sourceAvailable` 是本地 source manager 查询，不会抓取远程源数据。

### 上传是否等待投影

`SyncDatabaseExchange` 会执行：

```
while (projector.project(...) == 50) yield()
```

然后才调用 `outbox.uploadNext()`。

但 projector 对 SOURCE、DESCRIPTION、IDENTITY 不可用字段会清除 dirty 状态并保存状态，因此这些失败不会无限阻塞整个交换。

类注释明确写着：

[SyncDatabaseExchange.kt (line 31)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncDatabaseExchange.kt:31)

```
/** Drains durable queues in bounded transactions; confirmation decisions do not gate exchange. */
```

---

# 7. SQL、因果闭包与事务的实际成本

## 结论

源码确认：

- 一个字段归并会读取该字段的全部关联事件，然后递归读取父节点闭包。
- 闭包读取按 256 个 key 分块。
- 不同字段之间没有共享已经构造好的内存闭包。
- 关键字段、dirty 状态、未索引事件和导入队列有索引。
- 待上传批次、published event 统计、remote event 统计没有专门匹配状态/排序的覆盖索引。
- 本轮没有执行 `EXPLAIN QUERY PLAN`，因此只能指出潜在扫描点，不能断言实际使用了全表扫描。
- 网络请求不在 inbox/projector 数据库事务中执行。
- Android 和 JVM 使用不同事务协调实现，Android 对事务入口使用全局 mutex，JVM 使用每个 handler 的事务 mutex 和 JDBC 线程绑定。

## 关键 SQL

### 字段事件索引

[sync_inbox.sq (line 21)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/sqldelight/tachiyomi/data/sync_inbox.sq:21)

```
CREATE TABLE sync_event_fields (...);
CREATE INDEX sync_events_for_field
ON sync_event_fields(space_id, generation, object_key, field, event_key);
```

[sync_inbox.sq (line 160)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/sqldelight/tachiyomi/data/sync_inbox.sq:160)

```
SELECT sync_events.event_key, sync_events.event_json
FROM sync_event_fields
JOIN sync_events USING (space_id, generation, event_key)
WHERE object_key = ? AND field = ?;
```

### 因果闭包

[SyncInboxProjector.kt (line 328)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/inbox/SyncInboxProjector.kt:328)

```
var next = getFieldEvents(...).map { it.event_key }
while (next.isNotEmpty()) {
    next.chunked(256).forEach { chunk ->
        getInvalidEventKeys(..., chunk)
        getEventsByKey(..., chunk)
        event.effects.flatMap { it.parents }.forEach { following += ... }
    }
    next = following.filterNot { it in visited }
}
```

这意味着某字段的工作量与：

```
该字段历史事件数量
+ 父节点闭包规模
```

有关，而不只取决于最新批次。

### dirty 字段

[sync_inbox.sq (line 52)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/sqldelight/tachiyomi/data/sync_inbox.sq:52)

```
CREATE INDEX sync_dirty_fields
ON sync_field_state(space_id, generation, dirty);
```

[sync_inbox.sq (line 181)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/sqldelight/tachiyomi/data/sync_inbox.sq:181)

```
SELECT *
FROM sync_field_state INDEXED BY sync_dirty_fields
WHERE space_id = ? AND generation = ? AND dirty = 1
ORDER BY object_key, field
LIMIT :limit;
```

### 待上传选择

[sync_journal.sq (line 180)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/sqldelight/tachiyomi/data/sync_journal.sq:180)

```
SELECT *
FROM sync_batches
WHERE space_id = ? AND generation = ?
  AND status != 'PUBLISHED'
  AND event_count > 0
ORDER BY actor_id, epoch, first_seq
LIMIT 1;
```

`s‍ync_batches` 没有针对 `status/event_count/order` 的专用索引。本轮未执行查询计划，暂不能断言实际扫描方式。

### 运行统计

[sync_journal.sq (line 184)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/sqldelight/tachiyomi/data/sync_journal.sq:184)

```
SELECT coalesce(sum(event_count), 0)
FROM sync_batches
WHERE status = 'PUBLISHED';

SELECT count(*)
FROM sync_events
WHERE batch_id IN (
    SELECT batch_id
    FROM sync_inbox_batches
    WHERE status = 'RECEIVED'
);
```

这些统计会在每轮交换和下载批次后重复执行。

### 事务边界

| 操作事务边界                 |            |
| ---------------------- | ---------- |
| 业务操作 + 本地同步事件          | 原业务事务      |
| 初始冻结                   | 一个大事务      |
| baseline 处理            | 每 50 条一个事务 |
| inbox 接收               | 每批一个事务     |
| 字段投影                   | 每字段一个事务    |
| prepared artifact 保存   | 一个事务       |
| 发布确认后的 outbox/batch 更新 | 一个事务       |
| GitHub 网络调用            | 数据库事务之外    |

Android：

- [AndroidDatabaseHandler.kt (line 16)]\(D:/Codex/worktrees/85be/mihon/data/src/androidMain/kotlin/tachiyomi/data/AndroidDatabaseHandler.kt:16)
- [TransactionContext.kt (line 22)]\(D:/Codex/worktrees/85be/mihon/data/src/androidMain/kotlin/tachiyomi/data/TransactionContext.kt:22)

JVM：

- [JvmDatabaseHandler.kt (line 16)]\(D:/Codex/worktrees/85be/mihon/data/src/jvmMain/kotlin/tachiyomi/data/JvmDatabaseHandler.kt:16)
- [JvmTransactionContext.kt (line 24)]\(D:/Codex/worktrees/85be/mihon/data/JvmTransactionContext.kt:24)

Android 使用全局 transaction mutex；JVM 主要按 handler 维护事务状态和 JDBC 事务线程。

---

# 8. 进度、条目日志和 UI 是否引入显著额外工作

## 结论

源码确认：

- 进度写入在同步关键路径中同步等待。
- 下载批次被接收后会更新 totals、phase 和条目日志。
- 上传批次确认后会再次更新 totals、phase 和条目日志。
- 一个批次的日志按对象去重，不是每个 effect 一条，但对象很多时仍可能产生很多事务。
- 每条日志插入都会读取该运行的全部日志 key，然后执行 500 条裁剪。
- 面板打开时每秒刷新一次时间和统计。
- 面板关闭后，面板可见状态的每秒 ticker 会停止，但 controller 的数据库、coordinator 和偏好订阅仍然存在。
- F2～F4 确实增加了本地数据库和 UI 观察工作，实际耗时比例尚未测量。

## 关键源码

### 交换过程中的进度更新

[SyncDatabaseExchange.kt (line 75)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncDatabaseExchange.kt:75)

```
reconcileTotals()
...
progress?.totals(uploadedTotal, downloadedTotal)
```

[SyncDatabaseExchange.kt (line 107)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncDatabaseExchange.kt:107)

```
progress?.phase(SyncRunPhase.MERGING, ...)
progress.logBatch(...)
```

### 日志生成

[SyncDatabaseExchange.kt (line 178)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncDatabaseExchange.kt:178)

```
events.asSequence()
    .flatMap { it.effects.asSequence() }
    .groupBy { it.objectKey.stableKey }
```

一个对象在同一批次内只生成一条日志。

### 每条日志的裁剪成本

[SyncRunStore.kt (line 271)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncRunStore.kt:271)

```
handler.await(inTransaction = true) {
    insertRuntimeLog(...)
    val keys = getRuntimeLogKeys(runId).executeAsList()
    keys.dropLast(maxLogEntries.coerceAtLeast(1)).forEach {
        deleteRuntimeLog(runId, it)
    }
}
```

每条日志至少包含：

```
插入一条日志
→ 读取当前运行所有日志 key
→ 可能删除旧日志
```

### UI 刷新

[SyncPanelController.kt (line 132)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncPanelController.kt:132)

```
state.map { it.visible }.distinctUntilChanged().collectLatest { visible ->
    if (visible) {
        while (true) {
            mutableState.update { it.copy(nowMillis = clock()) }
            queueRefresh()
            delay(1_000)
        }
    }
}
```

[SyncPanelController.kt (line 178)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncPanelController.kt:178)

一次 `refresh()` 会查询：

- pending references
- pending category counts
- pending imports
- pending decision 分页
- bulk job
- 当前运行
- 日志
- 偏好和调度时间

本轮没有测量这些观察查询对 Android 真机的实际占用。

---

# 9. 编解码、压缩、加密和内存复制具体如何执行

## 结论

源码确认：

- 当前没有压缩步骤。
- batch 是 JSON 明文，随后进行 AES-GCM 加密或 v2 space payload 封装。
- index/head 也是 JSON 后加密。
- GitHub Git Data API 使用 JSON 请求体，并对 blob 内容使用 Base64。
- 密码保护模式下，至少存在 payload 内部 Base64 和 GitHub API 外层 Base64。
- HTTP 响应被完整读入 `ByteArray`。
- 已发现多次明文 decode、重新 encode、摘要计算和 payload copy。
- 本轮没有运行内存采样，不能给出峰值。

## 关键源码

### batch 编解码和加密

[SyncCrypto.kt (line 214)]\(D:/Codex/worktrees/85be/mihon/domain/src/commonMain/kotlin/mihon/domain/sync/crypto/SyncCrypto.kt:214)

```
val plaintext = SyncBatchCodec.encode(...).encodeToByteArray()
val digest = engine.sha256(plaintext)
val ciphertext = if (spaceMaterial == null) {
    engine.encrypt(secret, plaintext, binding.canonicalAad())
} else {
    SyncSpacePayloadCodec.encode(engine, spaceMaterial, binding, plaintext)
}
```

解密路径：

[SyncCrypto.kt (line 271)]\(D:/Codex/worktrees/85be/mihon/domain/src/commonMain/kotlin/mihon/domain/sync/crypto/SyncCrypto.kt:271)

```
val plaintext = ...
require(engine.sha256(plaintext).contentEquals(encrypted.plaintextDigest))
SyncBatchCodec.decode(plaintext.decodeToString())
```

### 无压缩证据

代码搜索未发现 gzip、deflate、zstd 或其他压缩步骤。当前转换链是：

```
SyncBatch
→ JSON UTF-8
→ SHA-256
→ AES-GCM / SyncSpacePayload JSON
→ StoredSyncBatch JSON
→ GitHub blob API JSON
→ Base64
→ HTTP
```

### GitHub blob Base64

[GitHubGitDatabaseClient.kt (line 662)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/transport/GitHubGitDatabaseClient.kt:662)

```
put("content", JsonPrimitive(content.toByteString().base64()))
put("encoding", JsonPrimitive("base64"))
POST /git/blobs
```

### 完整缓冲

[SyncHttpClient.kt (line 98)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/http/SyncHttpClient.kt:98)

```
val body = response.body.source().use { source ->
    val buffer = Buffer()
    ...
    buffer.readByteArray()
}
```

响应完整缓冲，单个 HTTP response 上限为 2 MiB。

### KDF

密码派生属于同步空间创建/打开路径，不是每个 batch 的处理步骤。当前性能报告没有重新读取 `SyncSpaceCrypto` 的具体 KDF 行号，因此：

- 未确认每次运行是否重复构造 KDF 对象
- 未测量 PBKDF2 实际耗时
- 从 transport 代码看，batch 处理直接使用已打开的 `SyncSpaceMaterial`

该部分需要单独补充计时证据。

---

# 10. Android/Desktop 的 HTTP 实现和请求执行环境有什么差异

## 结论

源码确认：

- Android 和 Desktop 的同步都使用 OkHttp。
- 两端都把生产 `OkHttpClient` 传给 `SyncRuntime`。
- 同步 client 会清除应用拦截器、network interceptor、cookie 和缓存。
- proxy、DNS、TLS、dispatcher、connection pool 由生产 client 继承。
- Android 和 Desktop 的同步请求数相同，平台差异主要来自底层网络配置、数据库 driver、调度和设备性能。
- 源码无法证明真机是否实际复用连接、走哪条代理线路或使用哪种 HTTP 协议。

## 关键源码

### Android production client

[NetworkHelper.kt (line 20)]\(D:/Codex/worktrees/85be/mihon/core/common/src/androidMain/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt:20)

```
OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(2, TimeUnit.MINUTES)
    .cache(Cache(...))
```

Android client 还可能启用 DoH 和 Cloudflare interceptor，但同步 wrapper 会清除这些 interceptor。

### Desktop production client

[DesktopNetworkHelper.kt (line 90)]\(D:/Codex/worktrees/85be/mihon/app-desktop/src/main/kotlin/mihon/desktop/platform/DesktopNetworkHelper.kt:90)

```
OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(2, TimeUnit.MINUTES)
    .cache(Cache(...))
    .apply { proxy / proxySelector ... }
```

### 同步 client

[SyncHttpClient.kt (line 36)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/http/SyncHttpClient.kt:36)

```
productionClient.newBuilder().apply {
    interceptors().clear()
    networkInterceptors().clear()
    cookieJar(CookieJar.NO_COOKIES)
    cache(null)
    followRedirects(false)
}
```

### token 缓存和刷新

[GitHubAuthClient.kt (line 274)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/auth/GitHubAuthClient.kt:274)

```
if (expiresAt == null || (expiresAt > now && expiresAt - now > 60_000)) {
    return success(current)
}
val result = auth.refresh(...)
```

token 有效期超过 60 秒时不会刷新。刷新过程由 mutex 串行化，并持久化回安全存储。

### 请求域名和端点

正常同步使用：

```
https://api.github.com/user
https://api.github.com/repos/{owner}/{repo}
https://api.github.com/repos/{owner}/{repo}/git/ref/heads/{branch}
https://api.github.com/repos/{owner}/{repo}/git/commits/{sha}
https://api.github.com/repos/{owner}/{repo}/git/trees/{sha}?recursive=1
https://api.github.com/repos/{owner}/{repo}/git/blobs/{sha}
https://api.github.com/repos/{owner}/{repo}/git/blobs
https://api.github.com/repos/{owner}/{repo}/git/trees
https://api.github.com/repos/{owner}/{repo}/git/commits
https://api.github.com/repos/{owner}/{repo}/git/refs/heads/{branch}
```

`Retry-After` 没有在普通 Git Data API 的 `requireSuccess()` 中单独解析：

[GitHubGitDatabaseClient.kt (line 776)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/transport/GitHubGitDatabaseClient.kt:776)

```
retryable = code == 409 || code == 429 || code >= 500
```

实际代理线路、HTTP/2、TLS 握手和连接复用需要在 Android/Windows 生产构建上通过 OkHttp EventListener 或抓包统计。

---

# 11. 重试、冲突恢复与平台调度是否叠加等待或重复工作

## 结论

源码确认存在三层重试：

1. Git ref 发布层
2. `SyncRuntime` durable run 层
3. Android WorkManager 层

这三层的请求次数和等待可能叠加。Coordinator 会合并部分自动触发，但不能把所有调度层重试合并成一个计数器。

当前 `MAX_AUTOMATIC_ATTEMPTS = 3` 表示：

```
整个自动运行的总 attempt 上限为 3
不是“初始请求 + 3 次重试”
```

因此当前源码行为是第 3 次网络失败后耗尽。

## 关键源码

### Runtime attempt

[SyncRuntime.kt (line 323)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncRuntime.kt:323)

```
val exhausted = automatic && run.attempt >= MAX_AUTOMATIC_ATTEMPTS
val attempt = if (exhausted) run.attempt else run.attempt + 1
...
if (exhausted) {
    runStore.finish(..., "retry_exhausted")
}
```

[SyncRuntime.kt (line 402)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncRuntime.kt:402)

```
result.problem == SyncRunProblem.NETWORK &&
    attempt < MAX_AUTOMATIC_ATTEMPTS
    -> WAITING_RETRY
...
private const val MAX_AUTOMATIC_ATTEMPTS = 3L
private val RETRY_DELAYS = longArrayOf(10_000L, 30_000L, 120_000L)
```

实际含义：

```
attempt 1 失败 → WAITING_RETRY，10 秒
attempt 2 失败 → WAITING_RETRY，30 秒
attempt 3 失败 → retry_exhausted
```

roadmap 中“第 4 次网络失败耗尽”的文字与此实现不一致。

### Android WorkManager

[SyncWorker.kt (line 48)]\(D:/Codex/worktrees/85be/mihon/app/src/main/java/eu/kanade/tachiyomi/data/sync/SyncWorker.kt:48)

```
result.problem == SyncRunProblem.NETWORK && runAttemptCount < 3
    -> Result.retry()
```

因此 WorkManager 还有自己的最多 3 次重试计数。

### Git ref 发布重试

[GitHubGitDatabaseClient.kt (line 286)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/transport/GitHubGitDatabaseClient.kt:286)

```
for (attempt in 1..MAX_PUBLISH_ATTEMPTS) {
    ...
    if (refError is SyncHttpException && refError.code in listOf(409, 422)) {
        current = observed
        delay(100L * attempt)
        continue
    }
}
```

`MAX_PUBLISH_ATTEMPTS` 为 3。

### Coordinator 自动触发合并

[SyncCoordinator.kt (line 68)]\(D:/Codex/worktrees/85be/mihon/domain/src/commonMain/kotlin/mihon/domain/sync/runtime/SyncCoordinator.kt:68)

```
val duplicateAutomatic = trigger in AUTOMATIC_TRIGGERS &&
    existing.trigger in AUTOMATIC_TRIGGERS &&
    existing.pending == null
```

周期和恢复触发会合并；手动触发可以排队等待成功后的下一次运行。

### 进程中断不消耗网络 attempt

[SyncRuntime.kt (line 378)]\(D:/Codex/worktrees/85be/mihon/data/src/commonMain/kotlin/mihon/data/sync/runtime/SyncRuntime.kt:378)

```
runStore.finish(..., SyncRunState.WAITING_SYSTEM, "cancelled")
runStore.rewindSystemInterruptionAttempt(...)
```

源码已确认系统中断和网络失败分开计数。

### 仍需复核

需要进一步用请求计数测试确认：

- WorkManager `Result.retry()` 是否会与 durable recovery worker 同时触发。
- 周期任务、恢复任务和前台恢复是否会在边界时刻造成额外调用。
- `SyncCoordinator` 的 skip 是否覆盖所有重入路径。

当前源码只能确认这些调度层同时存在，不能直接证明每种设备状态下都会产生重复网络请求。

---

# 12. 现有规模测试到底证明了什么，还缺哪些测量

## 结论

现有 10k/100k 测试证明了：

- 文件型 SQLite 可以承载大规模事件历史。
- production codec、AEAD、GitHub transport fixture、inbox、projector 和 panel wiring 可以完成收敛。
- 测试覆盖了首次交换、增量交换、无变化交换、待确认决策和 UI bulk 操作。
- 测试会记录请求数、耗时、数据库大小和 JVM heap 采样值。

它没有证明：

- Android 真机吞吐。
- Desktop 与 Android 的真实网络差异。
- GitHub 真实服务请求数。
- 生产连接复用效果。
- 请求数是否随历史线性增长。
- SQL 是否命中预期索引。
- 加密、JSON、SQLite、网络各自占用多少时间。

## 关键源码

[SyncScaleAcceptanceTest.kt (line 68)]\(D:/Codex/worktrees/85be/mihon/data/src/jvmTest/kotlin/mihon/data/sync/SyncScaleAcceptanceTest.kt:68)

```
/**
 * Sequential, file-backed scale acceptance through production HTTP, AEAD, SQL and panel wiring.
 */
for (scenario in listOf(
    Scenario(10_000, 3, 120),
    Scenario(100_000, 10, 10_000),
)) {
    runScenario(scenario)
}
```

测试对象：

- 10,000 / 100,000 个事件
- 3 / 10 个 actor
- 120 / 10,000 个 pending decision
- 文件型 JDBC SQLite
- production codec
- production AEAD
- Git fixture HTTP server
- production transport
- production inbox/projector
- panel controller

### 指标记录

[SyncScaleAcceptanceTest.kt (line 291)]\(D:/Codex/worktrees/85be/mihon/data/src/jvmTest/kotlin/mihon/data/sync/SyncScaleAcceptanceTest.kt:291)

```
val requests = git.server.requestCount
val started = System.nanoTime()
...
"elapsedMillis"
"httpRequests"
"databaseBytes"
"sampledJvmHeapBytesIncludingFixture"
```

测试会分别记录：

- `fixture_initial`
- `first_exchange`
- `fixture_increment`
- `increment_exchange`
- `unchanged_exchange`
- panel 操作阶段
- complete 汇总

但仓库中没有保存这些测试的实际输出结果，本轮也没有重新运行测试。

## 最小后续测量矩阵

| 场景输入必须记录目的         |                         |                                        |                         |
| ------------------ | ----------------------- | -------------------------------------- | ----------------------- |
| 无变化同步              | `H=0/100/1000`，无 outbox | HTTP 请求数、各 endpoint 次数、快照耗时、index 解密耗时 | 验证历史扫描成本                |
| 大历史小增量             | `H=1000`，新增 1 批         | 快照次数、blob 数、CPU、SQLite 时间              | 验证小增量是否反复读取全历史          |
| 首次上传               | 本地 1/4/20 批             | 每批请求数、prepared 时间、发布确认时间               | 验证 `2B+1` 快照关系          |
| 首次下载               | 1k/10k/100k 事件          | D、解密、inbox、projector、日志耗时              | 区分网络与本地处理               |
| Android/Desktop 对照 | 同一账号、同一仓库、同一 H/D/B      | endpoint 计数、DNS/TLS/连接复用、SQLite 时间、总耗时 | 找出平台额外成本                |
| 中断恢复               | 上传期间取消、进程重启             | 是否重复 blob/tree/commit、是否重复 batch GET   | 验证 prepared artifact 恢复 |
| token 即将过期         | access token 剩余 30 秒    | refresh 请求数及总耗时                        | 验证凭据刷新是否插入额外请求          |
| SQL 计划             | 典型 H/D 数据库              | `EXPLAIN QUERY PLAN`                   | 验证潜在全表扫描                |

---

# 最终判断：小增量主要受什么影响

## 远端网络工作量

小增量并不只取决于新增数据。

即使只新增 1 个事件或 1 个批次：

- `readSnapshot()` 仍然读取全部 index shard 和全部 actor head。
- 每次上传还会执行发布确认快照。
- 发布成功后的下一轮又会重新读取快照。
- `H` 越大，单次快照的 HTTP 请求数和解密工作越大。

因此，当前网络请求量至少包含：

```
全历史索引/head 成本
+ 新增 batch 正文成本
+ 每批发布成本
```

## 本地处理工作量

本地投影对单个字段会读取该字段的历史事件和父节点闭包。

因此一个小增量如果修改的是拥有很长历史的字段，仍可能处理较大的历史因果图。

## Android 额外成本

当前没有 Android 独立的同步协议或额外下载循环。Android 额外成本主要来自：

1. 移动网络 RTT、代理、DNS、TLS 和省电策略。
2. Android SQLite transaction dispatcher 和全局事务互斥。
3. WorkManager、前台服务和恢复任务的调度等待。
4. 真机 CPU 对 JSON、AES-GCM、Base64 和 SHA-256 的处理速度。
5. 面板进度、日志和数据库观察在同步期间持续运行。
6. 待机唤醒后可能重新执行恢复路径。

这些是源码可推导的成本来源，实际占比需要在相同 `H/D/B` 输入下对 Android 和 Desktop 进行 endpoint、事务和阶段计时。

当前最明确的性能问题是：**历史数据会反复参与快照读取，连续上传还会把这部分历史成本乘以** **`2B+1`** **次快照。**

用量：不可得
