# 同步性能优化 Roadmap

日期：2026-09-22
状态：PARTIAL_IMPLEMENTATION / BUDGET_STOP / NOT_RELEASE_VALIDATED
设计权威：[同步性能优化设计](../2026-09-22-sync-performance-optimization-design.md)
源码核查基线：`96dbb69d7aa75acce530d477c3c9e3e23db4d55f`

本 roadmap 面向负责实现的开发 agent。本轮已从 P0 开始推进，完成对象缓存与发布确认复用的局部实现；完整阶段仍按第一个未勾选项继续，未完成的阶段不能标记为通过。实际命令、提交和验证证据集中维护在本文件，不另建 active-task 或逐任务状态报告。

本轮执行边界：保留事件协议、加密格式、目录与批次上限；不写真实用户空间，不覆盖正式安装包。当前未提交候选已跨入持久缓存、manifest/发现和部分 API 路径，均按 PARTIAL 核验；不能再描述为“只有 transport 内缓存”。本次先补测并修订顺序，不默认扩展生产功能。

## 1. 权威与交付规则

历史源码基线见[源码核查报告](../evidence/sync-performance/inputs/source-audit-report.md)为准；恢复预算、真实进度、日志、数据库谱系和授权边界继承[修复设计](../evidence/sync-performance/inputs/2026-09-21-sync-repair-design.md)。原[修复 roadmap](../evidence/sync-performance/inputs/2026-09-21-sync-repair-roadmap.md)中的 F1–F4 历史状态不能用来替代本轮验证，F5/F6 未完成事项按适用范围纳入 P10。

先在核定 worktree 实施和独立验收，不提前合并另一分支。不得擅自覆盖用户 APK、改用户数据库、写真实同步空间、删除远端历史或生成新的空间/actor 规避问题。设备和远端缺少授权时，继续完成可执行的源码、fixture与隔离测试，缺项如实登记，不把缺项标PASS。

本轮阶段A保持远端事件协议、目录、加密格式、批次上限和冲突规则。多批次发布、压缩、导入与接收交错、历史检查点和原生Git属于阶段B条件扩展，见第7节；未启动不阻塞A，不能计作已完成优化。

### 1.1 任务勾选条件

一个 P 项只有在实现完成、对应适用测试通过、必要审查完成、证据可追溯并已提交后才能勾选。没有设备只允许登记实现/fixture通过，P10继续未勾选。独立审查不可得时记录缺项，不把实现 agent 的自检改名为独立审查。

GraphQL 改为条件扩展：REST 后仍有瓶颈且收益/预算已登记才实现。未选中标 DEFERRED；已有孤立 adapter 只算未验候选，不能登记 IMPLEMENTED_DISABLED。选中且实现/fixture 通过但真实启用证据不足时，才登记 IMPLEMENTED_DISABLED。任何会影响默认生产行为的测试失败都不能通过关闭测试绕过。

### 1.2 依赖和执行方式

默认一个实现 owner 连续推进共享主链路；最多另一个 agent 做独立审查和不冲突的测试工作。不并发修改同一 runtime、transport、SQLDelight schema或迁移；Gradle按仓库已有协调方式串行运行。

每阶段先加入可以识别现有问题的红测试，再修改实现并跑focused tests。相关集成在接口稳定后执行；最终受影响全量和发布构建在P9/P10集中执行。计数套件不注入实际长延迟；重试采用虚拟时钟；慢速网络benchmark独立运行并有请求/时长预算。不为纯勾选状态单独提交。

### 1.3 修订后执行顺序与停止条件（2026-09-25）

本次审议已纳入设计 §0.2/§15.11。核心安全约束不变，P7/P8 的复杂扩展改为收益前置；不是先实现再决定值不值得。本轮先完成下面第 1 个批次，后续按退出证据推进：

| 顺序 | 有界工作与复用入口 | 退出/验证；不包含 |
| --- | --- | --- |
| 1：P0/P1 补测 | 核对当前 dirty 候选、已有失败与日志；复用 SyncScaleAcceptanceTest、GitFixture、文件 SQLite，采 T77–T82 及暖增量 | 保存可复跑命令、输入/源码指纹、真实缺项和初步分解；补齐旧版同条件对照并冻结首次同步目标才关闭 P1。不改生产行为，不实现新 adapter |
| 2：P2–P4/P6 安全闭环 | 对已有 cache/manifest/discovery/确认/恢复代码补缺；先以真实文件计数复现目录扫描放大，再 TDD 修复 | focused 红绿、相关集成、一次批次安全审查；保留冻结制品与原子发现，P4 留量化收益报告。不得顺带重写协议/归并器 |
| 3：P5/P7/P8 必做路径 | 按分解选择 SQL/日志热点，验证 REST inline 与串行 raw 的完整收益 | 真实 production wiring、字节兼容/错误/恢复契约及配对耗时；不必等待可选 GraphQL/pipeline 完工 |
| 4：条件扩展决策 | 首轮分解后比较多批次发布、GraphQL、下载流水线的预计节省、内存、恢复与维护成本 | 选择项须有启动决策、数值目标、范围和预算；未选择项 DEFERRED，不阻塞阶段 A。不是自动同时实施 |
| 5：原生 Git 候选 | 仅当前路径仍未满足已冻结目标时，同数据/同制品比较 | 包含 Android 依赖、磁盘、取消、凭据、恢复与业务归并；不能预判最优或直接替换 |

依赖解释：已有跨阶段代码可以用于候选测量，但不允许因源码存在绕过 P0/P1；完成第 1 批不代表所有基线门禁已通过。首次样本允许有界小规模定位，10k/100k 与正式双端配对仍须补齐。没有真实网络/设备授权时继续本地诊断，保留外部缺项，不制造远端写入。

持续执行授权（2026-09-25）：用户授权使用周额度的 10% 完成本次优化，优先采用 GPT-6 Sol medium 实施与 GPT-6 Luna xhigh 承担边界明确的分析/测试，主代理规划、整合及独立验收。额度百分比不能伪装成固定 token 预算；以可取得的实际用量核对，接近上限时停止新增成本并如实保留未完成项。上一批文档与候选诊断提交不是整体停止条件。

当前执行预算：最多 2 名子代理；Sol 连续承担 P0/P1 基线及后续主要实现，Luna 先只读核对缓存复杂度与安全边界，允许并行的只有不冲突读取/测试准备。重型 Gradle 由 Sol 协调串行；每个行为改动执行 focused 红绿与相关集成，首个稳定功能批次由未实施该部分的主代理独立审查。默认独立审查 1 轮、必要修复复验 1 轮；实现后全量测试 1 次，若仍有必须扩大的验证，先说明具体失败、必要性和新增成本。完整目标仍包括 P9/P10 所要求的发布、设备与真实网络证据，不因上一批未运行就删去门禁；外部输入暂缺时继续本地可完成工作。

最终验收分工（2026-09-25 用户明确指定）：真机与真实仓库最终验收由用户执行。Agent 继续完成本地实现、自动化验收和交付构建，并提供可执行步骤、预期结果与已知差距；不以 adb 无设备为当前工作阻塞，不擅自安装用户设备或写入真实仓库。P10 用户部分在反馈前保持待验收，不能把测试步骤交付写成真实设备/网络已经通过。

本轮先核对真实进程、历史失败与测试覆盖，再补完整 Runtime/账号 gate/L2 的候选及旧版对照。已有源码只有在测试、审查与对应门禁满足后才提交收口；不得把所有 dirty 内容一次性作为已验收产物。没有产品链路或旧版配对的分段样本仍只用于诊断。当前预估基线批次 25–40 分钟，主要成本是 JVM 编译、真实数据库/HTTP 测量；缓存修复与发布成本随首轮结果登记，不预设可选优化必做。

规模与内聚性说明：已有候选超过 8 个文件/400 行，涉及共享 transport、持久快照/发现、数据库迁移、恢复状态及双端 DI；这些接口必须共同编译并用真实 exchange/wiring 验收，不能按文件数量机械拆开。主要风险是迁移谱系、失联后的确定制品与确认、旧 owner/快照误写，以及缓存故障影响业务状态。本轮修复只沿这些现有边界收敛；更大的差异不构成跳过审查或把未验证候选打勾的理由。

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
| P7 | 单批 REST 聚合；GraphQL 条件扩展 | P3/P4/P6 | T23–T30、T55、T61 |
| P8 | 串行 raw 下载；流水线条件扩展 | P3/P5/P6 | T33–T38、T61 |
| P9 | 完整回归、规模、性能门禁、集成审查 | P0–P8 必做项 | T01–T82 适用集，特别首次同步与本地放大 |
| P10 | 正式包/覆盖升级/授权真网/双端设备/用户验收 | P9 | T57–T64、T73–T74及用户验收 |

- [ ] P0：实际基线与约束冻结。
- [ ] P1：观测与基线红测试。
- [ ] P2：缓存与必要本地迁移。
- [ ] P3：快照/tree/guard/发现闭环。
- [ ] P4：交换循环与发布确认复用。
- [ ] P5：数据库、编解码、日志、计数和导入局部优化。
- [ ] P6：统一限流、重试和调度。
- [ ] P7：单批发布请求聚合。
- [ ] P8：串行 raw 下载验证；流水线仅在收益前置通过后实施。
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

**本轮核查记录（2026-09-24，阶段仍未勾选）**：当前 worktree 为 detached HEAD `f0e3d4f21a50620896e5288cccf1a23779595dae`，属于本轮中间 checkpoint；核查时未提交 diff 覆盖同步 runtime、transport、schema、测试及文档，用户安装包对应提交仍未知。迁移源码当前声明兼容 schema 36，worktree 已有迁移 33–35 的本轮变更；正式发布谱系仍需 P10 核对。R1 的 F11 与其所贴 SQL 自相矛盾：实际 `getFieldEvents`、`getAffectedFields`、published/remote totals 查询均按 `space_id/generation` 隔离；当前没有证据支持跨 scope SQL 缺陷，不应照 F11 文字重复修改。工作树实际 API request `Accept` 为 `application/vnd.github+json`，`X-GitHub-Api-Version` 为 `2026-03-10`；blob GET 已有 raw Accept 候选，但 production 验证仍在 P8/P9。当前测试命令使用 `scripts/gradle-coordinator.py run --key <unique-key> -- .\gradlew.bat :data:jvmTest --tests '<fully-qualified-filter>'`；全量、变体及正式包命令仍按 P9/P10实跑确认。R1 输入文件保留原样，矛盾只在本设计与本阶段记录中更正。

**退出**：实现者能够指出scope、guard、predecessor和durable队列边界；没有未解决的高风险语义歧义。只缺设备/真实网络授权不阻塞P1，但记录为P10缺项。

### P1：观测、基线与红测试

**范围**：现有 runtime/HTTP/SQL/codec/runstore接入低成本指标。性能诊断不记录标题、凭据或原始密文。

先运行小H基线，验证设计§1.1请求公式：合法空空间H=0/A_0=0；已有历史H=10/A_h=2，B=0/1/4、D=0/2选择合法组合。首次发布新增head采用可变A_j公式，不捏造无前驱的head；预置fixture请求不计入测试阶段。为暖no-op和暖小增量建立会在旧实现失败的“旧blob请求为0/固定no-op成本”测试，不只写耗时打印。

记录phase/span、cache、HTTP call与exchange、SQL/事务/锁等待、日志/计数、业务批次分布和正文大小。测试指标本身不会每个事件开事务、更新UI或发送网络；诊断关闭时做开销对照。

**测试**：T01–T04；按设计 §15.11 补 T77–T82，记录首次导入、已验证首批确认、完整上传/下载、恢复和目录/SQL/解密成本；新增T65/T66/T67/T69的红断言；T70–T74建立可运行框架但不提前登记通过。现有规模测试的历史PASS只记录为输入事实。

**退出**：首次同步基线与数值目标已按 §15.11 冻结；候选单次诊断不能关闭 P1。基线红失败能明确指向全历史blob读取/重复快照/日志小事务；报表能区分测量与公式推导，脱敏通过。基线对大H超预算时登记ABORTED，不阻止已得到的小样本诊断。

### P2：缓存与必要持久结构

**范围**：`SyncObjectStore`、验证上下文、cache文件处理、必要snapshot/discovery/facts/retry字段迁移。最终表名优先复用现有实现。

先为 T82 建立真实 FileSystem 计数失败用例，消除命中/追加逐对象目录扫描，冷启动容量重建和淘汰单列；不删减 LRU/多实例/故障安全。实现L1/L2的scope键、OID/长度/摘要检查、lease、LRU、single-flight、磁盘短写/满盘处理。cache失败仅影响可重建内容，guard/outbox/inbox/制品不得归入缓存清理。解析结果绑定密钥/路径/验证版本，不持久化秘密。

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

**退出**：稳定纯上传的快照解析≤B+1，额外终态只需轻量ref；未完成队列可恢复；旧历史blob请求消除。此时必须保存第一份可量化收益报告及网络/历史验证/业务归并分解，不等待后续 API 聚合；同时比较多批次发布的预期收益与恢复成本。缺配对证据不得勾选 P4。

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

**范围**：现有发布器提取最小`SyncCommitWriter`，必做 REST_INLINE_TREE；GraphQL_SINGLE_BATCH 仅在收益前置满足后选中实施；原REST_BLOBS保留兼容路径。

按最终存储字节严格UTF8往返选择inline，不按扩展名判断；二进制文件单独blob，所有路径保持base_tree和原子ref。按最终HTTP body做2MiB初始本地预算，超出在写ref前选择兼容方式。

条件扩展（未选中不要求新增实现）：GraphQL 单批适配需处理 HTTP200+errors、expectedHead、返回数据及未知结果处理；不把clientMutationId当幂等保证。实际凭据/分支保护未验证时默认关闭，不扩大权限。原制品可在不同传输实现间恢复，不能重新加密。

失败回退必须先区分是否可能已发布。未知状态先对账；同身份异内容/部分不合法发布阻塞。不能通过另一API绕过权限或分支保护。

**测试**：T23–T30、T55、T61；T67、T74。对全UTF8样本断言6→3次写请求；混合样本断言3+q。仅选中 GraphQL 时，fixture 断言一次 mutation 且确认安全；真实schema/token验证放P10并控制开关。

**退出**：REST优化默认路径正确且旧制品可恢复；GraphQL 的 DEFERRED/候选/实现/启用状态分别登记。写请求减少必须包含完整确认和节流对照，不只统计POST数量。

### P8：串行 raw 下载与条件重叠

**必做范围**：blob 表示兼容、串行 batch 接收与取消。流水线仅在网络等待和本地处理分解证明足够收益、冻结目标和预算后启动；队列/资源要求是选中后的约束。

实现raw与JSON/Base64交付相同最终字节；保持私有API端点与认证边界，不跟随未知origin。若选中流水线，默认单网络读取者，最多2批排队、原始缓冲合计4MiB，下载与上一批解密/校验/写库重叠。byte permit在请求前预留，释放与取消可靠。

保留同actor顺序和同快照dirty合并，不每个小窗口重复projector。所有错误仍走现有invalid/blocked/partial语义，网络等待不进入数据库事务。

**测试**：T33–T38、T61；T68、T71、T72。加大批次、无Content-Length、慢消费者、连接中断、scope切换、内存压力与暂停，验证请求并发始终1且没有lease泄漏。

**退出**：串行 raw 字节与业务结果一致，完整首次下载按 T80 验收。未选中流水线标 DEFERRED，不先付出实现成本。若选中，则 T71 及队列/资源/恢复测试必须通过，且达到事先冻结的收益目标才启用；无收益保持关闭，不得只凭“退化不超过 10%”启用。

<a id="p9"></a>
## 5. P9：集成、规模、性能与独立审查

**前置**：P0–P8 必做项提交完成，可选项有明确适用性决策，scope/schema/冻结制品/发布接口稳定。重要改动后重跑相关focused测试，不用只跑旧测试名单。

### 5.1 自动化完整矩阵

按设计 §15 执行 T01–T82 适用项。未选中 GraphQL/pipeline 不要求新增实现或附加 fixture，但保留候选代码时核对不可达/默认关闭；一旦选中实现，不能以未启用为由免除其安全契约。串行 raw 与取消正确性始终必测。测试发现设计不适用时写明原因并修订权威设计，不直接删除条目。

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

2026-09-25 本地发布准备：已核对当前工作树及已知主仓库正式产物最新均为 aex.11，当前 APK metadata 为 code 29。此候选预留 `app.mihon.desktop.fork / 0.19.4-aex.12 / code 30`，两个 fork 构建/签名脚本同步校验该身份；仍复用已有证书，不新建密钥。该记录只代表发布配置准备，尚未构建或证明用户设备当前安装版本；正式签名前须再次检查目标产物目录及 APK metadata。

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

首轮耗时分解后即可比较 B1，不等待阶段 A 可选项；其他扩展按各自条件。先按实际耗时占比分配工作，不同时启动所有扩展。每项都有独立启用结论和格式/迁移风险审查；未启动标`DEFERRED`，不得标完成。

| 扩展 | 启动证据 | 实施/测试要求 | 默认状态 |
| --- | --- | --- | --- |
| B1 多批次一次发布 | 首轮分解即可比较；固定发布/确认仍为主要成本且收益/恢复/预算决策成立后实施 | 按设计§17.1建立group冻结/原子确认/未知结果状态机；B-G矩阵与旧parser互通；禁止不明状态自动拆组 | DEFERRED |
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
| worktree / branch / HEAD / dirty diff | `D:\Codex\worktrees\85be\mihon`；分支 `codex/sync-performance-optimization-2026-09-22`，候选提交 `a5ae62d79047a1887f25d50c62ac76d5787f4e18`；旧版对照固定 `96dbb69d7a`；后续仅修正桌面能力证据行号并预留 BUILD 55 | 2026-09-25 已核对；文档/诊断 checkpoint 不是完整交付提交 |
| 基线与候选构建变体 | 用户安装包来源commit未知；候选须按P10核对versionCode/schema后构建，不可据此声称覆盖升级兼容 | 待P10 |
| schema / 支持的历史指纹 | 当前生成的 `Database.Schema.version=37`，迁移 33–36 为已有候选；旧 schema28 同步谱系与 schema29 作者谱系兼容测试存在 | 2026-09-25 已核对生成 schema 与 COMPATIBILITY_SCHEMA_VERSION=37；历史文件库 focused 证据存在，当前完整迁移验收待执行 |
| API版本头 / Accept / 认证方式 | REST `Accept: application/vnd.github+json`、`X-GitHub-Api-Version: 2026-03-10`、Bearer token；raw blob GET另设raw media type | 源码已核对；真实网络未验 |
| focused domain/data/transport测试 | `python scripts/gradle-coordinator.py run --key <unique> -- .\gradlew.bat :data:jvmTest --tests 'mihon.data.sync.<TestClass>[.<test name>]'` | 已多次实跑，逐项登记§9及results目录 |
| migration Android/JVM测试 | `:data:jvmTest --tests 'tachiyomi.data.DatabaseMigrationCompatibilityTest'` | 内存谱系存在，文件数据库关闭重开待补 |
| WorkManager / Desktop scheduler测试 | `:app:testReleaseUnitTest --tests 'eu.kanade.tachiyomi.data.sync.AndroidSyncRuntimeWiringTest'`；`:app-desktop:jvmTest --tests 'mihon.desktop.sync.DesktopSyncSchedulerTest'` | 实际测试类存在，P9待运行 |
| presentation/controller测试 | `:presentation-sync:jvmTest --tests 'mihon.presentation.sync.SyncPanelContentTest'`、`SyncPanelOnboardingIntegrationTest` | focused部分已跑，P9待完整相关测试 |
| 10k/100k scale和性能报告入口 | `:data:jvmTest --tests 'mihon.data.sync.SyncScaleAcceptanceTest'`；fixture实际10k/3 actor/120 pending、100k/10 actor/10k pending | 原失败及超时保留；P5 后原规模测试完整通过，见§9。该通过不替代 H=100/1000/9000 结构矩阵或正式大规模配对 |
| 受影响全量与格式检查 | `:data:jvmTest`、Android/Desktop/presentation适用测试、`spotlessCheck` | 初次`:data:jvmTest` 307项中4项失败，仍待诊断与复跑 |
| Android签名release / Windows发行 / macOS构建 | Android 通过 `--init-script scripts/android-fork-release.init.gradle :app:assembleRelease` 构建 fork，再用 `scripts/sign-android-fork-release.ps1` 外部签名；Desktop 用 `./scripts/build-desktop.sh` | 待P10运行；fork 配置要求 telemetry/updater 关闭，不能套用上游标准发布参数；macOS 已只读连通，构建未执行 |
| 仓库构建协调器 | `python scripts/gradle-coordinator.py run/status`，同worktree重型Gradle串行 | 已核对并使用 |
| 用户设备/隔离仓库授权范围与预算 | 用户明确最终真机/真实仓库验收由其执行；Agent 交付本地构建、步骤与判定标准 | `USER_VALIDATION_PENDING`；不阻塞本地工作，不代写真实仓库 |

### 8.2 阶段执行记录

| 阶段 | 实现commit | 实际测试命令/退出码 | 覆盖ID | 审查结论 | 证据路径 | 状态/缺项 |
| --- | --- | --- | --- | --- | --- | --- |
| P0 | 未收口 | 历史只读核对；本次补核源码/日志 | 基线/作用域 | 待完整退出 | §9 | PARTIAL；待冻结全部输入及前版对照 |
| P1 | `a5ae62d` | 旧/候选 Runtime 小样本；T70 各 7 样本通过 | T70；T77–T81 局部诊断 | 主代理核对计时边界、源码指纹及日志 | [候选基线](../evidence/sync-performance/baseline-2026-09-25.md) | PARTIAL；暖增量配对已补齐，首次300七样本与预冻结目标已通过；大规模多样本及完整成本分解仍待收口 |
| P2 | `a5ae62d` | 缓存三类 focused 共 20 项通过；T82 N=100/200 红绿；见§9 | T05–T12、T60/T82部分 | 本次增量账本由主代理独立审查通过；其余边界按实际证据核对 | [候选基线](../evidence/sync-performance/baseline-2026-09-25.md) | PARTIAL；正常命中/追加扫描放大已修复，N=1000/9000文件系统矩阵已通过；完整历史成本/进程内存仍缺 |
| P3 | `a5ae62d` | `SyncGitSafetyContractTest`、`SyncSpaceTransportContractTest` / 0 | T13/T16 部分 | 待独立审查 | `docs/evidence/sync-performance/implementation-2026-09-22.md` | PARTIAL；已有分层 tree/normalized manifest/fence 候选与局部日志，缺完整安全/性能验收 |
| P4 | `a5ae62d` | `JvmSyncRuntimeStorageContractTest`、发布异常 focused / 0 | T21/T25/T27/T30 部分 | 待独立审查 | `docs/evidence/sync-performance/implementation-2026-09-22.md` | PARTIAL；已有确认复用、durable discovery/catch-up 候选，缺配对量化及完整恢复验收 |
| P5 | `a5ae62d` | panel/totals 历史红绿；本次投影批次红绿及 43 项相关契约通过；本轮正式300七样本T77–T80均通过；501项bulk三样本下降94.6% | T41/T43、T77/T78/T80/T82 部分 | 本次投影、失败回退与作者准备由主代理独立审查通过 | §9/唯一基线报告 | PARTIAL；投影及bulk提交放大已修复；T77冷读修复通过原阈值，仍比旧版慢6.5% |
| P6 | `a5ae62d` | HTTP/guard/historical 相关 focused 共 43 项通过 | T49–T56 部分及限流头后取消/超限 | 独立审查发现限流等待遗漏，修复后定向复验通过；完整阶段仍待验收 | §9/基线报告 | PARTIAL；头部已确认的限流先持久化，保留读取中取消；平台 Worker 本地 wiring 已通过，真实设备恢复待验收 |
| P7 | `a5ae62d` | 未在本次运行完整门禁 | T23–T30 待核 | 待审查 | §8.3 | PARTIAL；REST planner 已接入，GraphQL DEFERRED |
| P8 | `a5ae62d` | T80正式300七样本中位13096→2188ms | T80通过；T33–T38完整映射待核 | 待审查 | §8.3 | PARTIAL；raw 读取已有，流水线 DEFERRED |
| P9 | `a0142effab`及后置测试修复 | 三模块1151项/6旧fixture失败；真实OID修复后20项focused通过；根格式通过；100k完成 | T77–T80通过；T75/T76/T81/T82部分 | 本轮生产与测试修复独立审查通过；未宣称全部门禁满足 | 唯一基线报告及原始日志 | PARTIAL；未再次全量，完整状态机/精确故障点/应用堆/历史本地成本仍缺 |
| P10 | `a0142effab`预留BUILD56/aex.13 | macOS3164项/1安全存储失败/9跳过；Windows/Android本轮未启动 | 发布未完成 | 预算到约5.06%停止，无活跃构建 | 基线报告最终发布记录 | PARTIAL；本轮无新正式包，旧aex.12不含新增优化，真机由用户最终验收 |

### 8.3 默认策略与风险登记

| 策略 | 设计默认 | 实际默认/证据 |
| --- | --- | --- |
| L1/L2缓存、快照复用、delta发现 | 对应安全门禁通过后启用 | 候选已含 L2、normalized manifest、owner/guard fence、durable discovery；正常缓存扫描放大已修复并通过 focused，完整链路验收仍为 PARTIAL |
| 分层tree及容量保护 | 安全门禁通过后启用；保留总预算语义 | 当前候选有代码和部分 focused 日志，PARTIAL，未验整体收益 |
| REST_INLINE_TREE | 通过字节/确认及完整收益门禁；二进制按文件 fallback | 当前 GitHubGitDatabaseClient 调用 planner，PARTIAL，非“未实施” |
| GRAPHQL_SINGLE_BATCH | 收益前置满足才实现；启用须 API/凭据证据 | DEFERRED；候选有未接入默认链路的 adapter，未验，不算实现完成 |
| raw blob | 表示兼容与完整首次下载验收 | 当前候选已有 raw Accept 读取，PARTIAL |
| 下载pipeline | 先证明重叠收益再实现；达到冻结收益目标才启用 | DEFERRED；不以无退化作为启用充分条件 |
| 多HTTP并发下载 | 关闭 | 未实施 |
| 多批次group/压缩/导入交错 | 条件扩展；group 提前比较 | 未启动；不预判最优 |

### 8.4 设计变更记录

每次只记录会改变范围、不变量、默认策略、验收阈值或兼容性的决策：日期、触发证据、原规则、新规则、影响测试、审查结论。特别禁止在测试失败后静默放宽请求/内存/耗时阈值；有合理原因时保留原失败结果与批准记录。

## 9. 当前记录

### 本轮追加执行（2026-09-25）

用户追加周额度 5%，交付范围为：完成本轮优化与本地发布验证、更新同条件优化前后测试报告、单独交付剩余优化空间评估。真机与真实 GitHub 最终验收由用户执行；本地自动化和桌面发行产物仍由实施方完成。此前桌面必要完整复验的预算阻塞已解除。起点为 `f078ae98c5`，工作树干净；旧生产对照继续使用 `96dbb69d7aa75acce530d477c3c9e3e23db4d55f`，不得混淆旧生产、本轮起点和最终候选。

执行顺序：先分解 100k 用例的删除裁决后续热点，同时补 T82 规模测试；确定根因后仅实施有证据的最小优化，并处理 T77 首次导入退化；随后补必要随机故障、变异与恢复证据，完成相关检查及一次最终全量/发布验证。可选 GraphQL、下载流水线、多批次、压缩、检查点、原生 Git 不自动进入实施范围。保留全部既定安全不变量及性能阈值，不能以报告更新关闭失败门禁。

首个热点已取得本轮起点的真实文件库诊断：501 项删除裁决、10 actor，墙钟 17,950 ms，502 次外层提交累计 13,728 ms。新增批处理目标在候选测量前冻结为：同一 501 项 profile 总耗时中位数不超过 8,975 ms，正常提交次数随最多 50 项的页数增长。SQL 代理计时含嵌套重叠，不与墙钟相加。异常整页回滚后最多一次按原逐项事务回放，取消直接传播，失效 binding 与新到 pending 的冻结集合语义保持；该粒度调整不把原合法逐项提交称作半写入缺陷。完整 100k 对照放在最终验收，不因每次小修重复运行。

复用 Sol 为主要实施与 Windows Gradle 唯一协调者，Luna 承担无写入冲突的测试补充；主代理负责接口、独立审查、整合和两份报告。稳定批次独立审查一轮，必要修复复验一轮；行为变更采用 focused 红绿，最终完整验证一次。用量按现有工具估算并计入子代理，不把估算百分比当作账户真实扣减；达到本轮追加预算约 70% 后停止扩展范围，优先验证与交付。长时间构建或诊断失败只追加对应必要检查，不重复运行已完成的大矩阵。

报告沿用 [优化前后测试报告](../evidence/sync-performance/baseline-2026-09-25.md)；剩余空间评估单独成文，区分实测、源码推导、未实施候选和真实验收缺项。下方历史日志保留原时点事实；当前状态以本轮最终证据更新阶段表，不用历史 PARTIAL 或单次 PASS 代替最终验收。

2026-09-22：完成 P0 定向核对。实现并测试 transport 内 16 MiB 有界 Git blob L1 缓存、Git blob SHA-1/SHA-256 内容校验，以及 `SyncPublishResult.confirmedSnapshot` 驱动的交换循环复用；完整 ref/commit/tree 与 guard 路径保留。focused JVM/契约测试通过，独立审查为 `PASS_WITH_ADVISORY`；P2/P3/P4 仍为局部状态，P5–P10 未启动，阶段 B 保持 DEFERRED。

2026-09-24：从`f0e3d4f21a`继续执行，不将checkpoint作为结案。P0复核确认R1 的 F11描述与其SQL互相矛盾：当前`getFieldEvents`、`getAffectedFields`以及 published/remote totals 查询都按space/generation隔离；API版本头/Accept/认证方式和当前 schema 36/迁移33–35已登记。P2增加的 L2 restart/corruption/context、tree poisoning recovery、共享路径累计、LRU/深度及 tree/index/HTTP限额边界组合focused GREEN见`p2-tree-cache-limits-green-20260924.log`（23 tests）。P5 panel ticker与 production首次run/recovery totals 有成对RED/GREEN，保存于`docs/evidence/sync-performance/results/2026-09-22/`。完整`:data:jvmTest`初次307项中的4个失败尚未关闭，因此P0–P9均不能按局部绿测勾选。

2026-09-25 审议落地：必做范围保留缓存/确认/发现/恢复、REST inline、raw 与已测热点；GraphQL/pipeline 改为收益前置条件扩展；B1 首轮分解后即可比较。新增 T77–T82，首次同步目标须先采同条件基线再冻结，暖增量 50% 不是整轮成功依据。当前源码已跨多阶段，§8.3 已更正；§8.2 的旧执行记录不能覆盖后续候选事实。P0/P1 尚未闭环，未勾选任何阶段。

本次定向核对了 `SyncPersistentGitObjectCache.touchAccess/evictForWrite`：命中仍进入目录枚举/metadata 统计，复杂度风险存在，尚未测得设备耗时。`SyncSnapshotManifestStore` 已有 normalized 行存储与 admission 校验；`p3-fence-manifest-incremental-forced-green-20260924.log` 和 `p4-terminal-ref-valid-work-green1-20260925.log` 末尾为 BUILD SUCCESSFUL，仅作为对应历史 focused 证据，不作为当前全部 dirty diff 通过或全量失败已关闭的证据。旧报告中“缺 normalized/fence”等描述以本次核对更新，不改写历史输入。首批测量与剩余缺项见 [候选基线报告](../evidence/sync-performance/baseline-2026-09-25.md)。

2026-09-25 持续执行核对：`sync-perf-p9-data-sync-full-r1.json` 实际命令包含 `--tests mihon.data.sync.*`，完成时间为 2026-09-22；其 307 项/4 失败不是整个 data 模块完整测试。2026-09-24 `sync-perf-p9-fix-focused-r2` 及 `p2-p3-review-fixes-focused-green1` 已有文件冻结重开、树截断标志、不可变 blob 三个失败项的后续通过证据。所见 `reconciliation-remote-tests` / `sync-core-ui-final` 的 Scale PASS 均为 2026-09-21，早于此次失败，不能据此关闭当前 Scale 风险。当前无旧 Gradle 进程；本次 Runtime/L2 测量使用新协调器 key。Android SDK 三个必需文件存在，adb 暂无设备；macOS 主机只读连通性返回 Darwin。设备与真实仓库最终验收按用户要求移交用户，本地实现继续。

2026-09-25 缓存修复与首个正式性能门禁：L2 增量账本的 N=100/200 暖读和追加均为 0 次目录枚举，metadata 为 200/400 次；三类缓存 focused 共 20 项通过，主代理独立审查本次账本及故障边界，无阻塞项。T70 旧版/候选各 7 个有效样本，中位数分别 21,126/2,093 ms，候选减少约 90.1%；每样本请求 332→23，候选旧对象正文 GET 为 0。原始样本、生产指纹和测量局限集中在同一基线报告。该结果不代替首次大量同步验收，不关闭 P1/P9，也不表示全工作树已通过审查或可以交付。

2026-09-25 规模与安全修复：同一缓存快照被重复读取时积累旧 fence，导致第二接收端误报 `REMOTE_CHANGED`；定向测试先失败后通过，修复只消费最新 fence 并清除同对象旧记录，未放宽 guard。原规模复验的 10k 部分到达 `complete`（10,123 事件）；100k 首次交换耗时 1,124,136 ms、868 请求，随后增量交换完成，但整个测试触发 20 分钟超时。原始 XML/日志为 `results/2026-09-25/sync-scale-history-green-20260925.*`，结果为失败，不能扩大 timeout 后将其解释为性能通过。首次大规模成本仍需分解。

同一轮独立审查发现：已收到 429 或头部可确认的 403 主限流后，响应体超限或取消会漏存账号等待。新增失败测试后，将此类头部的持久化放在读取正文之前，保留读取正文期间的 Call 取消；43 项相关 focused 通过。另补充 warm admission 对实际可变列表内容的 checksum 校验，将无 manifest 的内存快照缓存收敛为最新一个。上述结果只关闭对应修复边界，未完成平台 DI、完整恢复、迁移和发布验收。

规模解释边界：上述原 Scale fixture 的初始对象分布为 `index % (pending + 1)`；10k 事件对应 121 个唯一对象，100k 对应 10,001 个唯一对象。事件数与待投影对象数同时变化，约 112 倍的首次交换时长不能单独证明按事件数 N² 增长。后续诊断须分别记录事件数、唯一对象/字段数、因果链长度、归并次数和事务数，再选择 SQL 或事务优化。

2026-09-25 首次 Runtime 三样本定位：旧/候选分别使用全新文件库、相同 300 事件和零附加延迟，共同起点为接受连接。导入中位数 843→859 ms，首批 durable PUBLISHED 6,925→7,310 ms，全部确认 11,985→7,744 ms，空端下载归并 11,644→6,954 ms。完整上传/下载改善约 35.4%/40.3%，首批没有改善证据。后续定位预算以旧版中位数冻结：导入≤927 ms、首批≤7,618 ms、全部确认≤9,588 ms、下载归并≤9,315 ms；此预算仅约束同一 300 事件定位 profile，不替代设计 §15.11 的正式网络 profile 七次配对或 10k/100k 验收。逐样本、候选指纹与真实缺项保存在唯一基线报告。

2026-09-25 P5 测量驱动调整：501 字段/10 actor（跨 actor 父链）真实文件库投影 11,049 ms，501 次外层事务结束累计 8,689 ms，约占 78.6%；dirty 查询仅 18 ms。采用设计 §9 的至多 50 字段批量事务候选，保留单字段失败隔离：作者准备在事务外完成，整批异常先回滚，取消直接传播，非取消 Exception 仅回退一次旧逐字段路径。实施范围仅共享 projector、必要 writer 准备与对应契约；不改全局 SQLite 耐久配置、schema、reducer 或远端格式。验收包括真实结果一致、提交次数随批次数增长、501 定位总时长至少减半，以及失败/取消恢复/作者 ready 边界；正式首次同步及 100k 完整验收仍单列，不能由此诊断门禁替代。

P5 修复及复验：真实 501 字段归并降至 939 ms，相关契约复验为 762 ms；提交均为 14 次（11 个字段批次及首次作者准备固定 3 次）。原测试上限 `ceil(N/50)+2` 漏计一个固定准备事务，保留该失败，按源码中读取迁移组件、写 RUNNING、写 COMPLETED 三个事务更正为 `ceil(N/50)+3`，未放宽按字段批次增长的结构目标。43 项相关契约通过，包含 SOURCE 隔离、取消未提交页回滚、作者迁移状态保持、SQL trigger 意外错误传播和恢复无回声。旧逐字段路径在取消前已完整提交的字段属于原来的合法粒度，不能将本次新批次取消测试的 RED 误写为旧版半写入缺陷。

修复后完整规模 `sync-scale-history-after-p5-20260925` 通过：10k 最终 10,123 事件；100k 首次接收归并 84,145 ms（修前 1,124,136 ms）、最终 110,003 事件，完整过程 1,076 请求，文件库 192,090,112 B、含 fixture 的采样 JVM 堆峰 283,543,400 B。后续 10,000 项删除裁决的恢复处理段仍需 283,460 ms，保留为剩余成本，不扩大到新的批处理改造。首次 Runtime 三样本中位数进一步变为导入 779 ms、首批确认 1,342 ms、全部确认 1,825 ms、下载归并 1,141 ms；此处仍为零附加延迟定位，正式网络 profile 和最终平台验收另行记录。

2026-09-25 正式首次受控 profile：300 事件、50 ms 响应首部延迟、10 Mbit/s 正文，旧/候选各七个独立样本。T77 导入中位数 1,231→1,581 ms，超过候选运行前冻结的 1,354 ms 上限，判定 **FAIL**；测试进程成功不等于性能门禁通过。T78 首批确认 8,829→4,184 ms、T79 全部确认 15,838→5,853 ms、T80 下载归并 13,096→3,907 ms，分别通过预冻结的 6,180/11,086/9,167 ms 门槛。连接阶段请求数 7→11，耗时中位数 450→735 ms；新增请求是否可消除尚需路径核对，不能通过略过安全验证或改阈值消除失败。七样本、范围、计时边界、日志/XML 和指纹统一见基线报告。

当前自动化缺项：T75 至少 100 seed 的可复放随机交错、T76 三类实际变异负对照，以及 T82 N=1000/9000 冷建/暖读/追加/重启及历史本地成本完整矩阵，尚无完整运行证据。现有正向契约、N=100/200 计数与完整单元测试不能替代它们；P9 保持 PARTIAL。补齐需要额外测试实施与运行，不能默认为用户承担的真机验收，也不能在候选交付时静默删去。

T77 路径定位：一次旧/新同 profile 诊断确认新增四请求全部来自 tree 读取：旧版一次 `recursive=1` 根 tree，候选五次非递归 tree；账号、仓库、ref、commit 及两次 blob 请求相同。候选逐个真实 tree 执行 OID/路径/mode/截断/深度/循环检查并缓存，旧递归响应的平铺结果不能直接冒充子树 OID 对应内容。当前保留这段首次连接成本；若后续设计混合冷读策略，须证明相同验证边界和持久缓存兼容，再按原阈值复验，不能直接删去四请求或放宽 T77。诊断仅解释请求差异，不替代正式七样本。

本轮集成审查待修复边界：当前分层 tree 的全局上限仅统计展开后的 blob，目录只有单层条目数、深度与祖先环检查。不同路径反复引用同一空子树是合法 Git 对象图，可能在文件数不增长时大量重复展开。提交前须增加独立的目录访问预算及有界 DAG 回归测试，不能只统计不同 OID，也不能为修复而改变原文件数量边界。此项由原实施者执行定向红绿，主代理复验；完整模块测试已经启动，修复后只补受影响树契约，不重复整套全量。

完整模块回归已执行一次（`sync-data-domain-presentation-full-20260925`，14 分 25 秒）：data 589 项、domain 519 项、presentation-sync 28 项，共 9 项失败，不能标记全绿。同步相关失败为旧连接格式被前置账户门禁误分类为 STORAGE、schema 27 夹具未剥除本轮新字段，以及 UI 将恰好 60 秒的实际分钟显示误断言为秒。作者/扩展仓库旧测试的历史不兼容单列在基线报告，不借本任务修改无关能力。发布复核另发现仓库检查的网络/限流异常被新 failureClass 统一转成 AUTHORIZATION；需保留异常原分类与 retryAfter，仅真正非私有或权限失败按授权错误处理。所有本轮修复以对应失败及修后 focused 证据收口，整套失败历史不覆盖。

本轮修复复验：目录路径预算已按设计 §5 实施，空树 DAG 的恰好上限/多一次、原文件数与深度契约通过；旧连接、schema 27 夹具及相关 HTTP/迁移/树契约通过 `sync-legacy-schema-tree-data-focused-20260925`，同步面板通过独立 `sync-ui-rate-limit-focused-20260925`。发布仓库检查的 429、500、真实断连及非私有场景在 `sync-publish-private-check-green3-20260925` 共 13 项通过；底层可重试 UNKNOWN 包装保留 NETWORK 语义，429 保留等待信息，取消继续传播。主代理独立复验上述具体修复，未发现未关闭的本轮审查阻塞。此前误把多模块 Test 过滤器只附于最后任务，已立即仅取消对应协调器任务，分成单 Test 命令复跑；取消与编译/断言失败均保留，不记作通过或第二次完整验收。

发布候选记录：Android `a5ae62d` 已生成 `app/artifacts/android/0.19.4-aex.12/Mihon-Fork-0.19.4-aex.12-arm64-v8a.apk` 与 universal 包，code 30、R8 开启，沿用原证书；详细哈希与日志见同目录校验记录。Windows evidence 完整测试 3,164 项、1 失败、3 跳过，唯一失败为本次 DI 插入导致能力清单的源码行号过期；仅修正 6 个 line 数字，未改能力状态或符号，34 项定向契约已通过。macOS 在独立 worktree 启动后为避免同源失败，仅通过该协调器停止。桌面新提交预留 BUILD 55，后续 evidence 必须绑定此修复提交，不能将未产出的 BUILD 54 包称为交付物。依据用户提供的全量测试次数上限，已请求追加一次必要桌面完整复验及打包（预计 10–25 分钟），答复前不启动；此预算确认不阻塞已完成的 Android 交付，也不代表 T77/T75/T76/T82 已通过。


2026-09-25 追加收口：生产范围冻结为已测 bulk 页事务及首次冷读校验复用，不扩 GraphQL、下载流水线或协议。bulk 501项三样本中位16972→910ms、提交502→11；首次300正式七样本T77–T80为1311/3657/5333/2188ms，全部通过原阈值，T77仍比旧版慢6.5%。N=1000/9000缓存正常暖读/追加均零目录枚举。本轮源码/测试超过8文件400行，内聚于共享同步的两个已测热点、跨端wiring及其安全回归；作者/扩展改动仅修复既有schema测试夹具，不新增产品能力。详见唯一测试报告与[剩余空间评估](../evidence/sync-performance/optimization-space-assessment-2026-09-25.md)。历史FAIL记录保留，后续修复不能反向改写旧运行。


追加预算停止记录：估算消耗约5.06%，略超用户追加5%上限，停止新增实施/验证。源码提交a0142effab；六处真实OID测试夹具20项focused通过并随报告提交。两份报告已交付，完整发布未完成；不把版本预留、旧APK或局部测试计为本轮正式产物。后续首先补本地发布/验收，不自动扩可选优化。
