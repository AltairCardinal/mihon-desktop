# 多设备同步实施计划

状态：HANDOFF（用户要求收紧并移交；S5b仍未完成）；S1、S2a、S2b、S2c 已完成（S2c 提交 `2e5c8bf47`），S3a1 提交 `fc7a9c482`，S3a2 提交 `ba14962ac`，S3a3 提交 `40993b94b`，S3b1 提交 `c79057639`；S3b2 提交 `ce444410d`；S3c1 提交 `c4a12e7b3`；S3c2 提交 `7e3bdf22d2`；S4a 已完成（`2f52c5c3c2`），S4b 原生交互已完成（`ef0e42f31a`）；S4c 已完成（`49381942b0`），S5a 已完成（`7338d529ab`），S5b执行中。2026-09-15 用户授权主模型接续完成全部 roadmap，不再使用 Luna 技能；当前任务从下方有序清单的第一个未勾选项推导。完整目标是按已审核技术方案与最终 DEMO 在 Android / Windows / macOS 实现真实同步，完成共享协议不代表产品完成。

## 当前交接入口（2026-09-16）

用户要求本代理停止实施，后续由其他 agents 接手。以[收紧交接文档](2026-09-16-multi-device-sync-handoff.md)为当前状态与剩余步骤入口；下方较早的执行记录保留作历史证据。Windows v36 / Mac v37 及真实 GitHub 非空交换已通过，Android 最新隔离 R8 构建成功，仍需既定 ART 验收和正常发布包，随后最终提交。产品 S5b 候选仍在原工作树未提交；本次仅提交交接文档和本计划，不勾选 S5b，不代表产品完成。

范围冻结：不重做已通过的 Desktop/规模/授权验收，不追加功能或通用基础设施整治，不追赶扩展任务的新 main。新失败先判断是否直接阻断现有交付，非阻断问题仅记录移交。

## 目标、权威与授权

- 技术权威：[已审核技术方案](../2026-09-13-multi-device-sync-technical-proposal.md)。后续确认条款优先于其中旧 PAT、三行状态、人工冲突匹配等描述。
- UI 权威：[DEMO](../prototypes/multi-device-sync/README.md)及其当前源码：书架顶栏 → 同步底部面板 → 同一面板的设置/记录子页。立即同步在状态栏右侧；定期启用显示省略零单位的倒计时；空列表为“当前没有待确认的操作”；记录页没有解释性引导行。
- 当前主模型负责规划、强耦合实现、整合与最终验收；子代理仅承担独立能力或独立审查，继承当前模型，不使用 Luna 技能。相同上下文复用同一个执行者，不同时写相同文件。本文后部的 Luna GOAL、暂停点及旧预算保留为历史证据，不再支配本次执行。
- 工作区：`D:/Shell/Github/mihon-sync`，分支 `codex/multi-device-sync`，基线 `b686564d3`。提交仅包含本任务文件，不自动发布远端或覆盖正在使用的数据。
- 现有 Use Case / SQLDelight / NetworkHelper / Injekt / Voyager / AdaptiveSheet / WorkManager / Desktop runtime 为接入权威。现有业务表不是操作日志，需新增同步表；平台差异限制在 adapter，不复制两套规则。

## 并行开发与共享文件边界

Android 扩展任务在 `D:/Shell/Github/mihon` 的 `main` 上开发；本任务不改该工作树。当前已观察到它修改 domain/build.gradle.kts、Source API、扩展 repository、AndroidManifest、备份和数据库迁移 18.sqm，后续还可能修改漫画/章节 memo 映射。

当前环境没有 list_threads/read_thread/send_message_to_thread 工具，不能声称已取得对方确认。先通过独立 worktree 隔离；合并前必须复查 main 最新提交与未提交范围，顺序整合共享接口。S1 只新增同步 domain 包及其共享测试，不修改扩展相关文件或构建依赖。持久化批次开始前重新核对迁移编号；不得同时占用 18，也不能用空迁移占位掩盖顺序问题。数据库 migration、Manga/Chapter 映射、BackupRestorer、Manifest、DI、依赖目录属于最终整合门槛 C18。

Gradle 由当前工作区一个协调者串行运行；不清理其他工作区的 Java/Gradle 进程。按本机资源限制 workers，网络使用会话代理 127.0.0.1:10808，本地请求 bypass；最多一次网络重试，不永久修改系统设置。Python 与文本使用 UTF-8。

## 实现单元与文件所有权

### 2026-09-15 调整后的有序交付清单

原 S2 的授权、加密、Git 协议可独立交付；Git 固定快照、索引与发布状态机共享同一格式和故障 fixture，保留在一个能力内，避免人为拆开尚不可用的接口。S3 按日志事务、接收投影、恢复各自的完整能力划分。以下拆分不降低 C1–C18，不扩大服务商、同步字段或后台常驻范围。

- [x] S1：共享操作协议、归并与接收端规则；提交 `ed5501cb1`，证据见原 S1 记录。
- [x] S2a：GitHub 设备授权、凭据续期、私库选择及安全 HTTP。真实 HTTP 成功/拒绝/过期/取消/分页失败契约；提交 `52ae2d4e0`。外部账号与系统安全存储装配仍在 S4/S5。
- [x] S2b：共享 AEAD、恢复资料与严格批次校验。两目标固定密文解密向量、AAD/篡改/范围失败；提交 `46827b207`。不把 JVM 测试冒充 ART/R8。
- [x] S2c：Git 交换闭环。固定 HEAD 与近线性完整性校验、完整可持久化上传制品、非强制并发发布、未知结果确认、初始化竞争，真实 HTTP 故障注入。独立审查/一次修复复审和相关验证通过，同本记录提交；真实 SQLite 制品落盘接入仍在 S3。
- [x] S3a1：事务日志/outbox 与收藏完整接入。空间/actor 序号、来源上下文、漫画稳定身份、单条/批量收藏真实入口、数据库回滚及重启契约；为后续作者和阅读提供稳定的事务 API。
- [x] S3a2：作者关注事务接入。portable_key、关注/取消关注真实入口、来源隔离、保留本机扫描偏好；依赖 S3a1，同一事务 API，无额外同步字段。
- [x] S3a3：阅读与明确已读/未读事务接入。一个多效果阅读信封、幂等、无痕独立许可、真实双端阅读与章节入口；依赖 S3a1，与 S3a2 的写入文件独立。最终相关 238 项通过，一次交叉审查及一次限定复核通过，随本记录提交。
- [x] S3b1：完整对象描述与可靠上传。严格认证的最小漫画/章节/作者描述、真实编码体积切批、持久冻结 batch/index/head 制品、重启重试和只清已确认发布集合；真实 SQLite → 加密 → Git HTTP 契约。最终 224 项通过，一轮独立审查及一次限定修复复核通过，随本记录提交。
- [x] S3b2：持久接收与投影、待处理决定和批量。缺源保留、重启/乱序/失效版本/有界事务；三真实数据库经加密 Git HTTP 交换，120项分页/分段及迁移回滚。最终相关351项全绿，一轮独立审查和一次限定修复复核通过，随本记录提交。
- [x] S3c1：首次基线导入与空间可靠性。事务冻结水位、分段/幂等低优先级导入、同期间USER操作优先、新actor/epoch保留旧队列、断开/切空间隔离、已见远端锚点回退检查；依赖S3a/S3b。
- [x] S3c2：真实备份恢复与本机历史屏蔽。复用S3c1基线/身份API，接两端备份恢复来源和共享HistoryRepository清除入口，普通备份不克隆同步凭据/身份/决定，旧记录重放不复现已清除历史、新阅读可显示。双端真实恢复/默认DI、取消/部分恢复、历史清除回放验证通过，一轮交叉独立审查通过，随本记录提交。
- [x] S4a：应用级 coordinator、系统安全存储和三触发。串行合并请求、取消与恢复、Android WorkManager/Desktop runtime、真实 DI；555项相关测试、限定格式和一轮交叉独立审查/一次修复复核通过，随本记录提交。
- [x] S4b：Android/Desktop 原生产品交互。书架入口、同步面板/设置/记录、浏览器设备授权/恢复资料、批量处理和倒计时，遵循最终 DEMO。
- [x] S4c：阅读器续读接入与保护。补齐原验收项 C5：真实续读入口选择同步候选、页码失效从章首继续并提示，正在阅读的会话不被收件跳页；复用既有 reader 状态与提示，不增加阅读同步字段。
- [x] S5a：扩展任务整合与规模验收。只合并已提交上游改动，复核 schema/Source API/备份/DI；1万/10万事件与长待处理列表试验。
- [ ] S5b：真实 GitHub 与正式双端发布收口。Android/R8、Windows 正式 EXE、macOS 构建运行、全量回归及可追溯交付证据；未实际验证不勾选。

每个勾选表示实现、独立审查、相关验证和提交全部完成。子能力只是同一产品阶段中的交付边界，不为每个测试类创建计划。每项实现完成即提交源码、测试与本文件证据/checkoff；不单独提交推进状态。成本、失败与分工调整统一记录在[执行经验与成本](2026-09-15-multi-device-sync-execution-costs.md)。

当前安排（2026-09-16）：S4c提交 `49381942b0`；S5a已完成固定上游 `dbf3f050a1` 的整合与规模验证，schema25包含真实迁移19。当前S5b统一原生真实服务与发布验收。主树未提交改动仍未复制，产品范围继续以最终DEMO及C1–C18为准。

S5b最新进度（以下为证据状态，不等于整个任务已提交勾选）：

| 验收项 | 当前结果 | 剩余门槛 |
| --- | --- | --- |
| 完整共享/Android JVM | 共享1459项、app409项、test-desktop52项通过 | 不代替ART/R8 |
| Windows Desktop | 3067项零失败、3项既有条件跳过；规定脚本正式构建及扩展运行验收通过 | 当前产物包含S5b未提交候选，最终提交后核对可追溯性 |
| 正式EXE同步接线/DPAPI | 正式EXE原生panel、DPAPI跨进程、授权重启持久及非空双实例交换通过；保留本机无反向回声 | 修正待上传计数后的最终EXE复验 |
| GitHub真实设备授权 | 两个隔离正式EXE已完成原生授权/空间恢复、并发交换、待确认时上传及KEEP_LOCAL；两库7事件/2actor | 已通过；不声称现场强制制造同HEAD碰撞 |
| Android正式R8/ART | 许可证真实采集修复通过，R8隔离APK已生成；跨APK测试入口被优化导致runner启动失败 | 限定保留实际测试入口后重建；API26/36及正式APK |
| macOS正式构建/Keychain | 最终v37完整3067/0/8跳过；正式App跨进程Keychain写201→读取200/重复409、面板与正常退出通过 | 已通过，原始证据已取回独立核验 |

Windows实际交付路径：[Mihon Desktop.exe](../../app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.34.7338d52-unpacked/Mihon%20Desktop.exe)。`sync-s5b-windows-release-fixed`总用时4分55秒、exit0；构建日志Final unpacked EXE与实际文件已核对。其来源为7338d529ab加本工作树S5b候选，不把版本中的基础提交短hash当作全部未提交改动的身份。

S4b 完成证据（2026-09-16）：唯一交叉独立审查和一次限定复核 PASS。重新授权入口、跨空间批量生命周期/持久指针、迟到恢复保存回执均正确 RED 后修复。`sync-s4b-storage-final` domain 两目标各6项、data JVM59/Android54项（含面板共享契约各15项）共125项通过；`sync-s4b-native-final`共享UI14项、Android19项、Desktop10项通过，合计168项零失败/错误/跳过。真实Desktop偏好重建覆盖长空间ID、空间和代际隔离。四张实际Compose离屏图通过；`sync-s4b-format-check`限定28个Kotlin输入检查通过（Desktop7项已核真实输入，相同输入缓存复用），3个旧Desktop大文件保留既有风格并核对diff。新增presentation-sync只共用面板内容，平台适配原生外壳/文件/浏览器；Android资源型UI测试隔离基础Application且保留真实onCreate测试。源码、行为契约及必要文档规模较大，但属于同一原生产品入口，不拆出不可独立使用的碎片；没有新增迁移或发布构建，GitHub/ART/R8/macOS仍留S5真实门槛。C5实际阅读器入口明确留S4c，未用投影测试替代。

S5a 完成证据（2026-09-16）：合并边界固定为主树已提交 `dbf3f050a1`（3个上游提交），未复制主树未提交文件。8个内容冲突保留同步来源/备份事务与上游memo/扩展能力，真实迁移19补齐。唯一交叉独立审查及三条查询修复的限定复核PASS。`sync-s5a-merge-data` JVM53/Android40全绿；native最初Android34/2、Desktop36/2均为旧fixture缺依赖或合成旧schema错误，`sync-s5a-merge-fix`定向复验通过。最终 `sync-s5a-query-focused` 两目标各50项零失败/错误/跳过，覆盖memo共存、收件/因果闭包/决定/面板；`sync-s5a-scale-fixed`一项含两规模场景完整通过，12个Kotlin格式输入通过（Desktop1个真实输入），diff检查通过。没有运行S5b全量或正式发布构建。

规模首次运行在20分钟护栏处超时；同一实际库只读EXPLAIN/VM对照确认三条查询空间级扫描，随后只调整已有索引提示/连接顺序，无新schema。初次10万首次724,111ms，修复复验277,062ms；fixture外键同时由一次PRAGMA改为production连接Properties，不能将整体差异称为严格单变量基准。批量阶段180秒观测护栏校准600秒、整个@Test仍20分钟，输入与正确性断言不变。

| 规模 / 历史actor / 待处理 | 1万 / 3 / 120 | 10万 / 10 / 1万 |
| --- | ---: | ---: |
| 首次交换（ms / HTTP请求） | 6,454 / 91 | 277,062 / 814 |
| 增量交换（ms / HTTP请求） | 2,771 / 170 | 229,606 / 1,413 |
| 面板打开 / 下一页 / 冻结选择（ms） | 47 / 38 / 20 | 226 / 196 / 153 |
| 批量首50项 + 剩余及关闭重开（ms） | 1,156 + 1,583 | 1,742 + 371,151 |
| 无变更交换（ms / HTTP请求） | 94 / 56 | 1,309 / 458 |
| 最终完整事件数 | 10,123 | 110,003 |
| DB / 采样峰值堆（字节） | 16,576,512 / 193,168,968 | 191,606,784 / 455,208,712 |
| 最终可达blob / 增长（字节） | 6,758,146 / 96,254 | 80,512,548 / 7,489,459 |

限制：2GiB单worker，堆包含fixture；仓库数字非Git packfile；3/10是独立历史actor而非3/10台并发OS实例，1万组额外有第二真实接收数据库。已有共享三数据库契约验证设备独立决定，规模测试不替代真实GitHub/ART/macOS。面板响应保持分页，但1万项持久批量仍约6分13秒；首次/增量均为本机HTTP，不能承诺真实服务相同速度。完整历史、原批次digest、无force push、旧冻结项失效/新到项不混入、本机上传不被待确认阻断、关闭不重放通知均通过。


S4c 完成证据（2026-09-16）：root负责共享候选/冻结快照/Desktop，一个代理负责Android完整reader上下文簇，唯一交叉独立审查PASS，无修复复审。SQLite同事务读取有效heads、精确章节候选及因果快照，真实三入口/默认DI传递到首次阅读会话；显式选章和Android已保存恢复保留原目标，收件不跳当前页/改模式/改基线，失效页码归零并走原生反馈。候选、snapshot、默认factory和无效页码均有正确RED；Android详情夹具修正至真实手机/平板触摸GREEN后，`sync-s4c-detail-causal-red`恢复旧行为得到4/1 expected2/null，恢复正式实现。`sync-s4c-final` PASSED：data JVM29/Android29、Android原生27、Desktop64，共149项零失败/错误，其中1项旧非发布构建分支因当前BuildInfo发布配置跳过（148项通过）。41个Kotlin输入限定格式检查通过，Desktop18项真实输入已核验，7个既有大文件保留风格并核对diff空白。源码/测试/维护说明规模较大仍属同一跨平台续读能力；未新增schema、同步字段、服务商、全量发布流程。随源码和本记录一起提交，真实GitHub/ART/R8/macOS仍为S5门槛。

进度维护与范围边界：每次任务切换或出现影响验收的失败时更新本文件的当前安排和证据；只有实现、独立审查、验证及提交全部完成才勾选。源码、测试和本文件 checkoff 随同一个功能批次提交，不为状态推进单独提交。执行以本有序清单和 C1–C18 为边界；新发现先判断是否属于当前验收项，范围外需求不顺手实施。确需改变原范围时先说明原因、成本及取舍，再调整计划。

S4a 完成证据（2026-09-16）：coordinator正确RED 6/5，共享SQLite交换4/3、runtime graph4/3、Desktop启动/DI3/3，安全存储首批及跨进程正确RED后接通。Android新增onCreate真实入口及取消启动不关闭订阅均确认行为RED；最终Worker取消使用真实WorkManager宿主链路，裸stop遗漏宿主取消不是产品故障。唯一交叉独立审查和一次限定复核PASS；其中Desktop周期取消后订阅停摆、可重试令牌刷新误归授权失败分别1/1正确RED后修复，共用Windows凭据空值正对照也在1/1 RED后恢复兼容。`sync-s4a-related-final`已完成domain JVM36/Android36及data JVM198全绿；其中Desktop旧namespace断言随新增SYNC_V1精确更新后，`sync-s4a-native-final` data Android193、Desktop83、Android9全部全绿，合计555项零失败/错误/跳过。27个Kotlin输入限定格式通过（含相同输入缓存），5个既有未配置lint的Desktop大文件保留原风格并核对diff空白。没有添加schema迁移，本批2个SQL只新增查询。37个文件覆盖同一应用同步链路的共享coordinator、密文存储、真实交换装配、两平台生命周期及必要fixtures；规模超过提示但没有拆开不可独立接入的界面基础，也未扩大产品字段/UI/发布范围。Windows真实DPAPI、真实子进程锁与终止已验证；Android JVM注入cipher不冒充Keystore/ART，macOS/GitHub/R8及正式产物仍为S5门槛。

S3c2 完成证据（2026-09-16）：共享恢复首轮4/4、历史初轮7/6与追加9/7为预期业务RED；两端普通偏好使用真实平台存储重新确认本机状态被覆盖的RED后修复。`sync-s3c2-native-final` Android8项、Desktop37项全绿，真实默认DI→原生恢复→SQLite基线链路均有行为断言。唯一交叉独立审查通过，无修复复审。`sync-s3c2-stage-final` 于同一候选完成domain JVM457/Android392、data JVM318/Android192、Android app8、Desktop37，共1404项零失败/错误/跳过；20个Kotlin目标受限格式通过（19项本次IS CLEAN，1项相同输入UP-TO-DATE，前次IS CLEAN），旧Desktop未配置lint的大文件保留现有风格并通过diff空白检查，避免无关整文件重排。root负责共享恢复/Desktop，复用同一代理顺序完成history/Android，Gradle统一串行。31个文件覆盖同一恢复能力的共享SQL、迁移、两端必要接线与真实fixtures；超过规模提示仍保持能力内聚，未增加新UI、服务商或协议字段。最新main `8a3ebaf5b3`及其备份创建/构建未提交改动仅只读核对，未复制；迁移19整合和正式产物仍在S5。历史屏蔽只保证已观察水位和基线不复现，不声称判断从未观察actor的绝对时间；技术文档第8节已记录维护边界。

S3b1 完成证据（2026-09-16）：描述协议先 RED；实际 SQLite→AEAD→Git HTTP 首轮 `sync-s3b1-outbox-red` 5/5 失败，接通后全绿；中文超长资料 `sync-s3b1-size-red` 7/1，限定描述长度后通过。独立审查发现关联、旧 wire 字节、空白/非有限元数据及重复自然键四类遗漏，`sync-s3b1-review-red` domain 3/1、data 11/4 为真实业务失败，集中修复后限定复核通过。`sync-s3b1-final` domain 两目标各30项、data两目标各82项，共224项零失败/错误/跳过；16个Kotlin文件有实际限定格式覆盖。恰好512KiB的无描述旧OPEN批次经migration21、继续追加、封存、加密往返仍保持原wire/摘要/事件身份；保存异常零网络写，UNCONFIRMED重启复用全部密文，确认只清对应批次。21个文件包含共享codec/SQL/事务及两目标同一套真实存储契约与必要文档，超过规模提示仍属一个上传能力，无额外UI或服务商。主树已提交迁移19和 `112016470` 已只读核对，未触碰；当前分支的迁移缺口仍留 S5a 整合，未据此发布产品。

S2b 完成证据（2026-09-15）：`sync-s2b-red` 10 项中 4 项业务断言失败，修复序号缺口、存储路径 ID 与恢复解析错误脱敏；独立审查新增 `sync-s2b-review-red` 13 项中 1 项失败，修复接收事件 batchId 与外层不一致。`sync-s2b-refactor` JVM/Android release JVM 各 15 项、零失败/跳过；`sync-s2b-format-verified` 对本项全部 9 个 Kotlin 文件逐个 IS CLEAN。同一独立审查代理的一次限定复审通过。基础依赖共 7 行追加，能力超过 8 文件的原因是共享契约、两目标必须的 Tink/SecureRandom adapter 及同一套测试；未引入独立平台业务规则。真实系统安全存储、ART/R8 和 Git 完整制品保留后续门槛。

S2a 完成证据（2026-09-15）：`sync-s2a-red` 28 项中 14 项行为失败；补齐授权等待/过期/无过期 token/续期、严格权限和有界同源分页、HTTP 错误及默认输出脱敏。初次两目标各 33 项绿后，独立审查发现第 100 页合法结束被误拒绝；`sync-s2a-review-red` 17 项中 1 项失败，补一行结束返回及 chunked 动态读限额测试。`sync-s2a-verified` 两目标各 35 项、零失败/错误/跳过，受限 Spotless 成功；独立限定复审通过。保留公开 Client ID 设备流程、production ProxySelector/DNS/TLS、原子凭据替换，无 client_secret/PAT。真实账号与平台装配仍未冒充完成。

S2c 完成证据（2026-09-15）：`sync-s2c-red` 14 项中 7 项失败；补齐完整上传制品、保存失败不发 HTTP、索引链与固定快照后通过。初始化/严格布尔响应补测分别确认 2 项和 1 项业务失败；`sync-s2c-result-red` 23 项中 1 项失败，定位初始化响应丢失。初次独立审查发现残缺空间会被重新初始化、历史快照误确认当前发布；`sync-s2c-review-red` 26 项中 2 项业务失败，修复后关闭。认证索引缺序接收测试包含合法对照，`sync-s2c-chain-mutation-red` 暂时移除相邻序号校验时精确 1/1 失败，校验已恢复。`sync-s2c-verified` 两目标各 Git 26 + service/旧契约 6 = 32 项全绿，受限格式真实通过；唯一一次限定复审通过。

S2 阶段收口：`sync-s2-stage-tests` PASSED，domain JVM 427 / Android release 368，data JVM 209 / Android release 88，共 1,092 项，零失败、错误、跳过。真实 GitHub、系统凭据存储、ART/R8 与应用 wiring 仍在 S4/S5，不以共享模块绿灯代替。S2c 超过 400 行的原因是同一 Git 格式/发布状态机及状态型 HTTP fixture；保持单一上下文便于审查不可变性与可达性，未增加服务商或独立业务能力。

2026-09-15 最新隔离核对：主工作区 HEAD 为 `bc8256569`，已提交 migration 18；还有未提交 migration 19，以及 Manga/Chapter 模型、更新对象与 SQL 修改。仅核对路径，不复制这些未提交改动。当前未提供跨任务消息工具。S3a 开始前在本工作树整合已提交接口，重新核对迁移，不占用对方正在使用的 18/19。

S3a 已确认的接入边界：复用现有 `SyncOrigin`，在现有 Database 事务内分配序号、写事件与 outbox；收藏同时覆盖 `updateMembershipsAtomically` 和 `partialUpdate` 两条链。作者取消关注补同事务，远端投影保留本机扫描偏好。明确已读/未读不因本机同值过滤用户意图；阅读以 reading_events 幂等插入成功为入队条件。另设同步许可，不能用 `recordHistory` 代替无痕判断；未配置空间与暂停/授权失效分别处理。跨平台 UI 调用点在 S3a 实施时定向验证，不另开全量审计。

S3a1 基线整合：已在隔离树执行 `git merge --no-commit --no-ff bc8256569`，自动合并无冲突。此合并仅包含主工作树已提交代码，暂与 S3a1 同批保留；其待合并的 staged 文件不误记为同步新功能，不重复改写扩展实现。数据库迁移编号在 S3a1 提交前再次核对，未提交 19 不复制。

S3a1 当前证据（2026-09-16）：共享 SQLite 契约首轮 6 项/目标通过，追加重启、256 条/512 KiB 边界与迁移后 10 项/目标通过，既有 migration15/16 回归通过。独立审查发现显式旧观察的并发 heads 被覆盖及 generation=0 与既有协议不一致；两项正确 RED 后修复，Android 来源浏览与批量取消入口均补齐并验证。迁移 20 为同步预留，主工作区未提交 19 仍归另一个任务；此暂态分支不得发布，S5 整合须先纳入其正式提交并验证连续迁移，禁止造空迁移 19。

Android focused 测试环境边界：JitPack 的 FlexibleAdapter POM 返回 404，阻塞许可证生成；测试使用忽略目录中的临时 init script 停用该生成任务，并仅声明测试编译所需的 raw 资源 ID，不提供或伪造许可证内容。该设置不进入源码或发布流程，也不能作为许可证/R8/正式发布通过证据。

| 单元 | 前置与输入 | 实现范围、所有权与输出 | 验收 |
| --- | --- | --- | --- |
| S1 协议与共享规则 | 已审核操作语义、真实领域模型；无需改业务表 | Luna 新增 domain/src/commonMain/kotlin/mihon/domain/sync 与 commonTest 对应包：序列化事件、身份、校验、因果归并、接收端决定、阅读候选。主模型维护本计划并核对后续接入 | C1–C6 |
| S2 GitHub 与加密/授权可行性 | S1 协议冻结；核对官方 API 与现有 HTTP/安全存储 | 同一 Luna 继续实现 crypto/transport/auth 窄接口及现有模块内 adapter；不要求用户维护令牌。MockWebServer、互通与真实服务试验 | C9–C11 |
| S3 本地可靠记录与投影 | S1；再次检查扩展迁移/接口 | domain/data 共享日志、SQLDelight 事务、收藏/作者/阅读各真实入口、inbox/outbox/决定/批次/游标、恢复来源；对生产 repository 做集成故障注入 | C7–C8、C15 |
| S4 双端产品链路 | S2/S3；实际 coordinator 端口 | 书架入口、同步/设置/授权/恢复页面、来源重试、批量、三触发、安全存储、Injekt/Voyager 接线；复用原有 UI 组件 | C12–C14 |
| S5 整合与发布验收 | S1–S4；扩展代码已能整合 | 同步/扩展共同回归、规模试验、Android release/R8 与 Windows/macOS 正式产物，维护说明 | C16–C18 |

S1 是后续生产引擎的共享规则单元，不能以纯内存测试替代 S3/S4 的生产 wiring。不得照搬 HTML 同步模型，不删减后续单元来宣布目标完成。

## 冻结验收表

所有项目初始 pending；Luna 自测只记 self-tested。主模型独立审核当前源码和实际输出后记录通过证据。每单元只运行相关 focused tests 做红绿；完成后相关集成/格式；模块/发布阶段才运行全量。

| ID | 输入与操作 | 预期与验证门槛 | 状态 |
| --- | --- | --- | --- |
| C1 | 编解码漫画/章节/作者身份、64 位源 ID、一次多效果阅读信封 | 来源+原始 URL/portable_key，不按标题或本地自增 ID；单信封分类计数一次；没有阅读模式、token、扩展配置字段。共享契约执行真实 codec | S1 passed |
| C2 | 三端 ADD→REMOVE→ADD 的所有收件排列、重复接收、缺失前驱、跨字段前驱/环/重复 ID 异内容 | 明确后继覆盖旧取消；依赖完整才参加归并；有效头与收件顺序无关；缺失/无效输入不污染有效投影 | S1 passed |
| C3 | 并发 ADD/REMOVE、FOLLOW/UNFOLLOW、明确未读/读完；初始导入或旧备份迟到 | 并发保留收藏/关注、明确未读优先；baseline 低于明确操作；缺失不生成取消；后续明确用户操作仍可改变结果，无强制冲突选择 | S1 passed |
| C4 | 有效远端取消到已收藏 B/空 C，B 忽略或确认，再接收重复及后继 | 只 B 产生待确认，决定绑定有效版本集合且只影响本设备，无反向 ADD；后继重新收藏使旧决定失效；不同接收端可不同 | S1 policy passed；持久化与批量接入仍在 C8/C14 |
| C5 | 重读早页、不同章节阅读、同时间候选、坏页码提示、正在阅读时接收新位置 | 因果后继允许页码回退；并发按可用时间+稳定 ID 后备选续读、保留其他历史；不篡改当前会话页/模式，不使用永久最大页码 | S1 policy / S4c真实入口、默认DI、SQL快照、两端reader及反馈接线 passed；实际发布运行仍由S5验收 |
| C6 | 非法类别/效果组合、未知版本/代次/空间、超限字段/批次、损坏一个效果 | 信封整体拒绝或隔离，不部分接受坏信封；单批至多 256 事件与 512 KiB 明文；不泄露凭据；共享失败契约 | S1 passed |
| C7 | 所有实际收藏/批量/浏览、作者关注、章节已读未读、阅读提交；事务失败与无痕 | 业务和 outbox 原子提交，重复幂等阅读不重复计数；metadata/迁移/恢复/REMOTE 不伪造 USER；无痕不上传也不追补。真实 repository + SQLite 故障注入测试 | S3a/S3c passed；真实入口与事务测试见对应完成记录 |
| C8 | 重启、同 ID 异内容、收件/应用崩溃、缺源后恢复、同步时新增本机动作 | inbox/outbox/游标/决定持久化，成功只清发布集合，待确认不阻塞其他数据；来源恢复自动重试，无强制匹配或自动安装扩展 | S3b/S4a 持久化与重试、S4b 原生反馈 passed；正式产物 S5b pending |
| C9 | 同一 HEAD 竞争、未知发布结果、空库竞争、分页/截断、403/429/500/畸形响应 | Git DB REST force=false，有界重试且保留其他设备文件；确认有效 ref 内容后才清队列；固定密文字节重试；MockWebServer 完整解析链 + 真实私库竞争 | S2c/S3b/S4a HTTP与SQLite契约 passed；真实私库 S5 pending |
| C10 | Android/Windows 加密互读、错误密钥/AAD、历史回退、凭据后端故障 | AEAD 绑定空间/代次/协议/批次；密钥与授权分离、系统安全存储不可用不降明文；最低 Android/R8 与真实 Desktop runtime | S2b/S4a 两目标与Windows凭据 passed；ART/R8/macOS runtime S5 pending |
| C11 | 首次 GitHub 设备登录、授权取消/过期/限流/重新连接、选择新/已有专库 | 系统浏览器设备码授权，无 PAT 输入；自动接收结果；恢复资料确认与首次合并；设备名称、换空间/断开隔离。真实服务配置与运行证据 | S2a HTTP/S4a安全凭据与graph、S4b原生UI passed；真实账号 S5b pending |
| C12 | 手动、启动、15分/1时/6时/24时周期同时触发、离线/休眠恢复 | 共享 coordinator 串行且合并请求；UI 异步；各设备设置独立；Desktop 退出不运行，Android WorkManager 尽力；保留队列、失败可重试 | S4a shared coordinator/真实WorkManager/Desktop生命周期 passed；正式产物 S5 pending |
| C13 | 双端从书架进入/关闭同步，进入设置返回，后台完成，长倒计时 | UI 对齐最终 DEMO：三种顶栏表现可组合忙与数量，99+，设置齿轮子页、状态右侧立即同步、瞬时通知不重放；真实 Compose/导航/DI 与视觉验收 | S4b共享UI/两端入口/四张Compose离屏图 passed；正式运行 S5b pending |
| C14 | 120/1万待确认、长按/范围/全选/反选/全部处理、执行中新增/失效/关闭 | 条目直接上方吸附操作条，LazyColumn/分页、冻结版本、汇总确认、有界分段事务、实际完成/跳过/失败反馈、恢复不重复应用 | S3b/S4b行为契约及S5a规模 passed；1万项耗时与边界见规模表 |
| C15 | 空设备、已有书架、旧备份、克隆 actor、首次导入中继续操作、本机清除历史 | 只合并基线且不复活明确取消；水位后操作不被导入覆盖；新 epoch；本机历史屏蔽持久化、真实新阅读可重新显示，不上传全局删除 | S3c/S4a 基线、历史、水位和新epoch passed；不可观察整机回滚边界见技术方案 |
| C16 | 1万/10万事件、3/10设备历史、120/1万待确认 | 记录首次与增量时间、请求量、内存、仓库增长、交互响应，暴露真实上限；保留完整历史，不自动 force push/删 tombstone | S5a passed；actor/真实运行边界见上表 |
| C17 | 全部产品链路和构建 | 相关共享 Android/JVM 契约、完整 Android/Desktop 测试、Test Mode、Android 发布/R8、Windows 构建脚本正式 EXE 与 macOS 发布验收；未运行不算通过 | pending |
| C18 | 整合扩展任务后的最新仓库 | 逐项核对共享 schema、migration、备份、Source API、DI、依赖，冲突已解决且两功能回归；主工作区用户修改未被回滚，提交/正式产物可追溯 | S5a固定dbf3f050a1整合passed；S5b发布门仍pending |

S3c1 完成证据（2026-09-16）：基线4/4、回退7/6、发布确认10/3均有正确RED；无痕追补边界在初轮发现并修复。独立审查的P1为私密已读被正常部分重读/Metadata继承，10/2 RED后以最后可共享值修复；同次限定复核发现REMOTE接线遗漏，11/1 RED后补齐三个同事务字段写入，最终复核通过。`sync-s3c1-final` domain JVM/Android各38，data JVM170/Android149，共395项零失败/错误/跳过，20个Kotlin文件限定格式通过。事务冻结/水位、120章分段、重启续传、断开和新actor、23→24迁移、真实Git回退/不可变初始化索引/发布确认落盘失败有实际契约。代码与共享测试、平台薄适配、schema和必要维护文档共26个文件，超过规模提示仍是同一首次同步可靠性能力；只扩既有publish观察回调，不增加HTTP轮次。S3c2、原生UI与正式发布尚未完成。

### S3c1 后续产品接线约束

- 首次启用走 `SyncBaselineStore.connectAndImport`，注入与作者仓库相同的 bootstrap。激活、冻结对象/描述与记录 actor/epoch/next_seq 在同一事务；重复调用返回同一 importId。`process` 每次1–256项，默认50，事务同时写事件/outbox和移除队列条目；失败原样重试。导入只提供正向基线，因果父仅来自同次导入，不能把刚下载的 USER heads 设为旧备份的父。
- `disconnect` 关闭交换许可，继续记录用户操作；`renewIdentity` 封存旧开放批次并产生新 actor/epoch，不改旧事件、密文或队列。普通连接不得重置已存在序号。S4安全存储恢复/设备身份检查使用此边界，不能复制另一设备可继续使用的 actor。
- 每轮使用经 transport 认证的当前完整快照。持久接收和上传 exchange 必经 `SyncRemoteSnapshotGuard`；Git 发布的成功确认与竞争重读也调用同一观察器。可以先接收再上传；上传后若继续收件或上传下一批，重新取得当前快照，不能重用已经落后于本机锚点的旧快照。
- 已观察批次、不可变索引（含 bootstrap）缺失或改写后，永久阻断该空间的交换且保留队列；重连不会清除。S4须反馈需要恢复，不能自动删除锚点或强制推送。新设备无已见锚点时不能证明更早历史不存在，界面/维护说明不得宣称防住此窗口。
- 首次冻结读的是业务数据库的既有可共享状态；本机阅读隐私标记不上传、不进入普通备份。备份来源/本机历史清除的完整接线仍属于S3c2；尚未接入的原生按钮、凭据和定时器仍属于S4，不能把共享契约通过当作产品验收。

## S1 七行 GOAL

结果：实现 Android 与 Desktop 共用的多设备同步协议与确定性操作归并核心，为真实生产同步提供唯一规则。
证据与上下文：D:/Shell/Github/mihon-sync/docs/roadmap/2026-09-13-multi-device-sync-implementation.md 的 S1 与 C1-C6；技术方案后续确认覆盖旧人工冲突规则，HTML 仅是交互基准。
范围：仅新增 domain/src/commonMain/kotlin/mihon/domain/sync 与 domain/src/commonTest/kotlin/mihon/domain/sync 中的协议模型、codec、校验、因果归并、接收端决定及共享契约测试；不实现 UI、HTTP、数据库或修改依赖。
约束与授权：用户授权此实现及本人创建独立持久 goal；先核实子 goal 隔离；使用 UTF-8、apply_patch、红绿重构和 gradle-coordinator；不得修改主工作区、扩展文件、现有测试、计划、提交、发布或启动下级代理；网络采用本机代理与一次重试上限。
完成标准：(C1) 稳定身份和白名单多效果信封真实编解码；(C2) 三端乱序重复与因果完整性安全归并；(C3) 并发保留收藏/关注及明确未读优先、基线低于用户操作；(C4) 仅接收端分别确认有效取消且不反向生成事件；(C5) 重读早页和确定性续读候选保留历史及本地会话；(C6) 坏信封与协议/大小边界整体隔离；共享 focused tests 红绿证据与格式检查通过，不宣称生产接入完成。
正当阻塞项：不可替代的构建环境或依赖不可用需报告真实命令和错误；子 goal 隔离不可用只报告流程缺口并继续已授权实现，不操作父目标。
最终交付：按 C1-C6 提交源码路径、红绿命令与退出码、未验证边界以及 status/diff/tests/commit/process/next 回执，交由主模型独立验收；不自行提交。

## S2 交付契约

前置：S1 提交 `ed5501cb1`，本工作树干净；主工作树的扩展改动仍独立保留。S2 构建依赖只允许追加 data/build.gradle.kts 与 gradle/libs.versions.toml 必需项，避免改对方正在使用的 domain/build.gradle.kts。

所有权：Luna 新增 domain sync 下 auth/crypto/transport 契约，以及 data/src/commonMain/kotlin/mihon/data/sync 与对应 commonTest/jvmTest（必要时 androidMain/jvmMain adapter）。本批不改 S1 归并规则、SQL schema、UI、主工作区和已有扩展代码；发现必须改这些边界时先报告具体依赖。主模型维护本计划并核查后续生产 wiring。现有 NetworkHelper/OkHttp、协程 await、平台 CredentialStore 是复用入口；数据库和平台安全存储最终装配仍由 S3/S4 完成。

| S2 标准 | 输入与执行 | 必须观察到的结果 |
| --- | --- | --- |
| C1 授权协议（对应总 C11） | 真实 HTTP 设备码申请、pending→success、slow_down、拒绝、过期、403/429/500/畸形 JSON、用户取消 | 公开 Client ID，无 client_secret/PAT；先展示验证码再依 interval 轮询；slow_down 增加间隔，取消及时取消 HTTP 与轮询，不吞 CancellationException；有界超时，不把 error 的 HTTP 200 当成功 |
| C2 续期与仓库选择（总 C11） | 设备流程 refresh、并发取 token、授权撤销、分页 installation/repositories、仓库无权限/非私库 | 续期 grant 不含 secret；一次刷新并原子替换整套凭据，失败保持原记录；返回可操作重新连接状态；只选可访问专用私库，分页去重且防循环、拒绝越主机 Link；不自动创建或覆盖仓库 |
| C3 HTTP 边界（总 C9/C11） | 注入 production 客户端含代理/DNS/头日志/Cookie/重定向；连接测试和 Git API | 派生客户端保留 production 代理、TLS、DNS，隔离同步 Cookie 与日志并禁止跨站自动重定向；授权/API 主机受限；请求/错误/状态 toString 不泄露 token/device_code/密钥；MockWebServer 捕获实际请求而非 mock parser |
| C4 加密与恢复数据（总 C10） | 新密钥、导出/导入、跨 Android/JVM 密文向量，错误密钥/AAD/篡改/超限/畸形恢复内容 | 采用验证过的 AEAD（Tink 候选），绑定 space/generation/protocol/batch/path；相同冻结密文字节可重试，禁止重生成覆盖同 ID；密钥访问显式、默认输出脱敏。客户端加密格式与解密后 SyncBatchCodec 串联；不把 JVM Android 单元测试冒充 ART/R8 实测 |
| C5 固定 HEAD 读取（总 C9） | Git ref/commit/tree/blob 实际 HTTP，目录树截断、missing、大小超限、非法 index/批次/链断裂 | 从固定 HEAD 的 SHA 获取一致快照；清单有界分片/链式读取，不以 Contents 目录前 1000 项当全集；校验空间、代次、digest、seq 范围和解密后的批次；错误不伪报空同步或对象删除 |
| C6 发布与竞争（总 C9） | 同一 H 的两设备发布、ref 409/422、提交后响应丢失、相同路径异内容、重试耗尽 | base_tree 保留所有其他文件，commit parent 为读取的 H、force=false；同一冻结密文有界重建提交，最多 3 次竞争重试；确认目标批次可从有效 ref 达到且字节一致才返回 Published，blob/commit 创建成功不代表已发布；未知结果保留未确认状态 |
| C7 初始化（总 C9/C11） | 分支不存在、仓库为空、两端初始化竞争、私库 404/权限失败 | 初始化必须为显式动作；空库需 Contents bootstrap 后才可创建 ref；落败端加入胜者既有空间，不覆盖 spaceId/密钥；404 不自动新建。已有空间要导入匹配密钥，不凭 GitHub 登录推断可解密 |
| C8 接口连通与失败保留（总 C9–C11） | 同步信封→批次→加密→真实 HTTP 发布/下载→解密→S1 codec，auth/transport取消及失败 | 一条集成路径实际执行所有 production 组件；上传和收件结果分离，调用方仅可清理已确认发布集合；模型保留原始批次与错误，不产生假成功。真实 App/私库竞争、系统安全存储与发布运行验收继续作为总 C9–C11 未完成门槛 |

测试：用现有 mockwebserver3 与协程测试工具；可将 data 的 MockWebServer/coroutines-test 依赖追加到两个目标共用的测试集合，覆盖共享真实 HTTP/加密实现。先运行能失败的行为测试，再最小实现和重构；仅缺接口的编译失败不能替代行为 RED。格式使用受限文件集合的一次自动格式化，检查器的格式化中间行号不能用于逐行猜测修补。自测回执交接后由主模型进行一次独立审查及最多一次修复复验，最后一次相关模块全量与格式检查。

外部资料：GitHub [设备授权](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app)、[续期](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens)、[Git refs](https://docs.github.com/en/rest/git/refs)、[Git commits](https://docs.github.com/en/rest/git/commits)、[Git trees](https://docs.github.com/en/rest/git/trees)、[安装仓库](https://docs.github.com/en/rest/apps/installations)、[Tink Java/Android](https://developers.google.com/tink/setup/java)。依赖实际可解析后锁定版本；应用 API version 使用已核对官方支持的版本。

## S2 七行 GOAL

结果：实现可用于真实多设备同步的 GitHub 设备授权、AEAD 批次加密及 Git Database HTTP 传输核心。
证据与上下文：D:/Shell/Github/mihon-sync/docs/roadmap/2026-09-13-multi-device-sync-implementation.md 的 S2 交付契约 C1-C8；S1 已提交 ed5501cb1；已审核技术方案和最终 DEMO 是产品权威。
范围：在隔离工作树新增 domain sync 的 auth/crypto/transport 契约、data sync 生产 adapter 与共享/HTTP 测试，按契约追加 data 和版本目录必需依赖；不改 UI、schema、扩展代码或主工作区。
约束与授权：用户授权本人创建并执行独立持久 goal；逐字核对目标与隔离，UTF-8、红绿重构、复用 production 网络链、单 Gradle coordinator；禁止泄露凭据、外部账号写入、提交、下级代理和未经说明的范围变更。
完成标准：(C1) 设备授权完整失败与取消协议；(C2) 安全幂等续期与有界私库选择；(C3) 复用代理且隔离凭据泄漏的实际 HTTP；(C4) AEAD 及恢复数据校验与平台共享向量；(C5) 固定 HEAD 的完整有界读取；(C6) 非强制发布、并发及未知结果确认；(C7) 显式初始化和竞争安全；(C8) 信封到 HTTP 再到解密的 production 集成路径和失败保留；相关 focused 与格式通过，真实服务未验证不宣称产品完成。
正当阻塞项：不可替代依赖不可达或当前工具能力缺失时报告真实证据；用户尚无 GitHub App，不伪造 Client ID/授权/私库结果，继续可独立实现的工作；子 goal 隔离失败不操作父目标。
最终交付：逐项 C1-C8 源码与真实测试证据、完整 goal objective 或哈希、命令和进程终态、未验证门槛，以及 status/diff/tests/commit/process/next 回执；停止写入后交主模型独立验收。

## S2 独立审查与一次定向修复

正式审查结论：未通过，进入原预算中的唯一修复复验。S2 自测 `sync-s2-final-green2` 的 JVM/Android release 各 21 项通过已由主模型核对 XML，但不覆盖完整 C1–C8。主模型准备的测试与正式审查共同构成以下门槛；不将原绿色结果升级为生产完成。

- 子 goal 回执已核对：thread `01a09aea-666c-7110-918d-c4c3e7c0b4e3`，状态 active，与父线程不同；七行 objective SHA-256 为 `698911c67cfe76ad7accc350a93ae8253f4c618d6201f8dbc03aa4ec39caca82`，与原契约一致。子线程未提供 send_message 工具，不能要求它用不可用工具作即时回报；后续以最终回执交接，必要时由父模型停止空转后再 follow-up 恢复，不虚假完成 goal。
- `sync-s2-review-red` 于 2026-09-13 15:55:47 UTC 终态 FAILED，JVM 29 项、8 项失败：授权 5 项、空库初始化、不可变批次覆盖、跨空间发布。授权矩阵中的循环在首个断言失败后停止，未运行的后续组合不能算作已复现；修复须运行完整矩阵。
- 响应体取消测试曾等待首个字节到达后才取消，旧整体延迟 fixture 因而没有覆盖中途阻塞。主模型改为立即交付第一字节、第二字节延迟 4 秒；`sync-s2-review-body-red` 于 2026-09-13 15:58:11 UTC 终态 FAILED，8 项中该项失败，实测取消等待 4022 ms。修复不是放宽阈值，而是取消真实 call/body 并保证调用线程不执行阻塞网络读取。
- 原 Git fixture 仅返回文件，遗漏真实递归 Git Tree 中的目录项。主模型补齐 `type=tree/mode=040000` 及子树读取；`sync-s2-review-tree-red` 于 16:01:35 UTC 终态 FAILED，7 项中 6 项失败，主要新增现象是把目录 SHA 交给 blob API。此轮核验属于同一次正式审查的 fixture 修正，不是第二轮审查。

| 修复项 | 对应契约与证据 | 通过门槛 |
| --- | --- | --- |
| R1 授权与续期边界 | C1/C2；SyncAuthorizationSafetyContractTest 的 5 项失败 | 设备端点 HTTP 错误保留可重试/重新连接分类；JSON 字段严格类型、token_type、正数时限和溢出校验；GitHub 验证链接在展示前核对；pending/slow_down/deny/expired/取消完整矩阵，使用有界时限；refresh 的 error 优先于 token，真实 HTTP refresh 后整套原子保存；expectedRevision=null 表示预期不存在，不覆盖新登录 |
| R2 完整 HTTP 生命周期 | C3；分段 body 取消等待 4022 ms，源码在调用协程中阻塞 source.read | 完整响应处理在可取消 I/O/回调链，不阻塞 UI；取消发生在等响应头或读 body 时均及时取消 call/关闭 response；保留代理/DNS/TLS、Cookie/日志/cache/redirect 隔离，响应/异常默认输出不含凭据；不得只在 Job 完成后 cancel，也不得用捕获 CancellationException 当普通失败 |
| R3 可用恢复资料与冻结加密对象 | C4；当前只有未使用的 SyncRecoveryData 类型，没有生成、导入或导出流程；plaintextDigest 仍为外露可变数组 | 新建随机密钥，严格、有界的恢复资料编解码，足够的 space/generation/key 身份供新设备加入，错误版本/密钥/格式拒绝且脱敏；原始密钥不误称 wrapped；整个冻结制品不可被外部数组修改；实际共享解密向量覆盖 Android/JVM，不仅各自 round trip；加密前拒绝混合 actor/epoch 或错误 path/scope，解密后核验完整元数据 |
| R4 完整有界索引读取 | C5；真实 Git Tree 目录项引发 blob 404，当前无 actor head、按文件名字典序猜前驱且 distinctBy 隐藏冲突 | 区分目录/文件，固定 HEAD；实现既定 actor/epoch 加密 head 与不可变分片链，不猜文件名顺序；有界读取和近线性链校验；校验 actor/epoch/seq 连续范围、path、空间代次、真实内容 digest、重复 ID 异内容及前驱/环，错误不伪装为空；接收批次还要对比 snapshot 与 index，而非只验证其自述 header |
| R5 不可变发布与未知结果 | C6；现有路径异密文、跨空间发布实际通过而本应拒绝 | 发布前校验 repository/snapshot/scope/actor/path；同一 ID/path 已存在且字节相同可幂等确认，不同则拒绝且原文件不变；base_tree 与 parent SHA 正确、force=false，最多 3 次竞争尝试；重试和进程恢复都使用已冻结批次及索引字节；未知结果必须核对有效 ref 可达的真实制品，所有网络阶段失败保持准确未确认结果，取消不被内部 runCatching 吞掉 |
| R6 显式初始化及竞争 | C7；空库 GET Contents 404 被直接当仓库不存在；有 README 也总是修改默认分支 bootstrap | 先区分仓库存在/私有/权限/默认分支：仅真正空库做明确 Contents bootstrap；已有提交时从已读 SHA 建同步分支并保留其他内容；重复初始化无无关默认分支写入；创建 ref/初始化竞争后读取胜者身份并返回可加入或需匹配恢复资料的状态，不重写其 bootstrap/space/key；404、损坏索引及权限错误不能触发新空间覆盖 |
| R7 发布前可持久化的接口 | C8；SyncBatchSyncService.upload 在返回密文前已发起 HTTP，失败/取消时调用方拿不到待持久化制品 | 将准备冻结制品与 HTTP 发布分开，使 S3 能先在事务中保存批次和索引原始字节再请求网络；加入保存失败不发请求、重建服务后重试同一制品的集成测试；接收失败与上传结果独立，不能以 regenerated ciphertext 代替恢复；真实数据库装配仍留 S3 |
| R8 补足实际失败证据与格式 | C1–C8 自测只有 6 项自身测试，加父模型 15 项，存在未覆盖正常/失败矩阵 | 在现有测试簇补齐 403/429/500/畸形响应、取消、分页循环/外链、树截断/链损坏、空库/竞争、同 ID 异内容、错误恢复资料；测试执行真实 production parser/crypto/Git HTTP；保留父模型断言与真实目录 fixture，允许接口变化所必需的机械适配及格式化，不弱化预期；最后相关 focused 两目标与格式通过后交回父模型一次复验 |

修复范围仍为 S2 的 domain/data sync 包、对应测试及必要依赖。数据库、UI、扩展、主工作区和外部账号写入均不在本次修复范围。原 Luna 继续同一 active goal，不新增代理或审查轮次；主模型负责计划和最终复验。新增完整模块测试尚未运行，待修复通过后执行一次。

### S2 修复复验结果：仍未通过

2026-09-13 16:30 UTC，Luna 的最终 JVM / Android release 自测分别为 31 项，零失败、零跳过；`sync-s2-format-final-check` 对 data/domain 的 spotlessKotlinCheck 通过。主模型核对了真实 XML 和协调器终态。该结果不等于完整 C1–C8：Luna 回执明确尚缺授权拒绝/过期/取消、分页循环/外链、树截断/链损坏、初始化竞争等专门回归。新增“固定向量”测试只验证恢复资料的固定密钥十六进制文本，不是 Android/JVM 共用的固定 AEAD 密文解密向量。

主模型在原定的唯一修复复验中，向既有 SyncGitSafetyContractTest 增加四条真实 HTTP/Git fixture 回归，未修改 production。`sync-s2-repair-review` 于 2026-09-13 16:35:06 UTC 终态 FAILED、exit 1，19 项中 4 项失败：

| 失败项 | 实际结果与已确认原因 | 修正门槛 |
| --- | --- | --- |
| R5：旧快照遇到同批次异密文 | 第一发布者成功后，第二发布者持旧快照发布同 ID 的另一密文；409 后重读没有重新检查已存在批次，原远端字节被覆盖 | 每次重读和每次真正发布前都核对不可变路径；同字节幂等确认，异字节拒绝且不改原文件 |
| R5/R7：服务重建后重试 | 使用已保存的同一 SyncEncryptedBatch 重建 service 并重试；批次密文相同，但远端不可变 index 文件变为新密文 | prepare 必须包含 batch/index/head 全部制品；调用方可在任何 HTTP 写入前持久化，重试与进程恢复不重新随机加密索引 |
| R4：序号缺口 | 同一 actor 先发布 seq 1，再发布 seq 3；当前实现和读取均接受，缺失 seq 2 未被发现 | 验证约定的连续序号范围和链连接，不能仅检查前一范围小于后一范围 |
| R4：缺失 actor head | 合法发布后，在 Git fixture 创建一个删除该 actor head 的后继提交；readSnapshot 仍成功并当作完整快照 | 每个实际 actor/epoch 索引链必须有且仅有可验证 head，不能把 head 当可选附加文件 |

HTTP 的 8 项回归全部通过，包含真正分段响应的及时取消。原来“只有当前快照中已存在同 ID 才拒绝”的检查仍不足以覆盖竞争重读；prepare 目前也仅冻结批次，索引/head 在 publish 内生成。链验证仍逐节点回溯，最坏 O(n²)，尚未达到 R4 的近线性门槛。初始化竞争仍缺胜者恢复资料的端到端专门证据；“保存失败不发 HTTP”不能以尚未接数据库为由从原 S2 接口契约移除，至少要验证可注入持久化边界与失败时零网络写入。

当前原预算的一轮正式审查和一轮修复复验均已执行。保持根 goal 与 S2 goal 未完成，停止继续改 production，不提交失败批次；完整模块测试尚未执行，避免把未通过修复的代码作为最终候选。拟向用户申请追加一轮原范围定向修复与复验，预计 60–90 分钟，复用原 Luna，无新子代理、无新过程报告；仅闭合上述失败与原 C1–C8 剩余覆盖，之后运行已计划的一次模块完整测试和格式检查。是否追加仍待用户答复，不把等待视为批准。

## S3 接入准备（前置仍为 S2 验收，不代表已开工）

本单元应形成可被既有业务调用链实际使用的持久化同步能力，不能只交付一组尚未装配的通用表或内存 repository。界面新增入口留 S4，但业务写入来源、无痕许可和当前阅读会话边界必须从真实调用方传递。继续由原 Luna 接同一上下文簇；开始前公布该单元流程预算并冻结验收表。

| 接入项 | 复用入口与必须实现的行为 | 所需实际证据 |
| --- | --- | --- |
| 操作与业务原子性 | 收藏的 updateMembershipsAtomically / MangaUpdate 路径、CreatorRepositoryImpl、SqlDelightReadingProgressRepository、明确已读/未读用例在既有事务中写日志。无痕、恢复、metadata、REMOTE 的来源不能靠全局布尔值猜测 | 真实 SQL 故障注入同时回滚业务、序号、outbox；批量中途失败不得部分记日志。覆盖未配置、暂停自动同步、授权失效等连接状态 |
| 重启可靠性 | 持久化空间绑定、actor/epoch/seq、原始事件、字段头、inbox/outbox、接收游标、已冻结发布制品与运行状态。新设备/恢复不能复制旧 actor 继续写序号 | 关闭数据库并重新打开；上传响应未知、收件后未投影、投影事务失败均可继续；清队列只包含确认为 Published 的原集合，运行时新操作仍保留 |
| 接收与本地决定 | 调用 S1 reducer 与 receiver 规则，决定绑定精确有效头。远端取消只为接收端当前已收藏/关注对象产生待处理；本机保留或确认不产生反向 USER 事件 | 三个真实数据库乱序交换、重复投递、依赖迟到、远端重新收藏使旧决定失效、关闭重启后决定仍有效；有待处理时其他对象继续应用 |
| 最低对象元数据 | 在批次中增加独立、严格校验的对象描述；复用稳定源/URL 和作者 portable_key，不猜测同名对象。缺源保留已验证输入并在条件恢复后重试 | 空库收到收藏后能显示真实标题；同名不同作者 key 独立；错空间/异内容重复 ID 隔离；不覆盖阅读模式、扫描来源/周期或扩展设置 |
| 阅读与历史 | 一次进度提交产生一个多效果信封；幂等重试不重复。当前阅读会话携带自己的因果基线，收件不改变当前页；本机清除历史保存屏蔽范围 | 重读早页、并发跨章、明确未读、无痕退出不追补、recordHistory=false 与无痕分别验证；旧记录重复及投影重建不复现已清除历史 |
| 批量持久处理 | 冻结选中项与有效头，按有界事务分段执行；新增项不混入已确认集合，失效项跳过；提供列表分页与完成/跳过/失败计数 | 批量中断重启、执行间到达重新收藏、重复提交同一任务；实际查询与事务数量有界，不能把 1 万项全部一次性映射为 UI 节点 |
| 首次与旧备份 | 导入水位和 importId 持久化；baseline 低于明确 USER；导入期间新操作不能被扫描覆盖。普通备份不包含可继续使用的 actor、密钥、token 或接收端决定 | 空设备不产生取消，旧备份不复活有效取消，导入中断可继续，换空间不改写旧 outbox 的归属；现有备份测试继续通过 |
| 迁移与接线 | 开工前重新核对扩展任务的 schema/migration；复用既有 DI 构造与 DatabaseHandler，不默认绑定永远不记录的 Noop 来假装完成 | 新建 schema 和真实旧 schema 升级均有测试；两端 production handler 跑共享契约；新增依赖解析/构造测试，扩展整合及正式运行门槛仍留 S5 |

本表是原 S3 范围的接入准备，尚不分配迁移编号、不创建下一子 goal、不运行另一组 Gradle。S2 修复仍拥有当前重型验证与源码写入时段。

## 过程与证据

- 初始核对：主工作区另有扩展改动，隔离 worktree 已建立；尚未实施生产功能或运行验收。
- S1 的 Gradle focused 命令：`python scripts/gradle-coordinator.py run --key sync-s1-green -- gradlew.bat '-Dhttp.proxyHost=127.0.0.1' '-Dhttp.proxyPort=10808' '-Dhttps.proxyHost=127.0.0.1' '-Dhttps.proxyPort=10808' :domain:jvmTest --tests "mihon.domain.sync.*" :domain:testReleaseUnitTest --tests "mihon.domain.sync.*" --max-workers=2`。PowerShell 必须引用完整的 `-D` 参数，避免被拆为两个参数；Gradle JVM 不自动采用 HTTP_PROXY。仍在运行时只查询同一协调器，不重复启动。
- 2026-09-13 恢复核对：`sync-s1` 于 13:36:52 UTC 终态 FAILED、exit 1，新增 SyncProtocolContractTest 引用的 production Sync* 尚未存在。保留此日志，不重复启动已结束的 RED。初次依赖配置直连等待、一次参数被 PowerShell 拆分的启动均只算环境失败，不算业务 RED；两次旧进程树已按协调器身份停止。当前继续从现有测试实现并完成可观察行为验证，不能把缺接口编译失败当已通过的业务测试。
- 主工作区最新文档提交 `53f96e679` 更新了扩展任务的恢复边界；扩展 production 仍未提交。该计划记录暂停不替代代理/进程实际状态，本任务继续保留全部外部差异和迁移 18 边界。
- 格式：限制于本次新增包的 Spotless 检查，不顺手格式化其他文件。阶段全量与正式构建由主模型统一安排。
- 外部配置边界：当前源码未发现专用 GitHub App client ID；实际授权与私库竞争验收仍需真实 App/仓库，不能以模拟页或 MockWebServer 宣称完成。
- 用户已回复没有现成 GitHub App。授权模块就绪后提供一次性注册步骤；此前继续协议、持久化和平台接入，不让终端用户手填 PAT。
- 2026-09-13 后续配置：用户已创建 GitHub App，提供公开 Client ID `Iv23liNtj6rhGAXJEwCS` 与管理页 slug `mihon-desktop`；公开页面 https://github.com/apps/mihon-desktop 已核对存在，当前为 private GitHub App。此前“没有 App”的注册前置已解除；设备流程开关、仓库权限、真实登录/续期和私库交换尚未实际联调，不能据注册成功标为通过。这里记录的是公开应用标识，不保存任何客户端密钥、私钥、设备码或访问令牌。
- S2 首次可执行行为 RED：`sync-s2-http-red` 于 2026-09-13 15:24:52 UTC 终态 FAILED、exit 1，实际执行 data JVM 9 项、6 项失败。其中主模型准备的 SyncHttpSafetyContractTest 有 6 项，4 项被短响应读取时的 EOFException 阻断；其余两项拒绝越主机和超限响应通过。Cookie/日志隔离断言尚未到达，不能将这些测试的失败误记为所有隔离问题均已复现。较早的测试语法与 Tink API 编译错误属于准备失败，不代替业务 RED；后续保留不同 coordinator key 的原始结果。
- S2 实现方向：使用 Tink Aead 产生随机化密文，冻结首次产生的密文字节供发布重试；不自行推导固定 nonce。Maven Central 的 tink 1.23.0 POM 已通过本机会话代理取得 HTTP 200，此证据仅证明该 artifact 可达，不替代 Gradle 依赖解析和平台运行验证。
- S1 自测交接：Luna 的隔离 goal 为 `01a09aea-666c-7110-918d-c4c3e7c0b4e3`；`sync-s1-green` 于 14:23:50 UTC 终态 PASSED。代理当时自报 14 条，最终以测试 XML 为准，不能用自报数量代替独立验收。
- S1 独立审查：主模型新增 `SyncSafetyContractTest`，`sync-s1-review-red` 实际执行 Android release 测试 12 条，其中 10 条业务断言失败；Gradle 在该 task 失败后没有完成 JVM 测试，不能把两平台都记为已执行 RED。失败包括同 ID 异内容的顺序依赖、坏首条污染空间选择、恢复基线覆盖用户取消、相同 effectId 混淆阅读元数据、跨代次及重复本地决定、缺失身份和 payload/parent 校验。原始日志保留在协调器目录。
- 原 Luna 已执行一次定向修复，保留失败语义，并以拓扑遍历收敛前驱图成本。主模型完成一次复验，审查中的行为缺陷已修复；未增加另一轮独立审查。
- S1 内聚性说明：本批预计超过 8 个文件或 400 行，内容仍是一套共同演进的协议、投影规则及其 Android/JVM 契约，加上实施依据文档。拆开接口、实现和失败回归会使批次无法独立验证，因此维持一个提交。主要风险是输入校验与因果归并，使用实际 production 编解码/投影的共享回归覆盖；数据库和界面接入继续由 S3/S4 单独验收。
- S1 最终证据：`sync-s1-module-final` 在 2026-09-13 14:55:23 UTC 终态 PASSED、exit 0，执行 `:domain:jvmTest :domain:testReleaseUnitTest :domain:spotlessCheck --max-workers=2`。XML 实际为 JVM 427 项、Android release JVM 单元测试 368 项，全部零失败、零跳过；其中每个平台各 27 项同步契约（SyncProtocolContractTest 13 项，SyncSafetyContractTest 14 项）。2,000 节点长链已执行；未以此替代 C16 的 1 万/10 万规模和真实 I/O 测试。
- S1 验收边界：`SyncReduction.events` 保留有效原始历史，`SyncReadingChoice.history` 只表示当前续读候选；S3 必须持久化原始事件和历史摘要。`SyncReceiverMemory` 是域规则的内存承载，不能作为最终接收端决策存储；S3 需在业务事务内持久化决定并保持相同绑定语义。未来网络收件须显式传入已绑定的 spaceId/generation，原始字节一致性还需由 C8/C9 持久化校验。
- 流程偏差：Luna 最终回执称修复 goal 的 objective 曾多出“(未提交)”；因此不把本批记录为七行 GOAL 全链逐字一致。隔离工作区、代码范围及红绿/复验证据已由主模型实际核对。下一单元创建 goal 时须立即核对返回 objective，发现偏差先如实报告。

### 主模型接入核对（S1 实现期间并行读取）

- GitHub 官方[设备授权](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app)使用公开 Client ID；按服务返回的 interval 轮询，slow_down 增加等待。安装/仓库权限和用户授权是两个条件，需要列出已授权的 installation repositories。
- 官方[续期规则](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens)明确设备流程取得的令牌续期不要求 client_secret。可以保留过期/刷新/重新连接闭环，无需客户端内置 App secret 或新增服务端托管。
- Android NetworkHelper verboseLogging 当前会添加 HEADERS 日志拦截器，未声明 Authorization 脱敏。同步 adapter 必须在共用 production HTTP/代理链的前提下消除授权头日志泄漏，并覆盖连接测试与业务请求；不能直接新建默认 OkHttpClient 绕过代理/DI。
- DesktopCredentialStore 已封装 Windows DPAPI、macOS Keychain；新增同步 namespace 即可复用，不另做明文 Preferences token store。Android 需要 Keystore 包装 adapter。
- UpdateLibraryMembership→MangaRepositoryImpl 的事务已有分类关联与作者索引；Library/Browse 的批量和快速收藏仍有通用 MangaUpdate 路径，必须统一显式 USER 语义后同事务记录。迁移和恢复不是用户取消。
- SetChapterReadStatus 当前会过滤值未变的条目。新的显式未读意图需要与自动进度分开，不能仅凭最后 read 布尔值推导用户决定；注意原有下载删除等副作用不得因 REMOTE 路径重复触发。
- CreatorRepositoryImpl.followCreator 会替换扫描来源/语言策略；远端跟随只调整 enabled，应抽取既有事务内的最小关注写入以保护本地策略。
- Android ReaderViewModel 的进度记录与 updateHistory 分开；SqlDelightReadingProgressRepository 有幂等事务，接入时按一次阅读信封计数并保留平台无痕差异。当前会话必须保留自己的因果基线，收件不直接修改正在阅读的页码。
- DesktopAppRuntime 已有 DesktopRuntimeService 启停顺序及关闭等待，同步 scheduler 沿此链装配；Android 仿 CreatorDiscoveryJob 的平台 Worker，业务互斥仍在共享 coordinator。
- 现有 presentation-core 是 Android 模块，不能直接给 Desktop 引用 AdaptiveSheet。共享业务状态放 domain，Android 复用其 AdaptiveSheet，Desktop 使用当前 Compose Desktop 组件承载同样的底部子面板；此平台差异不允许复制归并或批量语义。新增入口分别接 Android LibraryToolbar 与 Desktop LibraryComponents，保留原有筛选/更新/搜索。
- 本机 SDK 36 的 android.jar / aapt2 / adb 已核对存在；只有 Android 36 系统镜像。现存 emulator-5580 属于扩展任务 `mihon-aex-api36`，禁止安装或更改它；最低版本/独立同步实机验收保持待验证。
- S2 依赖预查：当前没有可直接复用的 AEAD 批次实现。Tink 官方[安装正文](https://developers.google.com/tink/setup/java)当前列出 Java/Android 1.23.0、Java 11+ 与 Android API 24+；页面自动摘要的旧版本不可用作依据。后续锁定依赖前仍需解析实际 artifact，验证 Android/JVM 互通及 release/R8；官方兼容声明不能替代本项目运行验收。
- S2 网络边界：共享 adapter 应从注入的 production OkHttpClient 派生，保留其 ProxySelector、DNS、TLS 和超时，并移除同步请求链中的 HTTP 头/正文日志、跨站 Cookie 与自动重定向；GitHub token 只发送到受限授权/API 主机。授权、连接测试和 Git 交换都用该派生客户端，不另建绕过代理的测试客户端。
- S3 身份接入补充：`author_archive_creators` 的 portable_key 唯一，normalized_name 仅有普通索引；新设备收到同名不同 key 时可沿用 `getArchiveCreatorIdByPortableKey` / `insertArchiveCreator` 建独立记录。不要走旧 creators 表的 normalized_name 唯一约束，也不要调用按名字归并的 upsertCreator。同步需要携带可重建最低限度对象的描述信息；S1 的身份/效果规则已存在，S3 必须补齐新漫画/章节/作者的必要描述与映射，不用 UUID 占位标题冒充可用作者。
- S3 关注接入补充：现有 followCreator 会重写 watch sources/languages；远端投影应只修改关注 enabled，已有设备的扫描策略保持独立。新建关注采用本设备默认策略，缺来源时保留准确身份和输入，条件恢复后重试。
- S3 阅读接入补充：Desktop 的 recordHistory=false 不等价于无痕。应从真实会话传递独立的同步许可/无痕标记，不能用关闭历史记录推断禁止全部阅读位置同步；数据写入与 outbox 仍在同一事务，接收路径不调用追踪、下载删除或当前页跳转副作用。
- S3 事务接入补充：JvmDatabaseHandler 已有事务上下文与互斥复用，journal writer 应接收当前 Database 并在调用事务内同步写入，不在事务外另启协程或第二次连接。SqlDelightReadingProgressRepository 则直接使用 Database.transaction；须在其幂等插入确认之后写入同一次阅读的同步信封，失败时业务、历史和同步日志一起回滚。测试优先沿用现有 JdbcSqliteDriver、MangaRepositoryMembershipIntegrationTest 与 SqlDelightReadingProgressRepositoryTest 的真实数据库 fixture。
- S3 本机历史清除补充：HistoryRepositoryImpl 的 resetHistory、resetHistoryByMangaId、deleteAllHistory 均需在原写入事务中持久化本机屏蔽水位/已见事件范围。重建投影或重复收件不能让旧历史复现；之后的新阅读可以显示，且此屏蔽不进入全局 outbox。
- S3 对象描述补充：当前 S1 的 ADD/REMOVE payload 白名单为空，因此不能直接塞入标题等字段。S3 需以独立、受白名单与长度限制的批次对象描述扩展补充 manga/chapter/author 的最小可重建元数据，并以共享 codec 测试固定格式；不放宽操作 payload 为任意 JSON，也不改变已验收归并语义。恢复已有对象时以 source+originalUrl/portable_key 为准，不覆盖本设备阅读配置与作者扫描策略。
- 整合前再次核对：2026-09-13 16:08 UTC 主工作区的扩展实现与 migration 18 仍未提交。不能以未提交文件已存在视为可合并基线，也不能发布跳过该迁移的升级路径。S3 开始前重新确认迁移编号，S5 必须验证从当前已发布 schema 到两项功能整合后 schema 的真实升级。当前没有可调用的跨任务消息工具，工作树隔离是已落实的措施，不宣称已与另一任务达成文件占用协议。
- S4 设置入口复核：最终 sync-interactions.js 已包含登录、恢复资料、首次合并、连接测试、频率、设备名称、阅读与历史、断开和更换空间。断开会保留业务数据但停止原空间的未上传操作；更换空间需独立绑定与首次合并，不能把旧空间 outbox 直接改成新 spaceId 后上传。关闭自动同步则继续记录操作，并保留手动同步。
- S4 组件复用复核：Android AdaptiveSheet 已有手机底部和平板居中适配，以及返回、遮罩和滑动关闭能力；同步子页的返回处理须优先于关闭整个面板。Desktop 使用其现有 Compose overlay 与应用主题，并复用 DesktopShareService 的复制/保存能力以及系统浏览器入口；domain 的 ExternalActionParser 是外部输入解析器，不为登录开浏览器修改该扩展任务正在使用的解析器。
- S4 生命周期复核：同步运行属于应用 coordinator，面板关闭只结束局部通知展示，不取消正常数据交换。瞬时完成事件使用无重播的通道，重新打开仅呈现持久状态；不能读最后成功文案再次显示。Android Worker 复用现有 CreatorDiscoveryJob 的调度方式，保留独立工作名；共享取消传播不得照搬其将全部异常转为 retry 的写法。
- S4 凭据复用复核：DesktopCredentialStore 的 backend 已提供 DPAPI/Keychain/Secret Service 且拒绝明文后备。同步使用独立 namespace 和单条完整凭据记录，token 与恢复密钥分开管理；不得复用 tracker 账号槽覆盖追踪服务。Android scoped 搜索未找到 AndroidKeyStore/EncryptedSharedPreferences 的现成包装实现，后续仅增加平台 adapter，真实 Keystore 与 release/R8 仍是验收门槛。
- S3 两目标数据库契约准备：AndroidDatabaseHandler 和 JvmDatabaseHandler 都接受 Database/SqlDriver，Android 事务上下文不要求 Activity 或 Context。可在 commonTest 编写同一数据库行为契约，以两个测试目标的 fixture 分别实例化真实平台 handler，并用 JDBC SQLite 进行回滚、重启与收件测试；测试依赖和适配只加到测试集合。此证据执行的是 production repository/handler，不冒充 AndroidSqliteDriver 或 ART，真实 Android driver 仍在后续运行门槛验证。


S3a1 完成证据（2026-09-16）：`sync-s3a1-heads-review-red` 1/1、`sync-s3a1-entry-generation-red` 零代次 1/1 与 Android 两个独立入口失败（测试插件各重试两次），均因正确业务原因失败。修复后 `sync-s3a1-verified` 终态 PASSED：domain JVM/Android 各 3、data JVM 22（含迁移回归）/Android JDBC 12、Android 页面模型 10、Desktop 书架模型 58，共 108 项，零失败/错误/跳过。一次限定复审通过；19 个 Kotlin 文件经实际目标格式检查，Desktop 两个文件由其模块临时加载现有 lint 插件完成，`git diff --check` 通过。

本批新增 6 张共享 SQL 表及迁移，日志与收藏同一事务写入，固定 actor/seq/批次身份，按真实编码字节和 256 条界限切批，失败整体回滚、重启保持事件和因果头；用户入口显式标记来源，元数据/远程/恢复/本机阅读模式不会被误记。超过 8 文件的内聚原因是同一能力跨共享模型、存储、两端既有入口和对应契约，不新增独立 UI。上传制品落盘、接收投影、首次连接导入与设置入口继续按 S3b/S3c/S4 实现，不把本批描述为最终可用同步产品。


S3a2 完成证据（2026-09-16）：同一既有代理实施作者事务与双端入口，root 独立审查未发现阻塞，无追加修复复审。首轮作者数据库 RED 两目标各 5/4；扩展来源约束与平台入口 RED 为存储 7/5，双端 User 断言和 Test Mode 失败。`sync-s3a2-author-isolated-verified` 终态 PASSED：作者共享 SQLite 各 7、Android 作者入口 1、Desktop 作者/测试模式 11 与漫画详情相关回归 56、共享作者分页 2，共 84 项，零失败/错误/跳过；15 个拥有文件经实际目标格式检查。

验证隔离说明：`sync-s3a2-verified` 中作者契约全绿，但漫画详情一项旧“跳过同值已读/未读”断言被并行开发的 S3a3 新规则改变。仅临时将 root 未提交的 SetChapterReadStatus 用例替换为 HEAD 版本后验证作者，使用内存保存并在 finally 原样恢复，无文件快照、无用户改动回滚；后续阅读任务负责更新对应行为与断言。作者提交只纳入作者 15 文件及两份长期记录。

S3a3 完成证据（2026-09-16）：阅读日志首轮两目标各 6/5 正确 RED；补充会话因果基线、会话自身前进、切换空间隔离后两目标各 9/3 RED。Android 的实际入口缺少会话绑定和 User 来源、同值操作被过滤，另有接受操作后改变无痕造成补记，均由实际 ReaderViewModel/章节包装用例测试确认 RED。Desktop 的真实 runtime 四项会话/隐私测试正确 RED 后通过；书架回归另复现下载删除范围被同值规则扩大，已将明确操作集合与原未读下载清理集合分开。

S3a3 独立审查由 root 与同一个代理交叉承担，各自不审自己的实现。唯一新增阻塞为 Desktop 打开因果基线失败后停留 LoadingPageList；`sync-s3a3-review-red` 精确 5 项中 1 项失败，修复后通过既有 Storage Error/重试链反馈，保留取消及旧激活隔离。另补齐真实第二条 outbox 失败的手动多章回滚契约，两目标各 10 项通过；这是必要覆盖补充，不虚构为已确认的实现缺陷。一次限定复核通过。

S3a3 最终候选 `sync-s3a3-final` PASSED / exit 0：data JVM 12、Android JDBC 10，Android 实际入口与阅读器相关 29，Desktop 实际 runtime/session/tracker/书架/漫画详情 187，共 238 项，零失败/错误/跳过。26 个 Kotlin 文件完成受限格式检查，Desktop 临时 lint 明确校验 8 个实际输入；没有运行阶段全量或正式构建。Android focused 仍沿用上文仅测试使用的许可证任务适配，不代表发布许可证/R8 门槛通过。

S3a3 维护边界：每次实际章节激活先打开 ReadingProgressSession，接受进度时绑定该次会话；异步保存不得重新查询“当前会话”。只有事务提交成功才前进其自身因果头，切空间/actor/epoch 后旧会话不能上传到新绑定。无痕许可与 recordHistory 独立，在接受操作时固定；同值已读/未读仍是明确用户操作，下载清理等本机副作用保留原范围。此能力跨共享事务、双端既有入口及真实契约共 26 个 Kotlin 文件，保持内聚批次；没有新增同步字段、阅读模式同步或额外 UI。

2026-09-16 提交前整合边界核对：main 最新为 `112016470`，migration 19 已在该提交中且对应文件无未提交改动。本树仍只整合到 `bc8256569`；新 main 的备忘/Source 接口与迁移 19 留 S5a 正式整合，不复制或改写另一工作树。发布前必须闭合迁移连续性，不能跳过 19 发布本暂态分支。


S3b2 完成证据（2026-09-16）：root 实现收件/归并/决定/批量，同一代理实现稳定接口的业务 writer，双方交叉审查。收件初始 RED 3/3；writer 两目标各8/8；新增接收决定 8/5；阅读历史与单条故障 10/2；审查作者合并旧决定 16/1（expected INVALIDATED / actual APPLIED）。Android writer 外层回滚曾停滞，线程栈确认 nested runBlocking 丢事务上下文，保留完整上下文后真实契约通过。限定复核 PASS；`sync-s3b2-final` 为 domain JVM/Android 各38，data JVM147、Android128，共351项，失败/错误/跳过均0。15个Kotlin目标完成格式；最终14个data目标实际IS CLEAN，未再变动的domain版本常量沿用此前同批已通过格式。三真实数据库实际走 User repository→SQLite outbox→AEAD→Git HTTP→inbox→业务行，确认/保留各自持久且无回声；120项实际分页和50项处理；旧22→23保留原事件身份与上传。此处 Git HTTP 是真实客户端对 fixture 的集成验证，ART/R8、外部 GitHub 和发布产物仍在 S5。

S3b2 维护边界：接收事务先持久化完整认证批次、对象描述及字段索引；应用状态独立以字段因果头和revision保存，收到不等于已应用。归并读取相关字段及完整信封的父闭包，统一调用既有SyncReducer；坏祖先隔离、依赖迟到、缺源/描述/身份均不能推进错误业务投影。业务写入与应用标记/决定同事务；已存在实体按精确身份复用，新实体才需要来源和描述，保留本机阅读设置、备注、分类和作者扫描策略。阅读摘要已投影效果单独记账，不重复累计时长；本机清除历史的基线屏蔽仍属S3c。取消绑定精确有效头，保留不生成反向ADD；本机作者动作按真实identity及合法MERGED祖先将已显示旧决定持久标为INVALIDATED。批量以数据库INSERT SELECT冻结绑定，分页显示、每次最多50项且逐项事务，失败保留待处理并独立报告，重启继续未完成项；新到或已失效条目不会混入旧确认。21个文件跨共享SQL/业务事务、两目标契约和必要文档，保持同一个可验收的接收能力，没有新增同步字段、服务商或UI。


## S5b 执行契约（2026-09-16）

前置：S5a提交 `7338d529ab`，隔离工作树提交后干净。仅闭合C10/C11/C17与发布证据，不增同步字段/服务商/交互。root负责Windows正式runtime、GitHub真实联调和整合；两个新代理分别负责Android instrumentation/R8及macOS隔离构建/Keychain，按平台上下文分工。每个工作树的重Gradle由唯一协调者串行，远端mac工作树可独立并行。

固定流程：核对发布硬依赖与现有自动化入口；补充必要production验收接线及focused验证；冻结统一候选再做全量Android/Desktop回归、Windows规定脚本与macOS真实构建、Android ART/R8；一次交叉审查与最多一次对应真实失败的限定修复复核；汇总实际正式产物/限制后提交勾选。预计1–3小时，成本主要是构建、上下文与外部授权等待。过程仍仅维护本计划及成本记录；构建产物、测试结果与日志是交付/验证证据，不再建快照或额外报告。

当前只读前置：emulator-5580是扩展任务的mihon-aex-api36/API36，禁止覆盖其appId或数据，同步instrumentation使用独立app.mihon.syncacceptance身份；最低API若缺设备如实列门槛。mbp-lan已连接Darwin，实际仓库/Users/altair/github/mihon停留c84ed331fa且有用户改动，必须新worktree；既有/Applications应用及/tmp构建目录不覆盖，脚本只增加显式隔离参数且保留默认。GitHub公开App已配置，但用户本人登录/专用私库尚未取得实际证据；只通过生产流程联调，不导出token、不用curl或辅助JDK替代发布运行时。

条件处理：测试或运行失败只定位相应接线、配置与产物；不可替代的签名/账号/设备缺失记录真实阻塞，先完成其余独立工作。只准基于具体失败追加有限复验，不因为全量较慢而重复重跑；发现需新增能力或超出上述流程时先明确边界，不能静默扩张。

S5b阶段记录（未完成）：真实Test Mode→默认DI→原生同步面板的安全状态与合成凭据探针接线已通过12项focused测试、目标格式和独立审查。探针仅使用保留前缀及随机UUID，凭据异常只返回阶段/异常类型；图重建测试不代替正式EXE/App的系统后端跨进程验证。macOS隔离目录脚本已通过当地3项/12场景红绿验证；Windows运行该测试全部skip，不算额外通过证据。

Android隔离R8构建 `sync-s5b-android-art-build-fixed` 在许可证生成阶段失败：既有 FlexibleAdapter c8013533 的POM在JitPack两主机均返回404，缓存只有AAR；此处是外部元数据缺失，未绕过许可证任务。旧主树生成的许可证JSON缺本次Tink依赖，不能整份当作当前发布输出。Android26系统镜像已确认且创建独立AVD，尚未启动；不改扩展任务的应用数据。macOS基于7338d529ab与6个明确候选文件启动正式构建，隔离版本0.11.19.34；本机使用规定脚本full-tests运行完整Desktop回归。一次合并式全量命令被自动审批拒绝且未执行，随后改为规定脚本、前台协调执行获准；不得将未执行的Android/shared测试记成全量通过。

首轮完整Desktop结果：Windows 3063项/9失败/3跳过，macOS 3059项/9失败/7跳过，均在测试阶段终止，未产生正式EXE/App。两处许可证固定数量断言由196更新199并补实际Tink/Gson/protobuf条目断言；Source更新API、reading session和Compose依赖的三个旧fixture按已整合真实调用修正。parity manifest维护58条当前evidence定位（含2处已迁移符号），状态/要求/固定原版证据不变。

已确认的S5b接线缺陷：通用HTTP宿主默认读取已关闭全局DI的lazy同步面板，会重新启动数据库订阅；`sync-s5b-stopped-graph-red`得到503/200正确RED。已改为真实TestMode入口显式传入当前实例，集成测试实际执行TestMode.start→HTTP→原生同一panel。后续103项定向仅剩manifest旧定位失败；另45项profile/HTTP/书架生命周期/许可证复验无失败（1项既有平台跳过）。Java原生prefs不受user.home单独隔离，新增仅TestMode接受的显式DI profile与独立prefs命名空间；真实owner默认DI和重启读写已通过。限定审查又发现URI注册及全局辅助路径边界，实际发布启动必须同时隔离JVM user.home（Windows另APPDATA/LOCALAPPDATA），不可只依赖DI profile，更不能修改Mac HOME/CFFIXED_USER_HOME导致Keychain失真。URI注册保护尚在修复，不据此勾选S5b。

Mac原生凭据测试另失败，安全包装未保留内部cause；SSH交互限制是已观察线索，不把它直接认定为唯一原因，已请求用户确认本机登录/钥匙串状态。当前完整shared/Android单测正在运行，仅单测使用既有测试资源适配；R8/release仍禁止该适配。全量data测试包含先前规模用例，会再次运行约15分钟，属于本次完整回归且已启动，不额外复制另一份规模试验。以上修复与验证仍只闭合C10/C11/C17/C18，不增产品功能。

限定复核收口：Mac `sync-s5b-profile-uri-red` 3项/1失败（expected0/actual1，16秒）→`sync-s5b-profile-uri-green` 3项零失败/错误（9秒），root实际读取回传XML确认。显式profile已跳过系统URI注册，普通启动保持原行为；manifest中受新增guard影响的ID81 Main evidence同步625→627。独立复核PASS，双目录实际启动契约为明确边界；真实Keychain、GitHub、Android release仍未通过。

2026-09-16 最新验证（替代上文“正在运行”等历史状态）：`sync-s5b-shared-android-full` 已结束，共享domain JVM471/Android406、data JVM357/Android225全部通过，合计1459项；其中包含1万/10万规模用例。test-desktop 52项通过，presentation-sync Android目标无测试，不计覆盖。app 409项中16项失败，已读取全部失败后修复9个测试类的Voyager宿主生命周期与JDBC驱动注册；`sync-s5b-android-fixtures-focused` 29项零失败/错误/跳过、10个Kotlin输入格式通过，正在执行app完整复验。仅JVM单测使用既有许可证资源适配，不作为R8或ART通过证据。

Desktop第二次完整回归3067项中仅1项失败，原因是新增默认DI测试复用了先前已移除的Preferences节点；改用独立Injekt容器并清理真实服务。`sync-s5b-profile-di-focused` 的7项Desktop接线、14项共享UI及1项finalParityAudit全部通过。Windows正式脚本首次调用在测试启动前因GRADLE_OPTS中未保护的竖线被cmd解析而退出255，只分配版本34，未生成产物；这是命令准备失败，后续移除多余nonProxyHosts参数再执行原脚本，不降低验证门槛。

Android应用层完整复验 `sync-s5b-android-app-final` 已通过：409项零失败/错误/跳过，1分39秒。Windows已用原正式脚本重新开始 `sync-s5b-windows-release-fixed`，包含完整集成测试；前次仅分配未产出的BUILD34由本任务恢复后重新分配，避免同一候选无意义递增。没有改主工作树或用户版本。S5b保持未勾选。

Android构建阻塞的有限追加方案（待用户确认，未实施）：核对[AboutLibraries 14.0.0发布说明](https://github.com/mikepenz/AboutLibraries/releases/tag/14.0.0)及固定tag源码，旧13.2.1的`lenientConfiguration.allModuleDependencies.flatMap { it.allModuleArtifacts }`在14.0.0已改为`incoming.artifactView { it.lenient(true) }`，存在保留官方生成流程的候选修复路径。拟仅将构建插件版本与运行库版本分开、插件升级14.0.0，补充已验证的FlexibleAdapter单库许可证资料，再检查当前所有依赖及Tink完整性；不升级应用运行库，不伪造原始POM或整份复用旧JSON。新插件还变更了Android任务接入，实际兼容性尚未证明。预计30–60分钟，复用原Android代理，无新代理/新过程报告；增加定向生成/解析测试和一次限定审查，通过后恢复原定R8/ART验收。因超出原流程审查预算已发出确认，等待不是批准。

阻塞复核（2026-09-16 03:03 UTC）：实际Windows正式EXE进程18680仍存在，原生同步状态为SIGN_IN/setupBusy，尚未取得授权；Mac只读`security show-keychain-info`再次返回`User interaction is not allowed`，该线索仍不等于已证明唯一故障原因。未收到GitHub授权完成、Mac钥匙串状态或Android追加流程的用户回复。相同外部条件已连续三个goal轮次保留，独立可执行的Windows及回归工作已完成，故goal标记blocked，S5b仍未勾选且未提交候选。恢复时先核对现有实例/授权是否有效；若验证码过期才重新发起，不循环生成验证码，不重复全量测试。当前完整目标与C1–C18保持不变。

恢复执行（2026-09-16）：用户确认Mac已解锁，并明确批准Android许可证构建修复。流程增量：复用原两个平台代理，无新代理/报告；Android仅插件版本与单库metadata、真实生成/解析回归及一次root限定审查，必要失败定向复验，随后恢复R8/ART；Mac仅现有失败focused→通过才完整构建，root继续GitHub联调和本机Gradle串行。整体预计45–90分钟；不扩服务商/字段/UI，也不升级应用运行库。当时goal工具记录仍为blocked且用量停在10,264,466；后续工具已恢复active与累计计数，最终以实际get_goal采样报告，不将冻结窗口虚构为完整用量。

GitHub旧码已由production状态确认EXPIRED，再从同一正式EXE取得新码；用户完成授权后，实际状态已到REPOSITORY、setupBusy=false、authFailure=null，真实授权门已通过。当前可用私库数量0，已引导用户将GitHub App安装到专用私有仓库并提供仓库选择；未凭登录成功宣称交换成功，任何设备码/令牌不写本文。Mac同步18个明确Desktop候选文件且逐项SHA核对后，`sync-s5b-macos-unlocked-focused` 11项中1失败：原生Keychain roundtrip失败，另外profile3项和真实TestMode接线4项通过。用户解锁说明未自动解除SSH上下文失败，正做随机合成账户的限定阶段诊断，未启动失败候选的完整构建。

用户明确要求代为准备专用私库后，已核对当前GitHub CLI账号及仓库名不存在，再创建private、带README初始提交的`mihon-sync`，只读复查private=true、默认main与可写权限。该仓库仍需用户在GitHub App安装页选择授权，不把CLI建库当作Mihon授权或同步交换证据。Mac系统Security.framework真实返回默认login钥匙串statusFlags=2（read位有值，unlock/write位未设），合成账户find退出44、save退出36，因此已定位当前写入被锁定钥匙串拒绝；用户需本机解锁login钥匙串，未请求或获取密码，未更改默认钥匙串/自动锁定设置。

真实服务恢复进展：用户已将App授权到专用私库。两个正式Windows EXE使用独立profile，经原生界面分别创建与加入同一空间；授权、恢复文件保存/导入及首次合并均完成，重启后connected仍为true，私库已出现mihon-sync-v1分支。空交换SUCCESS只算建链，不冒充非空同步；后续仅在两个隔离测试库放入合成漫画元数据，以原生详情收藏动作产生真实outbox，未直接写入同步事件或凭据。Mac用户解锁已生效：GUI与SSH为不同安全会话，实际测试worker和daemon均核对为GUI asid，focused11/0、完整3067/0/8跳过通过；不要求用户重复解锁，不改变默认钥匙串或其安全策略。

GitHub非空真实验收：两个正式EXE各自独立授权/profile，合成漫画仅以SQL准备既有业务元数据，后续取消/收藏均通过原生详情production调用生成事件并经真实GitHub加密交换。两端并发触发后各自发布集合保留，最终两库均7条事件/2个actor，outbox全部PUBLISHED；没有以此推断强制同HEAD碰撞已发生，该确定性边界已有HTTP契约。远端取消在接收端产生1项决定时，本机2条独立操作仍上传成功；用户选择KEEP_LOCAL后再次双向交换，发起端漫画仍favorite=false，接收端true，事件总数仍7且待处理0，无反向回声。证据保存在ignored运行目录github-exchange-evidence.json，不含授权或恢复材料。

真实发布运行另发现待上传计数查询未排除PUBLISHED：界面仍显示3/4，而实际outbox均已发布。既有panel真实exchange契约新增断言，sync-s5b-published-count-red-proxy为1/1 expected0 actual1；仅getPendingCategoryCounts增加status过滤，不改schema/协议。sync-s5b-count-green-license-app两目标panel各15项全部通过，Mac原代理限定只读复核PASS；最终桌面产物需纳入该修复后重新生成，旧Windows34/Mac35不作为修复后的交付。


S5b最终候选修复验证（2026-09-16）：macOS v37已用规定脚本生成正式App，完整3067项零失败、8项既有条件跳过；真实GUI安全会话的两个App进程完成Keychain写入/读取与正常退出，root已读取runtime-final-result.json核验。Windows计数修复后的完整回归3067项仅1失败：许可证配置使manifest ID95 CURRENT_ANDROID的implementation(projects.domain)证据224→230；只更新这一行引用，全部当前角色锚点只读核验无其他失效。其余3066项原结果保留，定向补验契约后以规定脚本build-only生成，避免无业务变化的重复全量。
