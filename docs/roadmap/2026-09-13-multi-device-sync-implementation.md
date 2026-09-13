# 多设备同步实施计划

状态：IN_PROGRESS；S1 已通过独立复验，当前执行单元 S2。完整目标是按已审核技术方案与最终 DEMO 在 Android / Windows / macOS 实现真实同步，完成共享协议不代表产品完成。

## 目标、权威与授权

- 技术权威：[已审核技术方案](../2026-09-13-multi-device-sync-technical-proposal.md)。后续确认条款优先于其中旧 PAT、三行状态、人工冲突匹配等描述。
- UI 权威：[DEMO](../prototypes/multi-device-sync/README.md)及其当前源码：书架顶栏 → 同步底部面板 → 同一面板的设置/记录子页。立即同步在状态栏右侧；定期启用显示省略零单位的倒计时；空列表为“当前没有待确认的操作”；记录页没有解释性引导行。
- 主模型规划与独立验收；实现模型 GPT-5.6 Luna，xhigh。一个实现代理连续承担相同上下文，不同时写相同文件。
- 工作区：`D:/Shell/Github/mihon-sync`，分支 `codex/multi-device-sync`，基线 `b686564d3`。提交仅包含本任务文件，不自动发布远端或覆盖正在使用的数据。
- 现有 Use Case / SQLDelight / NetworkHelper / Injekt / Voyager / AdaptiveSheet / WorkManager / Desktop runtime 为接入权威。现有业务表不是操作日志，需新增同步表；平台差异限制在 adapter，不复制两套规则。

## 并行开发与共享文件边界

Android 扩展任务在 `D:/Shell/Github/mihon` 的 `main` 上开发；本任务不改该工作树。当前已观察到它修改 domain/build.gradle.kts、Source API、扩展 repository、AndroidManifest、备份和数据库迁移 18.sqm，后续还可能修改漫画/章节 memo 映射。

当前环境没有 list_threads/read_thread/send_message_to_thread 工具，不能声称已取得对方确认。先通过独立 worktree 隔离；合并前必须复查 main 最新提交与未提交范围，顺序整合共享接口。S1 只新增同步 domain 包及其共享测试，不修改扩展相关文件或构建依赖。持久化批次开始前重新核对迁移编号；不得同时占用 18，也不能用空迁移占位掩盖顺序问题。数据库 migration、Manga/Chapter 映射、BackupRestorer、Manifest、DI、依赖目录属于最终整合门槛 C18。

Gradle 由当前工作区一个协调者串行运行；不清理其他工作区的 Java/Gradle 进程。按本机资源限制 workers，网络使用会话代理 127.0.0.1:10808，本地请求 bypass；最多一次网络重试，不永久修改系统设置。Python 与文本使用 UTF-8。

## 实现单元与文件所有权

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
| C5 | 重读早页、不同章节阅读、同时间候选、坏页码提示、正在阅读时接收新位置 | 因果后继允许页码回退；并发按可用时间+稳定 ID 后备选续读、保留其他历史；不篡改当前会话页/模式，不使用永久最大页码 | S1 policy passed；实际阅读器定位/提示仍在 S3/S4 |
| C6 | 非法类别/效果组合、未知版本/代次/空间、超限字段/批次、损坏一个效果 | 信封整体拒绝或隔离，不部分接受坏信封；单批至多 256 事件与 512 KiB 明文；不泄露凭据；共享失败契约 | S1 passed |
| C7 | 所有实际收藏/批量/浏览、作者关注、章节已读未读、阅读提交；事务失败与无痕 | 业务和 outbox 原子提交，重复幂等阅读不重复计数；metadata/迁移/恢复/REMOTE 不伪造 USER；无痕不上传也不追补。真实 repository + SQLite 故障注入测试 | pending |
| C8 | 重启、同 ID 异内容、收件/应用崩溃、缺源后恢复、同步时新增本机动作 | inbox/outbox/游标/决定持久化，成功只清发布集合，待确认不阻塞其他数据；来源恢复自动重试，无强制匹配或自动安装扩展 | pending |
| C9 | 同一 HEAD 竞争、未知发布结果、空库竞争、分页/截断、403/429/500/畸形响应 | Git DB REST force=false，有界重试且保留其他设备文件；确认有效 ref 内容后才清队列；固定密文字节重试；MockWebServer 完整解析链 + 真实私库竞争 | pending |
| C10 | Android/Windows 加密互读、错误密钥/AAD、历史回退、凭据后端故障 | AEAD 绑定空间/代次/协议/批次；密钥与授权分离、系统安全存储不可用不降明文；最低 Android/R8 与真实 Desktop runtime | pending |
| C11 | 首次 GitHub 设备登录、授权取消/过期/限流/重新连接、选择新/已有专库 | 系统浏览器设备码授权，无 PAT 输入；自动接收结果；恢复资料确认与首次合并；设备名称、换空间/断开隔离。真实服务配置与运行证据 | pending |
| C12 | 手动、启动、15分/1时/6时/24时周期同时触发、离线/休眠恢复 | 共享 coordinator 串行且合并请求；UI 异步；各设备设置独立；Desktop 退出不运行，Android WorkManager 尽力；保留队列、失败可重试 | pending |
| C13 | 双端从书架进入/关闭同步，进入设置返回，后台完成，长倒计时 | UI 对齐最终 DEMO：三种顶栏表现可组合忙与数量，99+，设置齿轮子页、状态右侧立即同步、瞬时通知不重放；真实 Compose/导航/DI 与视觉验收 | pending |
| C14 | 120/1万待确认、长按/范围/全选/反选/全部处理、执行中新增/失效/关闭 | 条目直接上方吸附操作条，LazyColumn/分页、冻结版本、汇总确认、有界分段事务、实际完成/跳过/失败反馈、恢复不重复应用 | pending |
| C15 | 空设备、已有书架、旧备份、克隆 actor、首次导入中继续操作、本机清除历史 | 只合并基线且不复活明确取消；水位后操作不被导入覆盖；新 epoch；本机历史屏蔽持久化、真实新阅读可重新显示，不上传全局删除 | pending |
| C16 | 1万/10万事件、3/10设备、120/1万待确认 | 记录首次与增量时间、请求量、内存、仓库增长、交互响应，暴露真实上限；保留完整历史，不自动 force push/删 tombstone | pending |
| C17 | 全部产品链路和构建 | 相关共享 Android/JVM 契约、完整 Android/Desktop 测试、Test Mode、Android 发布/R8、Windows 构建脚本正式 EXE 与 macOS 发布验收；未运行不算通过 | pending |
| C18 | 整合扩展任务后的最新仓库 | 逐项核对共享 schema、migration、备份、Source API、DI、依赖，冲突已解决且两功能回归；主工作区用户修改未被回滚，提交/正式产物可追溯 | pending |

## S1 七行 GOAL

结果：实现 Android 与 Desktop 共用的多设备同步协议与确定性操作归并核心，为真实生产同步提供唯一规则。
证据与上下文：D:/Shell/Github/mihon-sync/docs/roadmap/2026-09-13-multi-device-sync-implementation.md 的 S1 与 C1-C6；技术方案后续确认覆盖旧人工冲突规则，HTML 仅是交互基准。
范围：仅新增 domain/src/commonMain/kotlin/mihon/domain/sync 与 domain/src/commonTest/kotlin/mihon/domain/sync 中的协议模型、codec、校验、因果归并、接收端决定及共享契约测试；不实现 UI、HTTP、数据库或修改依赖。
约束与授权：用户授权此实现及本人创建独立持久 goal；先核实子 goal 隔离；使用 UTF-8、apply_patch、红绿重构和 gradle-coordinator；不得修改主工作区、扩展文件、现有测试、计划、提交、发布或启动下级代理；网络采用本机代理与一次重试上限。
完成标准：(C1) 稳定身份和白名单多效果信封真实编解码；(C2) 三端乱序重复与因果完整性安全归并；(C3) 并发保留收藏/关注及明确未读优先、基线低于用户操作；(C4) 仅接收端分别确认有效取消且不反向生成事件；(C5) 重读早页和确定性续读候选保留历史及本地会话；(C6) 坏信封与协议/大小边界整体隔离；共享 focused tests 红绿证据与格式检查通过，不宣称生产接入完成。
正当阻塞项：不可替代的构建环境或依赖不可用需报告真实命令和错误；子 goal 隔离不可用只报告流程缺口并继续已授权实现，不操作父目标。
最终交付：按 C1-C6 提交源码路径、红绿命令与退出码、未验证边界以及 status/diff/tests/commit/process/next 回执，交由主模型独立验收；不自行提交。

## 过程与证据

- 初始核对：主工作区另有扩展改动，隔离 worktree 已建立；尚未实施生产功能或运行验收。
- S1 的 Gradle focused 命令：`python scripts/gradle-coordinator.py run --key sync-s1-green -- gradlew.bat '-Dhttp.proxyHost=127.0.0.1' '-Dhttp.proxyPort=10808' '-Dhttps.proxyHost=127.0.0.1' '-Dhttps.proxyPort=10808' :domain:jvmTest --tests "mihon.domain.sync.*" :domain:testReleaseUnitTest --tests "mihon.domain.sync.*" --max-workers=2`。PowerShell 必须引用完整的 `-D` 参数，避免被拆为两个参数；Gradle JVM 不自动采用 HTTP_PROXY。仍在运行时只查询同一协调器，不重复启动。
- 2026-09-13 恢复核对：`sync-s1` 于 13:36:52 UTC 终态 FAILED、exit 1，新增 SyncProtocolContractTest 引用的 production Sync* 尚未存在。保留此日志，不重复启动已结束的 RED。初次依赖配置直连等待、一次参数被 PowerShell 拆分的启动均只算环境失败，不算业务 RED；两次旧进程树已按协调器身份停止。当前继续从现有测试实现并完成可观察行为验证，不能把缺接口编译失败当已通过的业务测试。
- 主工作区最新文档提交 `53f96e679` 更新了扩展任务的恢复边界；扩展 production 仍未提交。该计划记录暂停不替代代理/进程实际状态，本任务继续保留全部外部差异和迁移 18 边界。
- 格式：限制于本次新增包的 Spotless 检查，不顺手格式化其他文件。阶段全量与正式构建由主模型统一安排。
- 外部配置边界：当前源码未发现专用 GitHub App client ID；实际授权与私库竞争验收仍需真实 App/仓库，不能以模拟页或 MockWebServer 宣称完成。
- 用户已回复没有现成 GitHub App。授权模块就绪后提供一次性注册步骤；此前继续协议、持久化和平台接入，不让终端用户手填 PAT。
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
