# 同步性能优化 Roadmap

日期：2026-09-22
状态：IN_PROGRESS / PARTIAL_IMPLEMENTATION / NOT_RELEASE_VALIDATED
设计权威：[同步性能优化设计](../2026-09-22-sync-performance-optimization-design.md)
源码核查基线：`96dbb69d7aa75acce530d477c3c9e3e23db4d55f`

本 roadmap 面向负责实现的开发 agent。本轮已从 P0 开始推进，完成对象缓存与发布确认复用的局部实现；完整阶段仍按第一个未勾选项继续，未完成的阶段不能标记为通过。实际命令、提交和验证证据集中维护在本文件，不另建 active-task 或逐任务状态报告。

本轮执行边界：不改远端事件协议、加密格式、目录或批次上限；不写真实用户同步空间，不构建或覆盖正式安装包。已实现的缓存只存在于 transport 实例内，保留 ref/commit/tree 探测及既有 guard；发布确认快照仅在当前 ref 已完整读取并与原制品匹配时复用。

## 1. 权威与交付规则

当前实现事实以[源码核查报告](../evidence/sync-performance/inputs/source-audit-report.md)为准；恢复预算、真实进度、日志、数据库谱系和授权边界继承[修复设计](../evidence/sync-performance/inputs/2026-09-21-sync-repair-design.md)。原[修复 roadmap](../evidence/sync-performance/inputs/2026-09-21-sync-repair-roadmap.md)中的 F1–F4 历史状态不能用来替代本轮验证，F5/F6 未完成事项按适用范围纳入 P10。

先在核定 worktree 实施和独立验收，不提前合并另一分支。不得擅自覆盖用户 APK、改用户数据库、写真实同步空间、删除远端历史或生成新的空间/actor 规避问题。设备和远端缺少授权时，继续完成可执行的源码、fixture与隔离测试，缺项如实登记，不把缺项标PASS。

本轮阶段A保持远端事件协议、目录、加密格式、批次上限和冲突规则。多批次发布、压缩、导入与接收交错、历史检查点和原生Git属于阶段B条件扩展，见第7节；未启动不阻塞A，不能计作已完成优化。

### 1.1 任务勾选条件

一个 P 项只有在实现完成、对应适用测试通过、必要审查完成、证据可追溯并已提交后才能勾选。没有设备只允许登记实现/fixture通过，P10继续未勾选。独立审查不可得时记录缺项，不把实现 agent 的自检改名为独立审查。

GraphQL适配实现与真实启用分开：fixture通过而真实凭据验证缺失时，状态为 `IMPLEMENTED_DISABLED`，默认关闭；不会阻塞已验证的REST兼容路径。任何会影响默认生产行为的测试失败都不能通过关闭测试绕过。

### 1.2 依赖和执行方式

默认一个实现 owner 连续推进共享主链路；最多另一个 agent 做独立审查和不冲突的测试工作。不并发修改同一 runtime、transport、SQLDelight schema或迁移；Gradle按仓库已有协调方式串行运行。

每阶段先加入可以识别现有问题的红测试，再修改实现并跑focused tests。相关集成在接口稳定后执行；最终受影响全量和发布构建在P9/P10集中执行。计数套件不注入实际长延迟；重试采用虚拟时钟；慢速网络benchmark独立运行并有请求/时长预算。不为纯勾选状态单独提交。

## 2. 阶段A总览

| 阶段 | 交付目标 | 前置 | 核心验证 |
| --- | --- | --- | --- |
| P0 | 冻结实际基线、权威行为、命令与输入 | 无 | R1矛盾定向核查、环境/授权/数据谱系记录 |
| P1 | 观测接入与可失败基线测试 | P0 | T01–T04、建立T65–T76红门禁 |
| P2 | 本地schema/对象缓存/验证上下文 | P1 | T05–T12、T57–T59 |
| P3 | 分层tree、manifest/guard/发现原子化 | P2 | T13–T22、T60；缓存安全独立审查 |
| P4 | 快照返回复用、交换与恢复闭环 | P3 | T21、T25–T27、T30–T32、T43 |
| P5 | SQL隔离、批次复用、事实计数/日志/导入成本 | P4 | T39–T48、T62–T63 |
| P6 | HTTP限流、四次失败预算、统一唤醒 | P2；集成依赖P4/P5 | T49–T56；运行安全独立审查 |
| P7 | 单批REST聚合及GraphQL受控适配 | P3/P4/P6 | T23–T30、T55、T61 |
| P8 | raw下载、有界流水线 | P3/P5/P6 | T33–T38、T61 |
| P9 | 完整回归、规模、性能门禁、集成审查 | P0–P8 | T01–T76适用集，特别T65–T76 |
| P10 | 正式包/覆盖升级/授权真网/双端设备/用户验收 | P9 | T57–T64、T73–T74及用户验收 |

- [ ] P0：实际基线与约束冻结。
- [ ] P1：观测与基线红测试。
- [ ] P2：缓存与必要本地迁移。
- [ ] P3：快照/tree/guard/发现闭环。
- [ ] P4：交换循环与发布确认复用。
- [ ] P5：数据库、编解码、日志、计数和导入局部优化。
- [ ] P6：统一限流、重试和调度。
- [ ] P7：单批发布请求聚合。
- [ ] P8：raw下载与网络/本地处理重叠。
- [ ] P9：自动化、性能和独立集成审查通过。
- [ ] P10：正式交付及用户验收通过。

<a id="p0"></a>
## 3. P0–P4：先消除历史读取放大

### P0：实际基线与验收输入冻结

**范围**：只读核对实际仓库；不先开始协议或UI重构。

记录 worktree、branch、HEAD、未提交diff和可见安装包/发布包对应提交。若HEAD不同于R1，列出与同步性能有关的变化，重新确认受影响调用链；不得reset或覆盖用户变更。记录实际schema、迁移编号、已发布谱系、签名/包名、Android/Desktop构建变体。

必须定向查清四点：

1. `getFieldEvents`完整SQL与生成调用是否缺space/generation过滤；published/remote计数是否跨scope。
2. guard保护路径集合、已知head回滚判断、fingerprint输入和现有允许集合；后续cache/delta不得凭猜测缩小。
3. tree/index预算的实际计数对象、最大响应检查方式、batch/index/head最终存储字节表示。
4. 首个鉴权/数据/确认请求边界、MAX_AUTOMATIC_ATTEMPTS生产语义、HTTP内部重试和Worker重试实际调用。

记录真实API版本头、Accept、认证类型和分支保护；没有版本头就标明，不能抄官方示例顺带升级版本。发现实际Gradle任务/测试过滤方法/协调器、已安装依赖版本及现有benchmark入口；将真实可运行命令登记到第8节。复用`SyncScaleAcceptanceTest`、现有migration fixture和production wiring测试，不虚构新task。

冻结两类数据集：结构用H/A_h/B/D确定的合成Git对象集；业务用10k/100k事件、3/10 actor、原有decision规模及偏斜字段分布。所有输入记录seed/hash/实际事件与批次数。大H计数实验不等于大事件业务实验，两类均保留。

**输出**：实际基线和差异、P0核查结论、命令清单、数据集清单、测试预算、API适配/权限未知项。保留原修复权威“四次网络失败耗尽”，不以当前错误实现重写产品要求。

**退出**：实现者能够指出scope、guard、predecessor和durable队列边界；没有未解决的高风险语义歧义。只缺设备/真实网络授权不阻塞P1，但记录为P10缺项。

### P1：观测、基线与红测试

**范围**：现有 runtime/HTTP/SQL/codec/runstore接入低成本指标。性能诊断不记录标题、凭据或原始密文。

先运行小H基线，验证设计§1.1请求公式：合法空空间H=0/A_0=0；已有历史H=10/A_h=2，B=0/1/4、D=0/2选择合法组合。首次发布新增head采用可变A_j公式，不捏造无前驱的head；预置fixture请求不计入测试阶段。为暖no-op和暖小增量建立会在旧实现失败的“旧blob请求为0/固定no-op成本”测试，不只写耗时打印。

记录phase/span、cache、HTTP call与exchange、SQL/事务/锁等待、日志/计数、业务批次分布和正文大小。测试指标本身不会每个事件开事务、更新UI或发送网络；诊断关闭时做开销对照。

**测试**：T01–T04；新增T65/T66/T67/T69的红断言；T70–T74建立可运行框架但不提前登记通过。现有规模测试的历史PASS只记录为输入事实。

**退出**：基线红失败能明确指向全历史blob读取/重复快照/日志小事务；报表能区分测量与公式推导，脱敏通过。基线对大H超预算时登记ABORTED，不阻止已得到的小样本诊断。

### P2：缓存与必要持久结构

**范围**：`SyncObjectStore`、验证上下文、cache文件处理、必要snapshot/discovery/facts/retry字段迁移。最终表名优先复用现有实现。

实现L1/L2的scope键、OID/长度/摘要检查、lease、LRU、single-flight、磁盘短写/满盘处理。cache失败仅影响可重建内容，guard/outbox/inbox/制品不得归入缓存清理。解析结果绑定密钥/路径/验证版本，不持久化秘密。

按设计§4建立可原子发布的snapshot revision结构；本阶段可以先通过旧完整读取器填充，P3再接增量。禁止为每个commit保存完整manifest副本。新迁移编号来自P0实际目录，不预占34或其他猜测值。

**测试**：T05–T12；T57–T59的新增迁移部分。用真实文件SQLite验证两条旧schema28谱系及R2规定其他输入；验证cache目录/DB交叉故障，DB旧副本与新cache并存不提升信任。

**审查门禁**：缓存键、scope/密钥失效、guard独立性、migration谱系、crash一致性单独审查；不安全项未关闭不能进入P3默认快路径。

**退出**：缓存完整/缺失/损坏/被系统清理都不改变业务结果和信任历史；数据迁移可恢复；未使用增量解析时的性能收益只按实际结果描述。

### P3：分层tree、已验证快照与增量发现

**范围**：`GitHubGitDatabaseClient.readSnapshot`、`SyncRemoteSnapshotGuard`、manifest、actor cursor、discovery与路径索引。

实现ref不变快速路径；blocked/回滚/scope检查在cache返回前执行。变化commit固定tree；默认非递归读取且省略recursive参数；不变子树复用，变化子树diff。旧不可变文件消失/修改、head合法推进、bootstrap/space/chain全部保留校验。

第一版本可以复用新快照的全量本地guard计算，但同一快照只计算一次，计量保守路径；不得把CPU从HTTP转移到每批复制全部manifest。路径索引先查重后建表，局部更新。

新snapshot/guard/cursor与待发现批次必须原子提交；使用scope/owner/guard revision条件写。重启ref未变仍能从durable队列继续未接收工作。冷cache重建不能清guard。

**测试**：T13–T22、T60；T65/T66的网络读门禁开始转绿。用固定seed的恶意tree/index场景与原完整guard差分。保留单actor大平铺目录实验，报告tree字节残余成本。

**退出**：完整保护范围有证据；暖no-op无需旧blob或tree；新增批次不会因快照已保存而在崩溃后丢失。容量超限显示明确原因，不接受部分树。

### P4：交换循环与确认快照复用

**范围**：`SyncDatabaseExchange`、outbox exchange、publisher结果、run/segment恢复接口。此时可以仍使用原REST六次写请求，先证明重复读取消除。

`publish()`返回实际确认的`VerifiedSnapshot`及delta，外层复用。已知自己刚上传的精确字节用于确认缓存，不把prepared当作发布成功。发布结果带其他actor变化时完整处理。

无新snapshot不遍历全历史逐批查去重；改从durable待办集合处理。保留下载→投影→上传的既有因果顺序；将“ref没变”与“没有本地待办”分开。收尾按设计作必要final ref，持续变化使用最多3个追赶segment并持久保留后续工作。

结果未知优先对账；scope/owner竞争不能写旧run事实。循环内部不重新load全manifest或clone整个path map。

**测试**：T21、T25–T27、T30–T32、T43；T67请求数量与manifest/查询增长门禁。重点重开runtime/文件库测试“确认后ack前”“新快照提交后正文前”。

**退出**：稳定纯上传的快照解析≤B+1，额外终态只需轻量ref；未完成队列可恢复；旧历史blob请求消除。此时应保存第一份可量化收益报告，不等待后续API聚合。

<a id="p5"></a>
## 4. P5–P8：局部成本、限流与上传/下载吞吐

### P5：SQL、编解码、计数、日志和导入

**范围**：journal、outbox/inbox、projector、runstore、baseline、controller及必要索引。

对P0确认的scope查询问题做最小修复；为真实热点保存SQL plan后新增/调整索引，不改reducer规则。建立冻结批次只读工作对象，复用事件/描述/摘要；事务内scope、owner、状态、revision校验继续执行。

将新确认事实与业务检查点同事务提交，正常热路径删除反复全局COUNT/SUM；新run/恢复允许一次有作用域的reconciliation。日志一批一次事务/裁剪，保留稳定业务身份、500条/20次终态与截断提示。不能靠日志是否存在决定业务是否已计数。

导入使用事务内processed/remaining，避免每50条COUNT全部剩余行；阶段A不交错远端接收，避免改变parents。不能因导入块大小降低批次填充率。

计时ticker与数据refresh分开；可见数据刷新合并，终态立即派发。关闭面板停止可见专属查询，真实controller/SQLDelight通知路径必须联测。

**测试**：T39–T48、T62–T63；T69、T72中本地成本部分；受影响迁移T57–T58回归。长历史字段reducer差分必做，不将scope修复与旧错误结果强求相同。

**退出**：正常每批没有全局计数、每对象日志事务或批次反复decode；计数在崩溃后可靠重建；长因果历史仍有的成本被明确记录，未偷偷删除验证。

### P6：统一HTTP限流、重试与平台恢复

**范围**：`SyncHttpClient`、GitHub错误适配、`SyncRuntime/RunStore/Coordinator`、Android Worker/scheduler、Desktop scheduler。

实现HTTP/GraphQL统一错误分类、账号级notBefore、Retry-After/reset及secondary无提示等待；不把普通403/422统一当网络或冲突。大量修改请求串行节流，并区分主动等待和服务端限流。

分离attemptId、owner接管和networkFailureCount。遵守首次失败后3次自动重试、第4次耗尽；10/30/120秒与服务端较晚时间持久保存。系统中断、早醒、重复触发、局部ref冲突不消耗网络预算，零星HTTP成功不重置本run预算。

去除WorkManager对同一持久业务失败的第二套重试预算，保留基础设施补偿和到期wake。暂停/取消/耗尽先判断，避免不合格run仍发鉴权请求。旧HTTP call取消和owner释放必须真实到达生产路径。

**测试**：T49–T56；与P4的未知结果、P5的事实事务组合。受控时钟验证精确请求时点，无长sleep；Android真实WorkManager测试驱动与Desktop共同恢复契约。

**审查门禁**：错误分类、notBefore、四次失败、owner fencing、Worker早醒尾部竞争独立审查。不能仅以新增`MAX=4`替代预算/调度修复。

**退出**：无叠加业务重试，无绕过服务端等待；更新schema后恢复旧run的转换规则有测试；权限错误不自动高频重试。

### P7：单批发布请求聚合

**范围**：现有发布器提取最小`SyncCommitWriter`，支持REST_INLINE_TREE和受控GraphQL_SINGLE_BATCH；原REST_BLOBS保留兼容路径。

按最终存储字节严格UTF8往返选择inline，不按扩展名判断；二进制文件单独blob，所有路径保持base_tree和原子ref。按最终HTTP body做2MiB初始本地预算，超出在写ref前选择兼容方式。

GraphQL单批适配实现HTTP200+errors、expectedHead、返回数据及未知结果处理；不把clientMutationId当幂等保证。实际凭据/分支保护未验证时默认关闭，不扩大权限。原制品可在不同传输实现间恢复，不能重新加密。

失败回退必须先区分是否可能已发布。未知状态先对账；同身份异内容/部分不合法发布阻塞。不能通过另一API绕过权限或分支保护。

**测试**：T23–T30、T55、T61；T67、T74。对全UTF8样本断言6→3次写请求；混合样本断言3+q。GraphQL fixture断言一次mutation且确认安全；真实schema/token验证放P10并控制开关。

**退出**：REST优化默认路径正确且旧制品可恢复；GraphQL实现状态/启用状态分别登记。写请求减少必须包含完整确认和节流对照，不只统计POST数量。

### P8：raw下载与有界重叠

**范围**：blob读取表示、batch下载/接收调度、队列预算、取消路径。

实现raw与JSON/Base64交付相同最终字节；保持私有API端点与认证边界，不跟随未知origin。默认单网络读取者，最多2批排队、原始缓冲合计4MiB，下载与上一批解密/校验/写库重叠。byte permit在请求前预留，释放与取消可靠。

保留同actor顺序和同快照dirty合并，不每个小窗口重复projector。所有错误仍走现有invalid/blocked/partial语义，网络等待不进入数据库事务。

**测试**：T33–T38、T61；T68、T71、T72。加大批次、无Content-Length、慢消费者、连接中断、scope切换、内存压力与暂停，验证请求并发始终1且没有lease泄漏。

**退出**：结果差分一致且队列/资源门禁通过。若配对测量显示流水线无收益或>10%退化，默认关闭pipeline，保留raw与安全优化，写明证据和原因；不能假称下载已达到指定提速。

<a id="p9"></a>
## 5. P9：集成、规模、性能与独立审查

**前置**：P0–P8提交完成，scope/schema/冻结制品/发布接口稳定。重要改动后重跑相关focused测试，不用只跑旧测试名单。

### 5.1 自动化完整矩阵

按设计§15执行T01–T76适用项。GraphQL未启用不免除其纯适配fixture测试，但真实启用验收可以保持未执行；pipeline关闭时仍验证串行fallback和取消正确性。测试发现设计不适用时写明原因并修订权威设计，不直接删除条目。

必须覆盖四种cache状态：全冷、L1/L2暖、进程重启仅持久状态暖、强制淘汰/缓存损坏。无变化、单远端增量、单本地增量、连续上传、多actor并发、跨epoch、同字段长历史分别有输入。

复用10k/100k业务场景；另外跑H=100/1000/9000的结构成本场景。两类参数不能混称“10万条”；报告实际事件/批次/对象/字节。大规模fixture在测试计时之前预置，单独记录生成成本。

### 5.2 硬门禁

| 项目 | 必须达到 |
| --- | --- |
| 暖no-op | 符合完整cache/无待办前提时3次请求；无旧blob/tree、无历史逐批去重和全量evidence |
| 暖小增量 | 旧index/head/batch正文GET为0；只处理未知对象，tree成本单列 |
| 连续上传 | 快照解析≤B+1；无B×H旧blob读取、manifest重写和逐批去重 |
| 数据库热路径 | 正常每批不全局COUNT/SUM；日志每批至多一次写事务/裁剪；decode复用可量化 |
| 安全与恢复 | 篡改/删除/回滚仍拒绝；响应未知与中断后无丢失、双计、回声或错误删除 |
| 受控耗时 | 设计T70固定profile 7次配对，中位数≤旧实现50%；失败/预算中止完整登记 |
| 下载与内存 | T71不退化>10%，T72资源预算和无持续增长；没有收益的可选流水线保持关闭 |
| 迁移/旧客户端 | 支持谱系及新旧远端parser兼容通过；未知结构安全失败 |

不使用未经实测的几倍提速作为结论；不将fixture时间当真网；不因no-op3次通过就宣称新设备首同步也已验证。

### 5.3 故障、状态机与测试有效性

执行设计F-A～F-K固定故障切点，每个都新建runtime并重开文件库。T75至少100个有界seed；运行序列及最小失败轨迹可复现。T76测试变异必须证明旧blob重读、发现队列非原子、第三次耗尽至少各有测试能够抓到。

负对照实现不带入发行构建。不能把数据/安全故障作为普通网络重试掩盖；不能靠扩大timeout或测试跳过让性能回归消失。

### 5.4 独立审查和最终集成

复审范围：缓存信任与scope、完整guard等价、manifest/queue原子性、prepared/unknown结果、四次预算/owner、SQL隔离、计数去重、schema和默认开关。高风险接口在P2/P3/P6已审查，P9审查集成差异和遗漏。需要修复的项关闭后做定向复审；新增独立大功能不得默默加入本轮。

集中执行受影响Android/Desktop测试、格式检查、构建/Test Mode所需前置。保存实际命令、退出码、候选commit、证据路径与测试缺项。

**退出**：默认生产路径没有未关闭正确性/安全缺陷；性能结构门禁通过，受控benchmark可复现；有剩余tree/长字段成本的明确说明。此时状态为`AUTOMATED_VERIFIED`，仍未等同P10设备验收。

<a id="p10"></a>
## 6. P10：正式产物、真实网络、覆盖升级与用户验收

### 6.1 构建与发布追溯

核对实际包名/签名/最新versionCode/对应schema后分配新版本，创建新的产物目录，不覆盖历史aex.11或旧候选。记录Android签名arm64/R8包、Windows发行产物及所需macOS构建/Test Mode证据的完整路径与SHA-256。不能用unsigned APK、debug或错误签名替代正式覆盖升级。

先在隔离设备上验证所有已支持谱系→本轮构建；真实Android数据库driver与production DI必须接通。书架/阅读/作者字段/同步制品/凭据保留。数据副本只能通过已授权且实际可用的方法取得，release无法run-as时不宣称备份完成。

### 6.2 真实网络

获得隔离私有仓库写入授权后，先进行小规模smoke与API适配契约验证。测量当前凭据下REST_INLINE/raw及可选GraphQL；GraphQL真实验证失败或缺失继续关闭，不影响明确合格的REST路径。

按设计默认API/写入预算运行，不能把大H/B数万请求的基线直接打到GitHub。记录会话quota、notBefore、代理路径、连接复用、总耗时与各阶段分解。相同冻结输入和远端起点做至少3次基线/候选配对；真实错误与限流不从报告消失。

真机性能受网络波动导致不可比时，补足同条件数据后再定结论；只能交付候选并标缺项，不能凭fixture提升推断用户设备已提速。

### 6.3 设备与平台

Android用户实际设备覆盖：亮屏手动同步、暖no-op、小增量、首次下载/导入、锁屏再返回、普通kill重建、网络断开/恢复、主动暂停、周期关闭后手动失败自动恢复、耗尽再显式重试。force-stop独立记录，不与系统kill混同。

API26/33/35/36受影响分支按仓库支持范围覆盖，实际设备与模拟器分别标记。正式R8下检验权限、通知、前台提升失败、HTTP取消、数据库锁和错误处理进程。Windows发行构建执行对应共享恢复/数据一致性与性能对照；macOS不足明确缺项，不使用Windows替代。

### 6.4 用户验收清单

- [ ] 已确认实际安装的是本轮指定候选，代码提交、包版本、schema可追溯。
- [ ] 覆盖升级后原书架、阅读、作者信息、设置与同步连接保留。
- [ ] 无变化同步耗时稳定；报告可核对无重复历史index/head下载。
- [ ] 日常少量变更在Android真机明显改善，并有同条件测量；未改善时已有阶段定位和未完成结论。
- [ ] 首次上传/下载有真实阶段与数量；不会将已接收直接显示为已投影。
- [ ] 同步途中锁屏、断网、普通kill后，恢复/等待原因可见，已确认进度不丢失/双计。
- [ ] 主动暂停不会被联网、重开或周期触发覆盖；耗尽后保持正确状态。
- [ ] 上传响应未知后恢复不产生重复事件、密文身份冲突或回声。
- [ ] 删除确认、阅读冲突、来源不可用等原业务语义正常。
- [ ] 历史日志可查看，500条截断有提示，关闭重开不补播旧成功。
- [ ] 无可观测持续内存/磁盘增长、卡死、OOM或数据库锁异常；剩余容量限制已说明。
- [ ] 用户明确接受本候选；产物hash与结论已记录。

**退出**：全部必需正式/设备/授权网络证据及用户明确结论齐备，才勾P10。缺项状态可以为`RELEASE_CANDIDATE / DEVICE_VALIDATION_PENDING`。本阶段不授权合并另一开发分支；未来合并按R2/R3另做双方最新谱系兼容验收。

<a id="extensions-roadmap"></a>
## 7. 阶段B条件扩展

阶段A结束后先按实际耗时占比分配工作，不同时启动所有扩展。每项都有独立启用结论和格式/迁移风险审查；未启动标`DEFERRED`，不得标完成。

| 扩展 | 启动证据 | 实施/测试要求 | 默认状态 |
| --- | --- | --- | --- |
| B1 多批次一次发布 | 固定发布/确认成本仍主要限制大量上传 | 按设计§17.1建立group冻结/原子确认/未知结果状态机；B-G矩阵与旧parser互通；禁止不明状态自动拆组 | DEFERRED |
| B2 压缩新格式 | 正文传输占比高，样本压缩收益大于CPU/内存成本 | 明确格式升级与旧客户端策略；认证codec/长度；B-C炸弹/混合格式/旧制品/真机对照 | DEFERRED |
| B3 导入与交换交错 | 生成全部baseline主导首次等待 | 先证明parents/frontier等价；禁止每50条封存；B-I并发用户操作/远端操作/恢复语义 | DEFERRED |
| B4 目录分片、检查点或原生Git | 单层目录/容量或首次冷同步仍不达标 | 单独格式与迁移方案；长期离线设备与安全证据；B-H损坏/回退/互通/资源测试 | DEFERRED |

- [ ] B1：仅在启动决策登记后实施，单独验收。
- [ ] B2：仅在启动决策登记后实施，单独验收。
- [ ] B3：仅在启动决策登记后实施，单独验收。
- [ ] B4：仅在启动决策登记后实施，单独验收。

本roadmap已经为扩展规定必要约束和验证方向，但不声明尚未选择的压缩算法、Git库或checkpoint协议已定案；实施前必须补齐相应详细格式，不允许agent自主猜测后写入用户空间。

## 8. 命令、证据和进度登记模板

### 8.1 实际环境与命令

以下由P0填入实际存在且已确认的命令，不填推测的Gradle task。

| 项目 | 实际值/命令 | 当前状态 |
| --- | --- | --- |
| worktree / branch / HEAD / dirty diff | 待核对 | NOT_CHECKED |
| 基线与候选构建变体 | 待核对 | NOT_CHECKED |
| schema / 支持的历史指纹 | 待核对 | NOT_CHECKED |
| API版本头 / Accept / 认证方式 | 待核对 | NOT_CHECKED |
| focused domain/data/transport测试 | 待发现 | NOT_RUN |
| migration Android/JVM测试 | 待发现 | NOT_RUN |
| WorkManager / Desktop scheduler测试 | 待发现 | NOT_RUN |
| presentation/controller测试 | 待发现 | NOT_RUN |
| 10k/100k scale和性能报告入口 | 复用SyncScaleAcceptanceTest，命令待核对 | NOT_RUN |
| 受影响全量与格式检查 | 待发现 | NOT_RUN |
| Android签名release / Windows发行 / macOS构建 | 待发现 | NOT_RUN |
| 仓库构建协调器 | 待核对 | NOT_CHECKED |
| 用户设备/隔离仓库授权范围与预算 | 待授权或核对已有明确授权 | NOT_AUTHORIZED |

### 8.2 阶段执行记录

| 阶段 | 实现commit | 实际测试命令/退出码 | 覆盖ID | 审查结论 | 证据路径 | 状态/缺项 |
| --- | --- | --- | --- | --- | --- | --- |
| P0 | — | — | — | — | — | NOT_STARTED |
| P1 | — | — | — | — | — | NOT_STARTED |
| P2 | 本轮候选提交 | `:data:jvmTest` focused / 0 | T05/T06 部分 | 待独立审查 | `docs/evidence/sync-performance/implementation-2026-09-22.md` | PARTIAL；未做迁移/L2/crash 验证 |
| P3 | 本轮候选提交 | `SyncGitSafetyContractTest`、`SyncSpaceTransportContractTest` / 0 | T13/T16 部分 | 待独立审查 | `docs/evidence/sync-performance/implementation-2026-09-22.md` | PARTIAL；未做分层 tree/manifest/delta |
| P4 | 本轮候选提交 | `JvmSyncRuntimeStorageContractTest`、发布异常 focused / 0 | T21/T25/T27/T30 部分 | 待独立审查 | `docs/evidence/sync-performance/implementation-2026-09-22.md` | PARTIAL；仅确认快照复用，未完成 durable discovery |
| P5 | — | — | — | — | — | NOT_STARTED |
| P6 | — | — | — | — | — | NOT_STARTED |
| P7 | — | — | — | — | — | NOT_STARTED |
| P8 | — | — | — | — | — | NOT_STARTED |
| P9 | — | — | — | — | — | NOT_STARTED |
| P10 | — | — | — | — | — | NOT_STARTED |

### 8.3 默认策略与风险登记

| 策略 | 设计默认 | 实际默认/证据 |
| --- | --- | --- |
| L1/L2缓存、快照复用、delta发现 | 对应安全门禁通过后启用 | L1 blob cache 与确认快照复用已实现；L2、delta 未实施 |
| 分层tree及容量保护 | 启用；不改变现有总预算语义 | 未实施 |
| REST_INLINE_TREE | 通过字节/确认门禁后启用；二进制按文件fallback | 未实施 |
| GRAPHQL_SINGLE_BATCH | 实际凭据/API证据齐全前关闭 | 未实施 |
| raw blob | 表示兼容门禁通过后启用 | 未实施 |
| 下载pipeline | 正确性/资源通过且无>10%耗时退化才启用 | 未实施 |
| 多HTTP并发下载 | 关闭 | 未实施 |
| 多批次group/压缩/导入交错 | 阶段B，关闭 | 未启动 |

### 8.4 设计变更记录

每次只记录会改变范围、不变量、默认策略、验收阈值或兼容性的决策：日期、触发证据、原规则、新规则、影响测试、审查结论。特别禁止在测试失败后静默放宽请求/内存/耗时阈值；有合理原因时保留原失败结果与批准记录。

## 9. 当前记录

2026-09-22：完成 P0 定向核对。实现并测试 transport 内 16 MiB 有界 Git blob L1 缓存、Git blob SHA-1/SHA-256 内容校验，以及 `SyncPublishResult.confirmedSnapshot` 驱动的交换循环复用；完整 ref/commit/tree 与 guard 路径保留。focused JVM/契约测试通过，独立审查为 `PASS_WITH_ADVISORY`；P2/P3/P4 仍为局部状态，P5–P10 未启动，阶段 B 保持 DEFERRED。
