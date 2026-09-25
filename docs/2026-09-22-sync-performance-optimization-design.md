# 同步性能优化设计

日期：2026-09-22
状态：IN_PROGRESS / PARTIAL_IMPLEMENTATION / NOT_VALIDATED
实施入口：[同步性能优化 Roadmap](roadmap/2026-09-22-sync-performance-optimization-roadmap.md)

<a id="scope"></a>
## 0. 文档权威、依据与实施边界

本文依据用户提供的源码核查报告制定优化方案。已知源码基线为 `96dbb69d7aa75acce530d477c3c9e3e23db4d55f`，核查 worktree 为 `D:/Codex/worktrees/85be/mihon`，当前候选 HEAD 为 `f0e3d4f21a50620896e5288cccf1a23779595dae` 并含未提交改动；`96dbb69d7a` 仅为历史基线。用户安装包对应提交未知；没有真实 GitHub 写入、真机测量或正式发布验收。[R1：核查基线]

当前候选已包含 L1/L2 缓存、分层 tree、manifest/发现、确认复用及部分计数、限流、REST inline/raw 代码；这些跨阶段未提交内容尚未完整验收。GraphQL 仅存在未接入默认发布链路的候选适配文件，不等于已完成可启用实现。不得沿用早期“仅 L1、其余未实施”的描述，亦不得把现有代码当作性能收益证据。

资料编号：

| 编号 | 材料与用途 |
| --- | --- |
| [R1](evidence/sync-performance/inputs/source-audit-report.md) | 《同步性能源码核查报告》：实现事实、调用链、源码位置及待测事项的主要依据 |
| [R2](evidence/sync-performance/inputs/2026-09-21-sync-repair-design.md) | 《同步实现审查修复设计》：恢复、进度、日志、数据库谱系、授权及发布边界 |
| [R3](evidence/sync-performance/inputs/2026-09-21-sync-repair-roadmap.md) | 《同步审查修复 Roadmap》：已登记历史状态与尚未完成的验收；其原有勾选不代表本轮通过 |
| [R4](evidence/sync-performance/inputs/implementation-review.md) | 《同步设计审阅说明》：协议、存储结构、冲突规则和原有安全边界 |
| [E1–E7](evidence/sync-performance/external-references.md) | 2026-09-22 查阅的 GitHub、SQLite、Android 官方资料；仅用于外部 API 能力和测试工具约束 |

输入原样副本及 SHA-256 见 [source-manifest.json](evidence/sync-performance/source-manifest.json)。输入之间有矛盾时，保留矛盾记录：实现事实采用 R1；既定产品行为采用 R2。本设计新增决策明确写为“采用”“要求”或“本轮”。不能把设计目标登记成现有实现。

### 0.1 分阶段交付

**阶段 A：本轮必做的协议兼容性能优化。** 完成观测基线、对象缓存、快照复用、增量发现、分层 tree 读取、局部数据库优化、REST 单批发布请求聚合、raw 下载、统一限流与恢复，以及自动化和真实设备验收。不改变事件协议、加密封装、远端目录和批次上限。

**阶段 B：有证据才启动的扩展。** GraphQL 发布适配、下载与本地处理流水线、多批次原子发布、压缩新格式、初始导入与同步交换交错、历史压缩/检查点、原生 Git 传输。第 17 节定义启动条件、关键约束及附加测试；未启动不阻塞阶段 A，但不得将阶段 B 记为已实现或声称首次同步/容量问题已全部解决。

本轮只在核定 worktree 实施。不得提前合并另一开发分支，不擅自安装用户设备，不向用户真实同步空间写入，不以清库、重建仓库、重新生成 actor/generation 或强制推送获得性能结果。任何真实远端写入、数据库副本取得及正式包覆盖安装，均需对应授权。[R2 §2；R3 §1]

### 0.2 审议后的执行决策（2026-09-25）

保留共享链路、不可变对象缓存、已验证快照复用与事务优化，以及冻结制品、未知结果先对账、scope 隔离、guard/发现队列原子提交全部安全约束。执行顺序调整为：

1. 收敛当前候选并补测首次导入、首次上传确认、大量上传、首次下载、暖增量与恢复；没有优化前执行证据时只登记 candidate-only。
2. 完成缓存、确认复用、持久发现和恢复闭环，优先消除新增磁盘扫描放大。未通过正确性门禁的既有代码保持 PARTIAL。
3. 用完整同步收益验证 REST inline、raw 和已测得的 SQL/日志热点；不为追求请求数删减安全验证。
4. 首轮耗时分解后即可比较多批次发布的收益/恢复成本；GraphQL 与下载流水线仅在剩余瓶颈证据成立、冻结收益目标及实施预算后启动。
5. 上述路径仍不能满足首次同步目标时，再比较原生 Git；比较同数据、同制品的应用总耗时与资源，不以 pack 传输耗时代替同步完成。

本修订不新增产品入口：使用现有同步面板触发并反馈真实导入、上传、接收/投影及恢复进度。用户可见语义保持一致。复用现有共享服务、fixture 和指标入口，不另建同步引擎或平台专属业务实现。新建指标只在测量所需边界增加，避免每事件落库。

工作树状态以 roadmap §8/§9 与实际源码、逐项证据为准；旧核查报告是历史输入。存在实现不等于通过集成/性能验收，文件名包含 GREEN 也不等于当前 diff 已全绿。首次同步新增门禁见 §15.11。

<a id="baseline"></a>
## 1. 已确认的问题与待测边界

| 问题 ID | 基线事实 | 本轮处理 | 依据 |
| --- | --- | --- | --- |
| F01 | 每次 `readSnapshot()` 获取并解密全部 index/head，无对象缓存 | 内容寻址缓存、已验证快照复用 | R1 §2 |
| F02 | 发布确认快照未返回外层，下一轮再次获取 | 发布结果返回已验证快照；终态只做必要 ref 探测 | R1 §1 |
| F03 | 每轮遍历全部历史批次并逐批本地去重；batch 路径使用列表线性查找 | 持久增量发现、待处理队列、路径索引 | R1 §2 |
| F04 | 每批 3 个 blob 加 tree/commit/ref，共 6 次写请求 | 单批 API 聚合；保留旧发布器作为受控兼容实现 | R1 §5 |
| F05 | 接收逐批串行，下载、解密、写库不能重叠 | 先测串行 raw；收益证据成立才实施有界接收流水线 | R1 §6 |
| F06 | 同一批次反复读取、解码、验证；因果闭包不跨字段复用 | 批次不可变工作对象、短生命周期缓存 | R1 §3、§7、§9 |
| F07 | 每轮/每批全局计数；每条日志独立事务和裁剪 | 事务内事实计数、日志批量写入、恢复对账 | R1 §7、§8 |
| F08 | 初始导入逐块 COUNT，全部生成后才进入数据交换 | 优化剩余计数；阶段 A 保持导入与因果顺序 | R1 §4 |
| F09 | Runtime、发布冲突、WorkManager 存在多层重试；预算与 R2 不一致 | 共享持久预算、统一 HTTP 分类、调度只负责唤醒 | R1 §11；R2 §3 |
| F10 | recursive tree 截断即失败；20k tree、10k index、2 MiB 响应限制 | 分层读取、分类型响应预算、明确容量边界 | R1 §2、§9 |
| F11 | R1 的问题描述称 `getFieldEvents` 缺少 space/generation 条件，但同一报告所贴 SQL 已含这两个谓词 | 以实查 SQL 与隔离测试为准：当前生产查询和 `getAffectedFields` 均带 `space_id`、`generation` 过滤；本轮不改查询语义，只将 R1 该项记为报告内部矛盾 | R1 §7；本轮 P0 定向复核 |

**P0 定向复核更正（2026-09-24）**：原源码核查报告的 F11 文字与报告中 `getFieldEvents` SQL 互相矛盾。逐项读取当前 `data/src/commonMain/sqldelight/tachiyomi/data/sync_inbox.sq` 后确认，`getFieldEvents` 同时限制 `sync_event_fields.space_id/generation`；`getAffectedFields` 的根查询、递归依赖和最终字段查询也都限制相同作用域。published/remote totals 的 SQL 同样绑定 space/generation。因此该项不构成已证实的跨空间查询 bug；T39 仍须用真实 production wiring 的隔离测试覆盖，不能以源码文本检查替代。

已排除：旧 batch 正文没有在每次快照中重下；已有正文下载前去重；创建 tree 使用 `base_tree`；projector 未请求漫画源网络；Android/Desktop 共享同步业务链路。[R1：汇总三]

待测：真实延迟、连接复用、代理路径、各阶段 CPU/SQL 时间、批次填充率、设备内存和 WorkManager 重入。不能依据源码断言这些因素的耗时占比。[R1：汇总三、§10–12]

### 1.1 请求数量基线

令 `H` 为历史批次数，`A_h` 为 actor/epoch head 文件数，`B` 为本次上传批次数，`D` 为新下载正文批次数。R1 的 `A` 在跨 epoch 场景下应按实际 head 文件数解释。

生产 `spaceMaterial` 路径、token 无需刷新、无重试/并发、actor head 数量固定时：

```text
S(H,A_h) = H + A_h + 5
N_noop = 2 + S(H,A_h)
N_download = 2 + S(H,A_h) + D
N_upload = 2 + (2B+1)(H+A_h+5) + B(B+1) + 7B
```

`7B` 包含每批私有仓库复查和 6 次写请求。若本次首次发布新增 actor/epoch head，令 `A_j` 为第 j 批发布后的实际 head 文件数，应使用以下未化简式，不能直接套用固定 `A_h` 公式：

```text
N_upload_variable_heads = 2 + S(H,A_0) + 2 × Σ[j=1..B] S(H+j,A_j) + 7B
```

无历史 fixture 应采用协议允许的空空间状态；默认 `H=0,A_0=0`，不能为满足公式凭空生成两个没有合法批次链的 head。上述公式来自 R1 调用链推导，不能标作实测。基线测试先在小样本验证公式，不在真实 GitHub 上运行数万请求的基线压力测试。

<a id="goals"></a>
## 2. 目标、不变量与非目标

### 2.1 交付目标

G01：暖缓存且远端未变化时，不下载历史 index/head/batch，不遍历全部历史批次逐一查询，不重新构造整份 evidence。

G02：暖缓存小增量只获取未知/变化对象；连续上传不再产生 `B×H` 次历史 blob 请求、历史批次去重查询或全量 manifest 持久化。

G03：减少单批写 API 请求和下载表示层开销；所有收益均计入完整确认、限流等待及恢复成本。

G04：日志/计数不再随批内对象数产生同等数量的小事务；减少重复编解码，不改变业务事实。

G05：Android 与 Desktop 在相同输入、同等缓存状态下产出相同协议结果；真实性能单独记录。

G06：安全、不丢事件、幂等、恢复、旧客户端兼容和数据库升级同时通过。性能指标通过不能覆盖任一正确性失败。

### 2.2 必须保留的不变量

| ID | 不变量 |
| --- | --- |
| I01 | 用户业务写入与本地事件/journal 同事务；事件身份、字段、parents 和已封存内容不可变 |
| I02 | prepared artifact 在发送前持久化；任何结果未知时复用原制品，先对账，不生成另一套 nonce/密文 |
| I03 | batch/index/head 随同一次 ref 发布原子可见；actor/epoch 序列连续且前驱一致 |
| I04 | 同一身份同内容幂等；同身份异内容拒绝；不使用 force push 解决冲突 |
| I05 | 新快照完整检查空间、generation、已知对象修改/消失、回滚、链缺口/分叉/循环；缓存不得缩小检查范围 |
| I06 | 固定 commit/tree 的读取一致；来自不同远端时点的对象不能未经验证混成一个快照 |
| I07 | 仓库身份、私有属性、账号绑定及连接修订检查保留；缓存不替代权限和当前连接检查 |
| I08 | inbox 接收、实际投影、上传确认分别计数；业务恢复不依赖 UI 计数；重复执行不双计 |
| I09 | 远端删除继续进入原有待确认流程；远端投影不产生本地回传事件；用户操作与 INITIAL_IMPORT 优先级保留 |
| I10 | 暂停、取消、耗尽、系统中断、网络失败含义保留；同一作用域最多一个有效发布 owner |
| I11 | 可清理缓存与 durable guard/队列/冻结制品严格分离；缓存清空不能使已知历史变为“首次信任” |
| I12 | 新版本不破坏两条已交付数据库谱系，不清库、不降 user_version、不覆盖已发布迁移 |

I01–I10 来自 R2/R4；I11 是缓存引入后对现有保护的落实；I12 继承 R2 §2。

### 2.3 阶段 A 不做

不扩大 256 事件/512 KiB 明文/32 effects/64 parents 限制；不改变远端加密格式或目录；不把事件合并掉以减少历史；不替换 reducer 语义；不放弃历史篡改检测；不增加全后台常驻服务；不以代理或换设备作为必要前提。

<a id="architecture"></a>
## 3. 目标架构与职责

沿用现有 `domain/data/presentation-sync` 与平台 adapter，不建立第二套同步系统。以下名称表达拟议职责；实现优先在既有类中提取最小接口，避免为每个职责机械增加类。

| 职责/拟议接口 | 输入与输出 | 接入位置 |
| --- | --- | --- |
| `SyncMetrics` | 分阶段 span、请求/字节/SQL/缓存计数 | runtime、HTTP、codec、database handler |
| `SyncObjectStore` | 固定对象 ID → 有界原始内容及校验结果 | `GitHubGitDatabaseClient` 下层 |
| `SyncSnapshotResolver` | 当前 ref、上次已验证锚点 → `VerifiedSnapshot` + delta | 现有 `readSnapshot()` |
| `SyncDiscoveryStore` | 新快照 delta → durable 待接收/待对账工作 | 现有 inbox/远端批次存储 |
| `PreparedBatchContext` | 冻结批次、摘要、已解析事件/描述 | `SyncOutboxStore/Exchange` |
| `SyncCommitWriter` | 固定文件集合、预期基线 → 发布候选结果 | 现有 GitHub 发布器 |
| `SyncRequestGate` | 身份/限流状态 → 允许执行或下次到期 | HTTP 与 runtime 共用 |
| `BatchFactsWriter` | 本次确认事实 → 计数/日志原子提交 | runstore、inbox、outbox |

`VerifiedSnapshot` 至少携带：作用域、固定 commit/rootTree、guard anchor revision、manifest revision、keyContextRevision、验证版本、actor 游标、路径索引/索引句柄及新增批次集合。它只能由完整验证路径或已验证且完整的持久快照恢复产生，禁止公开任意构造入口。

作用域 `SyncScope` 至少包含：API origin、稳定 repository ID、branch、account binding ID、connection revision、spaceId、generation。原始对象缓存可以按更窄的内容身份复用；执行许可、验证结果和队列必须绑定完整 scope。

<a id="cache"></a>
## 4. 对象缓存、快照持久化与信任边界

### 4.1 缓存层次

采用同步专用缓存，不直接打开通用 OkHttp 私有响应缓存。

| 层 | 内容 | 生命周期与信任 |
| --- | --- | --- |
| L1 | 有界原始对象、已解密/解析索引、批次工作对象 | 当前 runtime/活动运行内；只缓存验证成功结果；连接/密钥上下文变化即失效 |
| L2 | 固定 OID 的原始 blob 密文/封装和 tree 记录 | 私有应用目录持久化；可淘汰；不保存凭据、密码、派生密钥 |
| 已验证 manifest | 当前 scope 的路径/OID、actor 前沿、索引元数据和验证版本 | 本地数据库中的可重建派生状态；只有完整版本可供快速恢复 |
| durable guard/业务状态 | 已知对象证据、回滚锚点、inbox/outbox、prepared artifact | 必须持久保留；不能归入 LRU，也不能随缓存重建清空 |

原始 blob 键：`apiOrigin + repositoryId + objectFormat + objectOid`。解析结果键额外包含 `spaceId + generation + storagePath + objectKind + keyContextRevision + codecVersion + validatorVersion`。原始字节相同不代表路径绑定/AAD 相同。

对 blob 采用独立验证过的 Git 对象 OID 计算工具，或仓库已有等价实现，检查收到的原始字节与声明 OID 一致；未知对象格式安全失败。存储文件还记录长度与本地 SHA-256 防止磁盘损坏。Git OID 不替代 AEAD、明文摘要与协议验证。tree API 记录需校验请求 OID、条目类型、路径和本地完整性；未经验证的服务端 JSON 不直接成为 `VerifiedSnapshot`。

首次解密执行既有 AAD/摘要/协议验证。L1 复用的前提是相同验证上下文与不可变字节；校验规则升级后重新验证，不能仅沿用一个 `verified=true`。

### 4.2 缓存写入和崩溃顺序

原始对象写临时文件、完成长度/摘要检查后原子替换到 OID 路径；数据库仅在文件完整后登记可用状态。若文件系统不支持同目录原子替换，采用临时状态与恢复校验，不假设已持久成功。

新快照的 manifest 变更、guard 锚点更新、待发现批次登记必须在同一数据库提交中成为可见，或以一条原子发布的 revision 指针引用完整 staging 数据。文件系统与 SQLite 无跨介质事务：允许无引用孤儿缓存，不允许数据库将缺失正文等同于已接收。

崩溃后：不完整 staging 丢弃；guard 不回退；缺失/损坏缓存标 MISS 并重新读取；已接收、已发布和 prepared 状态保持。存储空间不足优先放弃可重建缓存，业务事务失败应明确停止，不伪造完成。

### 4.3 持久数据设计

优先复用现有 guard/inbox 表；下列为所需信息，最终表名与迁移编号在 P0 核对仓库后登记。

| 记录 | 关键字段/唯一性 | 更新规则 |
| --- | --- | --- |
| cache object | repo/OID/type、length/checksum、file/state | 仅完整对象可命中；淘汰不触动业务记录 |
| snapshot header | scope、commit/tree、manifestRevision、guardRevision、keyContextRevision、validationVersion、complete | 完整发布后替换当前指针 |
| manifest path | scope/path → type/OID，索引必要元数据 | 对 delta UPSERT；不为每个 commit 复制全部历史 |
| actor cursor | scope/actor/epoch → indexPath/lastSeq/headOid | 前驱验证成功后推进 |
| discovered batch | scope/actor/epoch/batchId、路径/OID/摘要、状态 | 唯一登记，与既有 inbox 对账；不建立独立矛盾的成功状态 |
| publish facts | scope/batchId/run/segment/direction/checkpointKey | 新确认才更新计数；可依据业务状态恢复 |

完整 manifest 允许冷启动时一次恢复索引，复杂度为 `O(H)`；每批发布禁止完整复制 manifest、复制整棵对象图或重写全历史 JSON，否则会把网络放大转移成本地 `O(BH)` 写入。采用原位事务增量或不可变结构加 delta；对旧对象的安全证据保留原有查询能力。

### 4.4 空间、密钥与缓存生命周期

退出账号、解绑/切换仓库、连接 revision 改变、space/generation 切换时取消对应 owner，清除 L1，关闭旧作用域句柄；L2 原始对象的物理删除可延后，但新作用域不能读取旧验证结果。密钥不可用时不能利用缓存绕过解锁/连接要求。

缓存损坏通常是可恢复 MISS；从服务器重新取得后仍未通过 AEAD/协议验证，按原有数据错误处理。禁止将所有校验失败当 MISS 循环重试。

阶段 A 初始资源预算为设计值：L1 原始/解析可淘汰对象共 16 MiB、L2 64 MiB、下载预取容量见 §8。manifest 与已有 guard 的实际堆/数据库占用另测，不能把这些预算宣称为进程总内存上限。达到预算只淘汰可重建对象；活动操作固定引用的对象用 lease 保护，释放后再淘汰。

2026-09-25 候选的 L2 容量与访问账本在进程内按 FileSystem 实例身份及规范化目录共享，最多保留 4 个目录、合计 20,000 条记录。冷启动重建一次，正常命中和追加增量维护；预计超过磁盘容量时再核对目录并淘汰。单目录超过记录上限后保留降级标记，只验证读取已有对象、停止新增缓存写入；该登记被淘汰或进程重启后才重新盘点，避免超限后每次操作重新扫描。外部删除和损坏在访问时处理，文件或 marker 写入/删除失败使账本失效并保守重建。此账本仅协调同一进程的缓存操作，不提供跨进程写入协调或对任意外部写入的实时容量保证；guard、发现队列和确定的上传制品不受其淘汰影响。目录记录数量上限也不等于应用总内存上限。

<a id="snapshot"></a>
## 5. 快照解析、tree 增量与安全验证

### 5.1 暖缓存不变路径

```text
检查暂停/取消/耗尽/到期与执行资格
→ 按原安全规则验证会话和仓库
→ GET ref，取得 C
→ 先检查 scope/guard blocked/已知回滚
→ C 等于完整且密钥上下文/验证版本匹配的当前锚点
→ 复用 VerifiedSnapshot/持久 manifest
→ 处理本地 pending inbox/projector/outbox
```

不能在检查 blocked/回滚前因 OID 命中直接返回。相同 tree、不同 commit 也必须执行现有 guard 的提交/回滚规则；不能自动按“内容没变化”接受。首次未知状态和缓存不完整均进入完整解析。

进程重启、持久快照完整且 scope/keyContextRevision 相同时，允许不重新获取历史 blob；L1 缺失时允许一次加载本地 manifest，不得每次内层循环重载。validator 版本变化可触发一次重新验证，必须计量并说明。

### 5.2 变化路径和分层 tree

固定 C 后取得 root tree。默认使用不带 `recursive` 参数的 tree 读取；相同子树 OID 复用已验证映射，变化子树继续展开并计算路径增删改。`recursive=false` 仍会启用递归，因此非递归请求必须完全省略该参数。[E1]

2026-09-25 冷读优化：T77 定位首次连接的五次串行 tree GET 后，允许具备生产 manifestStore 与绑定上下文、当前客户端尚无已验证快照且同 scope 没有持久 manifest 的冷读取使用一次 `recursive=1` 请求。它不是每个新 head 都启用的策略；客户端已接纳快照后，或新客户端在同 scope 发现既有 manifest 时，变化仍走原分层路径。Onboarding 与 Runtime 复用同一绑定算法及 manifest store，presence 检查只决定禁用冷读，不能准入快照。只有完整响应能重建每个目录的直接子项，且各子树与根目录按 Git 对象规范计算的 OID 全部匹配，才可使用展开结果。校验包含路径唯一、父目录存在、合法类型/mode、NUL/非法路径、深度、对象格式、循环及原资源上限；Git 名称按 UTF-8 字节及目录排序规则处理，不能用语言默认字符串排序替代。

递归响应截断或 HTTP 200 正文超限时放弃该结果并回退原分层读取；结构或摘要不符直接失败，不持久化候选 manifest。授权、限流、服务端失败与断连不通过递归/分层切换绕过原请求门禁。平铺响应及派生目录不能作为未经验证的原始 tree JSON 写入普通 L2 缓存，后续 guard、AEAD、发现原子事务保持不变。该冷读优化已通过真实 Git 生成的 SHA-1/SHA-256 独立固定向量、恶意结构契约及原 T77 七样本阈值；这是本地受控证据，真实设备与网络仍须验收。[Git 对象说明](https://git-scm.com/book/en/v2/Git-Internals-Git-Objects)、[GitHub tree API](https://docs.github.com/en/rest/git/trees#get-a-tree) 是格式与接口依据，实际收益以本项目测量为准。

最初可以用“完整 tree + 缓存 blob”的过渡实现验证缓存价值，但 P3 退出前必须完成分层读取和不变子树复用。tree 响应截断、结构不完整、目录循环/过深、重复路径、非法类型不能成为可发布 manifest。递归读取可保留为受控兼容/诊断路径，不将截断结果用于业务。[E1]

目录展开另设资源上限：每次读取快照，根目录与每个子目录路径的访问均计数，包括不同路径引用同一 OID 的重复访问。上限为 `maxTreeEntries.toLong() * 4 + 256`，默认 80,256；它与原 blob 数量上限、单层条目上限、深度 256 分别生效。额外 256 保留深链余量，乘数 4 为目录占比预留空间，不代表所有 Git 合法形状均可接收。超过时明确失败，不发布部分 manifest、不推进 ref；不能只统计不同 OID，否则空子树 DAG 仍可能造成大量展开。实现以有界空树 DAG、目录预算边界及既有文件/深度边界契约验收。

同一 actor/epoch 的目录目前保存全部历史批次。即使分层读取，向该目录新增一个文件仍可能需要列举该目录全部直接子项。**阶段 A 保证消除旧 blob 重读，不保证 tree 字节与本地所有验证完全为 `O(新增数据)`。** 记录每个变更目录的条目数；不凭请求数变少宣称目录成本为常数。远端目录分片属于阶段 B。

### 5.3 新增索引与旧对象保护

从变化路径确定新增 index、变化 head 及相关 batch 路径。新 index 后缀必须连接到同作用域已验证 actor cursor；新 actor/epoch 从合法起点验证。无已验证前缀、缓存不完整、前驱冲突或异常路径时退回完整链验证。

验证范围必须包含：旧不可变 index/batch 的修改或消失、head 的合法推进、bootstrap 唯一与完整、space descriptor、索引与 batch 对应、路径绑定、重复身份/区间、链连续/分叉/循环。未变化子树可以依据旧完整证据复用；head 未变化但历史文件消失必须被 tree delta 发现并阻止同步。

第一实现保留现有 guard 算法作为差分 oracle。相同快照的 evidence 只构建一次；新快照允许保守的全量本地 evidence 构造，耗时独立统计。只有证明与原 guard 等价后，才把新快照验证缩减为 delta。不能以“优化需要”修改原回滚允许集合。

建立 `path → entry` 索引后按路径读取 batch；构建前检查重复路径，不能用 `associateBy` 静默覆盖。路径索引使用不可变结构共享或局部更新，不能每批复制整张 map。

### 5.4 快照与发现的原子提交

验证产生 `CandidateSnapshot(oldRevision, C, delta, newBatches)`。提交时再次检查 owner fencing token、连接 revision、guard revision 未改变；不匹配就丢弃候选并重算，不允许旧 owner 覆盖新状态。

同事务提交 guard/manifest/actor cursor 与新批次发现记录。提交失败不推进任一“已处理”游标。这样即使在快照保存后立即杀进程，下次 ref 未变化也能从 durable 队列取得尚未下载批次。

未知批次经集合 SQL 登记，不逐个扫描全历史。缓存丢失而 guard 尚在时可以重新构建 manifest；不能清空 guard 后当作第一次加入空间。

<a id="exchange"></a>
## 6. 交换循环、发布确认复用与恢复

阶段 A 保留“接收当前快照新增内容 → 投影 → 上传”的因果处理顺序，仅调整重复发现及对象复用。[R1 §6；R4 §9–10]

```text
取得/恢复 run、scope 与执行权
完成该阶段要求的 INITIAL_IMPORT 生成
snapshot = resolver.refresh(currentAnchor)
循环：
  从 durable 队列接收 snapshot 对应的未知批次
  排空本轮可处理 dirty 字段，保留原 unavailable/decision 语义
  batch = outbox.nextPreparedOrSealedBatch()
  若没有 batch：进入收尾
  result = publisher.publish(snapshot, preparedBatch)
  若结果确认成功：
    提交本地确认及事实
    snapshot = result.verifiedSnapshot
    继续；不再立即调用完整 readSnapshot
  若基线冲突：按冲突规则刷新、接收/投影后再尝试
  若网络/限流/系统停止：持久状态并退出
收尾：
  有实际交换工作时做一次 final ref probe
  ref 未变：完成截至该 observed commit 的本轮工作
  ref 已变：登记下一 segment，执行有界追赶或保留待恢复工作
```

无变化且无本地待办时不增加 final probe；正常纯上传 `B` 批的完整快照解析最多 `B+1` 次（初始及各次确认），其中旧对象依赖缓存；终态额外 ref 不等于额外完整快照。

收尾最多 3 个连续追赶 segment，之后保留持久待办交给既有 scheduler，UI 显示仍有后续同步。该值为本轮有界调度参数，不能把“达到上限”标为全部远端已同步。新增本地操作在总量冻结后进入新 segment，避免无穷扩大当前分母。

`publish()` 返回的快照可能包含其他设备的并发变化；外层必须处理其 delta，不能只更新本 actor。发布前/确认后 scope 或 owner 变化时禁止写入旧运行计数。

恢复先处理已发送但结果未知的 prepared artifact，再决定是否接收自身远端批次；远端完全匹配可幂等确认，不能把本机已提交事件重新当新远端事件投影/计数。已接收但未投影、已发现但未接收、unavailable 转可处理、等待删除确认均是独立状态。

<a id="upload"></a>
## 7. 上传请求聚合与不确定结果处理

P0记录实际请求中的API版本头、Accept、认证类型和分支保护配置；没有版本头也如实记录。外部文档示例不代表当前应用使用的版本。本轮不顺带升级API版本；确需调整时单独登记版本变化及响应契约回归。HTTP fixture应覆盖本轮固定版本的实际字段和错误形态。

### 7.1 策略选择

单个逻辑批次继续独立持久化、确认和恢复。同一份 prepared 文件集合复用以下发布策略；阶段 A 必做 REST，GraphQL 为条件扩展：

| 策略 | 正常写请求数 | 选择规则 |
| --- | --- | --- |
| `REST_BLOBS` | 6 | 现有兼容实现及差分基准；保留安全修复 |
| `REST_INLINE_TREE` | `3 + q` | 默认优化路径；`q` 为不能无损作为 UTF-8 content 写入、必须单独创建 blob 的文件数 |
| `GRAPHQL_SINGLE_BATCH` | 1 | 单批更高吞吐候选；功能开关默认关闭，必须完成实际凭据/API 契约验收后启用 |

REST tree API 支持直接提供文件 `content`，并与 `base_tree` 合成；同一 entry 不能同时提交 `content` 和 `sha`。[E1] 对 batch/index/head 的**最终存储字节**逐个执行严格 UTF-8 解码及往返字节比较；可逆才使用 content，不因文件扩展名是 `.json`/`.bin` 推定编码。失败的文件走 blob API，不能改变密文格式以迎合接口。

GraphQL `createCommitOnBranch` 支持一次提交文件集合并推进分支，提供 `expectedHeadOid`。[E4] 能力存在不等于当前应用凭据已具备所需访问能力。REST 优化后发布 API 仍为明显瓶颈时，先登记预计完整收益、恢复成本和实施预算，再决定是否实现 GraphQL。未选中标 DEFERRED；已有适配文件不能算完成。选中实现后必须通过 fixture 与真实 API/凭据/分支保护验收才能启用。REST 聚合属于必做路径，不由 GraphQL 权限阻塞。

初始客户端聚合请求体预算为 2 MiB，按**最终序列化 HTTP body**测量，属于本地保护值，非 GitHub 公布的请求限额。超出时在发出任何有 ref 副作用的请求前选择可容纳同一制品的 REST 分文件路径。单个原有合法批次必须仍有兼容发送路径，不能因优化而突然不支持原协议边界。

### 7.2 发布流程

1. 读取已封存批次，建立不可变 `PreparedBatchContext`；复用历史 prepared artifact。未准备时先完成验证和加密，再持久化冻结制品。
2. 校验 scope、owner、仓库私有状态、actor 前驱及 guard；阶段 A 保留每批私有仓库复查。
3. 以当前已验证 commit/tree 作为发布基线，创建文件对象/tree/commit 或单次 mutation。相同制品内文件路径不得重复，不得删除 unrelated 文件，不得修改 `.github/workflows` 等额外路径。
4. 将已持有的精确文件字节按对应 OID 放入缓存；只标记字节来源已知，不能将本地准备物直接视为远端已发布。
5. 取得远端 ref 的确认快照，验证预期 batch/index/head 的路径、OID/摘要、actor 链和完整安全证据；确认后本地幂等 acknowledge。
6. 返回 `VerifiedSnapshot` 与本次确认事实，供外层复用。

REST 仍以 `force=false` 更新 ref；它提供快进约束，不能视为精确的 expected-head 比较接口。[E5] 更新失败后重新检查实际远端，保留其他 actor 提交；同 actor 前驱变化不能简单重写 prepared artifact。

GraphQL 必须同时检查 HTTP 状态、`errors`、mutation 数据以及返回的 commit/ref；HTTP 200 不足以判定成功。`clientMutationId` 只用于请求关联，不能当作服务端幂等保证。捕获的服务端错误结构需脱敏保存为测试 fixture，不依靠猜测的错误字符串或单一 `422` 判为并发冲突。

### 7.3 响应未知与策略回退

结果分类统一为 `CONFIRMED / DEFINITELY_NOT_PUBLISHED / OUTCOME_UNKNOWN / PREDECESSOR_CONFLICT / BLOCKED`。

| 场景 | 处理 |
| --- | --- |
| 写入前本地检测到不支持的编码/体积 | 保持同一 prepared 内容，选择兼容写法 |
| 只创建了不可达 blob/tree/commit，尚未尝试 ref 更新 | 可以复用内容继续；孤立 Git 对象不等于发布成功 |
| ref/mutation 超时、连接断开、200 响应无法完整解析 | 先记录未知结果并读取实际远端；对账前禁止第二种发布器再提交 |
| 远端完整匹配制品 | 幂等成功，确认一次；若 head 已前进，验证本批仍位于合法链中 |
| 相同 batchId 不同内容，或部分制品形成不合法可见状态 | 阻塞并保留诊断；不补写掩盖异常 |
| 明确未发布且前驱仍一致 | 允许在预算内重试相同制品 |
| 其他 actor 更新导致当前基线过期 | 刷新并验证快照，保留其他 actor 变化后重试 |
| 当前账号权限、分支保护或仓库私有检查失败 | 保留失败原因；不能切换 API 绕过授权/安全规则 |

既有 prepared 格式保持可读；阶段 A 的策略信息优先作为本地发布执行元数据附加，不能让关闭优化开关后旧制品无法恢复。进程重启后重新 POST 同一 blob 可以作为兼容行为，首先保证字节复用；保存远端 blob OID 可优化请求，但不能凭“保存了 OID”假定对象仍可访问。

### 7.4 请求数量验收模型

暖缓存稳定远端下，定义每次确认新获取的 tree 数为 `K_i`、必须获取的未知索引/head 数为 `U_i`、写请求数为 `W_i`。保守上界：

```text
N_upload_optimized <= 3 + Σ(W_i + 3 + K_i + U_i) + 1
                           ↑ private + ref + commit       ↑ final ref
```

已知创建 commit/tree 响应可减少上界中的请求。关键验收是：`U_i` 不含旧历史；同一进程已准备且校验的本批 index/head 应能从对象缓存复用；`K_i` 只来自实际变化且未缓存的树。不能把 `K_i` 固定为常数并忽略目录形状变化，也不能用该公式代替实际 endpoint 计数。

<a id="download"></a>
## 8. raw 下载与条件流水线

阶段 A 必做 raw 表示兼容及串行接收验证。以下流水线设计保留为条件扩展约束：先测网络等待与本地解密/写库占比，证明可重叠时间足以覆盖队列、内存和恢复复杂度，在基线报告冻结收益目标与预算后才实现。未选择不要求实现一个默认关闭的流水线。

### 8.1 raw blob

对固定 blob OID 优先请求 GitHub 官方 raw 表示：`Accept: application/vnd.github.raw+json`；保留 JSON/Base64 兼容解码路径。[E2] API 名称含 `+json` 不能作为“响应必为 JSON”的依据。按协商结果、Content-Type 和固定解析器处理，不猜测密文前几个字节。

raw 路径取消 GitHub API 的外层 Base64/JSON，但保留协议内层存储封装。对缓存和协议层交付的最终存储字节必须与 JSON 路径完全一致。测试空字节、非 ASCII、内层 Base64、分块传输、无 Content-Length、响应中断和尺寸超限。

不跟随到未知 origin 的重定向，不把认证头交给未核定域名。维持现有私有 API 域与 token 安全策略；不为优化切换到可能暴露仓库内容的公开 raw URL。

### 8.2 网络串行、本地阶段重叠

默认配置：网络读取者 1、解密/验证消费者 1、写库消费者 1，后两者可在同一顺序消费者中执行；网络请求经过统一 gate。预取队列最多 2 个批次；所有预取、排队和当前处理的原始 batch 缓冲合计预算 4 MiB。请求前按该对象最大响应上限预留 byte permit，缓冲释放后归还；不能等到 OOM 后才限流。

生产者只取得下一批原始对象，消费者处理上一批；这允许网络等待与本地 CPU/SQL 重叠，不提高 GitHub 并发请求数。未知 index/head 的独立对象读取也经过同一 gate；同一 OID 并发需求通过 single-flight 合并，失败/取消需释放条目，不能永久缓存失败 future。

接收顺序保持与现有验证/依赖处理兼容。同一 actor 的顺序不颠倒；即使其他 actor 正文预取完成，也不能绕过协议检查。所有当前快照新增批次接收完成后再投影，保留 dirty 合并效果；不能每预取两批就重新投影长历史字段。

原始缓冲预算不覆盖 Kotlin 对象、明文、数据库 page cache、L1 或 HTTP 库内部副本；另外测量真实峰值。禁止同时持有全部待下载正文，禁止持有数据库事务进行网络等待。

### 8.3 停止和错误

暂停、取消、scope 变化或 owner 失效时取消生产者及 HTTP call，消费者只允许原子完成当前事务或回滚；不继续写旧 scope。未提交正文留在可重建缓存，不能推进 RECEIVED。

某批验证失败时沿用原有 invalid/blocked 语义；后续已预取对象不可使错误批次被跳过为成功。缺少父节点/来源/描述与密文错误分别处理。投影失败不撤销已正确接收事件，但不能记作实际合并完成。

网络并发 2/4 仅可用于隔离 benchmark，对应功能开关默认关闭。只有在服务限流和内存约束内显著改善真实耗时后才提议启用；阶段 A 不以并发增加作为主要吞吐方案。[E3]

<a id="database"></a>
## 9. SQL、编解码、事务、计数和日志

### 9.1 先核对隔离条件

P0 核对 R1 展示的完整 `getFieldEvents`、调用参数及生成代码。若缺失作用域过滤，采用等价于以下约束的查询，并同时审查父节点、invalid key、batch 去重、统计和日志去重的作用域：

```sql
SELECT e.event_key, e.event_json
FROM sync_event_fields AS f
JOIN sync_events AS e
  ON e.space_id = f.space_id
 AND e.generation = f.generation
 AND e.event_key = f.event_key
WHERE f.space_id = :spaceId
  AND f.generation = :generation
  AND f.object_key = :objectKey
  AND f.field = :field;
```

这是目标约束示例，不代表仓库最终参数语法。用同 objectKey/field/eventKey、不同 space/generation 的真实 SQLite fixture 证明不会交叉读取。若原报告摘录省略了条件，记录已排除，不做无意义修改。

事件/batch 的 `INSERT OR IGNORE` 必须先检查同身份异内容，不能依靠唯一键忽略冲突。SQL 优化不得移除这项验证。

### 9.2 批次工作对象与验证复用

封存后构造一次 `PreparedBatchContext(batchId, scope, canonicalDigest, decodedEvents, descriptions)`。后续准备/加密/日志利用同一只读对象，不在 `nextBatch/prepared/savePrepared/acknowledge` 之间反复反序列化。

事务内仍校验 batch 状态、scope、owner、冻结 revision 和摘要一致；不能把原来事务内的完整校验简单删除后相信可能过期的内存对象。解密后 batch 只做一次字节解析和一次同版本协议验证；依赖本地数据库状态的校验继续按当前事务重新执行。

跨字段可以缓存不可变事件的解码结果；`invalid` 标记、依赖是否已到达、dirty 状态、业务 revision、reducer 输出不能使用没有失效机制的永久缓存。阶段 A 不改变 reducer 输入语义和算法，不承诺长历史字段归并成为增量常数时间。

### 9.3 查询计划与索引

优先处理带实际指标的热点：待上传选择、dirty 字段排序、按批次/字段/父节点读取、已接收集合查询、import queue。每个新增索引附实际 SQL、数据分布、Android/JVM 引擎版本及 `EXPLAIN QUERY PLAN` 输出。[E6]

不得以“有索引”宣称查询高效，也不得为了测试硬加 `INDEXED BY` 锁死不合适的计划。自动断言查询返回隔离、查询次数/行数上界和大样本性能；查询计划文本可能随 SQLite 版本改变，不做跨引擎整串快照比较。

事务保持有界，inbox 仍按协议批次提交。2026-09-25 文件 SQLite 分解显示，501 字段/10 actor 的投影耗时 11,049 ms，其中外层事务结束累计 8,689 ms；因此调整此前“projector 始终按字段提交”的保守策略，允许一次至多 50 个 dirty 字段在一个外层事务中顺序投影。此变更只减少提交频次，不改 reducer 输入、算法、接收后投影再上传的顺序或删除确认规则。

批次开始前，在外层事务之外复用作者索引的准备入口完成身份迁移及内存 ready 状态，避免批次回滚后只留下内存已就绪标记。每字段仍在事务内重读 active/current/dirty 与 revision 并检查取消；任何异常先使整个批次回滚。取消立即传播，不进入回退；其余 Exception 仅在回滚完成后回退一次到原逐字段路径，维持 SOURCE/IDENTITY 等独立失败状态与原有意外错误传播，不捕获 Error、不在已失败的事务中继续。已接收事件保持持久，未提交投影可重新执行，不能把中间结果记作已应用。来源或身份不可用的字段较多时会频繁回退，不承诺获得正常批路径的提速。此策略需真实文件库的提交次数、投影结果、字段失败、取消恢复及作者准备回滚测试；未通过前仍为候选。CPU 计算移到事务外仍须 field revision 比较及必要重试，本轮不做此移动。

追加测量也确认了批量删除裁决的逐项写提交热点：501 项裁决的 502 次外层提交累计 13,728 ms，总耗时 17,950 ms。`processBulk` 因此采用同样有界的至多 50 项页事务，处理对象仍只取已冻结 job 中的 QUEUED 项。事务内重读 job 与活跃空间，逐项检查取消和 binding；后到的待裁决项不混入已冻结集合，旧 binding 失效仍计为 INVALIDATED。

作者身份准备在页事务外完成。页中任一 Exception 导致整页回滚；取消直接传播，其余异常在回滚后至多回放一次原逐项事务，逐项记录 FAILED 并保留其 pending 行，其余项继续。不在失败事务中继续写入，不捕获 Error。取消时当前未提交页全部保留为 QUEUED，已完成页保留结果；这改变的是提交粒度，不改变确认/保留本地的业务含义。错误密集时回退到旧逐项成本，不能承诺同等提速。共享契约覆盖第二项 SQL 失败、第二项取消、binding 失效、新项隔离及恢复；性能门槛另以真实文件库同条件样本验收。

### 9.4 真实进度的增量维护

保留 R2 定义的 run/phase/segment 与稳定业务身份。上传确认、inbox 接收、字段实际应用在各自业务事务中登记唯一 checkpoint，并更新相应计数；重复处理同一 checkpoint 更新 0 次。

不能依靠可裁剪 UI 日志去重计数。复用既有 durable 业务标记；确需附加 fact 行时规定唯一键、恢复范围和清理条件，避免永久额外复制全部事件。

正常热路径不再调用全局 published/remote event 汇总。新 run 建立业务基线与恢复时允许一次 scoped reconciliation；优先从带 scope 的计数/批次状态取得。正常确认以后只增量更新。若遇到版本升级或缺失事实，按稳定业务检查点重建，禁止把历史其他 run 数据加入当前 segment。

计数、日志与业务状态若无法同事务写入，则业务事务中保存唯一待报告事实，后续幂等消费。测试必须覆盖业务已提交、事实未展示时崩溃；不能只测 happy path。控制器可以降低刷新频率，但持久事实不可丢。

### 9.5 日志批量与 UI

保留当前每批按业务对象聚合、运行/阶段/业务身份去重，以及每次最近 500 条、20 次终态运行的策略。[R2 §3；R1 §8]

新增批量写接口：同一批次的条目在一个事务内批量 upsert，再裁剪一次。截断以稳定排序键执行，避免读取全部 key 再逐条删除；相同时间戳使用确定性次序。活动恢复记录不裁剪。一次日志写入或跨批累计超过 500 条时仍留下正确最近 500 条与截断提示；测试不能为制造日志数量而构造违反事件协议的批次。

UI 计时 ticker 只更新时间显示，不每秒强制完整查询 pending/import/日志全部状态。数据库变化驱动实际数据刷新，批量合并短时间通知；活动时数据刷新上限建议 4 Hz，终态/暂停/错误立即派发。该频率是设计参数，不能延迟 durable 检查点。面板关闭停止可见性专属查询，历史按 runId 分页读取。

<a id="import"></a>
## 10. 初始导入与批次填充率

R1 §4 的“首个网络请求前”表述与入口 `/user`、仓库验证顺序不一致。统一指标名称：首个鉴权请求、首个同步数据请求、首个已确认发布；基线导入阻塞的是后两者中的数据交换部分。

阶段 A 保留冻结本地数据和全部 INITIAL_IMPORT 事件生成后进入数据交换的顺序。因为生成事件会读取 causal heads，直接让生成与远端接收/投影交错可能改变 parents；当前材料没有证明等价。

优化 `SyncBaselineStore.process()`：冻结时确认 total；每次事务处理实际成功消耗的行数，并原子推进 processed/remaining。恢复时允许一次 COUNT 对账，正常每 50 条处理不再 COUNT 全剩余记录。错误保留行、暂停导入、重复执行、部分事务回滚必须保持剩余数正确。

批次仍可跨业务事务积累；上传时按原规则封存尾批。记录 `eventsPerBatch`、明文字节、描述字节、封存原因。不得通过“每导入 50 条就 nextBatch”提前封存，使历史批次数增加。事件数、effect 数、业务对象数和传输字节分别统计，不能用“1 万条数据”代替实际输入描述。

<a id="retry"></a>
## 11. 限流、重试预算与调度

### 11.1 统一错误分类

分类在 HTTP/GraphQL 适配层生成，runtime 决定恢复资格。不得仅用状态码决定所有业务语义。

| 类别 | 识别与处理 |
| --- | --- |
| `RATE_LIMIT_PRIMARY` | 403/429 且额度耗尽等明确证据；等待服务端 reset/Retry-After 的较晚时点 |
| `RATE_LIMIT_SECONDARY` | 403/429 和明确次级限流信息；尊重 Retry-After，无有效提示至少等待 60 秒 |
| `AUTH_EXPIRED` | 按已有授权刷新规则；401 不直接无限刷新，每次凭据修订只允许一次受控刷新 |
| `FORBIDDEN/PROTECTED` | 权限或分支保护错误；保持可理解原因，不能归为短时网络失败反复写 |
| `ACCESS_OR_NOT_FOUND` | 私有资源404可能涉及权限；沿既有身份/权限检查判定，不能仅据404清空guard、重建空间或认定已知文件被合法删除 |
| `REF_CONFLICT` | 更新基线已改变且经远端对账确认；保留最多 3 次局部冲突尝试 |
| `VALIDATION/CAPACITY` | 非法 payload、schema、路径、超出本地预算；不盲目自动重试 |
| `NETWORK/TRANSIENT` | 连接失败、超时、可恢复服务错误；mutation 先进入 OUTCOME_UNKNOWN 对账 |
| `SYSTEM_STOP/CANCEL` | 协程取消、调度交接、进程停止；不消耗网络失败预算 |

GitHub 的 403/429、reset 与 Retry-After 规则来自官方资料；未公开的次级额度不能作为可以精确计算的固定吞吐容量。[E3] 对畸形响应、缺失头和服务端时间偏差使用保守下限，不把过去时间解释为可立即密集重试。

### 11.2 持久预算

采用 R2 的既定规则：首次实际网络失败后最多 3 次自动重试，退避 10/30/120 秒，**第 4 次网络失败耗尽**。使用单调 attemptId 关联执行；独立保存 run 内 networkFailureCount，不再通过系统取消时“减 attempt”间接维护预算。

一次被接受的运行执行最终因网络失败退出，计数加 1；单个 HTTP 重传、局部 ref 冲突、过早 Worker 唤醒不重复加计。该 run 内零星 HTTP 成功或确认一个批次不清零预算；运行完整成功结束后自然结束预算，显式“新重试”遵循既有新 run 规则。用户继续、系统恢复不能擅自重置已经耗尽的 run。

```text
nextRetryAt = max(
  当前失败时点 + 本级退避,
  有效 Retry-After 下限,
  有效 primary reset 下限,
  secondary 无提示时的 60 秒下限,
  已持久账号级 notBefore
)
```

成功响应提前告知 remaining=0 时可以主动等待，不制造一次网络失败。应用可识别为同一额度主体的账号级 quota/notBefore 跨仓库共用，scope 级运行状态分别保存；不得假定能够观测其他进程、其他应用或用户全部令牌的剩余额度；不能换仓库或切换 REST/GraphQL 绕过已知等待。所有等待均记录原因并可暂停。

对大量修改请求默认采用串行写入及至少 1 秒的客户端间隔策略；它是遵循官方建议的客户端节流，并非服务端额度保证。[E3] 指标区分主动节流、服务端限流及实际请求耗时，API 聚合收益必须包含该成本。

### 11.3 唤醒与执行权

WorkManager 只依据持久 run 状态安排下一次恢复，不再对同一已持久网络失败额外维护一套业务 `Result.retry()` 预算。内部调度基础设施失败可有独立受控补偿，但必须标明未执行任何业务 attempt。

首次意图提交、失败/等待/暂停/终结均触发调度对账；过早 Worker 不发 HTTP，并确保真正到期的唤醒仍存在。到期前台 timer、后台 Worker、周期触发共用 owner fencing；旧 owner 的 HTTP 取消并释放前不能开始第二次 ref 发布。

停止等待使用单调时钟计时，跨进程到期时间使用可恢复墙钟表示；同时保存接收时服务端Date、原始相对等待量与可获得的启动标识/elapsedRealtime。进程重启但设备未重启时优先复用单调时钟期限；设备重启或墙钟明显跳变且不能可靠重建剩余等待时，采用保守的剩余等待重建，不能单凭快进后的墙钟提前绕过服务端下限。force-stop 与普通进程 kill 分开验收，不承诺系统禁止执行状态下立即后台恢复。[R2 §4]

<a id="capacity-migration"></a>
## 12. 容量、迁移、兼容与可回退性

### 12.1 容量策略

阶段 A 保留既有实际语义下的 20,000 tree 条目与 10,000 index 条目总保护预算。P0 核对是否包含目录/bootstrap、是否统计全部仓库路径；测试精确覆盖 `limit-1 / limit / limit+1`，不得把近似 H 数当边界。

消除“递归响应超过 2 MiB 导致原本预算内空间不可读”的路径：分层 tree 读取、流式/有界解析，按端点设置响应预算。初始设计预算：tree 响应 4 MiB，blob/普通响应保留 2 MiB，错误正文至多保存 64 KiB 脱敏摘要。所有预算按流实际读入字节强制检查，不信任 Content-Length。数字属于待验证的本地资源保护，不代表 GitHub 服务上限。[R1 §2、§9；E1–E2]

若非递归单层目录也截断/超限，不能假装有分页 API，也不能接受部分索引；安全失败并显示明确容量诊断。该情况需要目录分片或其他传输方案，纳入阶段 B。提高保护预算必须附内存、解析、故障测试与兼容评估，不能随手改大常量。

禁止自动删除旧 batch/index、Git 历史或 guard 证据释放空间。设计不承诺无限历史；阶段 A 发布说明必须写明实际验证容量与剩余平铺目录限制。

### 12.2 迁移与历史状态

基线修复文档的目标为 schema 33，但实际实施 HEAD 可能已变化。[R2 §2.1] P0 读取真实 schema、迁移目录、双方已发布版本和结构指纹，再分配下一个合法迁移；本文件不预占编号。

新增字段/索引/表必须经过既有共享兼容入口，覆盖同步28、作者28/29/30、共同基线、修复schema33、新装及实施时新增的已支持谱系。保留事件身份、prepared 密文、run baseline、作者字段/触发器/阅读记录。未知同号结构、更高版本和不完整结构明确阻止启动，不能猜测修补。

升级后的第一次同步允许一次 manifest/计数初始化；必须明确标记，不能把这次冷初始化与之后暖缓存性能混为一谈。初始化失败可恢复，且不清除旧 guard。

### 12.3 开关与回退

内测可分别控制 cache、snapshot delta、REST inline、GraphQL、raw、pipeline。开关属于诊断配置，不新增面向普通用户的大量设置。默认按阶段门禁逐项启用。

关闭 cache/delta 回到完整验证路径，保留所有 durable 业务记录和 guard；关闭 inline/GraphQL 仍能发送既有同一 prepared 字节；关闭 pipeline 回到顺序接收。关闭优化不得回退限流、安全与迁移修复。

回退指同一支持新 schema 的构建关闭优化功能，或安装已经明确支持该 schema 的后续修复包。不能推荐用旧包直接覆盖降级；不可通过删除新列或降低 user_version 实现回退。

旧客户端互通以远端文件字节/协议及实际 parser 验证：新上传→旧读取、旧上传→新读取、新旧交替发布、密码/无密码与已有 prepared 恢复。源码“应该兼容”不足以替代测试。

<a id="observability"></a>
## 13. 观测、证据格式与指标定义

### 13.1 复用现有测试与新增测量

复用 `SyncScaleAcceptanceTest` 的文件型 SQLite、production codec/AEAD/transport/inbox/projector/panel wiring，不另写一个绕过生产代码的“快速同步模拟器”。现有测试已记录请求数和耗时，但未提供输出，也未证明真机性能。[R1 §12]

新增观测应通过接口注入，默认低成本聚合，详细请求事件仅测试/诊断模式启用。记录基线和候选 commit、dirty diff hash、构建变体、平台/设备/API、SQLite/HTTP 库版本、数据集 seed/hash、cache 模式、网络条件、开关与服务 quota 状态。

### 13.2 时间与数量

| 组 | 必须记录 |
| --- | --- |
| 时间 | requestAccepted、ownerAcquired、firstAuthRequest、firstDataRequest、firstPublishConfirmed、finished；等待执行与活跃时间分开 |
| 阶段 span | import freeze/generate、ref/commit/tree、index/head 获取及解密、guard、discovery、batch 下载/解密/校验、inbox、projector、prepare/publish/confirm、计数/日志、UI 查询 |
| 网络 | 按方法+端点类别的 call 数与实际 HTTP exchange 数；响应状态；连接/DNS/TLS事件；请求/响应正文、Git对象/明文/密文字节；重复 OID 获取 |
| 缓存 | L1/L2 hit/miss、校验失效、淘汰、single-flight 合并、被复用快照、重新构造 evidence 次数 |
| 数据库 | 查询类别次数、返回行数、事务次数、事务执行/锁等待、全量 reconciliation、manifest 写入行数 |
| 业务 | H、A_h、B、D、事件/effect/对象数、批次填充率、received/projected/published、pending/invalid/decision |
| 资源与等待 | 应用峰值堆/RSS或PSS、固定缓冲占用、cache磁盘字节、主动节流/限流/退避/系统调度等待 |

异步阶段 span 可能重叠，不能将所有 span 简单相加后宣称等于总耗时。数量区分逻辑请求、HTTP 重传与 server 实际接收；fixture 的建库/预置请求必须排除在被测区间之外。

HTTP body 字节与真实网络包字节分开命名；无法测 TLS/HTTP framing 时不得把应用正文大小称为总带宽用量。原始 body、token、密码、密钥、私人标题、完整仓库路径不写性能日志。OID/对象身份可使用本次运行稳定匿名映射，便于统计重复而不跨报告关联。

### 13.3 结果文件

建议路径：`docs/evidence/sync-performance/results/<candidate-short-sha>/<scenario>/`。每个执行批次保存一份 manifest、机器可读 JSON/JSONL、脱敏 endpoint 汇总、SQL plan 与必要设备证据。过程进度只写 roadmap，不为每个子任务生成新的状态系统。

```json
{
  "schemaVersion": 1,
  "status": "NOT_RUN",
  "baselineCommit": "96dbb69d7aa75acce530d477c3c9e3e23db4d55f",
  "candidateCommit": null,
  "scenario": "warm_increment",
  "dataset": {"H": 1000, "actorHeadFiles": 2, "B": 1, "D": 0, "seed": null},
  "cacheMode": "warm",
  "environment": {"platform": null, "buildVariant": null, "networkProfile": null},
  "metrics": null,
  "assertions": [],
  "missingEvidence": []
}
```

该示例是结果 schema，空值不能替换成猜测数字。`elapsedMillis` 仅在实际完成测量后填写；基线公式另存 `predictedRequests`，不能混入 `measuredRequests`。

<a id="testing"></a>
## 14. 测试分层、oracle 与执行方法

### 14.1 五层验证

L0：纯逻辑与状态机单元测试。scope、预算、身份/摘要、错误分类、缓存失效、发布结果分类、计数去重、受控时钟。

L1：生产 transport + 有状态 HTTP fixture。使用真实 parser/编码/加密/HTTP 代码，服务端模拟 Git 对象和 ref，记录所有请求；ref 更新、截断、限流、响应丢失在服务端实际状态上生效，不能只 mock 返回预期结果。

L2：真实文件 SQLite + 生产 exchange/guard/inbox/outbox/projector/runstore 集成。包含真实事务、中断重开、迁移、SQL计划、规模数据和 UI controller 观察。JVM fixture 的 heap 与应用 heap 分开，必要时把 server 放独立进程。

L3：Android 实际 driver/App DI/WorkManager 与 Desktop wiring。WorkManager test driver 验证受控 constraints/到期，但不能替代真实设备系统调度测试。[E7]

L4：隔离正式包覆盖升级、真实网络与已授权私有测试仓库、用户设备反馈。模拟器、debug、JVM或fixture通过均不得记作 Android release 真机通过。

### 14.2 oracle 与防止自证

对缓存、raw、inline、pipeline 优化采用同一不可变数据集做优化开/关差分：比较事件身份/摘要集合、业务状态、causal heads、inbox/outbox、decision、进度与日志身份。生产随机密文不要求不同独立加密运行字节相同；同一 prepared 制品的重试必须字节完全相同。

guard 使用基线完整算法与明确的恶意场景双 oracle；F11/重试属于已知疑点或行为修复，不能把错误基线作为唯一真值。用人工声明的作用域隔离和 R2 的四次失败规则断言。

fixture 必须能独立检查发布原子性、父 commit、保留 unrelated 文件与同批 identity 冲突，不能让客户端使用的同一错误 helper 同时生成“正确答案”。随机状态机测试固定 seed、失败序列可重放；测试用确定性随机只能注入测试构建，不进入生产 nonce 生成器。

所有故障点至少验证“中断前远端/本地实际状态 → 新 runtime/重新打开数据库 → 恢复后的最终状态和请求增量”，不只断言抛出了异常。

### 14.3 执行预算

逐阶段红→绿→必要重构，优先 focused tests。仅计数的测试不注入实际网络延迟；退避/Worker 使用受控时钟，避免长 sleep。延迟 benchmark 与正确性套件分开。大规模基线先验证小样本公式；超预算场景保留 `ABORTED_BUDGET` 和已完成指标，不能伪造完整耗时。

P0 发现实际 Gradle task/测试类和仓库协调器后登记命令；本文件不猜测不存在的 task 名。共享文件/SQLDelight/Gradle 构建串行调度。受影响全量、正式构建和整套设备验收在集成后集中执行，失败后定向复验，不为每次文档变化重跑全部规模套件。

<a id="test-catalog"></a>
## 15. 必须实现的测试目录与性能门禁

每个测试 ID 是可验收要求，可由参数化测试覆盖。实现报告需给出实际测试方法、命令和证据路径；一个只返回 mock success 的测试不能覆盖整行要求。

### 15.1 观测与输入基线

| ID | 输入/动作 | 核心断言 |
| --- | --- | --- |
| T01 | 合法空空间H=0/A_0=0；已有历史H=10/A_h=2，B=0/1/4、D=0/2按合法组合取值 | 固定/新增head分别符合§1.1公式；首次head与bootstrap场景独立；fixture预置排除；旧正文GET=0 |
| T02 | 相同生产调用含一次连接重试和一次 token 刷新 | 逻辑 call/真实 exchange/refresh 分开统计，无 token/标题泄露 |
| T03 | 异步下载和SQL span 重叠，注入调度等待 | 活跃时间、等待和总时间可区分；重叠 span 不简单求和 |
| T04 | 同数据集、不同 commit/开关运行 | 输入hash、构建、cache模式、endpoint及assertion报告完整；未运行不生成PASS |

### 15.2 缓存、作用域与持久化

| ID | 输入/动作 | 核心断言 |
| --- | --- | --- |
| T05 | 同一 OID 多次/同时请求 | 一次获取，single-flight共享；取消某等待者不错误取消其他有效使用者 |
| T06 | 相同 OID/路径切换 repo、space、generation、keyContext、validatorVersion | 原始内容允许的复用与验证结果失效分别正确；跨作用域不能误命中 |
| T07 | L1/L2暖、仅L2暖、全冷、主动淘汰 | 结果相同；完整持久快照重启不下旧blob；淘汰不删guard/事件 |
| T08 | 文件缺失、短写、校验损坏、缓存元数据不完整 | MISS/重建；服务器仍异常时阻塞；不无限重试或首次信任 |
| T09 | 文件写完前/后、DB登记前/后杀进程 | 半成品不可命中；孤儿可清理；durable状态不误推进 |
| T10 | 磁盘满、cache quota、低内存淘汰 | 可放弃缓存继续安全同步；业务事务失败不报成功；活动lease不失效 |
| T11 | 登出、解绑、换空间与正在进行HTTP/事务竞争 | 旧scope结果不能污染新scope；密钥不可用不能绕过解锁 |
| T12 | 数据库恢复到旧副本、缓存保留更新版本 | 缓存不得提升guard或业务锚点；按数据库当前guard重新验证 |

### 15.3 tree、快照、安全与发现原子性

| ID | 输入/动作 | 核心断言 |
| --- | --- | --- |
| T13 | 同commit暖同步，guard已blocked或已知回滚 | 快速路径仍拒绝；正常未变不重建完整evidence |
| T14 | 新commit仅一个actor新增批次，多epoch、多actor | 只读变化子树/新index/head；其他actor数据保留；cursor正确 |
| T15 | head不变，删除/篡改旧index或batch；修改bootstrap/space | 与完整guard同等拒绝；缓存不能掩盖异常 |
| T16 | 序列缺口、重复区间、分叉、前驱循环、跨scope AAD、相同ID异内容 | 明确拒绝且无错误ack/业务投影 |
| T17 | 同tree不同commit、已知旧commit回退、合法无内容变更 | 与既有guard允许集合一致；不按tree相同跳过提交检查 |
| T18 | recursive截断、非recursive参数、嵌套目录、重复路径/非法mode/深度 | 分层请求省略recursive参数；冷快路径仅接受完整且逐树OID验证通过的递归响应；不接受部分树或map覆盖；完整遍历或明确失败 |
| T19 | 多个子树相同OID，单actor平铺目录很大 | 相同子树不重下；变更目录字节成本被记录，不宣称常数 |
| T20 | guard/manifest/发现队列提交前/后中断；owner revision竞争 | 无“快照已推进但新批丢失”；CAS失败重算；每个新增批次唯一登记 |
| T21 | 暖ref未变，仍有已发现未接收、已接收未投影及unavailable记录 | 继续本地待办，不误返回no-op完成 |
| T22 | 缓存全清但guard仍在，远端已回滚/缺对象 | 重建后仍阻塞；不能重置锚点 |

### 15.4 发布、原子性、兼容与恢复

| ID | 输入/动作 | 核心断言 |
| --- | --- | --- |
| T23 | REST_BLOBS/INLINE分别发布同一prepared制品 | 存储字节、Git blob OID、索引和业务结果一致；全UTF8时写请求6→3 |
| T24 | 二进制、无效UTF8、中文、反斜线、换行、NUL及大payload | 仅字节往返一致文件inline；q文件回退；不重编码密文或丢字节 |
| T25 | 创建blob/tree/commit后、ref前分别中断 | 未发布不计上传；恢复复用制品；base_tree保留其他路径 |
| T26 | ref已生效但响应丢失、确认后ack前中断、ack后日志前中断 | 唯一确认、密文不变、不重复计数，正确返回确认快照 |
| T27 | 两个actor抢先提交、同actor前驱竞争、ABA/已知回退 | 不force、不覆盖他人；相同actor异常不自动生成新前驱制品 |
| T28 | GraphQL 200+errors/null/部分数据、超时、权限/保护失败 | 未完整确认不能ack；错误分类正确；未知结果先对账再回退 |
| T29 | GraphQL expectedHead冲突；切到REST恢复同制品 | 不双发布；策略切换不改变batch/index/head；clientMutationId非幂等依据 |
| T30 | 旧prepared格式升级恢复，优化开关关闭，新旧客户端交替 | 原制品可恢复；旧parser能读阶段A文件；事件/决策语义一致 |
| T31 | 本批发布确认快照含其他actor新batch | 外层处理全部delta；无重复完整快照；自己的事件不计远端回声 |
| T32 | 最后一次确认后远端再更新、连续更新超过追赶上限 | final ref能发现；保留下一segment，不能虚报全量完成 |

### 15.5 下载与本地处理

| ID | 输入/动作 | 核心断言 |
| --- | --- | --- |
| T33 | raw/JSON两表示，密码/无密码及legacy支持路径 | 交付完全一致存储字节与结果；解析器按真实表示选择 |
| T34 | 慢网络+慢写库、D=40、最大合法批次 | 网络并发始终1；预取≤2；原始buffer≤4MiB；有实际阶段重叠 |
| T35 | 第N批下载/解密/校验/SQL失败，后续已预取 | 原有invalid/blocked处理正确；未提交不RECEIVED；无越过错误的假成功 |
| T36 | 预取、接收事务中暂停/取消/换scope/owner失效 | HTTP被取消；原子提交/回滚；重开不漏批，byte permit全部归还 |
| T37 | 同字段被同快照多批修改、跨actor依赖乱序、缺父节点后补齐 | dirty合并和最终归并等价；无每窗口全历史重复投影 |
| T38 | 来源/描述不可用及待删除确认 | 无漫画源网络调用；状态可恢复；删除保护/不回传规则保持 |

### 15.6 SQL、计数、日志与导入

| ID | 输入/动作 | 核心断言 |
| --- | --- | --- |
| T39 | 相同object/field/eventKey跨space/generation，父节点跨域 | 查询与归并严格隔离；同身份冲突验证未被INSERT OR IGNORE绕过 |
| T40 | 待上传、dirty、闭包典型与偏斜分布，真实文件库 | 保存实际SQL plan；返回行/查询数量受控；不依赖跨版本plan整串文本 |
| T41 | 同批首次准备、已有prepared、确认恢复 | 内容只做必要decode；事务内冻结状态/摘要仍复核 |
| T42 | 相同事件多字段闭包，invalid集合变化、依赖后来到达 | 只读事件缓存可复用；可变验证结果失效；reducer差分一致 |
| T43 | 业务提交后计数前、重复receive/ack、segment切换、run恢复 | 计数不回退/双计/混入历史；业务检查点能恢复事实 |
| T44 | 每批1/256个可表达对象、跨批累计超过500条日志、相同时间戳、重复报告 | 每批最多一次日志写事务和裁剪；最近500条与截断提示正确 |
| T45 | 21个终态run与一个活动run，日志分页/关闭重开 | 终态保留20，活动恢复数据保留；历史隔离、无补播成功 |
| T46 | 初始导入块提交/删除/计数边界故障、暂停导入、重复重开 | 原子processed/remaining；恢复一次对账；正常每块无全量COUNT |
| T47 | 大描述触发字节封存、尾批、跨业务事务事件追加 | 256/512KiB及既有效果上限不变；不按50条导入提前封存 |
| T48 | import期间同时有远端新事件 | 阶段A保持生成/交换顺序与既有parents语义；setup仅真实条件满足才完成 |

### 15.7 限流、网络预算与平台

| ID | 输入/动作 | 核心断言 |
| --- | --- | --- |
| T49 | 403 primary/secondary、429有无Retry-After、普通403、422不同原因 | 分类正确；有期限遵守较晚时点；无提示secondary至少60秒 |
| T50 | 四次真实网络失败，期间系统停止/局部成功/前台切换 | 10/30/120秒及服务端下限；第四次耗尽；中断与HTTP成功不重置预算 |
| T51 | 3次系统中断、过早唤醒、重复Coordinator请求 | 不消耗网络失败数；暂停/耗尽不发请求；future wakeup仍存在 |
| T52 | 运行写等待状态后入队前崩溃，Worker/前台同时到期 | 唯一owner；无WorkManager第二套业务重试；持久补偿可恢复 |
| T53 | 两仓库同账号，REST/GraphQL同时遭限流；时钟跳变 | 共享notBefore；不换API绕过；不持有事务sleep |
| T54 | mutation请求失联，HTTP层自动重试/取消边界 | 记录实际exchange；幂等对账保护；旧owner释放前新发布不开始 |
| T55 | token剩30秒、已撤销、刷新失败、私有资源404、会话切换 | refresh数量受控；不缓存过期权限；账号/私有仓库检查仍生效 |
| T56 | Desktop周期关闭、Android后台受限/通知提升拒绝 | 已接受run恢复规则保留；真实等待原因可见，不宣称后台常驻 |

### 15.8 迁移、容量、UI与正式包

| ID | 输入/动作 | 核心断言 |
| --- | --- | --- |
| T57 | 所有已支持历史谱系及新装→当前目标schema | 真文件/真实driver升级成功；业务/作者字段/凭据/制品保留 |
| T58 | 未知同号/未来高版本/中断迁移/重复打开 | 明确安全失败或事务回滚；不降级、不重复ALTER、不清库 |
| T59 | 旧guard存在而新cache/manifest为空，初始化中断 | 重建可恢复；首次性能单列；原安全锚点不丢 |
| T60 | tree/index预算 limit±1，响应大小 limit±1，单层目录截断 | 边界精确、诊断明确；不接受部分树；不通过仅改大常量放行 |
| T61 | 调试开关逐个关闭/重开，磁盘缓存删除 | 阶段A可安全回退；durable记录/冻结字节/新schema可读性不变 |
| T62 | MAIN、SETUP/MERGING真实controller，密集DB更新 | 数据刷新受控；计时不触发全量查询；阶段/终态及时且真实 |
| T63 | 面板关闭/重新打开、分页、暂停/错误/完成瞬间 | 可见专属查询停止；无旧run混入，无等待时假动画 |
| T64 | 正式Android签名/R8/arm64包与Desktop发行构建 | 生产DI、数据库driver、迁移、HTTP、恢复入口真实接通 |

### 15.9 性能与规模硬门禁

下列为设计验收标准，不是已取得结果。计数门禁优先于有噪声的耗时门禁。

| ID | 场景 | 硬断言/所需证据 |
| --- | --- | --- |
| T65 | 暖no-op，H=0/100/1000/9000；空空间A_h=0，非空场景A_h=2；无待办/刷新/重试 | 每次仅2次会话/仓库验证+1次ref=3请求；旧blob=0、tree=0；内层历史去重/全量evidence=0 |
| T66 | 暖小增量，H=100/1000，同目录形状，远端新增1批 | 旧index/head/batch GET=0；仅新index、变化head、新正文；差异来自实际tree层级，不来自历史blob数 |
| T67 | 连续本地上传B=1/4/40，H=100/1000 | 快照解析≤B+1；最终多1次ref；旧blob=0；manifest修改行数/去重查询不为B×H |
| T68 | 冷/持久暖/进程重启/小缓存淘汰，10k/100k事件 | 全部收敛；暖与冷单列；淘汰导致的MISS被记录；无失效后假成功 |
| T69 | 路径查找、批次decode、日志/统计热点 | batch查找不逐次线性遍历tree；正常确认不重复全局汇总；日志事务≤批次数；记录优化前后计数 |
| T70 | 固定延迟profile，H=100、B=1，无限流；冷热分离 | 7次有效配对；候选暖增量总耗时中位数≤基线50%；失败/超预算单列，不作为成功样本排除后隐瞒 |
| T71 | 仅选中流水线后，固定 H/事件规模的冷首次下载，pipeline 开/关 | 5 次配对、正确性相同；不得退化 >10%，且必须达到事先冻结的收益目标才启用；串行首次下载另按 T80 必测 |
| T72 | 长字段历史、100k事件、20次no-op、反复打开面板 | 无OOM/无单调内存增长；候选峰值应用内存≤max(基线×1.25, 基线+32MiB)；fixture内存单独算 |
| T73 | Android/Desktop同版本同数据同网络，真实私有测试仓库 | endpoint数量可解释；展示总时长及网络/SQL/等待分解；至少3次配对，只报中位数/范围，不用3样本报p95 |
| T74 | 新旧互通、暂停/响应丢失/重启后，完整持久manifest及验证上下文仍有效时再做小增量测试 | 正确性与请求门禁同时成立；恢复不能重新制造全历史blob下载 |

T65 假设完整持久 manifest、验证版本一致、无额外凭据刷新；不符合前提的测量必须换标签，不能删除额外请求伪造3次。T66/T67 允许新快照的保守全量本地guard成本，必须单列；禁止因此声称所有CPU工作只与增量有关。

T70 的本地受控网络 profile 固定为每响应50ms延迟、10Mbit/s正文传输上限，生产节流策略保留，fixture setup不计时。profile是基准条件，不能当作用户真实网络。纯计数测试使用零附加延迟；高RTT补充实验选少量小H样本，避免数万请求长等待。任何阈值变更必须在实现前记录依据并复审，不能测不过后静默放宽。

### 15.10 故障切点、随机化与负对照

固定故障切点：F-A写缓存前后；F-B候选快照/guard/queue提交前后；F-Cprepared保存前后；F-D各blob/tree/commit后；F-Eref生效但响应前；F-F确认后ack前；F-Ginbox提交前后；F-H计数/日志提交前后；F-I等待状态落库后调度前；F-J迁移中间；F-Kscope/owner更换期间。对应测试必须重建runtime和重开文件库。

补充两个跨模块门禁：

| ID | 输入/动作 | 核心断言 |
| --- | --- | --- |
| T75 | 固定seed随机交错新增/接收/暂停/断网/冲突/中断，至少100个seed的有界序列 | 最终收敛、身份不重复、无错误删除/回声、guard不被绕过；保存最小失败序列 |
| T76 | 故意禁用旧blob缓存、丢失发现队列事务或把预算改3次的测试变异 | 对应请求/恢复/预算测试必须失败，证明测试能够识别原问题 |

负对照仅在测试变异分支/测试注入点运行，不能将不安全开关带入可发行生产功能。

### 15.11 首次大量同步与本地放大门禁（2026-09-25 修订）

T70 的暖增量 50% 目标保留，但不足以判定整个优化成功。新增以下必需证据；未采到配对基线或未冻结阈值时保持 `BASELINE_PENDING`，不能仅凭收敛/请求减少通过 P1、P4 或 P9。先采集当前候选用于定位，再在相同输入的可追溯旧实现上建立优化前对照；候选本身不冒充旧基线。

| ID | 用户行为与计时边界 | 验收要求 |
| --- | --- | --- |
| T77 | 首次连接并导入：接受连接/冻结本地范围至全部 INITIAL_IMPORT 事件生成完成 | 单列冻结、SQL/事件生成时间、行数、事务、批次填充率；不能把 fixture 构造时间算成应用导入 |
| T78 | 首次上传确认：接受首次同步至首批精确制品经远端读取验证并本地确认 | 单列导入等待、写入、确认、节流；PATCH 成功响应或 fixture 预发布不等于已验证确认 |
| T79 | 大量上传：接受同步至全部指定事件确认、终态及必要 final ref 完成 | 10k/100k 业务事件与 B=1/4/40 结构样本分别记录；不跳过限流/确认；批次协议上限保持不变 |
| T80 | 首次下载：接受空本地接收端同步至全部目标事件接收、归并、终态完成 | 冷缓存、持久暖缓存分开；下载完成不能替代业务投影完成；串行 raw 路径先测 |
| T81 | 中断恢复：固定 F-E/F-F/F-G 切点至重启后收敛 | 统计已确认事件重复发送/重复应用、旧对象 GET、重复解密、重新扫描/读取行数及恢复总时间；事件不得双计，响应未知必须复用同制品并先对账 |
| T82 | 缓存与历史本地工作：N=100/1000/9000 对象，容量未触顶；分别冷建、暖读、追加和重启 | 记录目录枚举次数、枚举条目数、文件 metadata 次数、marker 写入、SQL 读取/修改行数、索引解密及 tree 展开次数；累计工作不能以每对象全目录扫描形成 N² 放大 |

阈值冻结流程：记录旧版/候选 commit 与 dirty 内容指纹、输入 seed/hash、事件/actor/批次分布、平台/driver、网络 profile、缓存和故障条件；先完成小样本定位，再采大规模配对。T77–T80 每项填写基线中位数/范围、目标绝对秒数及相对改善比例、样本数和预算，写入同一份基线报告，在后续优化前完成独立审查。不能把“目标待定”视为通过。2026-09-25 的 300 事件零附加延迟、每版三样本仅建立定位预算：导入/首批确认不退化超过旧中位数 10%，完整上传/下载至少改善 20%；绝对阈值及证据见基线报告，不外推到 10k/100k。正式受控耗时仍复用 T70 profile 和 7 次有效配对，真机复用 T73 至少 3 次配对；失败、超时、限流样本全部保留。零附加延迟 loopback 定位通过不能替代正式 T77–T80 或推导真实网络提速。

T82 的结构目标为：暖命中不能逐对象全目录枚举；正常追加采用增量账本或等价有界策略，初始重建和容量淘汰单列，连续 N 次操作的目录枚举条目与 metadata 总量应为 O(N)，允许必要有序索引维护 O(N log N)。可以一次冷启动核对容量，不能每次命中再核对整目录。修复前先由真实 FileSystem 代理计数建立失败测试，保留 LRU、校验、满盘/短写、进程重启、损坏与多实例安全契约。SQL 全历史读取、重复解密和保守 guard 展开另报绝对次数/规模斜率；未消除的 O(H) 安全验证不能误标增量常数成本。

2026-09-25 正式首次 profile 的预先目标：300 事件、每响应 50 ms 首部延迟、正文 10 Mbit/s，旧版与候选各七个独立样本。导入 T77 中位数≤旧版 110%；首批确认 T78、完整上传 T79、下载归并 T80 各≤旧版 70%。首次反馈须有明确收益，30% 改善目标为固定网络开销留出余量；该决策依据此前的本地阶段分解，在候选正式七样本开始前冻结。旧版七样本完成后将绝对门槛写入同一报告，再运行候选，不根据候选结果调整。该 profile 仅覆盖 300 事件，10k/100k 规模和真实设备证据仍单列。

首轮分解后比较三类剩余成本：网络及发布确认、本地历史验证、业务导入/归并。按完整同步可节省时间与恢复复杂度选择下一批工作；若分解缺失，不能用继续增加上传适配器代替测量。复用现有 SyncScaleAcceptanceTest、GitFixture、文件 SQLite 与真实 runtime/transport；测试替身只模拟服务端/故障，不复制被测实现。指标缺失明确写 NOT_MEASURED，不记 0。

<a id="device"></a>
## 16. 真实设备、发布与完成条件

### 16.1 对照方法

先记录用户安装包实际版本/提交、设备/API、账号方式、仓库H/A_h和实际batch分布，取得所需授权后再采集。Android与Desktop使用同一候选源码和同一份冻结输入，确保远端起点、代理/Wi-Fi、cache冷热、构建类型一致。

主对照为“旧应用同步 vs 新应用同步”，其次为“Android vs Desktop同实现”。原生Git仅作为附加传输参考，用同一套冻结存储制品区分push与应用完整同步；不得把仅上传pack的时间直接当作包含导入/归并/日志的应用SLO。

小增量样本至少3次配对，交替执行顺序，保存全部结果、失败和quota等待。样本不足时不报告p95；真实网络结果不满足同条件可比时标记缺项。需要绝对秒数SLO时在P0基线后冻结，不凭当前报告承诺。

### 16.2 真机矩阵

Android至少覆盖用户实际设备上的前台、锁屏后返回、普通进程kill重建、网络断开/恢复、暂停保持、周期关闭后的手动失败恢复，以及release/R8真实driver覆盖升级。API26/33/35/36的受影响分支按既有支持矩阵以设备/模拟器补充，分别标注证据类型；force-stop测试单列。[R3 F5]

Desktop覆盖Windows真实发行包，macOS按既有发布要求补充构建/启动/恢复证据；完全退出期间不宣称由应用后台同步。没有macOS执行环境时保留缺项，不用Windows测试代替。[R2 §4；R3 F5]

### 16.3 真实GitHub测试的边界

使用已授权的隔离私有测试仓库和合成数据，不在生产空间注入删除、篡改或大规模压力。执行前计算请求与写入预算，默认单会话不超过1,500次API请求和100次修改请求，并服从服务端更低额度；需要增加预算时重新批准。可先做H≤20/B≤4的smoke，再在许可额度下做H≥100的小增量配对。

已知rate remaining低于20%或遇到服务端限流时停止新压力场景并等待；这属于测试保护，不代表未达到该阈值就不会触发次级限制。真实凭据不写fixture、不入仓库；测试空间清理需单独授权，不能默认删除用户拥有的仓库。

### 16.4 完成定义

阶段A完成需满足：P0–P10所需实现、独立审查、focused与集成测试通过；T01–T82 的适用项有证据，GraphQL/pipeline 仅在选中实施时执行附加 fixture/启用验收；未选中明确 DEFERRED，已有候选代码的可达性与默认关闭仍需检查；协议/安全/迁移缺陷清零；性能计数和受控耗时门禁通过；正式Android/Desktop产物可追溯，真实设备与授权网络必需证据完整，用户验收有明确结论。

“测试已编写”“fixture收敛”“发布包已构建”“未获设备/授权”对应不同状态。任一必需设备证据缺失只能交付候选并列缺项，不能标记整轮完成。R3原F5/F6尚未完成的适用事项可与本轮设备窗口共同验收，但必须分别更新记录。

<a id="extensions"></a>
## 17. 阶段B扩展：启动条件与预先约束

本节定义后续设计边界，不授权默认实施或写出新远端格式。每项启动时在roadmap新增明确决策和预算，不把A阶段结果无限等待于研究。

### 17.1 多批次原子发布

比较时点：首轮耗时分解后即可与 REST、GraphQL、流水线比较，不等待阶段 A 所有可选项完成。实施条件：固定发布/确认成本仍占主导，预期完整收益足以覆盖恢复成本，安全前置通过且范围/预算登记。保留每批256事件/512KiB边界；选取同scope、同actor/epoch、序列连续的已封存批次组成有界group。初始候选上限8批、聚合HTTP body 2MiB，先达到者截止；这是实验参数，按制品实际大小选组。

组持久化 `groupId、memberIds/order、first predecessor、逐批index前驱、最终head、精确文件字节/摘要、策略、状态`。所有制品在任何远端可见写入前完成事务冻结；不能混入已经单独prepared且前驱不兼容的批次。读取端必须通过旧parser多index链验证，不能仅因为未显式限制commit数量就宣称互通。

状态机：`PREPARED → OUTCOME_UNKNOWN/CONFIRMED → ACKED`，另有`CONFLICT/BLOCKED`。一次提交加入全部batch/index并只写最终actor head。结果未知按全部成员及最终合法链对账；发现部分匹配不能擅自把剩余项重组发布。正常group确认与全部本地成员状态同事务推进；进程中断靠组记录恢复，UI仍按业务事实计数。

缩组只允许在确认原组从未发布且原制品尚未形成不兼容前驱时生成新计划；网络超时或服务端状态不明不能自动拆组。组记录格式启用前必须设计新版本内关闭开关后的恢复；旧客户端本地数据库降级仍不支持。

附加测试B-G：1/2/8批与体积边界；组内前驱错误；两个actor并发；同actor竞争；请求过大；mutation响应丢失；发布成功、组ack中断；部分远端匹配；旧客户端读取多批单commit；同一group重试字节完全一致；请求数按group而非batch增长。

### 17.2 压缩格式

启动条件：实际正文传输时间/字节占比高，且压缩节约大于移动端CPU/内存成本。先对真实分布的合成/获授权样本离线比较可用算法，不直接引入某个native库或固定高压缩等级。

新格式必须显式声明codec/version、压缩前长度、原始明文摘要；这些字段受AEAD认证，AAD继续绑定原路径/空间/身份。处理顺序为序列化→压缩→AEAD；读取先认证后受限解压，再验证原始长度/摘要/协议。原始明文512KiB上限不变，解压输出/CPU有界，防止压缩炸弹；AEAD算法与nonce策略不变。

不能把未声明压缩字节写进旧格式。旧客户端不支持新格式时，采用明确的空间格式升级/能力约束；不能自动假定所有离线设备均已升级。已生成旧prepared保持旧格式重试。

附加测试B-C：零/极小/最大明文、不可压缩数据、压缩炸弹、错误codec、头被篡改、截断、认证失败、混合旧新批次、旧客户端明确拒绝/升级路径、压缩前后reducer等价、真机CPU/字节/内存对照。

### 17.3 导入与交换交错

启动条件：A后仍主要等待全部baseline生成，首个有效上传体验不可接受。必须先证明冻结范围和causal frontier可保持，或通过分阶段模型明确INITIAL_IMPORT的parents来源。不能简单在每50条生成后读取/投影远端，改变事件因果含义。

可评估“只生成并发送已封存的本地导入批次，远端业务接收/投影延后到生成完成”的受限流水线，但仍须证明与当前冲突/恢复/用户同时操作等价。不能按50条强制封存；setup只在导入为空且真实交换成功后完成。

附加测试B-I：生成过程中远端用户操作、本地用户新操作、冻结中断、暂停导入但允许其他交换、尾批填充率、不同调度顺序下事件parents和最终业务状态。

### 17.4 历史容量与原生Git

目录分片/状态检查点需要单独定义格式迁移、历史设备、因果tombstone、已确认删除、重放边界和安全证据；不能通过删旧索引破坏长期离线设备。进入当前容量上限前需给用户可理解诊断。

原生Git候选只替换传输层，先做相同冻结制品的push/fetch基准。必须计算Android实现/依赖体积、对象数据库磁盘增长、凭据、安全存储、取消、pack解压上限、损坏恢复和跨端发布竞争成本；本地归并不会因更换传输自动消失。

附加测试B-H：旧设备长期离线后加入、检查点丢失/篡改、分片目录完整性、原生Git对象损坏/中断/远端回退、新旧传输互操作及历史磁盘增长。

<a id="acceptance-summary"></a>
## 18. 验收结论模板

```text
基线 / 候选 commit：
实际 schema / 历史谱系：
默认启用策略 / 关闭策略及原因：
T01–T82 适用范围、通过/失败/未执行：
首次导入/首批验证确认/大量上传/首次下载目标与实测：
恢复重复工作与目录/metadata/SQL读取/重复解密：
暖no-op 请求数（按H）：
暖增量请求数、旧blob次数、变化tree字节：
连续上传 manifest/SQL/日志成本：
受控耗时与真实Android/Desktop配对：
峰值内存、数据库与cache增长：
安全/幂等/中断/旧客户端/迁移证据：
正式产物、版本、签名、SHA-256：
真实设备/授权网络缺项：
剩余瓶颈和阶段B是否启动：
用户验收结论：
```

只有记录了实际执行证据才能填写PASS。本文件提供设计与测试要求，不包含项目实施或性能测试结果。
