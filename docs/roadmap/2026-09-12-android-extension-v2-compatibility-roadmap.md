# Android 新版扩展系统完整兼容 Roadmap

- 日期：2026-09-12
- 状态：**COMPLETE（2026-09-16 收紧范围）；AEX-00 至 AEX-06 必需出口已验证，产品提交 `b3d81b34dc`；最终 rc9 ARM 业务与 Windows GUI 缺口已补齐。MangaPlus live 未验证成功，保留单站限制。**
- 父计划：[Android / macOS / Windows 正式 Roadmap](./2026-06-30-mihon-desktop-refactor-roadmap.md)
- 专项代码基线：`6d6263dcfeffdfbd5f810a54fa7a3d24c0a6ba50`
- 协议对齐基线：Mihon `v0.20.4`，实际 commit `df6507256acce8e7f3660783a3db6dbd1a31b6b5`

2026-09-12 首次审阅调整（历史记录）：解除早期组件测试对 AEX-04 安装/版本放行的反向依赖；AEX-03 拆为查询与更新数据闭环两个行为批次；AEX-00 增加可执行测试矩阵的强制关闭门槛。当时仅规划；后续已按用户授权开始 AEX-00，当前进度以下文实施证据为准。

本文件是本专项唯一计划与进度记录。实施进度仅从第 5 节第一个未勾选的批次推导；不增加 `active-task`。用户于 2026-09-15 明确要求主模型完成全部剩余工作，停止使用 Luna 技能，并授权按成本收益选择子代理。执行规则沿用 2026-09-15 接续约定，但下述 2026-09-16 收紧边界优先；此前的技能、等待批准和暂停描述保留为历史记录。规划、开始执行和子代理自测均不等于兼容性完成证据。

2026-09-16 用户要求收紧并交接。最新执行边界以本段及第 7 节剩余出口为准；历史日志中的“待批准、手机已拔出、Mac 隔离尚未实现”等不是当前状态。后续 agents 从[收紧交接文档](./2026-09-16-aex06-scoped-handoff.md)恢复工作，它是入口与证据索引，不是第二份进度权威。停止自动扩大站点诊断、一般网络排障和测试基础设施；不重新启用 Luna。

2026-09-13 执行复盘调整：保留全部产品范围、8 个交付批次及 C1–C11 门槛；区分交付批次与批内行为循环，细化 AEX-02 的依赖和恢复入口。原“每个 B 组红/绿/重构各一次”不再作为执行预算。用户本轮只要求修改 roadmap 并交接 Luna 技能改进，不恢复实施、不授予 TDD 例外。技能改进需求另见[交接清单](./2026-09-13-luna-workflow-improvement-handoff.md)，它不是本专项第二份进度计划，也不表示技能已修改或效果已验证。

## 1. 目标与完成定义

让本仓库 `app/` 产出的 Android 应用完整支持上述稳定版定义的新版扩展仓库协议和 extension-lib 1.6，并继续支持现有已承诺的旧扩展能力。Android 保持原版 Mihon 产品链路；不把 Desktop 应用或 JAR 运行时移植到手机。

“完整支持”必须同时满足：

1. 原有仓库能迁移；旧 JSON 入口、新版索引地址和添加仓库入口可用；仓库列表、扩展列表、更新与失效状态准确。
2. APK 能安全下载、校验、安装、更新、信任、卸载、重启后重新加载；系统安装与私有安装路径均有证据。
3. 1.6 扩展在真实 Android 运行时中可调用，包含只实现新版 `Source`、不继承 `CatalogueSource` 的扩展；不能以“列表出现”或“类加载成功”代替业务验收。
4. 浏览、搜索、全局搜索、详情刷新、书架更新、元数据更新、章节同步、阅读、下载均消费正确的 Source API；扩展依赖的漫画和章节 `memo` 可持久化、恢复并再次传回扩展。
5. 旧书架、source ID、阅读进度、下载、源设置及已有信任状态不被错误重置；失败不会把全部已安装扩展误标为已废弃。
6. 共享协议和 Source API 修改后，Desktop 的 JAR 优先、APK 兼容、Authors、FlareSolverr、阅读器与现有数据能力不回退。
7. 有经过 R8 的真实 Android 发布产物、目标设备业务验收和对应提交证据；不是仅有 JVM 单测或 debug APK。

完整兼容是对冻结协议和实际产品调用链的承诺，不保证所有网站持续在线、所有第三方扩展没有自身 bug，也不保证绕过所有 Cloudflare 挑战。未来 extension-lib 版本不自动纳入本专项。

### 明确不做

- 不整体合并上游 `main`，不顺带新增捐赠界面、遥测或自动更新行为；保持当前构建开关和用户选择。必要依赖升级必须说明 ABI 或安全原因。
- 不重做 Reader、下载器、书架 UI、备份引擎或全部非 Reader 共享核心；仅补齐本协议必要的接入、数据字段和迁移。
- 不把所有 Keiyoushi 扩展逐站联网测试设为强制门槛；离线契约加代表性真实扩展验收，不能反向宣称全站兼容。
- 不自动信任所有扩展、不绕过签名检查、不批量卸载用户已有扩展或应用。
- 本次规划不承诺使用官方签名覆盖用户已安装的官方 Mihon；签名不同时按独立测试安装或备份迁移方案验证。

## 2. 已确认缺口与证据边界

以下是专项基线的源码事实，不是设备复现结论。用户手机的 Mihon 版本、扩展版本、签名和日志尚未取得。

| 层次 | 已确认事实 | 需要补齐的行为 |
| --- | --- | --- |
| Android 仓库获取 | `app/.../extension/api/ExtensionApi.kt` 的生产入口仍请求 `index.min.json` | 通过共享 store/catalog 链路发现并读取 v2，不在 Android 再写一套解码器 |
| 仓库地址与持久化 | `CreateExtensionRepo` 主要归一化旧地址；`ExtensionRepoDto` 虽读取 `index_v2`，转入领域模型时没有保留；仓库模型无新版索引/远程列表地址 | 支持旧入口、新索引、持久化、去重、迁移和重启刷新 |
| Android 扩展加载 | `ExtensionLoader` 版本范围为 1.4–1.5，依赖旧 metadata 和 versionName 推导 | 读取 `tachiyomix.*`，按显式版本与兼容策略加载 1.6，保持旧 metadata 兼容 |
| Desktop catalog 可复用基础 | 已有 v2、签名检查和 JAR 优先；但独立解码器读取 `extensionLib` 后未用于共享兼容判断，仅覆盖内联列表等部分形式 | 对照上游校正后共享，不能将现有 Desktop 实现视为完整规范 |
| 内容分级和语言映射 | Desktop v2 的 `contentWarning > 2` 会漏掉 MIXED；语言通过文件名推导 | v2 目录与 APK metadata 分别按各自语义解析；语言以源声明为依据 |
| 共享 Source API | `source-api` 尚无 `SMangaUpdate/getMangaUpdate`；旧浏览接口集中在 `CatalogueSource` | 完整核对 1.6 二进制契约、旧接口桥接及 HTTP Source 行为，不只增加一个方法 |
| Android 真实调用方 | source manager、浏览和搜索仍存在 `CatalogueSource` 类型门槛；详情/书架/元数据更新仍直接走旧方法 | 接通新版 Source 查询与合并更新，同时保护本地源及旧扩展 |
| 状态数据 | 当前 source/domain/data/backup 链路未完整承载新版漫画和章节 `memo` | 端到端映射、数据库迁移、备份恢复、重启与重复更新保真 |
| 验证 | 已有共享 catalog、Android 安装生命周期/安全回滚及 Desktop 真实加载测试 | 扩充真实 1.6 二进制、Android ART、UI wiring 和发布压缩后的业务验收 |

源码入口：

- [Android ExtensionApi](../../app/src/main/java/eu/kanade/tachiyomi/extension/api/ExtensionApi.kt)、[ExtensionLoader](../../app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt)、[ExtensionInstaller](../../app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionInstaller.kt)。
- [仓库模型](../../domain/src/commonMain/kotlin/mihon/domain/extensionrepo/model/ExtensionRepo.kt)、[仓库 DTO](../../domain/src/commonMain/kotlin/mihon/domain/extensionrepo/service/ExtensionRepoDto.kt)、[添加仓库](../../domain/src/commonMain/kotlin/mihon/domain/extensionrepo/interactor/CreateExtensionRepo.kt)、[扩展 artifact 模型](../../domain/src/commonMain/kotlin/mihon/domain/extension/model/ExtensionArtifact.kt)。
- [共享 Source](../../source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/Source.kt)、[旧 CatalogueSource](../../source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/CatalogueSource.kt)、[远端漫画用例](../../domain/src/androidMain/kotlin/tachiyomi/domain/source/interactor/GetRemoteManga.kt)。
- [Desktop artifact 架构](../architecture/desktop-extension-artifacts.md)、[扩展历史权威基线](./source-extension-authority-baseline.md)。

历史报告中的“1.6 catalog 支持”和大批量 JAR 加载记录，不能证明当前 Android 或 Desktop 已通过全部 1.6 业务调用。现有 parity manifest 的 VERIFIED 项也不是本专项新增兼容面的证明。

### 上游协议权威

- [v0.20.4 稳定版](https://github.com/mihonapp/mihon/releases/tag/v0.20.4)。
- [冻结的 ExtensionStoreService](https://github.com/mihonapp/mihon/blob/df6507256acce8e7f3660783a3db6dbd1a31b6b5/data/src/main/java/mihon/data/extension/service/ExtensionStoreService.kt)：入口识别、旧仓库发现、JSON/protobuf、gzip 与扩展列表获取。
- [冻结的 NetworkExtensionStore](https://github.com/mihonapp/mihon/blob/df6507256acce8e7f3660783a3db6dbd1a31b6b5/data/src/main/java/mihon/data/extension/model/NetworkExtensionStore.kt)：显式 extensionLib、内容分级、内联及远程扩展列表。
- [冻结的 Android ExtensionLoader](https://github.com/mihonapp/mihon/blob/df6507256acce8e7f3660783a3db6dbd1a31b6b5/app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt)：新旧 metadata、版本及信任判断。
- [冻结的 Source API](https://github.com/mihonapp/mihon/tree/df6507256acce8e7f3660783a3db6dbd1a31b6b5/source-api/src/main/kotlin/eu/kanade/tachiyomi/source) 和 [UpdateMangaFromRemote](https://github.com/mihonapp/mihon/blob/df6507256acce8e7f3660783a3db6dbd1a31b6b5/app/src/main/java/mihon/domain/source/interactor/UpdateMangaFromRemote.kt)：接口、模型、flags、memo 和更新调用语义。
- [Keiyoushi 旧目录移除公告](https://github.com/keiyoushi/extensions-source/issues/18072)：触发本专项的仓库迁移背景。

本专项固定 tag 对应的实际 commit，而非 annotated tag object，也不把本地 `upstream/main` 当作互联网最新版本。历史原版基线 `6fbf6dfca203d99d6dd32137f2df97ced40c81b8` 继续用于既有项目溯源；不全局替换其他计划和 manifest 的 authority。

## 3. 架构与必须先冻结的决策

### 3.1 复用与唯一权威

| 能力 | 复用与调整方式 | 不共享的部分 |
| --- | --- | --- |
| 仓库获取和解析 | 复用 extensionrepo 服务与领域入口；在现有共享模块承载 v1/v2 decoder、映射和刷新策略，Android/Desktop 都走生产 wiring | 平台网络注入、文件/URI 权限 |
| 版本、更新、信任和列表状态 | 复用 ExtensionArtifact、共享 catalog/install/presentation 契约；显式保存协议字段，不再由展示版本猜协议版本 | Android 包管理器状态、Desktop 文件与类加载结果 |
| 安装生命周期 | 复用 ExtensionInstallCoordinator 和现有 AndroidInstallPort（位于 ExtensionInstaller 内） | Android APK 安装/ART；Desktop JAR 优先、APK 转换及 ClassLoader |
| Source ABI 与远端更新 | 复用 source-api、既有同步和远端查询用例；共享协议规则及测试 | 调度、平台上下文、挑战处理 adapter |
| 数据与备份 | 扩展既有 SQLDelight schema、映射、备份模型及恢复流程 | Android URI 与 Desktop 文件选择 |
| UI | 复用仓库页、扩展页、信任对话框、源浏览、详情及现有状态模型 | 保持平台原生导航与呈现，不共享 Desktop UI/DI |

不新建第二套 Android v2 parser、数据库镜像或远端更新引擎。迁移后的旧入口只能作为兼容 adapter；重复业务判断必须在所属批次内删除。Desktop 的 JAR 扩展字段作为平台资源选择保留，不能因对齐仅有 APK 的上游模型而丢失。

### 3.2 仓库与安全语义

- 输入矩阵覆盖旧根地址、`repo.json`、`index.min.json`、显式新版索引地址和现有添加仓库 deeplink。新 schema 可解析的 JSON/protobuf、gzip/非 gzip、内联/远程列表均以冻结规范与真实 fixture 为准。
- 仓库持久化保存稳定身份与实际索引定位信息；旧数据迁移幂等。刷新后的元数据、索引地址变化、重复添加和删除必须仍对应同一仓库，不生成两个安装/更新来源。
- 成功的权威目录确实移除扩展，才可据此判定 obsolete。超时、限流、畸形响应、签名不符或单仓库失败不是“全仓下架”；保留上次成功数据并显示刷新失败。
- 保持仓库 fingerprint 连续性、索引 signingKey 与已有信任关系，以及 artifact 按协议提供的签名/完整性校验。不能将未知签名、缺失字段或失败校验当作可信。
- 已声明 v2 后发生解析或认证失败，不悄悄降级到旧目录掩盖错误。无新版声明的合法旧仓库继续支持。
- 远程列表、重定向、取消和网络异常沿用 production 客户端与安全边界；限制响应大小、列表递归/重定向与重复请求，避免无界下载。fixture 必须覆盖对应限制。

### 3.3 版本、metadata 与二进制兼容

- 优先使用显式 `extensionLib` 和 `tachiyomix.extensionLib`；旧 metadata 缺失时才按旧规范回退。versionCode、versionName、协议版本是不同概念，不可互相替代。
- 上游冻结版显式支持集合是 1.4、1.6，不是任意 `1.4..1.6` 连续版本。该 fork 当前接受旧 1.5，须单独记录为历史兼容分支并用真实 fixture 保留；如发现实际不兼容，先报告影响并取得变更决定，不静默删除既有支持。
- AEX-00 的有界调查未找到可核验的第三方历史 1.5 发布 APK；用户在 2026-09-12 明确批准以官方 Mihon `v0.19.4` 固定旧 Source API 独立编译的受控扩展替代该输入。受控样本可以使用正常签名的固定 JAR/APK，作为 1.5 API/加载兼容证据；它不冒称为第三方 1.5 发布物，历史发布 provenance 仍单独记为未找到。
- 覆盖 `tachiyomix.name`、协议版本和内容分级；新旧字段冲突按冻结规范处理。未知未来协议版本显示不兼容，不伪装成已废弃。
- v2 目录枚举中 MIXED=2、NSFW=3 均按上游进入受限内容判断；APK metadata 的编码/阈值单独验证，不能把两者强行共用同一个数字比较。
- Source ABI 审计覆盖冻结 source-api 的公开成员、默认实现、Rx/协程桥接、模型、HttpSource、依赖类签名与压缩保留规则。不能只让本仓库重新编译通过，必须加载用外部冻结 API 编译的二进制。
- AEX-01 建立 ABI 与兼容桥，AEX-03A/03B 接通查询和更新数据链；Android 1.6 安装/加载版本准入仍在 AEX-04 放开。前面批次只承诺各自的生产组件契约通过，不要求尚未放行的 APK 经完整 ExtensionLoader 成功加载。
- APK metadata、版本准入、签名/信任、安装、ExtensionLoader 注册 SourceManager 及完整业务串联的首次集成验收归 AEX-04。不能修改 fixture 的真实版本号、伪造信任、增加只供测试的放行后门或绕过安全检查，让 AEX-01/03 提前宣称整链成功。

### 3.4 更新、memo 与数据保全

- 新版统一更新必须传入正确的既有章节及 fetchDetails/fetchChapters flags；按冻结规范处理返回值，复用本仓库已有同步、去重、进度和下载保留逻辑。
- 漫画/章节 memo 按 JSON 数据保真传递，不重新解释第三方内容；覆盖新增、更新、空值、重启、备份恢复及再次调用。不把 memo 或其中潜在凭据写入普通日志。
- 新字段由现有共享 SQLDelight schema 与备份格式演进承载；保留旧备份读取能力，明确新版备份回到旧应用可能损失新字段。禁止声称任意数据库降级可逆。
- 不因新版调用合并而覆盖用户自定义元数据、误清章节阅读状态、重排下载对应关系或改变既有 Source ID。

## 4. 与现有计划的交接

- [非 Reader 上游核心路线图](./2026-08-02-mihon-desktop-non-reader-upstream-core-roadmap.md) 保持 PAUSED；本专项只承接 `EX-01` 中 Android v2/1.6 所必需的共享协议、安装状态与真实调用切片，不代表 `EX-01`、`BR-01` 或整条非 Reader 主线完成。
- 不等待完整 BR-01 大重构才修复兼容性：AEX-00/AEX-03A 在现有查询边界中冻结本专项必需的 Source 接口与 adapter，AEX-03B 接通更新及数据链；后续 BR-01/EX-01 必须复用这些实现和 fixture，不能再维护另一套规则。
- [Reader adapter 重构计划](./2026-08-27-desktop-reader-upstream-semantics-adapter-refactor-roadmap.md) 不重新打开；仅回归其真实 source 输入和 reader/downloader 链路。发现无关 Reader 缺陷另行记录，不扩大本专项。
- 父计划已在本专项首批工作中将 `active-child-plan` 指向本计划；本专项的 Android 实际发布和设备验收属于必要门槛，不适用父计划早期“Android 发布延期不阻塞”的方针。
- `app-desktop/src/test/resources/parity/parity-manifest.json` 仍是 capability 机器权威。实施时仅针对实际受影响条目补充可复现证据；未验收项不得标为 VERIFIED，不批量改写历史状态。

## 5. 有序交付批次

默认串行执行；表中“初稿工作量”是按一名开发者、已有构建环境计算的历史人日粗估，不包含外部签名、设备、网络和 macOS 资源等待，也不是当前 agent 剩余墙钟承诺。原总计约 **10–19 个工程日** 保留作规划背景；不能将其直接换算成后续口头小时估算。恢复实施时先核对可复用证据与剩余行为，再给当前批次的墙钟范围、假设和下一可验收结果；范围变化必须说明原因。文件数/行数只作审查提示，不据此机械拆分不可独立验收的功能。

保留 7 个宏观阶段，实际有 8 个独立验收/提交批次：阶段 03 仅由 AEX-03A、AEX-03B 两行表示，不再设置另一条 AEX-03 勾选项。两批合计仍使用初稿的 2–4 日粗估，不因拆分宣称工作量减少；测试基础设施成本在 AEX-00 核实后重新评估。

| 进度 | 批次 | 可独立验证的结果 | 前置 | 初稿工作量（非剩余 ETA） |
| --- | --- | --- | --- | --- |
| [x] | AEX-00 协议与验收基线 | 固定 fixture、可执行测试矩阵和已验证的 runner | 本计划获准实施 | 0.5–1 日 |
| [x] | AEX-01 Source ABI 与旧版桥接 | 外部二进制执行真实 API/加载基础组件契约；不含 APK 版本准入整链 | AEX-00 | 2–4 日 |
| [x] | AEX-02 共享仓库协议与迁移 | Android/Desktop 使用同一正确 v2 catalog，旧地址和仓库数据可迁移 | AEX-00、AEX-01 | 2–3 日 |
| [x] | AEX-03A Source 查询与导航 | Source-only 的源发现、浏览、搜索及 UI wiring 可用 | AEX-01、AEX-02 | 0.5–1.5 日 |
| [x] | AEX-03B 统一更新与 memo | 更新、持久化、重启、备份恢复及阅读/下载数据传递闭环 | AEX-01、AEX-03A | 1.5–2.5 日 |
| [x] | AEX-04 Android 安装与整链集成 | 1.6 APK 经版本/信任准入、安装加载、源注册到业务流程全部通过；最终证据见文末 AEX-04 收口 | AEX-02、AEX-03A、AEX-03B | 1.5–3 日 |
| [x] | AEX-05 历史升级与故障验收 | 旧用户数据迁移、真实端到端与恢复路径通过 | AEX-04 | 1–2 日 |
| [x] | AEX-06 正式产物与跨平台收口 | R8 Android、Windows/macOS 发布及完整证据 | AEX-05 | 1–2 日 |

### 测试边界与依赖规则

- AEX-01 测真实 Source API、兼容桥及 production 使用的加载基础组件。Android 可执行受控外部二进制的 ART 组件测试；直接调用这些组件不等于已经验证 PackageManager、版本准入、信任或完整 ExtensionLoader。
- AEX-03A/03B 通过既有依赖注入/平台 port 提供受控 Source，执行真实 SourceManager、ScreenModel、用例、网络解析、数据库和备份实现。允许控制外部源响应，不允许 mock 被验收的 parser、更新用例、持久化或 UI wiring；这些批次不以成功安装 1.6 APK 为前提。
- AEX-04 用未改写 metadata 的签名 APK，通过正常安装/信任/加载入口注册源，再串联 AEX-03A/03B 的业务场景。前期组件测试和后期整链测试必须分别记录，不能互相替代。
- AEX-05 复用已通过的整链场景加入旧版本数据及故障恢复；AEX-06 在最终发布产物上回归。两者不是首次补做早期批次应有的业务测试，也不是每批都重跑全部 E2E。
- AEX-02 保持一个交付批次：地址识别、store 身份、信任连续性、持久化迁移与两端 catalog wiring 共同完成同一仓库能力，不先交付未被产品使用的 parser。B1–B4 是验收分组，不是各只能执行一次的 TDD 循环；批内顺序见第 9 节，不按测试类拆提交。

### 交付批次与批内执行粒度

- **交付批次**负责完整用户能力、一次集中独立审查及功能提交；批次完成勾选只在第 5 节这 8 行维护，第 7 节保留用户操作验收。**行为循环**负责一个可观察差异及其真实调用链，可以跨文件；不单独创建 goal、子代理、审查轮次、提交或进度文件。
- 一次交接完整批次背景与边界；同一代理按既定顺序连续执行，每个行为先确认正确 RED 再改对应 production。已通过的前置允许进入下一行为，无需逐测试等待主模型批准；未完成的前置不能被后续测试数量掩盖。
- 首次进入陌生测试层时，先读同模块已有测试、依赖和 driver 装配，复用 AEX-00 已验证 runner。只对新增或改变的装配做最小探针，不重复环境普查；测试装配/无关编译错误不计行为 RED，单纯目标 API 缺失须按实际契约判定，不额外制造无意义的运行时断言失败。
- 下面只冻结剩余批次的行为顺序和出口，准确 runner 沿用第 6 节并在该批执行交接时按实际文件更新；不得为对齐拟名新增空测试类。不提前为每个循环写长契约。

| 交付批次 | 批内行为顺序（每步有自己的必要红绿循环） | 前置出口与集成时机 |
| --- | --- | --- |
| AEX-02 | B1 协议与 HTTP → B2 身份/存储/备份 → B3 刷新状态与操作限制 → B4 既有 UI 整链 | 详见第 9 节；各组先完成当前语义，B4 补跨组端到端，不把全部 wiring 测试拖到最后 |
| AEX-03A | Source-only 可发现及能力投影 → 单源查询/分页/筛选/取消 → 全局搜索与结果导航 | 真实 manager 能提供源后才接查询；查询实际调用正确后接 UI，旧源/本地源/Authors 每步按影响回归 |
| AEX-03B | 更新 flags 与调用/合并语义 → memo 映射/真实迁移与重开 → 生产备份恢复 → UI/作业及阅读/下载再次消费 | 先稳定更新结果契约，再持久化；存储保真后验恢复与消费，每步保留旧进度/自定义字段/下载关联，最后闭合整链 |
| AEX-04 | metadata 与版本/信任决策 → 正常系统/私有安装及失败保全 → loader 注册源 → 查询/更新/阅读/下载/重启串联 | 前面组件批次全部验收后才放开 1.6；安全拒绝、正常权限确认和真实 ART 不能用组件注入代替 |
| AEX-05 | 固定旧数据/备份升级 → 部分失败及中断恢复 → 代表性真实站点有界验收 | 复用 AEX-04 场景，不重建 E2E 框架；只对新发现产品缺陷增加 RED，测试通过不要求人为制造失败 |
| AEX-06 | 最终 diff 分层全量/格式 → R8 APK 与平台正式构建 → 对同一产物做运行验收与交付 | 没有代码修改的发布验收不虚构 TDD；出现缺陷才定向修复并重验受影响产物，签名/设备/macOS 缺口明确阻塞所属门槛 |

### AEX-00：冻结协议、fixture 与验收环境

- 输入：本节固定 commit、现有权威基线、仓库/Source/安装生产调用点。
- 交付：将第 6 节测试归属表具体化为本文件内的可执行矩阵；逐项填写“行为 → 生产入口 → 现有/拟新增测试类与 source set → 所属批次 → 首个预期失败原因 → Gradle task/filter 或设备 runner → 通过标准”。确认支持集合、metadata 冲突规则、索引形式、Source API 差异和所需依赖 ABI，不只抄 release notes。
- fixture：取得固定 commit 对应的 Keiyoushi 索引样本、可合法保留的真实 1.4/1.6 APK 与 Desktop 对应 JAR；历史第三方 1.5 若无固定发布物必须保留缺失事实，同时使用用户批准的固定旧 API 受控扩展作为替代输入。记录来源、版本、包名、哈希、签名和许可。不能把可变 live URL 作为 CI 唯一输入，也不将 APK blob 无限制塞进仓库。
- 另备基于冻结外部 API 独立构建的受控扩展：Source-only、SourceFactory、多语言、分级、memo、getMangaUpdate flags、错误路径；包含对应 APK/JAR。AEX-00 已固定并正常签名 `v0.19.4` 外部 Source-only suspend-only JAR（仅作为 1.5 API/生产 loader 组件样本，未声称覆盖其余受控扩展类型），以及基于 `v0.20.4` 独立源码构建并正常签名的 v1.6 SourceFactory/multilingual/memo/flags/error JAR 与 metadata APK；后者另导出 `V16SourceAbiProbe`，通过静态 `Source` 接口调用新方法，供 AEX-01 产生真实 ABI RED。两组来源、编译 classpath、签名警告和再现边界分别见 `app-desktop/src/test/resources/extensions/real/aex00-external-v15-suspend-only.provenance.json` 与 `app-desktop/src/test/resources/extensions/real/aex00-external-v16-controlled-sample.provenance.json`。v1.6 受控 APK 的 `tachiyomix.contentWarning` 只作为 metadata 证据，样本 source 的 memo 字段不替代它。完整 APK 安装方式仍在 AEX-04/AEX-05 执行生产 loader。本批次只要求 fixture 来源/构建可核验、测试入口可运行，不要求当前尚不支持的 1.6 APK 已加载成功；不在 host 测试内重新定义接口来掩盖 ABI 差异。
- 确认 Android 最低/当前支持系统、代表性 ABI、现有 androidTest runner、SDK、可用设备/模拟器、release variant/R8/signing 配置和已有 app ID/versionCode。核对测试设备身份，不卸载或覆盖用户主实例。
- 输出基线只记录已测事实；当前手机版本未知不阻塞制定 fixture，但真实“从用户版本原地升级”须取得其版本及安装身份后验证。
- 验证：各必需测试层先运行一个与当前基线兼容的最小 production 用例，确认 runner、fixture 资源和报告真实工作；尚无测试配置的 source-api target 在此明确最小接入方式并验证运行，不只列出一个可能存在的 task。新增产品行为在所属实现批次进入 RED，本批次不通过修改产品行为使其提前变绿。
- 关闭条件：矩阵每行有唯一行为负责人、明确命令/runner 与可观察断言；必需 runner 探针实际发现并执行测试，非零执行数且必需用例未跳过；共享契约有 Android/Desktop 两个执行落点，设备测试与 JVM 测试分别记录。命令存在、编译成功、NO-SOURCE、全数 skipped 或只运行 DI 解析不算行为通过。不可替代 fixture/设备/签名若仍缺失，记录后续受阻门槛，不能把受影响的 runner/发布就绪项写成已验证。
- UI：本批次无新增 UI，只冻结后续入口与反馈矩阵。

### AEX-01：补齐共享 Source ABI 与兼容 adapter

- 范围：source-api、必要 core/network 依赖与平台 adapter；复用现有模块，不另建 Android/桌面接口副本。
- RED：通过真实 API/加载基础组件调用外部编译 1.6 fixture，暴露缺失方法/模型/桥接；分别覆盖 Source-only、HttpSource、SourceFactory、Rx 旧扩展及模型。首个失败必须是本批次拥有的 ABI/行为差异，不能用完整 ExtensionLoader 的“版本不支持”充当 ABI 的 RED。
- GREEN：按冻结 API 补齐 Source 查询、SMangaUpdate/getMangaUpdate、模型 memo、默认实现和旧接口桥接；核对共享/平台 API 可见性和扩展实际调用的依赖符号。只升级必要依赖。
- 重构：将新旧分派收敛到一处；为不可删除的旧 ABI adapter 留下原因和测试。查询调用方的临时桥在 AEX-03A 删除，更新调用方的临时桥在 AEX-03B 删除；真正面向旧扩展的兼容桥保留。
- 验证：共享契约在 Android/Desktop 对应 target 执行；外部二进制经过实际 API/加载基础组件并调用 Source 方法，Android ART 组件结果与 Desktop 结果分别记录。完整 ExtensionLoader 对真实 1.6 APK 的拒绝仍属当前预期；其成功路径、metadata 与信任链在 AEX-04 才成为关闭门槛。
- 关闭条件：接口、旧版桥接与加载基础组件契约通过，生产调用的组件与被测组件一致，无仅测试可用的替代实现；不宣称 APK 安装、版本放行或完整阅读链已经完成。
- UI：无独立新入口；保护既有源列表与错误反馈，不改变 Desktop Authors 扩展接口。

### AEX-02：共享 v2 store/catalog、仓库 CRUD 与持久化迁移

- 范围：既有 domain/data extensionrepo、catalog、共享 DTO/decoder 与两端网络 adapter；仓库设置页和现有添加仓库 deeplink。
- RED：旧 JSON、v2 JSON/protobuf、压缩/非压缩、内联/远程列表、未知字段、缺失字段、MIXED、多语言、显式 extensionLib 与 versionName 不一致等契约；原始 HTTP 到领域对象完整执行。
- 同时覆盖：403/429/500、超时、取消、空/畸形响应、签名不符、失败不丢旧 catalog、多个仓库部分失败、地址归一化/去重/删除、旧数据库迁移和重启。
- GREEN：共享解析与 store 定位；Android `ExtensionApi` 和 Desktop `DesktopExtensionApi` 消费同一结果。保存新版索引定位及必要 store 元数据，映射实际 APK URL 和 Desktop JAR 资源，版本/分级/语言按规范判断。
- 本批次拥有“目录可识别”与“当前平台可运行”的状态区分及列表反馈测试：复用平台实际能力边界，保留 Android 1.6 未放行状态；不能因协议 parser 支持 1.6 就显示可安装，也不能先过滤掉条目导致无法解释不兼容。AEX-04 复用这一状态契约接通准入与安装，不重新实现一套兼容判断。
- 仓库字段涉及备份时同步调整现有备份/恢复映射；数据迁移由共享 SQLDelight schema 单点维护。
- UI：现有“扩展仓库”入口支持粘贴旧/新地址及 deeplink；添加结果、重复仓库、签名冲突、刷新失败都有反馈。Android 未完成 AEX-04 前，1.6 可在目录中明确显示“当前运行时不兼容”，不能假称可安装。
- 重构：删除两端重复 catalog 协议判断；必要旧 JSON parser 作为共享兼容分支保留。保住 Desktop JAR-first/APK fallback 和现有信任校验。
- 关闭条件：两端从相同 HTTP fixture 得到相同协议决策；Android 不再只请求旧目录；生产 DI/仓库 ScreenModel 与刷新入口均被集成测试覆盖。

### AEX-03A：Source-only 源发现、查询与导航

- 范围：现有 SourceManager、浏览/全局搜索、共享查询服务、ScreenModel/导航与对应 Desktop 调用方。不修改数据库/备份格式，不以完成 getMangaUpdate 数据闭环为关闭前提。
- 测试入口：通过既有依赖/port 提供受控 Source，执行真实 manager、查询服务、ScreenModel 与 HTTP 解析；这不验证 APK 版本准入或安装后源注册，后者属于 AEX-04。
- RED：Source-only 被 CatalogueSource 类型门槛过滤、浏览/搜索未调用该源、分页/筛选参数错误等具体行为；覆盖不支持 latest 时隐藏入口、取消、部分源失败及旧 CatalogueSource 行为。破坏生产查询 wiring 时测试必须失败。
- GREEN：将产品查询门槛调整为真实 Source 能力，接通源列表、热门、搜索、筛选、可用时最新和全局搜索结果导航；保留本地源、旧扩展与 Authors 的特殊能力。
- 重构：查询临时 adapter 在本批次收敛，旧扩展 ABI 桥保留。不要用全部强转 HttpSource 替代能力判断。
- UI：浏览 → 源 → 热门/搜索/筛选/最新；全局搜索 → 选择结果 → 详情导航。加载、空结果、取消及失败有正确反馈；详情页面内的新更新语义归 AEX-03B。
- 验证：共享查询契约、真实 SourceManager/ScreenModel/DI、导航类型与 MockWebServer 成功/失败；Android/Desktop 使用同一受控 Source 场景，核对调用参数、分页结果、可见性和导航目标。
- 关闭条件：生产查询和导航链对 Source-only 与旧源均通过，不依赖 AEX-04 放开 1.6；不宣称已安装 APK 的端到端业务或 memo 持久化通过。本批次单独审查、验证和提交。

### AEX-03B：统一更新、memo 持久化与消费闭环

- 范围：远端更新用例、详情刷新、LibraryUpdateJob、MetadataUpdateJob、章节同步、source/domain/data 映射、共享 schema、备份/恢复与阅读/下载的数据消费；不重新实现 AEX-03A 查询能力。
- 内聚边界：模型、映射、数据库、备份和再次传给扩展属于同一 memo 行为闭环，不能按文件或测试类拆成可各自勾选的任务。
- 测试入口：受控 Source 接入实际更新用例和平台调用方，使用真实临时数据库、迁移和备份/恢复实现；仅控制外部源响应，不 mock 被验收的更新/同步/持久化逻辑。源的 APK 加载不作为本批次前提。
- RED：仅详情、仅章节、二者同时的 flags/调用次数不符；重复调用旧接口、章节顺序或同步错误；memo 返回 → 保存 → 重建存储/服务模拟重启 → 再调用 → 备份/恢复后再次调用的任一环节丢失数据。分别覆盖空/异常返回、取消和旧数据迁移。
- GREEN：生产调用 getMangaUpdate 并复用当前合并/同步规则，避免详情与章节重复取数；保持用户自定义元数据、已读状态、Source ID 及下载关联。漫画和章节 memo 保真传递，不只证明 codec 自己能 round-trip。
- 重构：删除仅为旧更新调用方保留的临时桥，保护旧扩展兼容桥。Android/Desktop、本地源和既有 Reader/下载行为分别回归。
- UI：详情刷新、书架更新、元数据更新有加载/完成/错误反馈；阅读/下载沿用现有入口。受控源生成的持久化漫画/章节必须进入真实页图获取、阅读和下载组件，不呈现空白成功。
- 验证：统一更新共享契约、真实 UI/后台作业 wiring、数据库迁移、备份生产创建/恢复、重启后再调用及页图/下载集成；Android/Desktop 都执行同一数据语义场景。不能只用 DI 解析、编码器 round-trip 或模拟数据库证明完成。
- 关闭条件：查询之外的更新和数据消费闭环独立通过，旧用户状态保留；所有新方法有生产 UI/作业调用和验证。完整签名 APK 从安装到业务的串联验收仍归 AEX-04。本批次单独审查、验证和提交。

### AEX-04：Android metadata、安装、信任与首次整链集成

- 范围：ExtensionLoader、ExtensionManager、现有 ExtensionInstaller/AndroidInstallPort、共享版本/状态决策及扩展列表/详情/信任入口。
- RED：新旧 metadata、显式协议版本、未来未知版本、名称/分级、SourceFactory、多源；1.6 安装前拒绝/完成适配后可用；历史 1.5 不因连续区间改集合而无声丢失。
- 安全矩阵：首次安装、可信更新、签名冲突、错误哈希/损坏 APK、下载取消、安装拒绝、缺失包、加载失败、卸载和重启；分别覆盖系统/私有安装及现有安装生命周期。
- GREEN：AEX-01、AEX-02、AEX-03A/03B 的组件/业务契约通过后正式放开 1.6；复用现有 coordinator、信任和签名能力，以及 AEX-02 的兼容状态契约，不另建安装引擎。发现兼容版本但加载失败时保留可诊断结果。
- UI：扩展列表显示可安装/已安装/有更新/不受信任/不兼容/确实废弃的正确状态；安装/更新/信任/卸载有进度、结果及必要确认。失败可查看原因并重试；不诱导“卸载全部再试”。
- 回滚边界：下载/校验/提交前失败不得损坏可用旧版本；私有安装沿用安全替换恢复。系统安装已经成功提交后的降级受 Android 限制，不能保证自动回滚；此时明确反馈并提供安全恢复指引。
- 验证：未改写 metadata 的签名 APK → 真实安装/信任与版本准入 → ExtensionLoader/ART → SourceManager → AEX-03A 查询导航 → AEX-03B 更新、阅读、下载和 memo → 退出重启后再调用。这是首次要求完整 APK 链路成功，不得通过测试注入 Source 跳过包加载/注册。保留独立 JVM 安全/生命周期测试；Desktop 同步回归共享信任/更新决策及每个 JAR payload 的真实性校验。
- 关闭条件：新版目录项经真实生产入口安装并执行上述全部工作流；版本放行、安装成功、加载成功、源注册、业务成功分别有证据。测试源必须由生产 loader 注册，不能拿 AEX-03A/03B 的组件测试替代此门槛。

#### AEX-04 整合出口及补验（2026-09-16）

按依赖顺序继续，不重新拆成独立交付批次，也不扩大上述范围：

1. **真实可信升级与失败保全（代表场景已补证）**：`aex04-upgrade-confirmation-final-art.log` 10项通过，包括系统/私有两种方式的真实同包升级与三类拒绝，见文末证据。已有 `SystemExtensionRollbackInstrumentationTest` 采用 shell 安装/删除权限，只作为底层回滚证据，不能代替普通用户确认链。新增验收不使用该权限绕过；批末仍须整合审查，不单独勾选或提交。
2. **失败原因可见与重试（已修复并补证）**：原 `ExtensionInstaller.toInstallStep` 丢弃typed错误，只显示重试图标。现已从同一事务经Manager/ScreenModel保留原因，在原扩展条目显示安全中英文分类文案；旧会话不得覆盖新重试，成功/取消/卸载清理对应包。76项相关JVM及2项真实UI通过，见文末。共享协调器和Desktop未改，不以登录提示代替签名问题；该簇已审查，整批仍未提交。
3. **实际加载源进入页面导航（已补证）**：`ExtensionInstalledSourceNavigationInstrumentationTest` 通过正常安装夹具注册真实 Source，默认 DI 的 BrowseSourceScreen → 真实查询/数据库 → 点击 MangaScreen → memo 和章节UI 验证通过，见文末。原 `BrowseSourceUiWiringTest` 仍作为组件回归，不拿测试 Source 替代此整链证据。
4. **整合收口（补验通过）**：独立审查发现首次安装未将候选 APK 证书绑定当前仓库，已用 RED 复现并在快照/提交前补齐校验；另一受信仓库或全局信任不再能代签。69项相关JVM和真实跨可信仓库UI反例通过，唯一一轮定向复审通过。Reader 的503最终由实际 production 客户端证明：删除系统代理设置后，运行态 ProxySelector 仍选择 `10.0.2.2:10808`；显式清除代理运行态后恢复，不属于扩展兼容缺陷。临时HTTP探针已删除，保留安装错误及代理诊断。当前宿主16项ART整链全部通过，另有真正跨进程重启2次运行通过；见文末。ARM、R8及正式跨平台产物仍归 AEX-06，个人手机不作为当前工作的等待条件。

### AEX-05：历史升级、部分失败与真实端到端验收

- 从本仓库旧版本数据夹具开始；若用户提供实际旧 Mihon 版本/备份，再增加对应迁移路径。分别记录原地升级与跨签名备份迁移，不能互相替代。
- 场景：旧 index.min.json 仓库 → 应用升级 → 刷新新版目录 → 旧扩展不误报废弃 → 安装/更新 1.6 → 保留 source ID 与书架 → 浏览/阅读/下载 → 退出重启 → 再更新。
- 故障：一仓库成功一仓库失败、真实空目录、索引签名变化、未知版本、断网/限流、安装拒绝、旧源暂时不可用、升级中断后再次启动。用户需能区分仓库故障、协议不兼容、安装失败和网站失败。
- 数据：漫画/章节 memo、进度、分类、自定义字段、源设置、信任状态、旧下载关联与新版备份 round-trip；明确旧应用恢复新版数据的限制。
- 先用本地受控 HTTP 与固定二进制跑全产品流程，再选代表性真实 1.6 APK/站点做有界联网验收。任何新发现行为 bug 先加失败测试，再做最小修复并按批次审查。
- 网络按仓库代理约定，失败检查代理后最多重试一次；不能用 curl 成功替代 APK 的 production 网络调用。外部站点不可用时记录，不把它臆断为兼容性失败；也不得将未完成的必要真实验收勾为通过。
- UI：无需新增页面；用现有入口完成第 7 节流程，故障应有可操作反馈。
- 关闭条件：正常升级、跨签名安全迁移和故障恢复边界明确；无全量误标 obsolete、数据丢失或签名绕过。

#### AEX-05 执行出口（不拆成额外提交批次）

1. **备份/恢复与源设置**：复用固定旧序列化器生成的 `android-full.tachibk`，已补默认DI、真实Android数据库及完整BackupRestorer/BackupCreator的ART验证。源未安装时仍恢复书架、分类、进度、笔记、源设置及仓库，并重导出库数据。另发现Source-only可配置源被旧CatalogueSource枚举漏掉，已按红绿流程修复，见文末。该出口不是跨签名双安装实例验收的替代物。
2. **旧数据原地升级及故障恢复**：固定旧宿主schema18→当前20→旧仓库发现/升级1.6→旧书架/下载保全→退出重启已补真实覆盖安装证据，见文末。旧仓库和扩展数据在旧宿主中播种，不用当前schema反向构造冒充旧应用。仍须汇总仓库部分失败/缓存、拒绝及中断恢复证据，不能仅凭编码器round-trip关闭。
3. **真实站点与安全迁移**：真实MangaDex1.6的正常安装→查询→生产更新/数据库→Reader在线页图已通过，见文末。隔离实例已验证不同签名覆盖被拒绝、旧数据仍可读，以及生产备份转移到换签名安装后缺源恢复/重启；下载图片另行复制并校验，未宣称备份包含下载或恢复后无需重装扩展即可阅读。不以单页读取代替完整下载/重启，剩余故障矩阵与批末审查不提前勾选；正式发布身份/ARM验证仍在AEX-06。

### AEX-06：正式发布、跨平台回归与证据收口

- 设备调度（2026-09-16 最新）：ARM 手机 rc9 来源确认及真实1.6业务链已通过，详见文末最终验收记录；连接仍为动态状态。后续新增设备任务另行核对，不重复已有效的验收。
- Android：对最终 diff 跑相关模块完整单元/集成测试、格式检查；构建 R8 开启的目标 release variant，明确 updater/telemetry flags，不因验收自动启用。确认版本标识属于本 fork、versionCode 可合法升级目标实例，不冒充官方 v0.20.4 签名或版本。
- 在 AEX-00 确认的最低/当前受支持 Android 系统及代表性 ABI 上，以可安装的正式 release 产物执行关键 fixture/代表性真实扩展的搜索、更新、阅读、下载、重启与信任验收。签名材料或设备缺失必须明确阻塞发布门槛。
- R8/反射/资源/类加载问题以该 APK 的真实 ART 行为验证；debug、仅 JVM 或系统 JDK 结果不能替代。记录 APK 路径、SHA-256、签名指纹、版本、构建 flags、设备和结果。
- Desktop：共享改动必须通过完整 Desktop JVM 测试、Test Mode 及 Windows/macOS 正式构建和运行验收；特别覆盖 JAR-first、旧 APK、Source ABI、Authors、FlareSolverr 和既有 Reader 行为。
- Desktop 构建只能走 `scripts/build-desktop.sh`。同一最终 diff 已有等价完整 Desktop 测试时可用 `build-only` 避免重复全量测试；仍需正式 runtime 验收。Windows 交付路径必须来自日志 `Final unpacked EXE:` 且确认存在，不用 tmp/build 目录。
- 无关已有失败与本次回归分别记录；不能凭“可能是旧问题”忽略。失败先映射到冻结协议、实际改动与明确验收项。必需项缺证据仍阻塞；额外站点或通用环境问题不自动升级为无限修复任务。既有调查后仍不能归因时保留已知限制，不宣称已修复；若需要追加诊断或影响最终兼容声明，提交证据、单一假设、预算及停止条件，请用户决定，不无限等待“证明无关”。
- 将测试命令/结果、设备证据、正式产物和受影响 capability 证据写入第 8 节及既有机器权威。实现、独立审查、验证和提交完成后才勾选批次；不另建关闭/推进状态的空提交。
- 关闭条件：第 7 节所有必需项通过，必要平台不存在“待验证”；交付真实 APK 和平台产物链接、版本边界、升级说明及 commit。未达成时报告剩余门槛，不宣布完整支持。

## 6. 执行与验证预算

### 初稿规划预算与产物

- 技能：无专门技能触发；按仓库文档与源码审查执行。
- 固定步骤：读现有计划/架构与源码 → 对照固定上游 → 编写本计划并添加父计划/EX-01 入口 → 一轮只读核验 → 文档提交。
- 子代理：0；无并行实现或独立 agent 审查。
- 审查：1 轮计划只读核验；验证：文档结构/链接 1 次、git diff 1 次。产品 focused tests、全量测试、构建均为 0，因为本次不改产品行为。
- 交付物：本 roadmap；必要衔接修改仅限父计划与 EX-01。过程产物：不另建计划、报告、逐文件快照或大型 diff 包。
- 预计墙钟：10–20 分钟；主要成本是协议/代码对照与计划撰写。网络不可用时标注未核实内容，不把实现或环境安装加入本轮。

本次审阅后调整预算：仅编辑现有 roadmap，0 个子代理，1 轮只读核验；文档结构、引用和 diff 各检查 1 次，发现文档错误时定向修正复查。产品测试/构建为 0，不新增报告、计划或快照；预计 5–10 分钟。调整不激活实施任务，也不改变其他计划的暂停状态。

### 未来实施预算

- 每个有行为变化的批次按 RED → 最小 GREEN → 重构 → 相关测试/格式 → 1 轮独立审查 → 必要时至多 1 轮修复复审 → 提交执行。执行前另报该批次流程预算；本计划不是无限测试、审查或子代理授权。
- 默认单工作流，重型 Gradle 串行；不为占槽启动子代理。如后续确有不共享写入的独立验证工作，届时说明成本并遵循现有授权上限。
- 红绿循环只跑当前 focused tests；批次末跑相关单元、集成、wiring 和格式检查；阶段末跑对应模块完整测试。最终全量 Android/Desktop 与正式平台验收集中在 AEX-06，各一轮，不在每个批次重复。
- 测试失败允许在同一行为范围修复；若新增未预算的迁移、依赖大升级、工具安装或产品范围，应先说明证据、成本与替代方案，取得新方向后推进。
- 一功能批次原则上一个提交，含测试、production、必要证据与 checkoff；审查修复至多增加一个。保护用户已有改动，不混入无关文件。

### 2026-09-13 起的纠错、监督与证据复用规则

- focused 次数按真实行为及必要修复估计，不把一个大组固定压成三次命令。正确 RED、GREEN 和有实际清理后的复验不能省；同阶段相关 target 可合并一次 Gradle 调用，但不能合并掉 RED 先于 production 的顺序。批末主审 1 轮、必要修复复审 1 轮、模块/最终验证层级保持不变。
- 当前范围内的普通编译/测试装配修复属于实施预算，不逐次请求用户批准。同类装配错误连续两次时，先停止追加构建，集中核对完整诊断和已验证同类代码，再给出有依据的修正；“两次”是停止盲试条件，不是两次后忽略失败或自动追加审查的许可。产品范围、权限、依赖体系或约定时间/审查预算实质扩大时仍先报告。
- 子代理在行为组出口发送简短结果与剩余项，批末发完整结构化回执；完整背景不反复重发。主模型把非阻塞问题集中到交接/批末反馈，避免在连续写入中逐文件指挥。安全越界、错误前置或已经证实会扩大返工的缺陷可以立即制止；实质 diff 审查计入实际成本，不用“监督”名称规避预算。
- 同一受影响代码、fixture、配置和运行时的有效结果可复用，说明是否实际执行或增量命中；仅有 key 名、退出码 0 或旧 XML 不足以覆盖新 diff。主审仍须检查实际代码、原始结果并做适当独立定向验证；不自动重跑所有已通过层。不增加逐任务快照或第二套证据数据库。
- 同一 worktree 只有一名重型 Gradle 协调者。工具超时先查既有状态/PID，不另启命令；暂停交接只报告一次等待原因、现存进程和恢复条件，不通过反复唤醒代理要求“保持空闲”制造执行回执。
- 恢复时传递当前 diff、有效测试 key、已知失败和剩余前置，保留已验收 AEX-00/01，不从头探索或重做。已有先改后测必须保留历史事实；事后回退/变异只能证明测试敏感性，不能把历史顺序改写为合规。接受流程例外需要用户明确决定；未经批准不自动撤回整个草稿或降低 C4。
- 工期更新按“已验收结果 / 剩余行为 / 验证与外部等待”说明，不按通过测试数或已勾选批次数计算完成百分比。没有可靠分项计时就不将命令间隔全部算作浪费，也不预报节省比例。

### 测试归属与可执行矩阵

下表是测试责任分配，不是已经通过的测试记录。拟新增测试名称仅为计划名称，不声称文件或 task 已存在。AEX-00 必须把每行具体化到 source set、准确 task/filter 或设备 runner、fixture、预期 RED 原因及通过标准；一个行为的首次实现/RED/GREEN 只归一个批次，后续批次记录更高层集成或回归。

| 行为 / 所属批次 | 被测生产入口与边界 | 测试落点及复用 / 拟新增套件 | 首个预期 RED 与通过标准 |
| --- | --- | --- | --- |
| Source ABI / AEX-01 | `source-api`、旧版桥接、production 加载基础组件；Android 组件测试直接使用 `ChildFirstPathClassLoader`，不经过版本/信任准入；不含 APK 准入整链 | 待新增 `source-api/src/commonTest/kotlin/eu/kanade/tachiyomi/source/SourceApiBinaryContractTest.kt`（`jvmTest`、`testReleaseUnitTest`）、`app/src/androidTest/java/eu/kanade/tachiyomi/extension/ExtensionV16SourceAbiInstrumentationTest.kt`（ART）与 `app-desktop/src/test/kotlin/mihon/desktop/extension/DesktopExtensionV16AbiIntegrationTest.kt`（Desktop `DesktopExtensionLoader.loadFromSingleJar`/`ExtensionClassLoader`）；现有 `app-desktop/src/test/kotlin/mihon/desktop/extension/DesktopExtensionLoaderTest.kt` 已实际执行同一 production loader 的受控 v1.5 路径，v1.6 本批次仅由 `DesktopExtensionArtifactAuthenticityTest` 验证签名；`:source-api:jvmTest --tests "eu.kanade.tachiyomi.source.SourceApiBinaryContractTest"`、`:source-api:testReleaseUnitTest --tests "eu.kanade.tachiyomi.source.SourceApiBinaryContractTest"`、`:app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.extension.ExtensionV16SourceAbiInstrumentationTest'`、`:app-desktop:jvmTest --tests "mihon.desktop.extension.DesktopExtensionV16AbiIntegrationTest"` | 外部二进制调用真实方法出现 ABI/行为差异；GREEN 时新旧调用结果正确且 Android ART、JVM 与 Desktop 实际执行，不能以版本拒绝作为 ABI 失败 |
| 目录协议 / AEX-02 | Android `ExtensionApi`、Desktop `DesktopExtensionApi` → 共享 decoder/catalog → 领域对象；MockWebServer 仅提供真实响应形状 | 已存在 `app/src/test/java/eu/kanade/tachiyomi/extension/api/ExtensionApiSharedCatalogTest.kt`、`domain/src/jvmTest/kotlin/mihon/domain/extension/ExtensionCatalogServiceTest.kt` 与 `app-desktop/src/test/kotlin/mihon/desktop/extension/DesktopExtensionApiSharedCatalogTest.kt`；待新增共享契约 `domain/src/commonTest/kotlin/mihon/domain/extension/ExtensionCatalogServiceContractTest.kt`；`:app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.extension.api.ExtensionApiSharedCatalogTest"`、`:domain:jvmTest --tests "mihon.domain.extension.ExtensionCatalogServiceContractTest"`、`:domain:testReleaseUnitTest --tests "mihon.domain.extension.ExtensionCatalogServiceContractTest"`、`:app-desktop:jvmTest --tests "mihon.desktop.extension.DesktopExtensionApiSharedCatalogTest"` | 显式 lib、分级、语言、列表或错误映射不符；真实 HTTP 成功/空目录/403/429/500/畸形响应矩阵通过且失败保留成功快照 |
| 仓库生命周期 / AEX-02 | deeplink/仓库 ScreenModel → Create/Replace/Delete → HTTP → 真实数据库与备份恢复 | 已存在 `domain/src/commonTest/kotlin/mihon/domain/extensionrepo/service/ExtensionRepoServiceContractTest.kt`；待新增 `domain/src/commonTest/kotlin/tachiyomi/domain/extension/ExtensionRepoIdentityContractTest.kt`、`data/src/commonTest/kotlin/tachiyomi/data/extension/ExtensionRepoPersistenceIntegrationTest.kt`、`app/src/androidTest/java/eu/kanade/tachiyomi/extension/ExtensionRepoPersistenceInstrumentationTest.kt`；`:domain:jvmTest --tests "tachiyomi.domain.extension.ExtensionRepoIdentityContractTest"`、`:domain:testReleaseUnitTest --tests "tachiyomi.domain.extension.ExtensionRepoIdentityContractTest"`、`:data:jvmTest --tests "tachiyomi.data.extension.ExtensionRepoPersistenceIntegrationTest"`、`:data:testReleaseUnitTest --tests "tachiyomi.data.extension.ExtensionRepoPersistenceIntegrationTest"`、`:app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.extension.ExtensionRepoPersistenceInstrumentationTest'` | 新索引地址不能创建、身份/信任或迁移后定位丢失；旧/新输入、重复、冲突、删除、重启及恢复通过，不能 mock 创建用例或数据库后声称整链通过 |
| 目录兼容状态 / AEX-02 | 平台能力与共享状态 → 列表呈现及安装入口禁用 | 已存在 `app/src/test/java/eu/kanade/tachiyomi/ui/browse/extension/ExtensionPresentationWiringTest.kt`、`app-desktop/src/test/kotlin/mihon/desktop/ui/extension/DesktopExtensionPresentationProjectionTest.kt`；扩充两者以消费真实 catalog 与平台能力 adapter；`:app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.ui.browse.extension.ExtensionPresentationWiringTest"`、`:app-desktop:jvmTest --tests "mihon.desktop.ui.extension.DesktopExtensionPresentationProjectionTest"` | 1.6 条目被错误过滤或 Android 尚未放行却显示可安装；GREEN 时条目可解释、不可错误操作，Desktop 既有可用状态不退化 |
| Source 查询 / AEX-03A | 真实 `SourceManager` → 查询服务/ScreenModel → Voyager nested navigator；外部 Source 为受控输入 | 已存在 `app/src/test/java/eu/kanade/tachiyomi/source/AndroidSourceManagerInitializationTest.kt`、`app/src/test/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreenModelBehaviorTest.kt`、`app/src/test/java/eu/kanade/tachiyomi/ui/browse/source/SourceSharedQueryWiringTest.kt`；待新增 `app/src/test/java/eu/kanade/tachiyomi/source/SourceOnlyQueryContractTest.kt` 与 `app-desktop/src/test/kotlin/mihon/desktop/extension/SourceOnlyQueryContractTest.kt`；`:app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.source.SourceOnlyQueryContractTest"`、`:app-desktop:jvmTest --tests "mihon.desktop.extension.SourceOnlyQueryContractTest"` | Source-only 被过滤、没有调用源或参数/导航错误；源可见，分页/筛选/可用时最新/取消和失败反馈正确；DI 可解析只是附加检查 |
| 更新与 memo / AEX-03B | 详情/后台作业 → 统一更新/同步 → 数据库 → 备份/恢复 → 再调用及页图/下载消费 | 待新增 `domain/src/commonTest/kotlin/tachiyomi/domain/extension/SourceUpdateMemoContractTest.kt`、`data/src/commonTest/kotlin/tachiyomi/data/extension/SourceUpdateMemoPersistenceIntegrationTest.kt`；复用 `app/src/test/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJobSharedLifecycleIntegrationTest.kt`、`app/src/test/java/eu/kanade/tachiyomi/data/backup/create/BackupCreatorBehaviorTest.kt`、`app/src/test/java/eu/kanade/tachiyomi/data/backup/restore/BackupRestorerBehaviorTest.kt`；`:domain:jvmTest --tests "tachiyomi.domain.extension.SourceUpdateMemoContractTest"`、`:domain:testReleaseUnitTest --tests "tachiyomi.domain.extension.SourceUpdateMemoContractTest"`、`:data:jvmTest --tests "tachiyomi.data.extension.SourceUpdateMemoPersistenceIntegrationTest"`、`:data:testReleaseUnitTest --tests "tachiyomi.data.extension.SourceUpdateMemoPersistenceIntegrationTest"`、`:app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.data.library.LibraryUpdateJobSharedLifecycleIntegrationTest"` | flags/次数错误或任一数据环节丢失 memo/进度；真实存储重开和生产备份恢复后重新调用正确，旧数据不损坏；codec 自身 round-trip 不足以关闭 |
| APK 准入、安装与整链 / AEX-04 | 真实 `PackageManager`/installer → `ExtensionLoader`/ART → `SourceManager` → 查询/更新/阅读/下载 | 待新增 `app/src/androidTest/java/eu/kanade/tachiyomi/extension/ExtensionV16LifecycleInstrumentationTest.kt`；复用 `app/src/androidTest/java/eu/kanade/tachiyomi/extension/SystemExtensionRollbackInstrumentationTest.kt` 的设备基础设施；`:app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.extension.ExtensionV16LifecycleInstrumentationTest'` | 真实 1.6 APK 被版本/metadata 拒绝或源注册/业务调用失败；同一签名 APK 经正常产品入口完成全部步骤；无 Source 注入、metadata 改写或信任后门 |
| 历史升级与恢复 / AEX-05 | 旧版本数据/备份进入已完成的产品整链 | 待新增 `app/src/androidTest/java/eu/kanade/tachiyomi/extension/ExtensionUpgradeWorkflowTest.kt`；复用 AEX-04 的 `ExtensionV16LifecycleInstrumentationTest`，并在同一 ART runner 使用固定旧数据/受控 v1.5 APK；`:app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.extension.ExtensionUpgradeWorkflowTest'` | 受控 v1.5 替代样本的 metadata、签名、安装或升级中断恢复行为无法验证；历史第三方 1.5 provenance 缺失仅作为来源限制，不作为受控样本的 RED；随后升级/中断恢复全流程通过，独立记录旧数据版本及结果 |
| 发布产物 / AEX-06 | 最终 R8 APK 与正式 Windows/macOS runtime | 已接入 `mihon.testBuildType=release`；按文末“正式release复验命令”构建、外部签名及指定模拟器运行 `ExtensionReleaseParityInstrumentationTest` 公开ABI门槛（身份/证书/SHA校验必需）；内部白盒debug结果与正式APK外部UI生命周期验收分开记录，不能互相替代。Desktop：`scripts/build-desktop.sh` 后 `scripts/desktop-smoke-test.sh`；正式产物hash、报告、API/ABI均须记录，ARM实机最后 | 本批次是发布集成门槛，不虚构必须先失败；若产物失败，定位后对相关行为补 RED 并修复；最终产物与受测 hash/版本一致、必要场景不跳过 |

### AEX-00 已具体化的行为—入口—runner 矩阵

下表把上表的责任落实到本批次可以执行的生产入口、唯一归属、准确 source set 和断言。`已存在` 表示当前工作树已有测试；`待新增` 只冻结后续测试位置，不表示本批次已经实现或执行。矩阵中的 AEX-00 探针只证明当前兼容 production 基线，不能替代归属批次的首次 RED。

| 行为与唯一批次 | 真实 production 入口/边界 | 准确测试 source set 与 runner | 首次 RED 原因 | GREEN 通过断言 |
| --- | --- | --- | --- | --- |
| 当前 Source/CatalogueSource bridge 基线 / AEX-00 探针（为 AEX-01 提供前置） | `source-api` 的 `Source`、`CatalogueSource` suspend 默认实现、旧 Rx bridge、`SManga`/`SChapter`/`Page` 工厂；不加载外部 APK | 已存在 `source-api/src/commonTest/kotlin/eu/kanade/tachiyomi/source/SourceApiCurrentBaselineTest.kt`；`:source-api:jvmTest --tests "eu.kanade.tachiyomi.source.SourceApiCurrentBaselineTest"` 与 `:source-api:testReleaseUnitTest --tests "eu.kanade.tachiyomi.source.SourceApiCurrentBaselineTest"` | 基线测试首次错误地给 `FilterList` 传空列表，实际 size=0 而断言 1（测试输入修正，非产品 RED）；未来 ABI RED 归 AEX-01 | 真实 production bridge 返回详情、章节、页面；分页、hasNextPage 和过滤器传递正确，两 source set 均实际执行且无跳过 |
| 固定外部 1.5 Source-only suspend-only fixture / AEX-00 探针（替代历史第三方样本，不提前实现 AEX-01） | `scripts/aex00-generate-external-v15-fixture.ps1` 下载官方 Mihon `v0.19.4` 固定源码快照，独立工程编译未改写的旧 `Source`/模型与 `LegacySuspendOnlySource`；Desktop `DesktopExtensionLoader` 与 `DefaultDesktopArtifactAuthenticator` 消费签名 JAR；不引用宿主 `source-api` classpath | 外部 fixture `LegacySuspendOnlySourceTest`：`python scripts/gradle-coordinator.py run --key aex00-external-v15-test -- .\gradlew.bat -p app-desktop/tmp/aex00-external-v15 test --tests "aex00.external.v15.LegacySuspendOnlySourceTest" --no-daemon --offline`；导出 `aex00ExportSampleJar`；宿主 `python scripts/gradle-coordinator.py run --key aex00-desktop-final-focused -- .\gradlew.bat :app-desktop:jvmTest --tests "mihon.desktop.extension.DesktopExtensionLoaderTest" --tests "mihon.desktop.extension.DesktopExtensionArtifactAuthenticityTest" --no-daemon --offline` | 固定旧 Source/API、模型或真实依赖缺失时，旧 suspend-only override 编译/调用应失败；独立工程 plugin marker 未缓存曾是配置故障，已改为同版本实际 plugin module 映射 | fixture XML 1/0/0/0；固定 JAR 仅含样本类，正常 JAR-v1 签名，生产 loader/authenticator 实际加载/验证；不把该受控样本写成历史第三方发布物 |
| 外部 Source ABI 与 factory / AEX-01 | 受控 `app-desktop/src/test/resources/extensions/real/aex00-external-v16-controlled-sample.jar` 与对应 `app-desktop/src/test/resources/extensions/real/aex00-external-v16-controlled-sample.apk` → Android 直接调用生产 `app/src/main/java/eu/kanade/tachiyomi/util/system/ChildFirstPathClassLoader` 组件加载只读 fixture DEX，再调用 `SourceFactory`/`Source`；Desktop 使用 `DesktopExtensionLoader` 的组件边界；不把版本拒绝当 ABI 通过 | 待新增 `source-api/src/commonTest/kotlin/eu/kanade/tachiyomi/source/SourceApiBinaryContractTest.kt`；Android ART 待新增 `app/src/androidTest/java/eu/kanade/tachiyomi/extension/ExtensionV16SourceAbiInstrumentationTest.kt`；Desktop 实际 loader 入口为 `app-desktop/src/test/kotlin/mihon/desktop/extension/DesktopExtensionLoaderTest.kt` 的 `loadFromSingleJar`（AEX-00 已执行同一路径的受控 v1.5 加载），另执行 authenticator；v1.6 loader/ABI 真实调用待新增 `app-desktop/src/test/kotlin/mihon/desktop/extension/DesktopExtensionV16AbiIntegrationTest.kt`；`:source-api:jvmTest --tests "eu.kanade.tachiyomi.source.SourceApiBinaryContractTest"`、`:source-api:testReleaseUnitTest --tests "eu.kanade.tachiyomi.source.SourceApiBinaryContractTest"`、`:app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.extension.ExtensionV16SourceAbiInstrumentationTest'`、`:app-desktop:jvmTest --tests "mihon.desktop.extension.DesktopExtensionV16AbiIntegrationTest"`、`:app-desktop:jvmTest --tests "mihon.desktop.extension.DesktopExtensionArtifactAuthenticityTest"`；完整 `ExtensionLoader` 的版本/信任/安装准入首次仍归 AEX-04 | 外部编译调用的真实方法/模型/桥接缺失或返回差异 | 同一受控二进制在 JVM、Android unit 与 ART 生产 classloader 中完成 factory/source 调用，结果与冻结契约一致；失败原因不得只是 1.6 loader 拒绝；AEX-00 仅记录 Desktop 旧 host 的边界观察与签名通过 |
| 目录协议与错误保全 / AEX-00 探针，v2 首次行为归 AEX-02 | `app` 的 `ExtensionApi` → 真实 decoder/catalog → `ExtensionArtifact`；MockWebServer 只提供 HTTP 响应形状，不 mock parser | 已存在 `app/src/test/java/eu/kanade/tachiyomi/extension/api/ExtensionApiSharedCatalogTest.kt`（`app/src/test`）；`:app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.extension.api.ExtensionApiSharedCatalogTest"` | v2 字段、签名或成功/空/403/429/500/畸形响应映射不一致；既有基线首次 RED 归测试输入/实现定位，不冒充 v2 RED | 真实 app production API 保留成功 catalog；空目录与失败区分，HTTP 403/429/500/畸形 JSON 映射准确且成功仓库不被另一仓库失败清空 |
| v2 catalog 与签名 JAR / AEX-00 探针，v2 业务归 AEX-02 | Desktop `ExtensionCatalogService` → v2 gzip protobuf decoder；`DefaultDesktopArtifactAuthenticator` 用 `JarFile(true)` 校验每个 payload | 已存在 `app-desktop/src/test/kotlin/mihon/desktop/extension/DesktopExtensionApiSharedCatalogTest.kt` 与扩充的 `DesktopExtensionArtifactAuthenticityTest`（`app-desktop/src/test`）；`:app-desktop:jvmTest --tests "mihon.desktop.extension.DesktopExtensionApiSharedCatalogTest" --tests "mihon.desktop.extension.DesktopExtensionArtifactAuthenticityTest"` | v2 signing key 不匹配、JAR payload 未签名/摘要异常或 parser 退化 | 固定 1.6 JAR hash 与 provenance 一致，production authenticator 对全部 payload 成功；v2 gzip protobuf 解析出真实 artifact/source 且错误不吞掉 |
| 仓库 CRUD、重启、迁移与备份 / AEX-02 | Android 仓库 ScreenModel/UseCase → HTTP → 真实数据库 → backup/restore；不以 fake repository 代替整链 | 待新增 `domain/src/commonTest/kotlin/tachiyomi/domain/extension/ExtensionRepoIdentityContractTest.kt`、`data/src/commonTest/kotlin/tachiyomi/data/extension/ExtensionRepoPersistenceIntegrationTest.kt` 及 `app/src/androidTest/java/eu/kanade/tachiyomi/extension/ExtensionRepoPersistenceInstrumentationTest.kt`；`:domain:jvmTest --tests "tachiyomi.domain.extension.ExtensionRepoIdentityContractTest"`、`:domain:testReleaseUnitTest --tests "tachiyomi.domain.extension.ExtensionRepoIdentityContractTest"`、`:data:jvmTest --tests "tachiyomi.data.extension.ExtensionRepoPersistenceIntegrationTest"`、`:data:testReleaseUnitTest --tests "tachiyomi.data.extension.ExtensionRepoPersistenceIntegrationTest"`、`:app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.extension.ExtensionRepoPersistenceInstrumentationTest'` | 新索引地址、重复/冲突、删除、重启或恢复后身份/信任丢失 | 旧 index.min.json 与 v2 deeplink 都能定位同一仓库；重复幂等、失败可重试，真实数据库重开与备份恢复保留身份/信任 |
| Source-only 查询、分页、筛选、导航 / AEX-03A | 真实 `SourceManager` → query service/ScreenModel → Voyager nested navigator；DI 仅是附加 wiring | 待新增 `app/src/test/java/eu/kanade/tachiyomi/source/SourceOnlyQueryContractTest.kt` 与 `app-desktop/src/test/kotlin/mihon/desktop/extension/SourceOnlyQueryContractTest.kt`；复用已存在 `app/src/test/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreenModelBehaviorTest.kt`、`SourceSharedQueryWiringTest`，分别用 `:app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.source.SourceOnlyQueryContractTest"`、`:app-desktop:jvmTest --tests "mihon.desktop.extension.SourceOnlyQueryContractTest"` | Source-only 被类型过滤、未调用真实 source、分页/取消/失败反馈或导航上下文错误 | Source-only 进入热门/搜索/筛选/最新（可用时）、分页和取消行为正确，失败有反馈并进入正确 nested navigator |
| update flags 与 memo 保真 / AEX-03B | 真实详情/后台更新作业 → source `getMangaUpdate` → data/backup → reader/download 消费；不以 codec round-trip 代替链路 | 待新增 `domain/src/commonTest/kotlin/tachiyomi/domain/extension/SourceUpdateMemoContractTest.kt`、`data/src/commonTest/kotlin/tachiyomi/data/extension/SourceUpdateMemoPersistenceIntegrationTest.kt` 与平台集成；复用 `app/src/test/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJobSharedLifecycleIntegrationTest.kt`、`app/src/test/java/eu/kanade/tachiyomi/data/backup/restore/BackupRestorerBehaviorTest.kt`；`:domain:jvmTest --tests "tachiyomi.domain.extension.SourceUpdateMemoContractTest"`、`:domain:testReleaseUnitTest --tests "tachiyomi.domain.extension.SourceUpdateMemoContractTest"`、`:data:jvmTest --tests "tachiyomi.data.extension.SourceUpdateMemoPersistenceIntegrationTest"`、`:data:testReleaseUnitTest --tests "tachiyomi.data.extension.SourceUpdateMemoPersistenceIntegrationTest"`、`:app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.data.library.LibraryUpdateJobSharedLifecycleIntegrationTest"` | flags/调用次数错误、memo 或已读/下载映射在存储重开/备份恢复中丢失 | 真实 source 调用次数与 flags 精确；重启和 production backup/restore 后 memo、章节状态、页图/下载关系保真 |
| Android metadata、安装、信任、首次整链 / AEX-04 | Android `PackageManager`/正常 installer → `ExtensionLoader`/ART → `SourceManager`；不改 metadata、不扩大信任、不使用 shell 权限冒充用户安装 | 待新增 `app/src/androidTest/java/eu/kanade/tachiyomi/extension/ExtensionV16LifecycleInstrumentationTest.kt`；复用 `app/src/androidTest/java/eu/kanade/tachiyomi/extension/SystemExtensionRollbackInstrumentationTest.kt` 仅作回滚基础设施；`:app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.extension.ExtensionV16LifecycleInstrumentationTest'` | 真实 1.6 APK 被旧版本/metadata 拒绝，或正常安装后 source 注册/查询失败 | 固定签名 1.6 APK 经正常生产入口完成准入、安装、加载、source 查询；不同签名/损坏/未知版本分别拒绝并保留旧状态 |
| 历史 1.5 升级与部分失败 / AEX-05 | 旧数据/旧 metadata → 已完成的 AEX-04 production chain；不把 1.6 改标签成 1.5；历史第三方 1.5 与用户批准的受控 1.5 分开 | 待新增 `app/src/androidTest/java/eu/kanade/tachiyomi/extension/ExtensionUpgradeWorkflowTest.kt`，历史第三方样本保持清单缺失；受控签名样本使用 `aex00-external-v15-suspend-only.jar` 与 `aex00-external-v15-controlled-sample.apk`；`:app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.extension.ExtensionUpgradeWorkflowTest'` | 受控 v1.5 替代样本的 metadata、签名、安装或升级中断恢复行为无法验证；历史第三方 1.5 provenance 缺失仅作为来源限制，不作为受控样本的 RED；不得以 mock/改 metadata 解除 | 受控签名 JAR 已有 JVM/Desktop loader/authenticator 证据，但不替代 Android APK 安装；旧数据/备份升级、部分失败和恢复均有真实设备证据后才可关闭，否则保持未验证 |
| 发布 APK/Desktop runtime / AEX-06 | 最终 R8 release APK 与正式 Windows/macOS runtime；不把 debug/临时目录产物当发布证据 | 已接入 `mihon.testBuildType=release`；按文末“正式release复验命令”构建、外部签名及指定模拟器运行 `ExtensionReleaseParityInstrumentationTest` 公开ABI门槛（身份/证书/SHA校验必需）；内部白盒debug结果与正式APK外部UI生命周期验收分开记录，不能互相替代。Desktop：`scripts/build-desktop.sh` 后 `scripts/desktop-smoke-test.sh`；正式产物hash、报告、API/ABI均须记录，ARM实机最后 | 发布产物不匹配受测 hash、R8/平台行为差异或必要场景跳过 | 版本、签名、hash、设备/API/ABI、报告和用户路径全部可追溯；Android/Desktop 必需场景无跳过 |

上述 AEX-00 矩阵保留当时的探针和拟新增名称，不是当前进度快照；实际完成状态以第 5 节 checkbox 和第 8/9 节证据为准。所有 runner 仍按协调器串行执行，新增套件应更新实际落点而非重复建立计划中的占位类。

runner 分层与命令约束：

- source-api 已在 AEX-00 显式配置 `commonTest`、`jvmTest` 与 `androidUnitTest` 的测试依赖、JUnit Platform launcher 和 `useJUnitPlatform()`；`SourceApiCurrentBaselineTest` 已实际运行 `:source-api:jvmTest --tests "eu.kanade.tachiyomi.source.SourceApiCurrentBaselineTest"` 与 `:source-api:testReleaseUnitTest --tests "eu.kanade.tachiyomi.source.SourceApiCurrentBaselineTest"`。后续外部二进制契约仍须接入准确的两个 target，不得把配置存在当作行为通过。
- app/src/test 的 JVM/Robolectric、app/src/androidTest 的设备/ART、domain/data 的 commonTest 平台执行，以及 Desktop jvmTest 分别记录。Android JVM 测试不能代替 ART；设备测试也不能代替共享数据迁移契约。
- `:app:testReleaseUnitTest` 不代表依赖模块的全部测试已执行。最终命令矩阵必须列出受影响 source-api/domain/data 的实际 Android/JVM 测试任务、app 设备任务和 Desktop 任务；不能仅复用下方两个示例命令便关闭全部验证。
- 对每组记录选中的测试数、实际执行数、失败/跳过数和报告位置；任务退出码为 0 但 NO-SOURCE、未发现测试、必要测试 skipped 或仅完成编译时不得判通过。正常增量复用必须能指向同一 diff/fixture/配置的既有有效测试结果。
- SystemExtensionRollbackInstrumentationTest 使用 shell 安装权限等测试控制条件；复用其基础设施不等于普通用户安装授权、取消、拒绝和恢复流程已验收。AEX-04 另需正常权限/用户确认路径的设备证据。

现有测试入口优先扩展；其已有覆盖与本专项新增责任必须区分：

| 领域 | 现有入口 | 本专项增加的实际断言 |
| --- | --- | --- |
| 仓库/目录 | ExtensionRepoServiceContractTest、Android/Desktop ExtensionApiSharedCatalogTest | 前者目前主要覆盖动作结果映射，不是 HTTP/持久化整链；AEX-02 补完整 v2、刷新/迁移、安全失败和生产 wiring |
| 安装 | ExtensionInstallCoordinatorWiringTest、ExtensionInstallSessionLifecycleTest、AndroidExtensionInstallSecurityRollbackTest | 1.6 metadata、真实 APK/ART、系统与私有安装的安全边界 |
| Android UI/来源 | AndroidSourceManagerInitializationTest、SourceSharedQueryWiringTest、BrowseSourceScreenModelBehaviorTest、ExtensionPresentationWiringTest、ExtensionReposScreenModelWiringTest | SourceSharedQueryWiringTest 的 DI 解析不能证明查询；仓库页面 mock 创建用例不能证明 URL/持久化。按归属表补 Source-only、安装状态和真实链路 |
| Desktop artifact | DesktopExtensionArtifactAuthenticityTest、DesktopExtensionApiSharedCatalogTest、既有真实加载 fixture | JAR-first/旧 APK、签名与共享 Source ABI 不回退 |
| 新增必要覆盖 | 外部编译 1.6 ABI fixture、更新 flags/memo 契约、Android release/device 集成 | AEX-00 冻结精确 runner/任务与断言；AEX-01/03B/04 分别承担首次行为验收，AEX-06 复用到最终产物；目前未实施 |

执行命令示例（将来实施时运行，本次未运行；task/filter 须在 AEX-00 按实际 target 确认）：

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'
$env:HTTP_PROXY = 'http://127.0.0.1:10808'
$env:HTTPS_PROXY = 'http://127.0.0.1:10808'
$env:NO_PROXY = 'localhost,127.0.0.1,::1'

# Focused 示例：一个调用期间不启动第二个重型 Gradle。
python scripts/gradle-coordinator.py run --key aex-catalog -- .\gradlew.bat :app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.extension.api.ExtensionApiSharedCatalogTest"

# 最终共享/Android 完整验证示例；AEX-00 核对并补足 source-api/data 等受影响 target。
python scripts/gradle-coordinator.py run --key aex-final-android -- .\gradlew.bat spotlessCheck :domain:jvmTest :app:testReleaseUnitTest

# Desktop 全量与正式构建使用项目构建脚本安排，避免同一 diff 重复全量。
# bash scripts/build-desktop.sh
```

MockWebServer 必须覆盖成功、空/缺失、403/429/500、畸形、取消和错误签名；真实 parser 不得被 mock。导航/DI/HTTP wiring 必须能因生产连接被破坏而失败。源码文本扫描、符号存在性检查和复制实现的测试不作为行为验收。

## 7. 用户验收清单

以下是专项最终验收门槛，不是“均未执行”的状态清单。AEX-00 至 AEX-05 已完成的实现、审查、测试与提交见第 5/8 节；AEX-06 的部分发布证据见文末。最终勾选须核对每项完整范围，不因这里尚未勾选而重复已有效完成的验证，也不以 debug/JVM 证据替代明确要求的正式运行时验收。

### AEX-06 执行出口（2026-09-16 最终核对）

| 出口 | 已有可复用证据 | 尚需动作 / 依赖 |
|---|---|---|
| Android 正式安装安全及更新 | AEX04/05 已提交的系统/私有升级、拒绝、信任及数据保全整链；rc8 双安装入口、ABI、真实源查询、下载及离线冷启；已补 rc8 信任UI和系统旧版→新版/签名冲突/损坏拒绝黑盒证据 | 已关闭：复用rc8发布签名/损坏拒绝；rc9补旧来源确认取消/继续、私有升级与冷启。未声称在rc9重跑私有升级全部反例 |
| 已知单站限制（不自动追加修复） | MangaPlus 缺类/JNI 已有红绿修复；当前 HTTP200、4字节错误字段响应仍失败；MangaDex 正式查询阅读下载已通过 | 保留失败，不声称外部故障或全站兼容；停止重复查询/解码诊断。新增调查须用户另行决定，不以该站必须成功替代“代表性真实源”门槛 |
| Desktop 正式 Test Mode | 隔离 profile 已获批实现；Windows/macOS 0.11.19.35 正式构建，Windows 旧 APK 运行验收，Mac 五类 Reader fixture 首屏及关闭均通过；完整 JVM 与相关回归证据可复用 | 已补 Windows 正式 GUI 五类 Reader 验收（本节末新增证据）；不重跑已有效覆盖项，不将通用 13-family 清单的既有 partial 项变成新增能力任务 |
| 最终变更审查 | AEX06 原首审/修复复审、Zstd 定向审查已完成；Desktop 隔离额外审查发现 P2，已主线程 RED/GREEN 修复 | 已关闭：新增来源确认1轮独立窄审查无阻塞发现；沿用既有审查边界，不虚构Desktop隔离的第二轮独立复审 |
| ARM 正式运行验收 | rc8 ARM64/API36 的 5 项 ABI/Zstd 已通过；真实私有安装、旧 1.4 搜索/详情/单章下载/离线冷启已取得证据 | 已关闭：rc9同签名1.4.202→1.6.0 Private，确认/取消、冷启、真实查询/详情刷新、在线翻页、新Ch.3下载和实际断网冷启2/9→9/9通过，旧系统包字节不变 |
| 提交与交付 | 产品基线 AEX05 `dbf3f050a1`；AEX06 dirty 候选产物和增量证据已记录；交接文档提交不是产品完成提交 | 已关闭：产品提交b3d81b34dc，rc9签名/受测SHA与构建来源见文末；本次仅补实际运行证据，完成判断依赖产品提交及累积有效验证，不以文档提交替代实现 |

第 7 节清单保留原必需范围，旧来源升级、ARM新版业务与Windows GUI缺口现已补齐。勾选复用AEX00–05、rc8有效发布反例和最终rc9新增链证据，不表示全部历史场景在rc9重复执行。Mac 用户报告网络提示，当前没有确切页面/错误原文，单列待澄清；已有五类本地 fixture 成功不证明真实互联网链路成功，也不授权网络层重构。

发布安全补验前置核对：现有 `ExtensionUpgradePreservationInstrumentationTest` 通过debug内部manager/DI组织整链，不能直接作R8黑盒复验；旧、新及冲突签名APK均已有固定fixture，无须重造。正常添加本地旧/新MangaDex仓库会由 `CreateExtensionRepo` 检出与现有Keiyoushi相同签名指纹，不能静默绕过。后续在专用模拟器采用可恢复的仓库/扩展临时切换，先记录原仓库入口和扩展字节，再走真实UI安装/更新/拒绝并恢复；保留原书库与下载。Android多用户共享同包安装版本，不把增加用户当作旧/新APK完全隔离方案。此段仅记录已检查的约束，尚未实施切换或通过升级验收。

- [x] 旧安装数据升级 → 原仓库、扩展信任、书架、进度和源设置保留；版本/签名不允许覆盖时使用明确的备份迁移流程，不卸载主实例试错。
- [x] 浏览 → 扩展 → 刷新原 Keiyoushi 仓库 → 能看到兼容的新扩展；不会因旧目录下线或一次请求失败全体变成已废弃。
- [x] 扩展仓库 → 分别输入旧 index.min.json 和新版索引/deeplink → 正确识别同一仓库；重复输入不产生重复项；重启后仍能刷新。
- [x] 扩展列表 → 安装真实 1.6 APK → 系统/私有安装各自成功后显示可用；进入源能搜索和打开漫画。
- [x] 扩展更新 → 同签名新版成功；不同签名、损坏文件或未知协议版本被拦截并说明原因；不会自动扩大信任。
- [x] 不受信任扩展 → 查看并确认信任 → 仅指定扩展/签名变为可信；拒绝后不加载其代码。
- [x] Source-only 测试源 → 热门/搜索/筛选/可用时最新 → 分页正常；全局搜索能找到并进入详情，不依赖 CatalogueSource 类型。
- [x] 漫画详情/书架更新/元数据更新 → getMangaUpdate flags 和调用次数符合请求；已读章节、下载对应关系及自定义元数据不被误改。
- [x] 测试扩展返回 memo → 退出重启、再次更新、备份恢复后再次更新 → 漫画和章节 memo 保真传入；旧备份仍可恢复。
- [x] 新版源 → 阅读章节、翻页、下载并离线打开 → 正式 release APK 正常；失败有明确反馈，不呈现空白成功。
- [x] 断网/限流/一仓库失败/安装取消/安装拒绝 → 保留可用旧数据和正确状态，恢复后可重试；成功的空目录与请求失败可区分。
- [x] 最低/当前支持 Android 系统和代表性 ABI → 同一发布配置的 R8 产物通过关键工作流；受测产物：[rc9 APK](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.1-rc9/Mihon-Fork-0.19.4-aex.1-rc9-universal.apk)，SHA `12f83d907ac6866c9fc6a321025bb78cb4945b8e1077f0d0274b007bff74671d`，API26及ARM64/API36各5项ABI通过，实机业务见文末。
- [x] Windows/macOS 正式 Desktop → 原 JAR 优先、旧 APK 兼容、Authors、FlareSolverr、源查询和阅读/下载行为无回归；正式[Windows EXE](D:/Shell/Github/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.35.dbf3f05-unpacked/Mihon%20Desktop.exe)，Mac正式应用 `/Applications/Mihon Desktop.app` 0.11.19.35；双端五类Reader证据与既有扩展回归见文末及交接索引，不外推通用13-family清单。
- [x] 受影响单元/集成/wiring、最终全量与格式检查 → 全部通过或明确未通过门槛；每项 capability 状态与机器权威一致。Android369项通过；Desktop3027项中唯一证据行号失败已定向修正复验、2项既有跳过；不把首次全量命令写为exit0。

## 8. 实施证据与风险记录

本节是本专项唯一实施证据记录。AEX-00 经独立审查、定向修复及用户批准的补修复验后，代码、样本和 runner 已通过主模型验收。2026-09-13 用户明确允许本批次豁免 Luna 目标回执校验，依据主模型独立证据提交并继续 AEX-01；该例外仅限 AEX-00，不豁免产品测试，也不把有缺口的子 goal 回执改写为通过。下文保留历史失败、修复和验证边界。

| 批次 | RED 与失败原因 | GREEN/回归命令和结果 | 独立审查 | 产物/运行环境 | 提交 |
| --- | --- | --- | --- | --- | --- |
| AEX-00 | `source-api` 首次 RED：`FilterList()` 实际 size=0 而测试断言 1，确认为测试输入错误并修正，非产品 RED；外部 fixture 首次 plugin marker 未缓存、首次导出把 `--tests` 传给 Jar task、ART 首次未引用参数被 PowerShell 拆分，均为 runner/命令配置故障并留存日志；ART 离线首次另因 AndroidX Test/Compose AAR 未缓存失败，未执行用例 | `:source-api:jvmTest --tests "eu.kanade.tachiyomi.source.SourceApiCurrentBaselineTest"` exit0，XML tests=2 skipped=0 failures=0 errors=0；`:source-api:testReleaseUnitTest --tests "eu.kanade.tachiyomi.source.SourceApiCurrentBaselineTest"` exit0，XML tests=2/0/0/0；`:app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.extension.api.ExtensionApiSharedCatalogTest"` exit0，XML tests=8/0/0/0；`:app-desktop:jvmTest --tests "mihon.desktop.extension.DesktopExtensionApiSharedCatalogTest" --tests "mihon.desktop.extension.DesktopExtensionLoaderTest" --tests "mihon.desktop.extension.DesktopExtensionArtifactAuthenticityTest"` exit0，XML catalog=11/0/0/0、loader=15/0/0/0、authenticator=8/0/0/0；外部 v1.5 fixture test exit0，XML tests=1/0/0/0；外部 v1.6 static probe fixture test exit0，XML `app-desktop/tmp/aex00-external-v16/build/test-results/test/TEST-aex00.external.v16.V16ContractTest.xml` tests=1 skipped=0 failures=0 errors=0；v1.6 JAR export exit0，unsigned hash `50628427539f0b2839d251dd2c3af773456ff07763aabef188f2ec3737cd1d9c`，签名 JAR hash `e623be999c1c6b7c9a5383253645f6dc3d496de44d44a4fa1cd1a4fe020c33c4`，受控 v1.5 APK hash `caf80d849e2eb5ad8f0be5121f914d9cee1ee06c15653d15f33321602183a316`、v1.6 APK hash `34c21ef4c3a5b60b789cd5dce95a78f638ba9875007f19d094cb3e344df4e182`；在线依赖重试后 `BrowseSourceUiWiringTest` ART exit0，专用 `emulator-5580` API36/x86_64 XML tests=4 skipped=0 failures=0 errors=0；签名后 Desktop loader/authenticator 亦已 exit0（上述 XML loader=15/0/0/0、authenticator=8/0/0/0） | 主模型已核验 C1–C3；C4 的代码/范围审查通过，目标回执按用户本批次豁免关闭；见本节独立复验 | `source-api/src/commonTest/resources/aex00/fixture-manifest.json`；固定索引；真实 1.4/1.6 APK/JAR；签名受控 v1.5 Source-only JAR+APK 及 provenance；签名受控 v1.6 SourceFactory/memo/flags/error JAR+APK 及 provenance；外部 v0.19.4/v0.20.4 独立源码/依赖生成器；设备报告 `app/build/outputs/androidTest-results/connected/debug/TEST-mihon-aex-api36(AVD) - 16-_app-.xml`；1.5 历史第三方发布物未找到，受控样本不冒称历史 provenance；C3 尚未执行 1.6 ABI/正常安装整链 | 与本行同一 AEX-00 提交；提交主题 `test: freeze AEX-00 extension fixtures and add foreground verification` |
| AEX-01 | 有效 RED：`aex01-desktop-red` 为固定外部 probe 调用缺失 `Source.getMangaUpdate`；`aex01-art-page-red3` 为真实 ComicFury 缺失 Android Uri 形态 Page 构造器；`aex01-review-image-override-red` 为零偏移新重载绕过旧 override；`aex01-art-final5/6` 与 `aex01-network-red` 为 Android 默认压缩链不兼容。编译错误、测试输入错误与中间绕过诊断不计产品 RED | 主模型最终 `aex01-main-final-verification` exit0（2026-09-13 11:46:58 UTC）：Source API JVM 17、Android release unit 17、core Android network 2、Desktop 21、API36/x86_64 ART 4，共 61 项，0 failure/error/skip；三个相关模块 spotlessCheck 通过，git diff --check 通过。此前模块完整 `aex01-source-phase-final` 两 target 各 19 项通过 | C5、A1–A4 与本批 C4 经首审及一次定向修复复验通过；仅 API/加载组件，不包含版本准入和安装整链 | 日志 `.gradle-coordinator/aex01-main-final-verification.log/.json`；各模块 test-results XML 与 Android connected/debug XML。7 个本批固定 APK/JAR 大小/hash 复核未变；Page Android Uri/JVM Object、旧 Authors 页图和 child-first 回归保留；详情见第 9 节最终复验 | 测试、production 与本次 checkoff 随同一功能提交；提交号见本文件 git 历史 |
| AEX-02 | 接续新增真实 RED 覆盖目录跟随/二进制响应/有界读取、显式地址、失败快照、不兼容与所属仓库判断；复审另发现 protobuf/JSON 判别、未知 meta、改名缓存及指纹等价缺口，均已修复。继承的先改后测和测试装配错误不改写为产品 RED | 主模型核验原始 XML：domain JVM 424、Android 359；data JVM 136、Android 10；app focused 50；Desktop focused 81；ART 3，共 1063 项，全部实际执行且 failure/error/skip=0。平台证据 `aex02-final-green-art1`（该轮 data 尚失败）；data fixture 修正后 `aex02-final-data-fixtures-green1` exit0，1m4s。app/domain/data/i18n 无 hook 真实 spotlessCheck 与 git diff --check 通过；Desktop 无 Spotless 插件，人工检查受影响格式，不声称执行不存在的 task | 共享协议一次独立审查及一次定向修复复审通过；主模型独立核对平台、存储、备份、UI、连带 fixture 差异及最终日志/XML。C6 和本批 C4 通过 | 专用 `emulator-5580` / `mihon-aex-api36` / API36 x86_64；上述日志位于 `.gradle-coordinator/`，XML 位于各模块 test-results 与 app connected/debug。Android loader 1.6 准入尚属 AEX-04；Desktop 失败警告沿用即时投影，不新增持久状态机 | 测试、production、必要迁移 fixture 与本次 checkoff 同一功能提交，hash 见本文件 git 历史 |
| AEX-03A | `aex03a-discovery-red2` 为 Android 真实 manager 漏 Source-only；`aex03a-query-red` 为 Android 筛选项应 1 实为 0、Desktop 真实浏览页从旧投影找不到源。首轮泛型编译错误、MockK getter 混淆和 ART Proxy 参数错误均为装配问题，不计产品 RED | `aex03a-validation` exit0：domain JVM 46、Android 6，app 23，Desktop 136，ART 4，相关 app/data/domain 格式通过。定向 `aex03a-review-verification` 中 Desktop 2 类 5 项通过，整轮因 ART 装配失败为 exit1；最终 `aex03a-art-final` exit0，真实数据库导航及模式按钮对照在内 ART 6 项通过，app 格式和 debug 编译通过。各最终目标 failure/error/skip=0；主模型核验原始日志和当前 XML，未重复计算替代轮次 | 主模型一次独立审查及定向复验完成；补齐两端 latest 入口可见性和 Android 真库结果导航，不接受固定 ID Proxy 作持久化证据。C7 和本批 C4 通过 | Android API36 x86_64、专用 emulator-5580。Desktop 离屏真实导航/HTTP parser、Authors、本地标记和旧源查询回归通过；不冒称本地磁盘重验或完整 APK 安装。日志 `.gradle-coordinator/aex03a-*.log/.json`；共享新契约 `tachiyomi.domain.source.service.SourceOnlyQueryContractTest` 在 domain JVM/Android 两 target 执行 | 测试、production、必要 UI 提取与 checkoff 同一功能提交，hash 见本文件 git 历史 |
| AEX-03B | 统一调用/flags、memo 映射、已有章节恢复、Reader 转换、Desktop 下载与 Source-only 详情入口均有真实行为 RED；ART 另暴露文本默认值读取尾部 NUL。装配失败与后补旧实现对照如实分列 | domain JVM/Android 433/368；data 138/12；app 23 个唯一案例；Desktop 292；ART 5。最终相关结果无失败/错误/跳过，详见本节末尾原始 runner 与复验范围 | 主模型审查真实 diff、固定上游及报告，定向补 UI 错误提示、页面点击时机、旧库装配与 ART 默认值修复后复验通过；C4/C8 通过 | API36 x86_64 专用 AVD；真实库更新/关闭重开/备份恢复/页图与文件落盘。Source-only 查询/更新及旧 HttpSource 图片消费者分别验收，不冒称 APK 安装或任意 Source 图片引擎 | production、测试及 checkoff 同一功能提交；hash 见本文件 git 历史 |
| AEX-04 | 进行中：`aex04-protocol-red` 证实范围判断误收未知协议、Android 拒绝 1.6；`aex04-loader-metadata-red` 证实显式协议未进入真实信任门；`aex04-installer-protocol-red` 的三项事务断言分别证实协议不匹配未拒绝、现代版本名掩盖降级、提交丢失显式协议；`aex04-gateway-metadata-red` 证实 APK inspection 将显式 1.6 误读为版本名的 9.0；`aex04-request-mapping-red` 证实目录项到安装请求遗漏协议 | 阶段内 checkpoint `aex04-metadata-checkpoint-green` exit0/1m9s：共享契约 JVM/Android 各 2；Android metadata 5、loader/真实 trust 与 gateway 接线 1、安装安全 26、manager 12、coordinator wiring 10，共 58 项，无失败/错误/跳过。app/domain 无 hook spotlessCheck、git diff --check 通过。此前加载器接线复验暴露测试用 InMemoryPreferenceStore.getStringSet 未实现，改用 AndroidPreferenceStore，不算产品 RED。测试中的 PackageManager 仍是边界模拟，不算签名 APK ART 验收 | 待批次独立审查 | 用户已接入 SM-S9280，ARM64/API36；仅只读核验，设备已有 app.mihon，不覆盖其安装。自动化继续指定专用 emulator-5580。真实普通权限安装、完整业务、重启和正式产物仍待验证 | 未提交、未勾选 |
| AEX-05 | Source-only源设置未导出、漫画/仓库取消后仍计进度，均先有正确RED；历史fixture哈希、目标文件、仓库唯一指纹及强杀时序装配失败单列 | 最终 `aex05-repo-cancel-green` 68项相关测试、真实格式检查通过；固定历史备份、真实旧宿主schema18→20/目录升级/下载读取/重启、MangaDex在线查询更新页图、跨签名拒绝保全/备份恢复/重启及升级下载中断重试ART通过，详见文末 | 1轮独立批审+1轮定向修复复审；仓库取消与默认测试门控问题已修复，主模型核验原始结果 | 专用API36 x86_64模拟器；隔离应用 `app.mihon.aex05.dev`；测试签名，不冒称正式fork/R8/ARM | 测试、production及checkoff同一功能提交，hash见git历史 |
| AEX-06 | — | — | — | — | — |

历史环境事实和验证边界（以下保留首次审查时记录，后续补修结果见下文）：`app` 当前 `minSdk=26`、`target/compileSdk=36`；专用 `emulator-5580`（AVD `mihon-aex-api36`、API 36、x86_64）已执行 `BrowseSourceUiWiringTest` 4 个用例，tests=4、skipped=0、failures=0、errors=0。API 26、ARM、release test APK/R8 和 1.6 正常安装/ABI 整链仍未验证。当前 Android 身份为 `applicationId=app.mihon`、`versionCode=18`、`versionName=0.19.4`；没有 release `signingConfig`（现有 preview/benchmark 复用 debug signing），不代表未来 fork 的 release 发布身份。`app-desktop` 没有 `spotlessCheck` task，因此该任务解析失败不能记作格式通过；`source-api:spotlessCheck` 实际通过。主模型复审用 Gradle 重跑被工具策略拒绝启动，协调器状态保持 `NOT_STARTED`；原始 JUnit/XML 通过报告未改写，未能重跑的验证仍保持未验证。

### AEX-00 首轮主模型核验与历史暂停点

- 固定资源：独立核对 12 项可用索引/二进制的 SHA-256 与大小，全部与 manifest 一致；受控 1.5/1.6 APK 的原始 metadata 和 v2/v3 签名通过。使用现有独立编译的 unsigned JAR 重新执行 APK packager，两个输出分别与 `caf80d849e2eb5ad8f0be5121f914d9cee1ee06c15653d15f33321602183a316`、`34c21ef4c3a5b60b789cd5dce95a78f638ba9875007f19d094cb3e344df4e182` 完全一致。测试密钥未提交、未用于应用发布。
- 外部编译输入：旧版 10 个、新版 17 个源码文件及实际编译副本均与对应官方固定 commit 的 git blob 一致；单独声明的 Rx JVM adapter 不冒称原始 API 文件。两份受控 JAR 的 1/4 个 class payload 分别与 unsigned 导出物一致，不包含宿主 API 类。
- 运行证据：独立读取 9 份原始 JUnit XML，合计 52 个用例，失败/错误/跳过均为 0，其中 Android ART 基线为 4 个。`source-api:spotlessCheck` 原始记录 exit 0；本批次不声称 Desktop 存在同名格式 task。这些记录不替代尚未执行的主模型 focused 重跑，也不证明 1.6 ABI/安装/业务整链通过。
- 首轮文档缺陷已修正：移除发布示例中的 telemetry/updater 开关，声明 release runner 的待实现前置，将 catalog 契约放回 domain，分离直接 ART classloader 组件测试与 AEX-04 完整准入，并区分样本提供的行为和真正执行过的断言。主模型对 3 个 PowerShell 脚本、3 个 JSON 和 `git diff --check` 的复验通过。
- C2 复验仍未通过：生成器的 `build.sequence` 把绝对 `$outputPath` 传回仅按仓库相对路径拼接的 `-OutputDirectory`，只读重现得到 `D:\Shell\Github\mihon\D:\Shell\Github\mihon\app-desktop\tmp\aex00-external-v16`；自定义目录的 package 步骤也未传对应 `FixtureProject`。另需补齐生成清单的 launcher 依赖、v16 签名验证字段路径与可执行签名说明。上一轮仅做语法解析，未实际重放生成的复现步骤，故未发现这些语义缺口；固定 APK/JAR 不受影响。
- C3 待验证：主模型启动 `aex00-main-focused` 被工具策略拒绝，状态核对为 `NOT_STARTED`。源码确认 `run` 仍调用同一个后台 `command_start`，未换命令或委派他人绕过。尚待用户选择增加真正前台模式或手动运行验证；新增前台模式不是已实施功能。
- 预算与边界：已使用 1 轮独立审查、1 轮定向修复复验。若获准追加，仅修正上述 C2 复现链并真实重放，预计 5–10 分钟；构建协调器前台模式另需一个限域批次，预计 20–40 分钟。未获新方向前不启动 AEX-01、不勾选 AEX-00、不作纯状态推进提交。
- 内聚性与风险：本批次超过 8 个文件/400 行，内容共同组成协议快照、独立样本、复现元数据和当前生产基线探针；没有产品行为变化。主要风险是二进制来源/签名、运行环境依赖与把基线误认作兼容性证明，已分别保留核验与未验证边界，不按文件机械拆分提交。

### 2026-09-13 获准的限域补修

用户已批准修正剩余复现命令并增加真正前台 Gradle 模式。恢复时 HEAD 为 `4bee4aab96d1090103a7ee18967ed48b621a43ec`；新增的多设备同步演示提交及未提交的 `docs/prototypes/multi-device-sync/ui-browser.test.cjs` 与本专项无关，全部保留、不混入提交。

流程预算：沿用一个 GPT-5.6 Luna（xhigh），主模型只读准备验收；先复现失败、最小实现、定向回归，再做本次获准的一轮主模型复验与 AEX-00 抽验。全量测试 0 次，不新增计划/报告，预计 25–50 分钟。允许写入现有 fixture 脚本及其 manifest/provenance、`scripts/gradle-coordinator.py`、对应 `scripts/tests/` 测试和本节证据；不得改产品行为、签名身份、固定样本二进制或其他工作流。发布密钥尚未授权，本次不创建。

| 补修验收 | 输入与操作 | 预期及证据 |
| --- | --- | --- |
| R1 / 对应 C2 | 在仓库相对路径和含空格的受控输出目录实际运行生成器，核对并重放生成命令；校验依赖与签名字段路径 | 生成与 package 命令指向同一明确目录；不重复拼接盘符；正常签名说明可执行、密钥不公开；默认固定样本与源码 hash 不变。测试须调用实际脚本，不能仅扫描字符串或解析语法 |
| R2 / 构建工具 | 新增显式前台入口，真实启动成功/失败/快速退出/持续运行的受控子进程，并测试状态查询、重复启动和取消 | 当前协调器同步持有子进程，不创建 detached worker；日志、退出码、PID/identity 和状态准确；同 key 不重复启动；取消或异常清理不触及调用 shell、无关进程或复用 PID；原有 start/run/wait 状态语义保持 |
| R3 / 对应 C3、C4 | Python focused 红绿与协调器相关回归、脚本解析/格式；前台入口执行 AEX-00 代表性 source-api 与 Desktop production 测试 | 真实命令可执行、必要测试非零且不跳过，退出码及原始报告可核对；不绕过工具限制、不启动并行 Gradle；只有全部通过并提交后才能关闭 AEX-00 |

本轮若仍失败或出现必须扩大范围的依赖，先记录具体未通过项及证据再请求方向，不自行追加审查/修复轮次。实现代理自测后停止写入和 Gradle，主模型负责独立验收与 scoped commit。

本轮限域补修实测记录（2026-09-13，仍为 Luna self-tested，主模型需独立复验）：先前 coordinator RED 为 `python scripts/tests/gradle-coordinator-test.py` exit 1，14 项中 5 项失败，原因是 `foreground` 尚未接入 argparse；修复后的同命令 exit 0，17 项、失败/错误/跳过均为 0，耗时 9.772 秒。生成器 RED 为 `python scripts/tests/aex00-fixture-generator-test.py` exit 1，实际生成清单显示 `testCommand` 仍使用旧 `run`；修复后同命令 exit 0，1 项、失败/错误/跳过均为 0，耗时 28.020 秒，实际重放默认与含空格目录的 generate/test/export、临时目录 package 及 `aapt2` metadata 检查。唯一前台 Gradle 抽验使用 key `aex00-20260913-foreground-focused`，命令为 `python scripts/gradle-coordinator.py foreground --key aex00-20260913-foreground-focused --timeout-seconds 1800 -- .\gradlew.bat :source-api:jvmTest --tests "eu.kanade.tachiyomi.source.SourceApiCurrentBaselineTest" :source-api:testReleaseUnitTest --tests "eu.kanade.tachiyomi.source.SourceApiCurrentBaselineTest" :app-desktop:jvmTest --tests "mihon.desktop.extension.DesktopExtensionArtifactAuthenticityTest" --tests "mihon.desktop.extension.DesktopExtensionLoaderTest" :source-api:spotlessCheck --no-daemon --offline`，协调器 exit 0，`workerPid=50840`、`processPid=34680`、`executionMode=foreground`，日志 `.gradle-coordinator/aex00-20260913-foreground-focused.log`；Gradle BUILD SUCCESSFUL（47 秒，97 actionable tasks，19 executed/78 up-to-date）。原始 XML 分别为 source-api JVM 2、source-api release unit 2、Desktop authenticity 8、Desktop loader 15，合计 27，失败/错误/跳过均为 0；报告路径保持原样。固定 APK/JAR hash 未因本轮文档/脚本修复改变，未执行 ART/ABI/正常安装整链，未提交且未修改用户 `testfile/`。

### 2026-09-13 本轮主模型独立复验

- R1：`python scripts/tests/aex00-fixture-generator-test.py` exit 0，1 项，29.931 秒，无失败/错误/跳过。实际重放默认及含空格相对目录的生成命令、默认 test 和含空格目录 export，再在该测试自有临时目录打包并读取原始 APK metadata。另分别重放 v15/v16 生成清单中的 JAR 签名和对应新输出验签命令，均 exit 0；自签名、无时间戳及公共证书链警告仍是 fixture 边界。独立复核 12 项固定资源的大小和 SHA-256 全部一致；重新签署的临时 JAR 不冒称与固定签名 JAR 字节相同。
- R2：`python scripts/tests/gradle-coordinator-test.py` exit 0，17 项，10.625 秒，无失败/错误/跳过。验证运行环境为本机 Windows/Python 3.14；包含真实直接子进程、同 key 互斥、不同命令拒绝、旧 owner 状态覆盖防护、启动异常清理、超时/取消与无关进程保护。POSIX/macOS 分支本轮仅审阅代码，未在该平台运行，不能外推为 macOS 已验收。
- R3：主模型串行运行 `python scripts/gradle-coordinator.py foreground --key aex00-main-20260913-foreground --timeout-seconds 1800 -- .\gradlew.bat :source-api:jvmTest --tests eu.kanade.tachiyomi.source.SourceApiCurrentBaselineTest :source-api:testReleaseUnitTest --tests eu.kanade.tachiyomi.source.SourceApiCurrentBaselineTest :app-desktop:jvmTest --tests mihon.desktop.extension.DesktopExtensionArtifactAuthenticityTest --tests mihon.desktop.extension.DesktopExtensionLoaderTest :source-api:spotlessCheck --no-daemon --offline`，exit 0，38 秒，97 actionable tasks（13 executed、84 up-to-date）。日志 `.gradle-coordinator/aex00-main-20260913-foreground.log`；协调器 `executionMode=foreground`、状态 `PASSED`。本次 Desktop 23 项实际重跑，Source API 4 项命中未变化输入的 up-to-date 结果，原 XML 时间戳和缓存事实保留，不写为四项重新执行。
- 格式与数据：3 个 PowerShell 脚本、3 个 Python 文件语法解析及 `git diff --check` 通过；JSON 实际读取通过；`source-api:spotlessCheck` 通过。全量项目测试 0 次，无新增发布构建。历史 4 项 ART 基线继续仅证明既有 UI wiring；本轮未执行 1.6 ART/准入/安装业务整链。
- 流程证据未通过：Luna 的目标回执 hash 与下达值不一致；补充原文经主模型计算为 2132 个 UTF-8 字节，与其自报 2287 字节不符，且原文混入上一轮“不得修改协调器”的旧约束；再次要求直接转交工具数据后，回执 Base64 不能通过标准解码。主模型不能据此确认持久子 goal 与本轮下达目标完全一致，也不采信其 complete 作为验收依据。以上是目标交接/回执缺口，不是上述代码测试失败。本次独立复验结束时曾据此暂停。随后用户明确批准：仅本批次豁免该回执校验，以主模型独立验收结果提交并继续 AEX-01。因此保留流程偏差记录，关闭本批次，不增加修复复审、不重复已通过测试。
- 工作树：复验期间其他任务新增提交仅影响 `docs/prototypes/multi-device-sync/`，未修改本批次受测生产输入；不将它们或用户 `testfile/` 混入提交。设备状态在本轮结束前复查已出现 `emulator-5580`，不沿用本轮开始时无设备的状态判断后续 ART 可用性。

### AEX-00 提交完整性

主提交为 `3fb5507461d68157d369491c6f8ef3a2396d405f`。提交输出暴露 `.gitattributes` 的全局 `text eol=lf` 将固定 gzip/protobuf 索引从 104412 字节归一化为 104411 字节；工作树原始样本及已执行测试输入没有变化，但该 Git blob 不可作为有效 fixture。随本节的补修提交为该目录的 `*.pb` 明确设置 `binary -eol` 并重新加入原始字节。验收为 `git rev-parse HEAD:app-desktop/src/test/resources/extensions/index/keiyoushi-index.pb` 与 `git hash-object --no-filters app-desktop/src/test/resources/extensions/index/keiyoushi-index.pb` 相同，且大小 104412、SHA-256 为 manifest 中的 `5135fef342c60f83c60b9abb865bc69630f0cc4ebc47a1cb75bc9fbebb2814ba`。这是提交表示层的机械配置修正，不重复运行应用测试，不改动协议或测试断言。

关键风险及停止边界：

1. **二进制兼容范围大于表面接口差异**：外部 APK/JAR 实测出现依赖或桥接缺失时，先定位到冻结 API/依赖符号；必要的兼容修复留在本专项。若需要平台/依赖体系重构，先报告估算与范围变化。
2. **数据与备份迁移**：失败必须可重试且不破坏原数据；采用测试数据验证，实际用户迁移前备份。不得在未说明数据兼容边界时承诺旧版本可直接降级打开新数据库。
3. **签名、设备及发布身份未知**：AEX-00 记录真实状态。没有官方私钥不能原地覆盖官方安装；无设备可先做非设备测试，但 AEX-06 不得勾选完成。
4. **外部网络/站点变化**：离线 fixture 证明协议和调用；实时验证证明选定时点、选定站点和产物，不泛化为全仓库所有站点。需要的实时验证无法执行时保留待验收状态。
5. **共享代码影响 Desktop**：必须用同一契约与正式 runtime 回归；不因当前目标是 Android 而跳过 Desktop 安全/ABI/数据验收，也不借机删除其独有能力。
6. **依赖其他暂停计划**：只使用已存在的稳定实现和本专项最小必要接口，不隐式推进非 Reader、Authors 或 Reader 相邻工作。若无法在该边界交付，先说明具体依赖并请求重新排期。
7. **完成标准失真**：catalog 成功、安装成功、类加载成功、业务成功和正式产物成功分别记录；缺任一必需层次，最终状态只能是部分完成。

## 9. 实施契约与主模型验收（含历史记录）

用户已明确授权实施本文件全部内容。主模型负责范围、标准、独立审核与提交；实施由 GPT-5.6 Luna（xhigh）承担。同一上下文优先复用一个子代理，各批按前置顺序交接；Luna 不创建下级代理，不擅自提交、发布或勾选完成。持久子 goal 的实际线程隔离与 objective 原文由双方核对；线程标识只用于临时交接，不写入仓库。

执行基线：`568ea7bbc8edfa1b68ef6f0b018b1a8cd45ee5d4`；开始时仅有用户未跟踪目录 `testfile/`，不得读取其中无关数据、修改或提交它。目标协议及产品边界仍以第 1–7 节为准，下面的 C* 不替代原有必需验收。

### 单元交接与文件所有权

| 单元 | 前置 / 输入 | 实现步骤与允许写入范围 | 输出 / 验收 |
| --- | --- | --- | --- |
| AEX-00 | 本契约、固定上游 API、现有测试和 SDK | 冻结协议/样本 → 接通最小测试配置与基线探针 → 具体化矩阵；可改本计划第 6/8 节、source-api 的测试配置/测试、app 的测试配置/androidTest、既有共享测试资源及本专项 fixture 脚本；实施中已明确追加授权既有 DesktopExtensionLoaderTest、DesktopExtensionArtifactAuthenticityTest 的基线探针和第 3.3/5 节的受控 1.5 替代说明；不得改产品行为。计划顶层状态与第 9 节由主模型维护 | 可执行矩阵、固定样本与基线证据；C1–C4 |
| AEX-01 | AEX-00 审核后的样本和 runner | 红绿实现 source-api、必要 core/network ABI 与平台加载基础组件；对应测试同批；不提前放开 APK 准入 | 双平台真实 ABI/旧桥契约；C4、C5 |
| AEX-02 | ABI 与仓库协议契约 | 红绿实现 domain/data 的 repo/catalog、两端 ExtensionApi/仓库 UI、必要 schema/备份字段；删除重复协议判断 | 仓库和目录完整闭环；C4、C6 |
| AEX-03A | AEX-01/02 已审实现 | 红绿调整真实 SourceManager、共享查询与两端浏览/搜索/导航；不改 memo 持久化格式 | Source-only 查询可用；C4、C7 |
| AEX-03B | 查询边界稳定 | 红绿接通统一更新、source/domain/data 映射、schema/backup 与既有 UI/作业/阅读下载消费者 | memo 和更新闭环；C4、C8 |
| AEX-04 | 前面各组件及业务契约通过 | 红绿修改 Android loader/manager/install adapter 与共享兼容状态消费者；设备整链测试 | 正常签名 APK 安装到业务闭环；C4、C9 |
| AEX-05 | 安装与业务整链通过 | 增补升级/恢复 fixture 和回归测试；新发现 bug 回到所属生产边界按红绿修复 | 旧数据、部分失败与恢复；C4、C10 |
| AEX-06 | 全部实现与相关回归通过 | 最终全量、格式、R8 Android 与正式 Desktop 构建/运行；只改必要发布配置、相应测试和本计划/受影响 parity 证据 | 可核验正式产物及完整验收；C4、C11 |

跨单元授权按主模型交接推进，不能因为表中列出后续可写模块就提前修改其行为。Luna 实施期间主模型只读准备独立验收；同一 worktree 的 Gradle 由当前指定协调者串行运行。每批自测后暂停写入，交还主模型审核、记录并提交，再接收下一批。

### 冻结验收 C*

所有状态初始 pending；Luna 提供结果只能记为 self-tested，主模型复核当前产物后才能写 pass。正常、边界、失败及兼容性矩阵完整继承第 5–7 节，不允许以总分或部分通过抵消硬性缺陷。

| ID / 单元 | 输入与操作 | 可观察预期 | 验证方式与必需环境 | 验收人 / 状态 |
| --- | --- | --- | --- | --- |
| C1 / AEX-00 | 对照冻结上游源码、现有生产入口和第 6 节逐行补矩阵 | 每个必需行为有真实入口、唯一负责批次、具体测试/source set、命令/runner、预期失败原因和断言；组件与整链无反向依赖 | 主模型逐条语义审阅、命令与文件存在性核查；本地仓库及固定 git 对象 | 主模型 / pass（2026-09-13，见第 8 节） |
| C2 / AEX-00 | 获取/构建固定索引、旧/新 API 真实扩展及独立外部 API 受控样本，核对原始 metadata | 来源/ref、hash、版本、签名、许可和再现步骤可追溯；无可变网络唯一依赖、伪造版本或宿主内重定义 API；缺失样本明确未满足而不冒称通过 | 独立 hash/签名/metadata 检查与受控样本构建；相关编译器/SDK、必要外部资源 | 主模型 / pass（2026-09-13，见第 8 节） |
| C3 / AEX-00 | 在 Android/JVM 共享 target、app JVM、app ART 及 Desktop 层执行当前兼容基线探针 | 准确 runner 可执行且必要用例实际运行、非零、无跳过；记录命令/退出码/测试数/报告；真实设备和正式签名未知项单列 | 主模型抽验代表性探针并核对各层原始报告；Gradle 协调器、SDK、对应设备/运行时 | 主模型 / pass（2026-09-13，见第 8 节） |
| C4 / 全批次 | 审核 diff、红绿日志、未提交状态和平台行为保留 | 仅授权文件；无用户数据/签名绕过/无关重构/捐赠或遥测引入；行为改动均有真实 production/wiring 测试；待验收项不勾选；模型和目标隔离如实报告 | 主模型检查实际 diff 与定向测试；本仓库规则和实际目标工具结果；历史 Luna 要求按后续用户取消指令处理 | 主模型 / AEX-00 通过（历史目标回执为用户明确豁免）；AEX-01、AEX-02、AEX-03A、AEX-03B 通过；AEX-04 起逐批 pending |
| C5 / AEX-01 | 外部二进制调用新旧 Source API、桥接及加载基础组件 | 真实 ABI 与语义符合固定规范；Android ART/JVM 和 Desktop 契约通过；完整 APK 准入仍不冒称完成 | 第 6 节 ABI 矩阵、各 target focused tests 和 ART 组件测试 | 主模型 / pass（2026-09-13，第 9 节最终复验） |
| C6 / AEX-02 | 从旧/新仓库地址进入两端 production HTTP、数据与 UI 链路，注入成功/异常/冲突 | v2 形式、版本/分级/语言、身份迁移、签名和状态正确；失败保留可用数据；Android 未放行版本不可误装 | MockWebServer、真实数据库迁移/备份恢复、两端仓库及列表集成 | 主模型 / 2026-09-15 通过，见第 8 节 |
| C7 / AEX-03A | 受控 Source-only 与旧源通过真实 manager、查询、ScreenModel 和导航 | 源可见，浏览/搜索/分页/筛选/最新能力/取消/错误反馈符合契约；无类型强转回归 | 双平台共享查询场景、真实 UI/DI/导航与 HTTP 集成 | 主模型 / 2026-09-15 通过，见第 8 节 |
| C8 / AEX-03B | 详情/后台作业更新，持久化后重建服务，生产备份恢复再调用并阅读/下载 | flags/次数/章节同步正确；漫画/章节 memo、进度、自定义数据与下载关联保真；失败不静默成功 | 双平台更新契约、真实 SQL 迁移、生产 backup/restore、Reader/下载集成 | 主模型 / 2026-09-16 通过，见 AEX-03B 最终验收 |
| C9 / AEX-04 | 正常签名真实 APK 经系统/私有安装、信任、loader、源注册到全部业务及重启 | 1.6 真正可用；错误版本/签名/损坏/拒绝/取消安全反馈；不靠 Source 注入或 shell 权限替代普通用户流程 | 最终16项ART、跨进程2次运行、119项Android/共享回归及83项Desktop回归；见文末 | 主模型验收、独立整批审查及定向复审通过；随本功能提交完成 |
| C10 / AEX-05 | 旧版本数据升级、跨签名备份迁移与断网/部分失败/中断恢复 | 旧用户状态保全、错误可区分和恢复；选定真实站点有实际 production 验收，缺失不伪报 | 固定旧数据/历史宿主、备份迁移与真实MangaDex链；下载请求阶段强杀后恢复，边界见文末 | 主模型 / passed，随AEX-05提交 |
| C11 / AEX-06 | 最终提交对应 diff 的全量、R8 APK 与正式 Windows/macOS 运行验收 | 第 7 节必需项通过；报告 hash/版本/签名/flags/设备/真实产物路径；Desktop 独有能力不退化；不可用平台不能标完整 | 完整测试/格式、Android release 设备、项目 Desktop 构建脚本与 Test Mode；必要签名/设备/macOS 环境 | 主模型 / pending |

每次交接使用七字段 goal；后续单元在其前置验收通过后沿用本表建立对应契约。goal 完成只代表子代理自测完成，不自动改变本表或第 5 节勾选状态。用量统计由统一插件负责，不另行采集或重复报告。

### AEX-01 执行契约（2026-09-13）

前置：AEX-00 主提交 `3fb5507461d68157d369491c6f8ef3a2396d405f`，Git 二进制保真补修 `95fa5c01de5dabd8066e3ccab993b8c2161f8a0a`。固定源码与样本保持不变。仍使用同一 Luna（xhigh），不新增代理、不与主模型并行写文件或运行 Gradle。AEX-00 的目标回执豁免仅适用于该批次，不扩大本批权限。

预算：预计 2–4 小时；按接口/模型、旧版桥、Desktop 组件、Android ART 四个行为组各执行红绿重构（正常每组三次 focused 验证，可将同阶段相关 task 合并串行执行）。主模型独立审查 1 轮；若有具体失败项，原代理定向修复与主模型复验最多 1 轮。结束时 Source API 模块完整 JVM/Android unit 测试 1 次；整仓全量、完整 Desktop 与发布构建均留到 AEX-06。本计划是唯一计划/报告，不生成逐文件快照。新增依赖体系、迁移或样本替换需求先报告，不能用修改固定 fixture、放宽准入或跳过测试消除失败。

可写：`source-api` 公开 API、模型及兼容 adapter/测试/必要构建配置；只在真实二进制调用证实缺口时修改 `core/common` 的必要 API/网络 adapter；Android 仅 `ChildFirstPathClassLoader` 必要兼容改动、`app/build.gradle.kts` 的测试资源 wiring 与 `app/src/androidTest`；Desktop 仅现有 extension loader/classloader 的必要类型边界兼容和对应测试；本节与第 8 节证据。公共契约优先放在 `source-api` 的共享测试中，平台套件必须实际调用各自 production loader；不能用独立辅助 loader 代替平台证据。设备 fixture 通过测试 assets 定向复用固定 APK，不复制第二份二进制权威。

实施中已由主模型限域授权必要适配：Android 编译报 `ChapterImpl` 未实现 `SChapter.memo` 后，允许该旧 app 模型增加内存字段并由 ART 验证默认值、赋值及 `copyFrom`，不扩展数据库或备份；真实 ComicFury ART 的 `Page` 构造器缺失后，允许在 `core/common` 的 Android/JVM 平台 source set 定义 `PageUri` 普通类型别名（分别为 `android.net.Uri`/`Any`），供 `source-api` 共用模型从已编译平台依赖获得正确签名，不复制模型或改变 Desktop 字节码 adapter。 真实 MangaDex 1.6 APK 随后明确拒绝 Android 默认 client 的 IgnoreGzip/Brotli 成对拦截器，已授权仅移除这两项注册及未用 import；保留旧类/依赖、Uncaught/UserAgent/Cloudflare、cookie/cache、超时/DoH 与旧 client aliases，并以真实旧 ComicFury plain/gzip 回归验证。

禁止修改：仓库目录协议/持久化、安装版本与信任准入、SourceManager/浏览 UI、domain/data/backup 的 memo 链路、其他计划、Authors/FlareSolverr 独有行为、用户演示与 `testfile/`、fixture 二进制/来源/hash、构建协调器和签名身份。真实调用证明必须调整禁止范围时先报告依赖，不先动手。

| 子验收 / 对应 C5 | 输入与操作 | 预期、真实证据和边界 |
| --- | --- | --- |
| A1 接口与模型 | 固定外部 1.6 SourceFactory/Source-only 二进制通过宿主 `Source` 调用查询、分页、筛选、latest、四种 flags、页列表及错误；执行外部静态 `V16SourceAbiProbe` | 首个 RED 是宿主 ABI 缺失，不是编译器/版本拒绝；补齐新成员及 `SMangaUpdate`，`memo` 为宿主同一 `JsonObject` 类型；工厂/复制/章节 copyFrom 保留 JSON。至少共享 JVM/Android unit 契约与平台实际二进制调用通过；Source API copy 不冒称数据库/备份持久化已完成 |
| A2 旧版与 HTTP 桥 | 1.4 Rx、受控 1.5 suspend-only、宿主旧 CatalogueSource/HttpSource 及本地源形状进入新更新接口，四种 flags、异常与取消 | 保持已有 suspend override 调用、旧 Rx bridge、source ID 和页图行为；只请求被指定部分，不重复请求，不吞异常/取消。不能机械照抄上游 CatalogueSource 的直接 Rx 更新而绕过 fork 已有 suspend override；必要差异只保留在共享兼容 adapter。涉及 HTTP 分派的测试执行实际 HttpSource/MockWebServer 链路 |
| A3 Desktop 真实加载 | 已有 `DesktopExtensionLoader.loadFromSingleJar`/`ExtensionClassLoader` 加载固定受控 1.5/1.6 和真实 1.4/1.6 样本，调用离线可验证业务/ABI | 缺失符号和类型隔离问题要有真实失败测试；真实 1.6 factory 使用 `keiyoushi.source.Generated`。保护旧 APK/JAR、Authors 和依赖 child-first 需求；不把把所有 serialization 强制 parent-first 当成无测试的通用修复。真实站点联网整链不属于此项 |
| A4 Android ART | 专用 AVD 的测试 APK assets 复制到唯一测试临时目录，按 Android 动态代码权限要求加载；直接使用 production `ChildFirstPathClassLoader` 调用固定受控 1.5/1.6 APK 与真实样本 | ART 验证与 JVM 结果分列，断言执行非零且不跳过；哈希、类来源、API 类型与结果可核验。不调用完整 ExtensionLoader 来制造准入成功，不使用 shell 安装权限/签名后门，不修改真实 APK metadata。这里只证明 API/类加载组件，不勾选 AEX-04 |

#### AEX-01 首轮独立审查与定向修复

2026-09-13 主模型首轮检查实际 diff、固定上游公开/默认 API 和原始报告后，判定当时 A1–A4 尚未全部通过，暂不勾选或提交。以下保留首审发现与修复过程，最终结论见本节末尾。

- A2 实际失败：主模型新增旧单参数 `getImage(Page)` override 回归测试，经 `aex01-review-image-override-red` 运行 1 项，1 failure、0 error/skip，退出码 1；预期 `legacy`，实际 `network`。零偏移新重载绕过旧 override，必须按红绿修复并保留正偏移续传及旧 ABI。
- A1/A2 初始证据缺口已由本轮补测收敛：`aex01-source-phase-final` 的 JVM 与 Android release unit 各 19 项均 0 failure/error/skip；共享 HTTP 契约包含 403/429/500、unknown length、取消、Range 及旧单参数 override；受控 1.5 已经由 Desktop loader 与 ART 的新 `getMangaUpdate` 四 flags 进入宿主桥。新增覆盖如实记为补充契约，不伪造产品 RED。
- A3/A4 初始证据缺口经实际平台补测收敛：受控 1.6 JAR 的公共查询、分页、筛选、flags/probe，以及受控 1.5 的宿主更新桥通过；真实 MangaDex 1.6 JAR 经既有 artifact adapter 和 production loader 的公共本地 HTTP 页解析通过。真实 APK 经 Android `ChildFirstPathClassLoader` 的 factory、公共更新无操作错误边界和 HTTP `getPageList` 通过。真实 ComicFury 1.4.8 在 ART 公共路径返回非空页图；`aex01-art-final5/6` 保留真实压缩 guard RED，固定样本和准入边界未改。
- 证据表须校正：`aex01-source-red` 实际为新增接口后 Catalogue 缺 `override` 的编译失败；`aex01-http-red` 同时含新增 API 缺失和测试漏导入既有 `HttpException`，不把后者归因产品缺口。`aex01-final-focused` 日志中 `:source-api:jvmTest` 无 `UP-TO-DATE`，该次确实执行 Source API 测试，但不能代替 Android unit。
- 本批超过 8 文件/400 行仍属同一 Source ABI 功能批：共享接口、模型、网络默认签名和双平台二进制互相制约，不能拆开编译与验收。主要风险为旧 override 分派、平台 Page 构造器、类加载类型身份和测试覆盖遗漏；按共享契约与真实二进制分层验证控制，不扩大产品范围。

原 Luna 接收一次汇总定向修复，预计 45–90 分钟；继续同一目标，不新建代理。只复验上述失败项、补齐原矩阵及受影响回归；模块最终验证仍按现有预算执行，整仓全量和发布构建为 0。若修复复验仍失败且需要追加轮次，先报告具体证据、原因与新增成本请求用户决定。

本轮定向修复实际证据：`aex01-network-red` exit1（2 tests/2 failures，旧 Android 压缩拦截器导致固定 1.6 client guard 与旧 gzip 正文断言失败）；按固定上游删除成对默认注册后，`aex01-network-green` exit0，2 tests/0 failure/error/skip，验证 Uncaught/UserAgent、gzip 协商与透明解压。`aex01-art-final9` 使用 `ANDROID_SERIAL=emulator-5580`、API36/x86_64，exit0，4 tests/0 failure/error/skip，实际调用固定 v1.5/v1.6、真实 ComicFury 1.4.8 plain/gzip 页图及真实 MangaDex factory 的本地 HTTP 页解析；报告为 `app/build/outputs/androidTest-results/connected/debug/TEST-mihon-aex-api36(AVD) - 16-_app-.xml`，日志为 `.gradle-coordinator/aex01-art-final9.log`。`aex01-desktop-final2` exit0，Desktop v1.5 四 flags bridge、v1.6 probe/真实 MangaDex 生产 loader 共 18 tests，0 failure/error/skip；同一新增测试 XML 为 `app-desktop/build/test-results/jvmTest/TEST-mihon.desktop.extension.DesktopExtensionV16AbiIntegrationTest.xml`（3/0/0/0），loader 回归 XML 为 `.../TEST-mihon.desktop.extension.DesktopExtensionLoaderTest.xml`（15/0/0/0）。其中 `aex01-art-final3` 的 `(false,false)` 主动 `IllegalStateException` 属于真实插件输入边界，不是宿主 ABI RED；`final5/6` 是有效的生产压缩 guard RED。`final7` 曾仅在测试中过滤拦截器，主模型拒绝将该 GREEN 作为完成证据；最终已撤回该测试过滤，由生产修复后的 `final8/9` 及主模型复验验收。`aex01-desktop-final` 等编译失败亦不计产品 RED。

运行方式统一使用 `python scripts/gradle-coordinator.py foreground --key <唯一批次键> --timeout-seconds 1800 -- .\gradlew.bat <第 6 节准确 task/filter> --no-daemon --offline`。无缓存依赖时按代理规则最多进行一次有依据的在线重试，不并行 Gradle。设备测试采用 `:app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.extension.ExtensionV16SourceAbiInstrumentationTest'`；先核对 `adb devices -l` 和 AVD 名称。主模型回收写入权后独立复验、提交；子代理不能自行勾选或提交。

#### AEX-01 主模型最终复验（2026-09-13）

主模型已完成一次独立审查和一次定向修复复验，A1–A4/C5 及本批 C4 通过。实际检查最终 production/test diff、原始 RED/GREEN 日志、7 个固定 APK/JAR hash；在验收测试中补充工厂空 memo、同一 JSON 对象复制和 Android ChapterImpl 默认 memo 断言，没有改变生产逻辑或伪造额外 RED。Desktop 新测试仅整理 import，属不改变行为的格式清理。

最终前台协调器 key `aex01-main-final-verification`，2026-09-13 11:44:02–11:46:58 UTC，exit0，BUILD SUCCESSFUL（2m56s，429 actionable，48 executed、381 up-to-date）。当前新增/受影响行为的测试均实际执行：

- `:source-api:jvmTest` 与 `:source-api:testReleaseUnitTest` 各过滤 `SourceApiBinaryContractTest`、`HttpSourceApiContractTest`，分别 17 项；包含 Rx/Catalogue/HttpSource 四 flags、并发/父取消、默认 API、memo、Range/200/206、403/429/500、unknown length、HTTP 取消与旧 image override。
- `:core:common:testDebugUnitTest --tests eu.kanade.tachiyomi.network.AndroidNetworkHelperExtensionCompatibilityTest --rerun`：2 项，验证生产 client 和 gzip；不是源码扫描或仅构造辅助客户端。
- `:app-desktop:jvmTest` 过滤 `DesktopExtensionV16AbiIntegrationTest`（3）、`DesktopExtensionLoaderTest`（15）、`RealExtensionComicFuryTextCompatTest`（1）、`RealExtensionPageListCompatTest`（2），共 21 项；保留真实旧 APK 转换/页图、Authors 文本图、serialization child-first 与 1.5 suspend-only 新桥。
- `:app:connectedDebugAndroidTest` 指定 `ExtensionV16SourceAbiInstrumentationTest`：4 项，唯一专用 `emulator-5580` / `mihon-aex-api36` / API36 x86_64，真实 1.4/1.6 与受控 1.5/1.6 APK 组件调用、本地 HTTP/gzip 和宿主模型验证通过。
- 同一命令执行 `:source-api:spotlessCheck :core:common:spotlessCheck :app:spotlessCheck`，全部通过；最终 `git diff --check` 通过。完整 argv、开始结束时间和退出码见 `.gradle-coordinator/aex01-main-final-verification.json`，日志同名 `.log`，原始 XML 位于相应模块 `build/test-results` 和 app `build/outputs/androidTest-results/connected/debug`。

合计 61 项，failure/error/skip 均 0。Source API 模块完整 JVM/Android unit 另已有 `aex01-source-phase-final` 各 19 项通过证据；本次只复验受影响行为，未重复整仓全量。当前边界仍是组件 ABI：Android 1.6 完整 loader 准入、普通权限安装/信任、memo 数据库与备份、真实站点整链、API26/ARM/R8/正式签名及 Windows/macOS 发布验证均未由本批代替，继续由后续责任批次完成。

### AEX-02 执行契约（2026-09-13）

前置：AEX-01 已由主模型独立验收并提交 `295107aa4de4f3979f31299f8e8083d534cd4dcf`。沿用同一 GPT-5.6 Luna（xhigh），不新增代理；旧子 goal 实际完成后才能建立本批七行目标。主模型持有验收权，本批结束前不勾选 C6 或 AEX-02。其他任务正在修改 `docs/prototypes/multi-device-sync/`，并有用户 `testfile/`；全部排除本批写入、测试与提交。

流程预算修订：原 3–6 小时为未充分校准的整批粗估，不再当作当前剩余时间；恢复前按下方已有证据和缺口给出当前批次的墙钟范围及假设，本次文档修改不虚构新 ETA。B1→B2→B3→B4 串行，各组内按下表多个行为循环执行，不再限定每组只有一次 RED/GREEN/重构；普通装配修复和停止盲试条件见第 6 节。批末相关 domain/data 模块完整 Android/JVM 测试合并 1 次；平台仅相关 app/Desktop、DI/Screen/HTTP、ART 用例及格式检查。主模型独立审查 1 轮，必要时原代理定向修复/主模型复验 1 轮；整仓全量、完整 Desktop 和发布构建 0 次，仍归 AEX-06。唯一产品进度计划仍为本文件，不新增逐行为 goal、代理、快照或提交。本次技能交接文档是用户要求的独立交付物，不参与 AEX 状态推进。

#### 现有入口与复用决定

- 共享核心放在现有 `mihon.domain.extensionrepo` / `mihon.domain.extension` 和 data；扩展 `ExtensionRepoService`、`ExtensionCatalogService` 及现有 DTO/model，不新增 module 或另一套仓库管理器。Desktop 现有 `DesktopExtensionRepoV2Catalog` 只有 gzip protobuf 内联解码，重复协议判断应迁入共享权威并删除或降为无分支委托；只把 APK/JAR 选择和实际运行时能力留在平台 adapter。
- 冻结上游 `df6507256acce8e7f3660783a3db6dbd1a31b6b5` 的 `NetworkExtensionStore` / `ExtensionStoreService` / `ExtensionStore` 是 v2 字段与列表语义权威。显式 `extensionLib` 独立于 `versionName`，MIXED 与 NSFW 均为敏感内容；同一源语言集合只有一项时使用该语言，否则 `all`，不能从 APK 文件名推断。Desktop field 501 JAR-first/APK fallback 属于保留的平台扩展。
- 复用 `ExtensionRepo`/SQL 表/备份及既有指纹冲突确认流程。新增可空索引定位/格式/远程列表/必要 contact 元数据；旧行默认从原 base URL 读取 repo.json 发现新版索引，不能因旧行缺新字段删除重建。旧 baseUrl 和信任身份稳定，新索引地址为定位而非静默更换身份；仅 v2 地址创建的仓库以规范化输入作为稳定身份，后续同指纹不同地址仍走已有替换确认。badgeLabel 复用 shortName，website 复用原字段。
- Android `ExtensionApi` 必须沿用实际 `AndroidNetworkResponseAdapter` / NetworkErrorMapper，不能为了共享 parser 丢失 Retry-After、HTTP 错误与 DI 链路；Desktop 沿用 production OkHttp/client、repo 指纹校验。共享传输接口可接收这些实际网络 adapter，但最终集成不能 mock parser 或将完整客户端换成只供测试的实现。
- 目录失败保全在既有 catalog/presentation 链补齐，不能新建旁路列表。成功空目录与获取失败、库不兼容与已废弃分开；仅所属仓库本次成功且包确实缺失时更新废弃状态，仓库归属未知或失败不能由其他仓库成功推断废弃；重新出现须清除旧废弃标记。按仓库 URL/指纹保留上一成功快照、标注本次失败，删除仓库或改变身份后不得复活旧快照。

可写：domain extension/extensionrepo 的协议、模型、服务、用例及 presentation；data 的对应 repository、唯一 `commonMain/sqldelight` 表/新 migration、共享 BackupExtensionRepos 与两端现有创建/恢复映射；Android ExtensionApi、ExtensionManager 的目录状态部分、Available 模型、仓库设置/deeplink/扩展列表相关 ScreenModel/UI/DI；Desktop ExtensionApi、Available 模型、仓库/扩展 presentation/UI/DI 和重复 decoder；必要 i18n 文案、受影响测试/fake repository/测试 build 配置、测试资产 wiring、本文件第 8/9 节证据。新增签名/运行时限制只复用现有能力边界；禁止提前放开 Android 1.6 准入或改变 installer/trust 的确认策略。数据库版本使用实际 Schema/migration 权威；历史 Authors 版本常量不作为新 migration 的目标版本，也不借本批重构 Authors。

禁止：Source 查询/SourceManager 语义、漫画/章节 memo 持久化、阅读/下载链、APK loader/install 引擎、固定样本二进制/来源/hash、生产用户数据、签名身份、遥测/捐赠、协调器、其他计划/演示、Authors/FlareSolverr 独有行为。对 repository 接口扩展导致的必要调用方适配仅作字段传递，不能无关重构。

| 子验收 / 对应 C6 | 真实输入与操作 | 必需可观察预期及证据 |
| --- | --- | --- |
| B1 共享协议与 HTTP | 同一原始响应矩阵经共享 parser 和两端 production API：旧 repo/index JSON，新 JSON/protobuf、gzip/非 gzip、内联/远程列表、index_v2 跟随；未知/缺失字段、空/畸形/压缩损坏；显式 lib 与 versionName 不一致、MIXED、多语言、JAR/APK | 两端协议决策一致，仅资源/平台能力允许差异。remote list 优先于 inline；真实字段保真，未知可选字段容忍、缺必需字段不伪造成功；指纹在旧 metadata 与 v2 store 的每次迁移跟随中校验，不能降级绕过失败。地址只允许 HTTPS 或 loopback HTTP，无 userinfo；跟随循环/重定向深度与解压读取设有限上界并有失败测试。HTTP 403/429/500、超时及父取消准确传播，取消不能返回空成功或 UnknownError。共享 JVM/Android 契约、两端 MockWebServer（mockwebserver3 实际依赖）覆盖完整 raw response→领域对象，保留 Android Retry-After=42 验证 |
| B2 仓库身份、CRUD 与数据保全 | 旧 root/index.min.json/repo.json 与新 index URL 输入，去空白/末斜杠/等价旧地址；既有 Create/Replace/Delete/Update、真实旧数据库 migration、关闭重开、生产备份创建/恢复 | 不重复添加同一身份；同指纹不同地址触发已有确认，指纹变化拒绝并保留原仓库；新字段在持久化/重启/更新/备份恢复中保真，旧备份缺字段可读；失败/取消不半写、不吞取消，不触碰真实用户数据。SQLDelight 只有 commonMain 权威；数据迁移要实际执行 generated Schema 和真实 driver/repository，不以 codec round-trip 或 mock repository 代替。Android ART 执行真实 Android 存储重开及恢复，Desktop 执行真实 JDBC 和现有 migration safety 回归 |
| B3 目录状态与可操作性 | 成功→单仓失败/全失败→恢复、成功空目录、删除仓库、同包目录恢复、显式新旧 lib；经实际 manager/presentation 刷新与安装入口 | 保留失败仓库上一成功数据并反馈本次失败，成功仓库可更新；不可将失败/不兼容误标废弃或把旧快照当新鲜成功；删除仓库移除缓存，恢复清除废弃。Android 1.6 条目可解释地显示不兼容、安装/更新/批量更新不可开始；现有 1.4/1.5 可用行为保留；Desktop 1.6 保持可用，未知更高 lib 显示不兼容。平台能力来自实际已存在的准入范围；不要仅上调共享 max 或仅禁按钮而后台仍可误装 |
| B4 两端 UI/DI 与批末集成 | 既有扩展仓库入口/添加 deeplink、列表刷新；从真实创建用例、数据库、HTTP 进入仓库 ScreenModel/action，再进入目录及操作反馈 | 添加成功、无效地址、重复/签名冲突/取消、删除确认、刷新失败反馈可见；DI/Screen 实例化与导航类型在实际受影响入口验证。不新建第二套页面；新增 Injekt 调用/导航必须有会在 wiring 损坏时失败的测试。最终 B1–B3 及受影响仓库、备份、状态回归无零用例/skip；格式、相关模块阶段测试与设备证据齐全，再交主模型 |

#### 批内行为顺序与测试落点（不是新增交付批次）

| 顺序 / 对应子验收 | 当前行为及允许推进的边界 | 正确 RED / 出口证据 | focused 落点 |
| --- | --- | --- | --- |
| B1.a 结构与字段 | 共享旧/新 JSON、protobuf、gzip 模型与 artifact 映射；先不改数据库或刷新状态 | 真实形状输入的 lib/分级/语言/APK-JAR 映射不符或必需字段缺失被接受；GREEN 后共享契约在 Android/JVM 一致 | `ExtensionCatalogServiceContractTest`；字段装配稳定后接两端 API 场景 |
| B1.b 定位与安全跟随 | 复用 B1.a；内联/远程优先、index_v2、逐跳身份、URL、循环/重定向/解压上界 | 未读取权威远程列表、变签名被接受或读取无界；成功和拒绝均经真实共享 resolver 及两端 HTTP adapter | 共享 catalog 契约 + Android/Desktop `ExtensionApiSharedCatalogTest` |
| B1.c HTTP 错误边界 | 在同一 production 请求链验证成功空目录、403/429/500、畸形/损坏、超时与父取消 | 错误类别或 Retry-After=42 丢失、取消被吞；不得通过仅 mock decoder callback 完成 | 同一组两端 API 测试；B1.a–c 完整后进入 B2 |
| B2.a 持久化语义 | 复用 B1 模型；真实 repository、唯一 schema/migration、关闭重开，保留旧身份 | 新字段保存后为 null 或旧表迁移失败；共享存储契约与真实 JDBC/Android driver 对照，不能只测 codec | 现有 `ExtensionRepoRepositoryPersistenceTest` + 待补共享存储契约/Android 落点 |
| B2.b 地址与仓库生命周期 | 在 B2.a 可用存储上接 root/旧索引/显式新索引、归一化、重复/冲突确认、替换/删除和取消 | 断言实际请求路径及持久化结果；同指纹新地址不可静默换身份，失败/取消不得半写 | `ExtensionRepoServiceV2DiscoveryTest`、`ExtensionRepoServiceContractTest`、`ExtensionRepoCreateRepositoryTest` 和身份契约；不 mock 被验收用例/库 |
| B2.c 备份与设备保全 | 复用 B2.a/b；生产创建/恢复、旧备份缺字段、Android 存储重开与恢复 | 新定位/信任字段恢复丢失或旧备份不可读；Desktop JDBC 和 Android ART 分列，保留既有 migration safety | 双方既有 backup 测试、`DesktopDatabaseMigrationSafetyTest`、待新增 `ExtensionRepoPersistenceInstrumentationTest`；出口覆盖 B2 全矩阵 |
| B3.a 刷新状态 | B1/B2 完成后接真实 manager；成功→部分/全失败→恢复、成功空目录、删除/身份变化、归属未知 | 失败误报废弃、恢复不清废弃、旧快照复活或 stale 不反馈；两端完整序列而非两个孤立断言 | Android `ExtensionManagerTest` 与 Desktop 实际 catalog/manager 回归；当前 B3 草稿须先按恢复规则处理 |
| B3.b 平台可操作性 | 复用 B3.a 状态和实际平台准入，不改安装引擎 | Android 1.6 被过滤或安装/更新/批量后台仍启动；Desktop 1.6 回退；未知版本误报废弃 | 两端既有 presentation wiring/projection 套件 + 真实操作调用路径；既有 1.4/1.5 不回退 |
| B4 入口反馈与批末整合 | 复用 B1–B3；旧 `tachiyomi://add-repo?url=` 与新 `mihon://extension-store?url=`、现有仓库页、列表刷新 | 从实际入口/ScreenModel 经真实创建、HTTP、DB 到结果反馈；覆盖无效/重复/签名冲突/取消/删除确认和刷新失败 | `PlatformParityContractTest`、`ExtensionReposScreenModelWiringTest`、`ExtensionRepoScreenFeedbackTest` 及双端实际入口；parser 测试不能代替 UI 整链 |

表中一行可包含同一行为的多次必要红绿循环，不要求把全部矩阵一次写完；但未测后续行为不得提前改 production。相关 UI/HTTP/DI 连接在首次修改时就加入失败测试，B4 是跨组整合，不是推迟所有 wiring 验证。行为组出口只交简短结果；除具体阻塞外继续既定顺序，不引入每行主模型审批。

实际 runner 映射（优先于第 6 节历史拟名；以下存在不等于完整覆盖已通过）：

- 共享 catalog：`domain/src/commonTest/kotlin/mihon/domain/extension/ExtensionCatalogServiceContractTest.kt`，`:domain:jvmTest` / `:domain:testReleaseUnitTest`，filter `mihon.domain.extension.ExtensionCatalogServiceContractTest`。
- 仓库发现：`domain/src/jvmTest/kotlin/mihon/domain/extensionrepo/service/ExtensionRepoServiceV2DiscoveryTest.kt`，`:domain:jvmTest --tests "mihon.domain.extensionrepo.service.ExtensionRepoServiceV2DiscoveryTest"`；已补显式 JSON/protobuf/gzip 与无后缀地址、真实请求路径及安全拒绝回归，最终结果以第 8 节为准。
- JDBC 持久化：`data/src/jvmTest/kotlin/mihon/data/repository/ExtensionRepoRepositoryPersistenceTest.kt`，`:data:jvmTest --tests "mihon.data.repository.ExtensionRepoRepositoryPersistenceTest"`。该类为 JVM 专属。共享存储语义位于 `data/src/commonTest/kotlin/mihon/data/repository/ExtensionRepoStorageContract.kt`，平台落点分别为 `mihon.data.repository.JvmExtensionRepoStorageContractTest`（`:data:jvmTest`）与 `mihon.data.repository.AndroidExtensionRepoStorageContractTest`（`:data:testDebugUnitTest`）。真实 Android driver/迁移/备份重开落点为 `eu.kanade.tachiyomi.data.backup.ExtensionRepoPersistenceInstrumentationTest`（`:app:connectedDebugAndroidTest` 指定 runner class），不以 JVM 测试替代 ART。
- 两端 API、状态、UI 和备份沿用第 6 节已有套件；平台 driver adapter 仅负责真实环境差异，共享矩阵不得复制实现代替 production。新增测试名称改变时更新这里的映射，不重复维护多个“当前”名单。

本批全部状态初始 pending。Luna 每组保留准确命令/退出码/测试数/RED 原因/原始报告，批末返回 B1–B4 映射与 status/diff/tests/commit/process/next，停止写入交主模型；不得按绿测试数量自行勾选。运行 Gradle 继续使用已验证 foreground 协调器及 UTF-8 环境，唯一指定协调者串行执行，不因外层等待超时重启命令。

#### AEX-02 实施中停点（2026-09-13）

本批尚未独立验收或提交，C6 和批次 checkbox 保持未勾选。子 goal 返回的七行文本核验为 1430 UTF-8 字节，SHA-256 `79bb820932adcb7b10a9234fbde9b1852a376ae026333e72b1022b56f3eb69a3`，与下达值一致；这仅证明目标交接，不证明产品完成。

- 初步自测：`aex02-b1-full-matrix-green2`（12:58:59 UTC，exit0）实际执行共享 catalog 7 项、元数据发现 1 项、Android API 10 项、Desktop API 12 项，failure/error/skip 均为 0。名称中的 full-matrix 不代表主模型已确认全部 B1 标准；本轮不是模块完整测试。
- B2 曾先写 SQL/备份草稿而没有持久化 RED，已在实施中撤回这些自有草稿、保留已验证的取消传播后重新先测。`aex02-b2-persistence-red2` 实际复现真实 repository 保存并重开后三个新字段均变成 null；后续 `aex02-b2-persistence-final`（13:03:27 UTC，exit0）执行重开和旧表迁移共 2 项通过。`aex02-b2-backup-real3`（13:02:44 UTC，exit0）执行 Desktop 生产备份创建、恢复及数据库重开 1 项通过。Android 存储/备份 ART、完整身份 CRUD 等仍待完成；测试装配编译错误不计产品 RED。
- B3 再次发生先改后测。原 Luna 已明确回执：修改 Android `ExtensionApi` / `ExtensionManager` 之前，没有运行正确的 B3 产品行为失败测试；`aex02-b3-android-state` 是 production 编译错误，`state2` 是测试编译错误。`aex02-b3-android-state3`（13:10:23 UTC，exit0）虽有 2 项通过，但不能据此声称完整红绿链。其他 B3 平台、UI 和完整 wiring 尚未完成。

当时主模型要求停止写入与新 Gradle，保留 diff，并提出“余下每组由主模型核对 RED 后再实现”的 20–40 分钟额外检查方案；该方案未执行。2026-09-13 复盘后不将它推广为默认流程，本次用户仅要求改计划与交接技能改进，实施继续暂停。正式批末独立审查及定向修复复验均未开始，但中途监督/局部 diff 检查已经产生实际协调成本。

#### 恢复入口与不重做边界

1. 用户要求恢复后，先只读核对原代理/协调器状态、当前差异和上述原始 key，将现有结果映射到 B1.a–B4。保留 AEX-00/01 提交与已验证输入；本次没有新增代码证据，不按 30 项通过推断 B1 全覆盖。
2. B1/B2 已有通过结果仅在受影响代码、fixture、配置、运行时仍相同时复用；补缺口而非重跑整个历史。B4 已有 deeplink parser 结果保留，但不算 UI 完成。恢复包只引用现有日志和本节，不新增快照/目标/报告。
3. 单列 B3 已证实的先改后测及 B1 HTTP 等已记录的顺序缺口；不把装配编译失败当作有效 RED。先说明每项受影响行为、现有测试能力及最小处置范围；接受现有补测作为流程例外须用户明确批准。未经决定保留差异，不自动撤回全批草稿，也不通过事后变异伪造历史 TDD；独立安全的缺口补齐可按原授权推进，受影响项与 C4 不得提前关闭。
4. 在处理当前差异的边界明确后，由同一代理从第一个证据不足的行为继续。只有本批完整 C4/C6、独立审查、验证和功能提交都完成，才勾选 AEX-02。文档修订提交不包含其 production/test 差异，也不是 AEX-02 完成提交。

### 2026-09-15 主模型接续约定

- 已审视依赖：保留 8 个交付批次。AEX-02 的协议/状态/身份共同保护仓库迁移，不拆成可单独宣称交付的 parser；AEX-03A 与 03B 分别保护查询和更新数据，现有拆分合理。批内 B1.a–B4 用作覆盖次序；对已经稳定且无需等待上游中间结果的验证可合并执行，不为每行新增审批、目标或提交。
- 主模型直接实现共享协议及连续调用链，复用当前上下文；默认只在稳定 diff 交付后委派一个独立审查者。可并行的实现必须有独立文件所有权、稳定输入和明确节省上下文/返工的依据。每批评估实际累计 token 增量、交接成本和失败再决定是否延续方案；不预设多代理必然更省。原 Luna 子代理保持停止，不使用 Luna 技能，不沿用逐字七行目标回执作为产品门槛。
- 本线程已创建覆盖全部剩余 roadmap 的持久 goal。实现、独立审查、匹配验证与 scoped commit 完成时，立即更新本文件对应 checkbox；每批在 commentary 汇总结果、失败、成本与方法调整，最终完整报告。目标没有显式 token 上限，不自行创建数字预算。
- 继承 AEX-02 未提交草稿与有效原始证据。已记录的先改后测仍是历史缺陷：对继承实现进行真实行为回归和独立审查，不撤回重造历史 RED；本轮新增或修复行为先确认正确失败再实现。既有通过证据只在受测输入一致时复用，必要产品标准不变。
- 同一 worktree 的 Gradle 由唯一明确指定的执行者协调；可从主模型显式交接给批次实现者，但交接前先核对已有进程，任何代理不能并行启动。AEX-02 批末起采用该方式减少主线程逐次转发成本。首次接续确认当前环境和构建负载，然后复用已验证 foreground 命令与本地缓存。相关 focused 按行为合并；批末一轮相关回归/格式、一次独立审查及必要一次定向复审；主模型保留最终独立核验，模块完整验证与最终发布分别按第 6 节执行。
- 唯一新增经验记录为[执行成本与经验](./2026-09-15-android-extension-execution-costs.md)。累计用量使用现有工具提供的口径，缺失时明确不可得；分别标注主线程 goal 计数与含子代理的统一统计，不将两者相加。记录只在批次交接/完成或方法实质改变时更新，不逐工具写报告。
- 当前成本判断：直接实施更能复用已加载的协议、平台 adapter 和测试上下文；独立审查仍具有发现盲点的价值。后续若某个平台需要大量独立上下文，再按已授权最多两个并发子代理调整，避免共享文件和重型构建冲突。外部发布身份/设备限制按具体证据处理，不扩展至遥测、捐赠或相邻功能。
- 发布前置核对：2026-09-15 用户已授权创建本 fork 专用的新 Android 发布密钥；仅在发布配置批次生成，密钥/密码不得入库或日志，需提供安全备份说明。新签名不能覆盖其他签名的既有应用，版本与应用身份按 AEX-06 明确。当前 release 未配置签名，不把 preview/debug 签名当正式交付。Mac 初次 SSH 探测超时，用户开机后 `mbp-lan` 只读复查成功：Darwin / x86_64 / macOS 14.8.4。仅表示远程入口就绪，不表示正式构建或运行验收已通过。

### AEX-03A 实施边界（2026-09-15）

- 前置 AEX-02 已提交 `d8f27b6df`。一名实现者连续负责源发现、共享查询、两端浏览/全局搜索及导航，独占 Gradle；主模型独立验收。预算一轮审查及必要一次定向复审，focused 按行为红绿，批末相关模块/平台和格式验证；不新增报告或执行整仓发布。
- 既有 `getCatalogueSources` 仍被阅读、下载、后台更新和 Authors 的强类型消费者使用。允许在同一个 SourceManager 增加 Source 查询投影供本批入口使用，旧 Catalogue 投影只保留兼容消费者；必须从同一真实注册集合派生，不能维护第二套可变源列表或复制筛选规则。所有本批查询入口都需使用新投影，不能只改测试默认实现。03B 在同一边界接通更新消费者，不另建管理器。
- 不改变数据库/备份、memo 或 APK 版本准入；HttpSource 判断只用于实际 HTTP 专属能力。Source-only、旧 Catalogue、本地源和 Authors 均有对应回归，不用全量类型强转换取编译通过。

### AEX-03B 实施边界（2026-09-15）

- 前置 AEX-03A 已提交 `bc8256569`。一名完整执行者依次完成更新 flags、数据映射/迁移、备份恢复、UI/后台和页图/下载消费，独占 Gradle；主模型核对固定上游和最终独立验收。预算一轮审查、必要一次定向复审；focused 红绿，阶段相关模块完整双平台验证和 ART，不提前执行正式发布。不按文件或迁移行数拆任务。
- 固定上游 commit 对象已在本地，可 `git show df6507256acce8e7f3660783a3db6dbd1a31b6b5:<path>` 读取，避免再次下载。备份模型原路径为 `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/`：漫画 memo protobuf tag 112，章节 tag 13，类型 ByteArray，默认空 JSON 对象字节。`data/src/main/java/tachiyomi/data/DatabaseAdapter.kt` 的 MemoColumnAdapter 在 JsonObject 与 UTF-8 字节间转换；上游 `12.sqm` 对 mangas/chapters 增加 `memo BLOB NOT NULL DEFAULT '{}'`。本 fork 按实际当前 schema 编号添加迁移，不复制上游编号。
- domain Manga 的 Java Serializable 行为也必须保全：上游通过 Kotlin serialization 代理处理 JsonObject，不允许仅新增字段后忽略序列化。统一更新复用上游用例和本 fork 原合并/同步策略，保留本 fork 的取消传播和 fetchWindow 传递；不能因 flags 关闭就丢弃插件返回的新 memo。
- 完成证据必须串联真实 repository 保存、关闭重开、再次调用 Source、生产备份恢复及 Reader/下载页图消费；两端分别执行，不能用固定 ID Proxy 或 codec round-trip 替代。版本准入仍归 AEX-04，未跟踪用户目录、遥测和相邻功能不在范围内。
- 实际 ART 升级发现固定上游的文本默认值 `'{}'` 在本 fork Android BLOB 读取路径带入尾部 NUL，触发严格 JSON 解码失败；因此 schema 19→20 及新库定义使用 `DEFAULT (CAST('{}' AS BLOB))`，得到等价 JSON 字节。当前 SQLDelight 解析器拒绝尝试的十六进制字面量写法，未为此升级依赖。不放宽解码器，也不吞掉任意损坏字节。设备测试同时检查迁移后 `typeof(memo) = blob`、`hex(memo) = 7B7D` 与重开后的模型值。

### AEX-03B 最终验收（2026-09-16）

- 执行者原始红绿日志位于 `.gradle-coordinator/aex03b-*.log/.json`。共享 `SourceUpdateMemoContractTest` 覆盖四种 flags、已存章节顺序、空结果、异常/父取消、源模型及 Java 导航序列化；平台真实更新补充无操作/失败/取消不写入、memo-only 变更、已有用户状态保留。Worker 最初 DI 注册未替换 mock 的失败不是产品 RED；修正装配后的旧生产调用对照为 `aex03b-workers-real-red`，随后恢复新调用补绿，不改写最初顺序。
- Android `SourceUpdateMemoBackupIntegrationTest`、`SourceUpdateMemoPageConsumersIntegrationTest` 串联真实更新、重开、再更新、生产备份创建/恢复、再次重开、`HttpPageLoader` 图片 Ready 与 `Downloader` 实际 PNG 落盘。`LibraryUpdateJobSharedLifecycleIntegrationTest` 执行两个真实 Worker；`MangaScreenModelSharedMutationWiringTest` 验详情刷新及空章节本地化/收尾。Desktop `SourceUpdateMemoIntegrationTest` 覆盖对应真实数据库、备份、Reader/下载、关联章节与 Source-only Scheduler；真实 `MangaDetailScreen` 自动及按钮刷新执行离屏测试。保留既有 HTTP 图片能力边界，不增加 Source 基类没有定义的 getImage 能力。
- 主模型接管后 `aex03b-root-ui-ready` 通过 3 项；手动点击须等待章节真正渲染，原来只等待模型状态的超时属于装配问题。`aex03b-root-validation` 实际执行 domain JVM 433、Android 368，Desktop 26 类/292 项全部通过；app 中两个 Worker/UI 更新既有组共 14 项通过，其余 9 项随后重新验证。该 runner **整体失败**，因 data 旧仓库 fixture 漏历史表及 ART 默认值错误；书签旧 mock 另出现一次失败后重试通过，不把重试当稳定证据。
- 最终 `aex03b-root-migration-green2` exit0、BUILD SUCCESSFUL（1m43s）：data JVM 20 类/138、Android 3 类/12；app 3 类/9；Desktop 9 类/68；ART 5，全部 failure/error/skip=0。修正原始旧表装配、mock 的显式类型返回及 BLOB 默认值后，只复验受影响集合；不把当前仅 68 项的 Desktop XML 冒称仍含前轮 292 项。合并有效覆盖为 1271 个案例，不重复计算子集与重试。domain/data 模块完整测试、本批两端相关回归及 app/domain/data 无 hook 的真实 SpotlessCheck、最终 `git diff --check` 通过。Desktop 无 Spotless task，受影响格式由主模型检查。
- ART 精确 runner：`:app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.extension.SourceUpdateMemoPersistenceInstrumentationTest,eu.kanade.tachiyomi.data.backup.ExtensionRepoPersistenceInstrumentationTest'`；设备 `emulator-5580` / `mihon-aex-api36` / API36 x86_64。原始 XML 在 `app/build/outputs/androidTest-results/connected/debug/`，各 JVM/unit XML 在相应模块 `build/test-results/`。维护时可从上述协调器 JSON 复用实际 task/filter，并使用新的唯一 key。
- 本批超出 8 文件/400 行，仍是同一更新与 memo 闭环：共享模型/schema、双平台合并与备份、实际 UI/作业和页图消费互相依赖；拆开提交将无法独立编译或证明保全。风险集中在复制方向、默认值类型及平台入口，已用共享契约、真实库和 ART 控制。AEX-04 安装版本/信任准入、AEX-05 升级故障整链及 AEX-06 正式发布尚未验收，未勾选。

### AEX-04 私有安装 ART 中间证据（未关闭批次）

- `ExtensionV16LifecycleInstrumentationTest` 在专用 API36 x86_64 模拟器运行，硬件身份保护拒绝在个人设备执行。固定 `aex00-external-v16-controlled-sample.apk` 原始字节从本地 HTTP 下载，SHA-256 和签名重新核验，未改 metadata、未注入 Source、未采用 shell 安装权限绕过扩展安装。
- 调用真实 `ExtensionManager.installExtension`、默认 `ExtensionInstaller`/gateway、真实保存的仓库指纹、`TrustExtension`、`ExtensionLoader`、应用 DI 中的 `SourceManager`。调试宿主及测试 APK 的 adb 部署不算扩展用户安装；扩展本身走 production 私有安装链。
- RED：`.gradle-coordinator/aex04-private-lifecycle-red-art.log`，实际运行 1、失败 1，已安装/加载后语言为 `""`，预期 `all`。原因是语言汇总仍过滤 CatalogueSource。GREEN：移除这一旧类型门槛，`.gradle-coordinator/aex04-private-lifecycle-green-art.log` 为 `OK (1 test)`；同一固定 APK 的语言 en/zh、生产注册对象身份、production 搜索、更新 memo 返回、再次从包加载及卸载清理通过。
- 构建记录：`aex04-private-lifecycle-red-build`、`aex04-private-lifecycle-green-build`、`aex04-private-lifecycle-test-refresh` 均 exit0。最后一次仅刷新晚于打包修改的测试源码。个人 ARM 手机仅只读检查，未部署本批 APK。
- 格式整理后复验：`aex04-lifecycle-refactor-check` exit0/1m25s，Android metadata/loader/manager 18 项零失败、零错误、零跳过，真实无 hook app spotlessCheck 与测试 APK 构建通过；`.gradle-coordinator/aex04-private-lifecycle-refactor-art.log` 再次 `OK (1 test)`，git diff --check 通过。没有把两次执行计为两个独立用例。
- 边界：再次加载不等于宿主进程重启；当前测试没有证明业务 DB 持久化/恢复、实际阅读下载或系统安装授权。受控样本的正常 getPageList 返回空数组，不能拿此结果冒充页面阅读成功；这些门槛仍 pending，未审查、未提交、未勾选 AEX-04。

### AEX-04 普通系统确认与交付文件生命周期（未关闭批次）

- 扩展系统安装使用真实 `ExtensionManager` → `ExtensionInstallService` → `PackageInstallerInstaller`；测试通过 accessibility 点击系统 Install/Cancel/卸载按钮，没有 adoptShellPermissionIdentity 或 INSTALL_PACKAGES 特权。未知来源开关通过模拟器 Settings 界面操作，在 instrumentation 启动前开启、结束后恢复。测试自身要求权限预先满足，不在宿主进程中切换它。
- 首次取消测试记录 `Process crashed`；设备 ApplicationExitInfo 证实系统以 `REQUEST_INSTALL_PACKAGES changed` / OTHER KILLS BY SYSTEM 终止 UID。属于测试恢复权限的生命周期错误，不称为产品崩溃 RED。改为进程外恢复后，`aex04-system-cancel-isolated-art.log` 为 `OK (1 test)`。
- 正向系统安装真实 RED：`aex04-system-install-art.log` 运行 1/失败 1，终态 Error；系统实际已装入插件。设备日志显示 candidate.apk 已被 installer 消费删除，DefaultAndroidInstallGateway 后续读取它写信任记录失败。测试遗留的受控插件通过系统卸载对话框移除后重新验证。
- 新增 `ExtensionInstallSessionLifecycleTest` 的交付文件参数化契约：无论平台是否消费文件，原事务 APK 仍在且交付副本最终不存在。`aex04-system-delivery-red` 证实交付原件而非副本；重试记录不重复计用例。修复在现有 installPrepared 内生成独立交付副本，事务仍保留原件用于签名/信任/运行时与回滚校验，沿用原清理协调者，不另建安装器。
- 失败如实记录：`aex04-system-delivery-green` 因误引用类私有 storage helper 编译失败，未执行测试；提升为文件私有共用函数后，`aex04-system-delivery-green2` 发现 8 个旧测试使用不存在的 extension.apk/extension-v1.apk 占位路径，未到达原取消/超时断言。仅替换为每测试独立临时文件，不放宽 production 检查。`aex04-system-delivery-fixtures-green` exit0/44s：生命周期 28 + 安全回滚 26，54 项全部零失败/错误/跳过。
- 真正设备 GREEN：`aex04-system-delivery-green-art.log` 为 `OK (3 tests)`：普通取消、私有安装、系统确认安装；两种成功路径均核验固定 APK 哈希、真实信任与 loader、双语言 SourceManager 注册、生产查询和更新 memo 返回、再次加载与正常卸载。未知来源权限已通过 Settings 恢复 deny；系统 `pm list packages --user 0 aex00` 为空。无个人 ARM 设备写入。
- 格式整理后复验：`aex04-system-delivery-refactor-check` exit0/1m19s，以上 54 项零失败/错误/跳过、无 hook app spotlessCheck、debug 宿主及测试 APK 构建通过；`aex04-system-delivery-refactor-art.log` 再次 `OK (3 tests)`，进程外恢复权限 deny，受控系统包为空，git diff --check 通过。仍不把重复执行累计成新的测试用例。
- 原私有安装段的“系统安装授权待验证”已由本段以上证据补充，但完整持久化/进程重启、页面阅读下载、缺失/异常包的加载安全矩阵、独立批次审查与提交仍 pending；AEX-04 保持未勾选。

### AEX-04 已安装源的业务数据库与跨进程恢复（未关闭批次）

- `ExtensionV16LifecycleInstrumentationTest#installedSourceUpdatesPersistMangaAndReadingState` 复用固定签名 APK 的正常私有安装与真实 SourceManager 注册；调用应用 DI 的 `UpdateManga.awaitFromRemote`、章节同步和 MangaRepository/ChapterRepository，将漫画/章节 memo 持久化。预置并核验收藏、标题、已读、书签、lastPageRead=7 与章节 ID，不使用源注入或假 repository。
- 默认无参数运行是自清理数据库契约，不声称跨进程。显式 `-e aex04RestartPhase prepare` 保留固定插件、仓库和受控数据；宿主结束并执行 `adb -s emulator-5580 shell am force-stop app.mihon.dev` 后，另一次 instrumentation 使用 `-e aex04RestartPhase verify`，强制断言保存的 PID 与当前 PID 不同，再从实际安装 APK 恢复源、读回业务记录并继续走生产更新链。
- 首次两个阶段均 `OK (1 test)`，prepare PID 17373 / verify PID 17505；这是新增验收，没有产品改动，也没有伪造 RED。格式整理后 `.gradle-coordinator/aex04-persistence-default-refactor-art.log`、`aex04-persistence-prepare-refactor-art.log`、`aex04-persistence-verify-refactor-art.log` 均通过；跨进程明确信息位于 `aex04-persistence-process-evidence.log`：preparePid=17958、verifyPid=18033、crossProcess=true。相同场景的重复运行不累计成新用例。
- 清理只对测试交接记录内的精确 manga ID + source ID + 固定 URL 执行参数化 DELETE，随后通过真实 repository 断言漫画消失、关联章节级联删除；正常卸载固定私有插件，删除本次仓库，清空测试交接记录。没有记录时不清理既有状态。数据录入与验证均使用 production repository，裸 SQL 仅用于回收本次受控测试数据。最终记录为 `<map />`，私有扩展目录没有测试 APK。
- 构建/格式：`aex04-persistence-test-build`、`aex04-persistence-format`、`aex04-persistence-refactor-build` 全部 exit0；最后构建 1m，包含无 hook app spotlessCheck。只改 Android 测试，未重复 JVM 或发布全量。
- 本段补齐上段“持久化/进程重启”中漫画、章节 memo 与上述用户状态的跨进程门槛，不等于实际翻页、页面下载、备份跨版本迁移或所有错误恢复已验收。阅读/下载须继续使用能返回真实页面的已安装插件，异常包安全矩阵和批次独立审查/提交仍 pending，AEX-04 不勾选。

### AEX-04 真实 MangaDex 1.6 阅读、下载与离线重读（未关闭批次）

- `InstalledMangaDexReaderInstrumentationTest` 使用仓库已有原签名 `keiyoushi-mangadex-1.6.0.apk`，重新核对固定 SHA-256，经真实 ExtensionManager 私有安装、仓库指纹信任和 SourceManager 注册取得 61 个语言源。不修改 APK，不注入 Source；仅将 production HTTP 客户端的请求路由至受控本地响应。
- 通过真实 MangaRepository/ChapterRepository 保存并读取本次 UUID 漫画章节；生产 ChapterLoader 完成页列表及图片加载，断言实际 stream 字节与返回的 PNG 完全一致。随后调用真实 DownloadManager.downloadChapters，由正常 WorkManager DownloadJob 执行下载；关闭 HTTP 服务后再次通过生产 ChapterLoader 加载，断言本地 loader、图片字节一致且没有新增网络请求。没有注入页面 loader，也没有绕过 DownloadJob 的网络检查。
- 首次阅读运行因清理时误用必须存在的 getMangaById 而失败，属于测试错误，不算产品 RED。受控插件和仓库已通过一次明确标记的 cleanup-only 运行回收，此运行不计验收；临时清理入口已从最终测试删除。改用可空查询后 `aex04-real-reader-fixture-art.log` 为 `OK (1 test)`。最终清理逐项执行并保留原始异常，将后续清理错误附加为 suppressed，避免一个断言阻止卸载或设置恢复。
- 首次完整下载 `aex04-real-download-art.log` 超时：系统 Wi-Fi 已连接但没有 VALIDATED，production DownloadJob 正常拒绝启动下载。临时模拟器代理恢复了系统网络验证，但代理启用时两个运行在 APK 安装阶段失败；没有归因于插件兼容性。恢复原代理配置后 `aex04-real-download-restored-network-art.log` 为 `OK (1 test)`，包含正常作业下载与断开测试服务后的离线重读。新增测试前置检查要求系统真实 validated 网络，环境不满足时及时失败，不强制 online 或修改产品限制。首次整理后运行 `aex04-reader-download-refactor-art.log` 因默认网络已转为 validated 蜂窝而被过严 Wi-Fi 前置拒绝，未进入业务；最终测试临时通过现有下载偏好允许蜂窝并恢复原值，不再依赖系统选择的传输类型。
- 测试只在专用模拟器执行；临时下载目录限于本应用 cache 内且精确回收，仓库、插件和测试数据库记录正常清理，保存并恢复存储及安装偏好。系统四项 HTTP proxy 设置已恢复原先 null，未知来源权限维持 deny，私有扩展目录仅剩 oat。个人 ARM 手机未写入。
- 格式处理曾因未引用的 Windows `-PspotlessIdeHook=D:/...` 参数被拆分而失败，随后因格式化后嵌套行超长失败两次，均未执行产品测试；`aex04-reader-format-wrapped` 已通过。`aex04-reader-download-refactor-build` 的测试 APK 构建和真实无 hook spotlessCheck 已通过；网络前置与偏好调整后的同级验证为 `aex04-reader-download-network-pref-build`，最终运行结果完成后补充，不用整理前绿灯证明新源码。
- 此处证明固定真实插件的生产读取、作业下载和离线文件消费，不等于 UI 翻页交互、真实外部站点、跨进程下载恢复、历史备份迁移、ARM/R8 发布或整个 AEX-04 完成。异常包安全矩阵及批次独立审查、提交仍未完成。
- 最终整理后证据：`aex04-reader-download-network-pref-build` exit0/51s，真实无 hook app spotlessCheck 和测试 APK 构建通过；`aex04-reader-download-network-pref-art.log` 为 `OK (1 test)` / 0.542s，执行最终 `installedV16MangaDexReadsDownloadsAndReopensOfflineThroughProduction`，非清理模式。git diff --check 通过。重复执行不增加独立用例数，本次只有测试和文档变化，不虚构产品 RED；整批未审查提交，checkbox 不变。

### AEX-04 损坏包元数据的加载失败边界（未关闭批次）

- 扩展 `ExtensionLoaderMetadataWiringTest`，只模拟 PackageManager OS 边界；版本解析、真实 TrustExtension/AndroidPreferenceStore 及 loader 入口均执行 production。缺失应用信息、签名信息和私有 archive 应用信息的矩阵在 `aex04-loader-malformed-red` 复现三个 NullPointerException；空证书列表已正确返回 Error，不把它写成新增修复。最小空值处理后 `aex04-loader-malformed-green` exit0。
- 已信任包的缺失 metadata bundle、缺失入口类、入口类错误类型，在 `aex04-loader-entry-red` 再复现三个 NullPointerException；空/空白类名、不存在类和非 Source/SourceFactory 类已有 Error 行为并保留回归。修复在原信任判断之后安全读取入口类；不自动信任、不放宽签名/协议，也不删除未知类错误处理。
- `aex04-loader-entry-green` exit0/39s：loader wiring 1、metadata 5、安全回滚 26，合计 32 个 JUnit 用例，failure/error/skip 均 0。矩阵分支与重试不重复计算成独立测试。格式与同组定向复验 `aex04-loader-safety-check` exit0/56s。
- 同一签名函数的私有替换路径另补旧包保全矩阵：旧签名信息缺失、证书列表为空时，必须拒绝且保留旧文件原始字节。首次 `aex04-private-prior-signature-red` 因旧文件实际消失而在读取时报 FileNotFoundException；将“不存在”纳入保全断言后，`aex04-private-prior-signature-red-capture` 明确显示缺失信息为 `(NullPointerException, true)`，空列表为 `(false, false)`，预期均 `(false, true)`。修复为旧签名缺失/空列表时在写文件前拒绝，不以 containsAll(empty) 判断匹配。
- 私有替换 GREEN 验证前两次被测试代码格式拦下（`aex04-private-prior-signature-green`、`aex04-private-prior-signature-verified`），没有算测试通过；最后按格式报告的准确差异换行，验证 key 为 `aex04-private-prior-signature-final`。此处未新建报告或过程提交，最终结果完成后补充。
- 最终 `aex04-private-prior-signature-final` exit0/57s：上述 32 个 JUnit 用例零失败/错误/跳过，真实无 hook app spotlessCheck 和 git diff --check 通过。包含保留旧文件的新增签名矩阵；未把矩阵分支或重试重复计数。该功能批次尚未完成，不单独提交本检查点。
- 首次私有安装另由 `aex04-first-signature-red` 确认：无签名/空证书的候选包均进入真实复制 helper（其 source.exists 被调用一次），预期应在文件操作前拒绝。File spy 仅计数真实文件方法，不替换复制或签名实现；测试环境最后返回 false 不能代表签名准入正确。将已有候选签名检查移至首次/更新共用入口后，`aex04-first-signature-green` exit0/57s，32 项相关测试及真实 spotlessCheck 通过。没有新增安装引擎或改变正常签名规则。

### AEX-04 固定 APK 的真实信任对话框（未关闭批次）

- 新增 `ExtensionTrustUiInstrumentationTest`，复用同一受控 APK 正常私有安装 fixture。仅撤去本次测试仓库和本包的信任记录，通过真实 ExtensionInstallReceiver 广播再次进入 loader，等待实际 Manager 不受信任状态及 SourceManager 移除该包源。没有注入 Source 或伪造加载结果。
- 使用真实 extensionsTab → ExtensionScreen 信任图标/对话框 → ExtensionsScreenModel → ExtensionManager.trust → loader → SourceManager。返回键关闭警告后，信任记录仍缺失且包仍不受信任；重新打开并点击确认后，双语言源及其注册对象身份一致，警告入口消失。复用正常 Navigator 上下文，不复制信任按钮的实现。
- `aex04-trust-ui-format` 通过。首次 `aex04-trust-ui-build` 宿主 APK 已完成，但测试编译因未提供 Espresso 类失败，不算产品 RED；改用已有 instrumentation 发送正常返回键，没有新增依赖。`aex04-trust-ui-key-build` exit0，包含真实 spotlessCheck 和测试 APK 构建；宿主及测试包均部署到 emulator-5580，`aex04-trust-ui-art.log` 为 `OK (1 test)` / 2.543s。
- 补强关闭对话框后仍无源注册的直接断言，最终验证 key 为 `aex04-trust-ui-final-build`，设备结果完成后补充。清理沿原 fixture 正常卸载/删除本次仓库，恢复测试前信任偏好。测试只在专用模拟器执行；此宿主已包含前述 metadata/签名安全修复，未操作个人 ARM 手机。
- 边界：这里覆盖现有扩展列表内的信任交互及真实包重载，不声称顶层 Browse 导航、系统外部 APK 文件打开、未知签名升级、备份迁移或 R8/ARM 发布已通过。没有产品 UI 改动，不虚构 UI RED。AEX-04 其余安全/兼容场景与独立批末审查、提交仍待完成。
- 最终复验：`aex04-trust-ui-final-build` exit0/46s，真实 spotlessCheck 和测试 APK 构建通过；`aex04-trust-ui-final-art.log` 为 `OK (1 test)` / 2.164s，包含关闭对话框后 SourceManager 仍无测试源的直接断言。最后私有扩展目录仅有 oat、git diff --check 通过。同一用例重复运行不重复计数，未新增检查点提交。

### AEX-04 旧版 APK 的系统/私有正常安装（未关闭批次）

- `LegacyExtensionInstallInstrumentationTest` 使用原始 ComicFury 1.4.8 APK 和用户批准的受控 1.5 APK；SHA-256 与 provenance 固定值逐次核验，ComicFury signer 另以本机 apksigner 核对为 `9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2`。1.5 的 APK 记录位于 `aex00-external-v15-suspend-only.provenance.json` 内，不另造历史发布来源或修改 metadata。
- 把原 v1.6 安装 helper 的固定样本输入参数化为小型 LifecycleApkFixture，复用真实下载、Manager、安装器、OS 确认、信任、loader、SourceManager 与正常卸载链；1.6 的双语言、查询和 memo 原断言保留。跨进程 receipt 仍仅允许固定 v1.6 输入，防止错误复用已有受控记录。
- 新增四个场景：1.4/1.5 各自私有安装及系统确认安装。全部验证原始 APK 哈希、版本、非空源集合、生产注册对象身份和 classloader、重新加载及卸载。1.4 通过已注册的真实 ComicFury 源与本地 HTTP HTML 响应执行页列表解析；1.5 通过已注册的旧 suspend Source 和 SourceMangaUpdateService 执行详情/章节更新。1.5 样本本来没有页面或目录查询功能，未把空章节等同完整阅读成功。
- `aex04-legacy-install-format` exit0/30s；`aex04-legacy-install-build` 的真实 spotlessCheck 与测试 APK 构建 exit0。`.gradle-coordinator/aex04-legacy-and-v16-art.log` 为 `OK (9 tests)` / 9.79s：新增旧版4项 + 既有v1.6生命周期4项 + 信任UI1项，同一模拟器与当前宿主执行。生命周期中的持久化是默认自清理模式，不冒充本轮又执行跨进程重启。
- 系统安装仍通过普通 Settings 未知来源开关和 OS Install/卸载确认，不用 shell 安装权限绕过；整个 instrumentation 前开启、结束后恢复原 deny。最终 aex00 和 ComicFury 系统包列表为空，私有扩展目录仅剩 oat，git diff --check 通过。个人 ARM 手机不探测、不写入。
- 本轮仅测试复用及新增验收，未改变产品行为，不虚构 RED。已补齐所选旧版本的安装准入/加载回归，不替代同包跨版本升级、旧用户备份数据迁移、真实站点、Desktop JAR真实性或发布验收；AEX-04 尚未独立审查与提交，保持未勾选。

### AEX-04 Desktop JAR 内容认证与相关回归（未关闭批次）

- 核对原定 Desktop 验收时，发现原真实性套件未覆盖向真实签名 JAR 追加未签名内容。新增参数化测试只在临时目录复制固定 MangaDex 1.6 JAR，保留原签名及内容字节，分别追加普通类、服务资源和 `META-INF/nested/extra.SF`；原 fixture 不改写。
- `aex04-desktop-payload-red` 11项/1失败：普通类和服务资源被拒绝，但嵌套 `.SF` 没抛认证错误。原因是签名元数据判断只取最后文件名，误把子目录资源也豁免。最小修复把豁免限定到 META-INF 直接目录，子目录内容仍逐项读取并核对签名。新测试同时执行真实 DesktopExtensionManager，要求明确的 JAR unsigned/differently-signed payload 错误及无安装 JAR/metadata 残留；仅 typed Authentication 不足以证明走了正确 JAR 路径，已补具体原因断言。
- `aex04-desktop-payload-green` 的真实性11、事务44、ABI3、更新检测10通过；状态接线14中1失败，整体 FAILED。定位为旧正向测试的已安装来源 `https://repo` 与候选 `https://last` 不匹配；git blame 确认测试输入来自旧提交，而已知仓库/指纹匹配是 AEX-02 的 `d8f27b6df` 既有规则，不是此次放宽或收紧的结果。修正正向输入的真实来源绑定，并新增跨仓库/指纹不匹配均不进入自动更新的反向断言；生产更新策略不变。
- `aex04-desktop-payload-refactor` exit0/29s：真实性11、安装事务44、ABI3、更新检测10、状态接线15，合计83项，failure/error/skip均0，git diff --check通过。Desktop 模块没有既有 spotlessCheck task，本轮沿原文件风格做局部检查，不把不存在的 task 声称通过；未执行完整 Desktop 测试、Test Mode 或正式发布构建，这些仍在批次/最终收口。
- 此处补齐相关共享状态和 JAR payload 回归，并修复已复现的认证豁免问题；不等于 Windows/macOS 发布运行验收或 AEX-04 全部完成。独立批末审查、整合与提交仍待完成。

### AEX-04 v2 目录到真实安装按钮（未关闭批次）

- `ExtensionCatalogInstallUiInstrumentationTest` 复用原生命周期夹具，仅给本地 HTTP 服务增加 v2 索引响应并把索引地址写入真实仓库。默认 DI 的 ExtensionsScreenModel 自行发现目录，实际 extensionsTab 的安装按钮触发下载/认证/加载；不传入构造的 Available，也不直接调用 installExtension 代替按钮。随后沿原夹具验证原始 APK 哈希、协议、双语言源对象身份、查询和 memo，再确认 UI 不再显示安装按钮并正常卸载。
- `aex04-catalog-ui-format` exit0/32s；`aex04-catalog-ui-build` exit0/62s，包含真实 spotlessCheck 和测试 APK 编译。`aex04-catalog-ui-art.log` 为 `OK (1 test)` / 1.832s，只有专用 emulator-5580 被操作。v2 协议本身不提供包哈希，该路径由实际仓库签名认证保护，安装后另与固定样本 SHA-256 对照，不伪造目录哈希字段。
- 合并回归 `aex04-catalog-and-lifecycle-art.log` 实际11项/2失败：信任测试在安装后读取到相同 ID 的另一 Source 实例；阅读下载在前置检查发现 `isConnected=true,isValidated=false,isWifi=true`，没有进入业务验收。前者的 fixture 原来只等 ID 出现、卸载只等 Manager 删除，而实际 SourceManager 是异步收集；改为等待本次源实例身份，并在卸载后等待其源 ID 全部消失，不取消对象身份断言。后者保持产品网络要求，不算通过或改成跳过。
- 最终 `aex04-catalog-ui-lifecycle-build` exit0/53s，真实 spotlessCheck 与测试 APK 编译通过；`aex04-catalog-and-lifecycle-final-art.log` 为 `OK (10 tests)` / 10.661s。包含新目录UI1、信任UI1、v1.6生命周期4、旧版安装4；阅读下载的网络失败未被算入通过数。恢复安装权限为 deny，测试系统包不存在，私有目录仅剩 oat，git diff --check 通过。
- 这次只有测试与文档调整，没有新增产品行为，不虚构产品 RED。未执行完整模块测试、独立批末审查或提交，AEX-04 保持未勾选。真实手机仍留到最终验收，未探测或等待。下一步优先补同包可信升级与失败保全的真实安装证据，复用现有安全矩阵，不重复本轮目录/旧版安装验收；阅读下载待系统网络验证恢复后复验。

### AEX-04 真实同包升级与三类失败保全（未关闭批次）

- 窄上下文执行者新增 `ExtensionUpgradePreservationInstrumentationTest`：真实 MangaDex 1.4.211 → 1.6.0，系统与私有安装各一条完整序列；每条先尝试错误哈希、非APK内容和有效但签名冲突的APK，再进行可信升级。每次拒绝核对旧APK哈希、信任记录原始字节、版本、全部61个源ID、当前生产实例注册和真实 getMangaUrl 调用；升级后核对新哈希、信任仓库/指纹和所有源ID及实例。它不是站点联网或阅读下载验收。
- 旧样本 SHA-256 `eff4ee157380f0cd4f19a2150f93220ca7a9bcd4e5d570736f639230ef338236`，新样本 `35d220b64162cb9409da47af81fbb09ae96f170ed77eaa0ffb440add65f92f35`，原签名均为 `9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2`。新增负样本位于 `app/src/androidTest/assets/aex04-mangadex-conflicting-signer.apk`，哈希 `734f3181871394486aba5e2da4842913d244ef0e3d2da6a5c022ebbfe05f5ec5`，复用既有受控测试证书 `9be8a18439915033e8362f25426323e8b7b94f223eadca4962ce5f91a23d6021`；同目录 provenance 记录重签命令，明确不是上游发布。主模型独立运行 apksigner verify 并对比 ZIP 内 manifest/DEX 哈希，确认两项与原版相同；原始二进制不修改。
- 早期失败均是测试假设：SManga 未设 title、把系统广播前的源对象当成永久实例、等待并不存在的第二次更新确认框。实际安装器在 API31+ 请求 USER_ACTION_NOT_REQUIRED，同安装器可信更新可由系统自动完成；最终测试在需要时点击正常确认，成功终态到达则取消确认等待，不采用 shell 安装/删除权限。首次系统安装仍正常确认。
- `aex04-upgrade-confirmation-build` exit0/50s，真实 spotlessCheck 与测试APK编译通过；`aex04-upgrade-confirmation-final-art.log` 为 `OK (10 tests)` / 9.99s，新增升级2项、既有生命周期4项、历史安装4项。`aex04-upgrade-final-runtime.log` 含两种方式各三类保全和可信升级的实际输出。主模型已读取原始日志及审查测试差异；仅局部簇通过，未代替全批审查。测试APK当时哈希 `4bd8d2f8471d2b562bd3e08cd4feb3531e1060c800d231318f89329ef29df6ae`。
- 权限通过Settings恢复deny，系统测试包已卸载、私有目录仅oat；无production改动、无新增发布密钥、无个人设备操作、无独立状态提交。AEX-04错误原因UI及导航/整合出口仍须完成。

### AEX-04 已安装 Source 的真实页面导航（未关闭批次）

- 主模型新增 `ExtensionInstalledSourceNavigationInstrumentationTest`，复用正常私有APK安装后注册的英文源，使用默认DI的实际 BrowseSourceScreen 和 CurrentScreen，不注入 Source/ScreenModel/仓库。点击实际热门漫画后，MangaScreen 的ID与真实数据库记录一致，详情更新产生的漫画memo与章节行均在真实存储/UI中验到。
- 首次 `aex04-installed-source-navigation-art.log` 在标题等待处超时：测试错误地将 null listingQuery 当成热门；实际 Listing.valueOf(null) 是空搜索。改为现有 GetRemoteManga.QUERY_POPULAR，未改production、不冒称产品RED。`aex04-installed-navigation-popular-build` exit0/47s，真实spotlessCheck和测试APK编译通过；`aex04-installed-source-navigation-popular-art.log` 为 `OK (1 test)` / 2.45s。
- 成功测试先销毁页面composition，再精确删除本次源的两个固定热门URL并复查不存在，恢复lastUsedSource偏好并正常卸载扩展。首次失败的空搜索另留下1条非收藏受控记录；主模型以模拟器run-as sqlite3只读确认ID=1、source=2919241217、URL与标题均为固定search--en-1后，按全部字段条件精确删除，changes=1，复查该测试源记录数为0。没有清空数据库或个人数据；测试记录可由固定样本再生成。
- 上述是debug/API36/x86_64的已安装源页面串联，不替代正式R8、ARM或真实站点阅读。批次整体尚未独立审查/提交，失败原因UI和阅读下载复验仍待完成。

### AEX-04 安装失败原因与重试闭环（未关闭整批）

- 复用上一执行者的安装上下文，保留原 `Flow<InstallStep>` API，在同一installer维护只读typed错误映射，由事务身份锁防止旧会话迟到结果污染新重试。Manager转发、原ScreenModel观察，原扩展条目展示安全本地化原因与原有重试按钮；列表旧collector完成也不能清除新会话状态。成功、取消、重试开始和卸载清理对应包，不影响其他包。
- 分类涵盖完整性/签名/仓库访问校验、网络、限流、服务端、安装许可、损坏/不兼容包、存储/恢复及未知错误。文案固定映射到资源ID，不展示cause中的路径、URL或凭据；签名错误不提示登录、不扩大信任；恢复提示明确Android可能无法降级且不要清除应用数据。未改共享AppError/coordinator、Desktop、发布标识或安装引擎。
- 正确RED：`aex04-install-errors-red` 中installer/Manager及ScreenModel两条行为断言预期Authentication实际null；`aex04-install-error-ui-red-art.log` 实际目录安装损坏包后等待原因文案超时。第一轮GREEN的卸载fixture模拟错PackageManager方法，触发Uri.parse not mocked；按真实getApplicationInfo调用修正，未记作产品RED。`aex04-install-errors-green-fixture` exit0/37s，14+7+1共22项focused通过。
- 主模型在冻结差异上审查事务归属、错误清理、旧collector、文案安全和真实UI测试，无阻塞项。`aex04-install-errors-regression-build` exit0/103s：安全回滚26、协调器接线14、会话28、列表接线7、文案1，共76项，XML failure/error/skip均0；真实spotlessCheck、assembleDebug、assembleDebugAndroidTest通过。`aex04-install-error-ui-green-art.log` 为 `OK (2 tests)` / 3.613s：损坏APK及合法换签名MangaDex分别经真实目录按钮显示分类原因，再点击Retry安装原始签名包、加载注册，原因和重试按钮消失。主模型已独立读取XML/ART日志并校验两份APK哈希，未仅采信回执。
- 当前已部署debug宿主SHA-256 `2d8f5e0c09841d63ff57fd96f4f00d7cbcb23b8ca8bcf15018b7fad075023368`；测试APK `974de8e3ef33aa02708aa31cfb11c589d2ba6c0ea558b80bc4c577f16240045d`。安装权限deny，测试扩展已清理，Gradle/runner结束。完整AEX-04仍需当前宿主的必要整合复验、阅读下载网络前置恢复及全批审查/提交；不提前勾选C9或本批，不把debug当正式release证据。

### 2026-09-16 AEX-04 收口（替代前文阶段内 pending 状态）

- 独立整批审查发现首次安装的仓库签名绑定缺口；`aex04-repository-signer-red` 明确以预期 Authentication、实际 null 失败。最小修复在快照/提交前比较当前仓库指纹与 APK 签名，支持指纹大小写/冒号归一，不借全局信任绕过。PRIVATE/SYSTEM 的准入均受保护；真实UI反例把冲突证书添加为另一可信仓库，仍拒绝并允许用正确APK重试。唯一一次定向复审通过。
- `aex04-final-related` exit0/80s：Android extension及列表相关完整测试组115项，共享协议JVM/Android各2项，合计119项，failure/error/skip均0；app/domain真实无hook格式检查通过。`aex04-signer-reader-refactor-format` 的69项及测试APK构建也通过。Desktop未再改动，复用并核对 `aex04-desktop-payload-refactor` 的83项XML（11+44+3+10+15），均0失败/错误/跳过；不冒称完整Desktop或正式构建验收。
- 当前宿主上的 `aex04-final-batch-art.log` 为 `OK (16 tests)` /18.526s：目录按钮、信任确认/关闭、失败原因和Retry、实际源查询/详情导航、真实MangaDex1.4→1.6系统/私有升级及拒绝保全、1.4/受控1.5兼容、1.6系统/私有安装与取消、在线页图/正常DownloadJob/离线重读、memo及用户进度保留。没有注入Source代替APK加载。
- `aex04-final-restart-prepare.log` / `aex04-final-restart-verify.log` 各1项通过；force-stop间隔后的运行日志明确 `preparePid=28687 verifyPid=28750 crossProcess=true`。不是同进程重建存储冒充重启。
- 验收设备仅 `emulator-5580` / API36 / x86_64。已部署宿主 [app-x86_64-debug.apk](../../app/build/outputs/apk/debug/app-x86_64-debug.apk) SHA-256 `a0a415fe51d60d83827b8b9b84647275a6888d52d2c1fd4b20f45078dcc35d93`；测试APK SHA-256 `7e20a5f3fd78955ef78d995f0b8fdef57d87901203ef9ed238d3051c1c3ac4ee`。该临时debug产物仅供本批验收，不是AEX-06正式交付。普通Settings开启未知来源，结束恢复deny；未操作手机或使用shell安装权限绕过系统确认。
- Reader 503诊断证明 Settings键删除并不等于Android运行态代理清除：实际client的ProxySelector仍选主机代理，回环夹具请求因此未到达。显式 `http_proxy :0` 清除后真实链路恢复，最后删除临时键恢复原未设置状态。临时探针已移除，保留失败诊断；没有为通过测试改变production网络策略。
- 范围说明：多个文件共同组成metadata、签名准入、正常安装、失败UI和真实包业务整链，不能按文件数拆开提交。既有Desktop签名payload补修一并通过审查；受控1.5样本沿用用户授权，非历史第三方APK。旧数据/跨签名迁移和真实站点验收留AEX-05；最低API、ARM、R8、Windows/macOS正式发布留AEX-06。当前只勾选AEX-04/C9，不宣称整个roadmap完成。

### 2026-09-16 AEX-05 首轮备份证据（批次未完成）

- `PreferenceBackupCreator.createSource` 仍枚举CatalogueSource，导致已注册的Source-only ConfigurableSource设置缺失。`aex05-source-preference-red` 首次以空备份列表失败；重试时另有MockK/Robolectric类加载装配错误，已改用明确的SourceManager边界实现，不把它混作产品RED。最小production修复为复用 `getQuerySources()`，保留私有偏好需显式选择、运行态偏好永不导出的原规则；新增测试还调用真实PreferenceRestorer恢复普通设置。
- `aex05-source-preference-green` 通过；格式整理后 `aex05-backup-refactor` 执行Android备份相关11项，failure/error/skip均0，真实app格式检查及测试APK构建通过。尚未作本批独立审查或提交。
- 新增 `HistoricalBackupRestoreInstrumentationTest`：固定历史备份未经重写，使用默认DI/生产BackupRestorer/真实Android DB恢复，核对漫画/章节memo默认为空、分类、已读/书签/第7页、笔记、应用/源设置、仓库身份，再通过生产BackupCreator生成并读取库备份。源设置的新版导出由上述独立行为测试覆盖；该历史导出测试未假称所有设置二次round-trip。
- 首次ART只因README过期哈希而失败。已核对旧提交 `907f1783e4`：当时修正分类引用7→1，同步更新二进制及契约测试，却遗漏README。现以既有契约/原提交共同确认的 `f8ddfe8bea24ff9d428ce06058beef8194144542c8774b6ab25493528acd89a8` 核验，未替换或重生成夹具；同步修正文档。
- `aex05-historical-backup-fixed-provenance-art.log` 为 `OK (1 test)` /0.153s，专用API36/x86_64模拟器。宿主SHA-256 `eb7f79ab7c7c0c4da6bdf00801c7aa189adf5fc379701e4996c5d836c773543e`，测试APK `42e4af71c526cf42498224470b38eaf4cb18186595627b85723b78da03ada1eb`。只清理preflight确认不存在的固定记录和偏好，恢复原theme/categorized设置，未操作实机。

### 2026-09-16 AEX-05 真实站点补验

- 新增 `LiveMangaDexAcceptanceInstrumentationTest`，复用AEX-04普通私有安装固定原始签名APK，安装后的真实Source由生产SourceManager注册；使用默认DI SourceMangaSearchService、UpdateManga、真实数据库及ChapterLoader，不改写业务HTTP响应、不注入Source或图片客户端。
- `aex05-live-build` 构建通过；`aex05-live-direct-art.log` 为 `OK (1 test)` /7.543s。运行日志：`source=2499283573021220255 chapters=31 imageBytes=1237854`，表明实际站点查询、合并更新、页列表和页图读取通过。首次直连即成功，没有启动代理重试。当前宿主仍为上一节的 `eb7f79...`，测试APK SHA-256 `77b68b939bb3d9dcd15dab17bdfd5a94fdee17a996649c3ababba18945fed748`。
- 业务部分限时120秒，安装由既有夹具限时；只删除本次创建的漫画/章节并正常卸载测试扩展，不碰已有书架条目。`aex05-live-format` 真实无hook格式检查及git diff --check通过。此项不证明整章下载、原地宿主升级、跨签名实例、R8或ARM，AEX-05仍未关闭。
- 下一步原地宿主升级不能用当前备份回放冒充：拟从固定AEX-01提交 `295107aa4` 构建旧宿主，再对同一隔离应用身份覆盖安装当前宿主。检查确认现有构建没有applicationId覆盖参数；应采用仅测试构建的身份覆盖，使模拟器既有 `app.mihon.dev` 数据不受降级影响。此工作需要独立的限域构建/数据播种预算，不创建新的产品功能或长期状态框架。

### 2026-09-16 AEX-05 旧宿主覆盖升级与下载保全

- 固定旧提交 `295107aa4d` 的独立工作目录 `D:/Shell/Github/mihon-aex05-old`；两次构建共同使用 `scripts/aex05-upgrade-identity.init.gradle`，只把测试applicationId改为 `app.mihon.aex05`，debug最终为 `app.mihon.aex05.dev`，不改namespace/数据库/签名。旧版本 `0.19.4-9079`，新版本 `0.19.4-9090`，versionCode均18；覆盖安装使用同签名，未清除新旧之间的数据。
- 历史构建边界：初次离线缺Compose POM，代理补齐后，上游FlexibleAdapter POM缺失导致许可生成失败，运行时AAR本机已有。历史工作目录仅移除AboutLibraries生成插件一行、添加已有许可资源目录一行；许可资源SHA-256 `5b3dc0eecb84288b0c7219d733c83d90978638d44d46f8050bba518c2d5e2c97`。历史应用源码与运行依赖声明未改。单纯 `-x` 跳任务因AGP资源Provider依赖失败，最终以上述显式两行构建差异成功；这不是可发布的历史官方APK，不隐去许可资源差异。
- `aex05-old-host-frozen-license` 构建通过，旧APK SHA-256 `3c97b68695e58c988fbe4df5503b368e4fbaf00aa332a64c732473d6ca0877ca`；`aex05-current-isolated-host` 构建当前隔离宿主，新APK `374dd13949bd3e10a54ebc4adec2e4c90b8b6666d9e78a1ed7299dc9700ff5dd`。apksigner分别验证两者证书指纹相同：`fb141e4e749dc634a3d5d79e179a1057ab81fa1fd7cb5077b6dfeccfe11f3079`（测试debug证书，不是未来fork发布密钥）。
- `HistoricalHostSeedInstrumentationTest` 真正在旧宿主运行，断言旧DB schema18；播种独立ID的漫画/章节/分类、源设置、旧仓库和原始签名MangaDex1.4私有安装状态。通过旧loader注册真实源，再用旧DownloadProvider命名创建固定页图下载；不是声称通过旧版用户安装UI。首轮因漏建下载根目录失败，只清除此专用测试应用后修正重建，原 `app.mihon.dev` 未操作。
- `aex05-old-host-seed-final-art.log` 1项通过；覆盖安装当前宿主后 `aex05-host-upgrade-art.log` 1项通过，真实schema18→20，漫画90005、章节90006、分类90007、Source ID、标题/笔记、已读/书签/第7页、源设置保留，memo默认空对象，旧仓库新增列为null。真实ChapterLoader读取旧下载且pageLoader.isLocal，未联网兜底。`aex05-host-upgrade-reopen-art.log` 强制退出再启动后仍通过。
- `aex05-host-catalog-upgrade-art.log` 1项通过：旧仓库记录不手工改indexUrl，生产发现链从原baseUrl读取新v2目录；旧扩展不标废弃，正常事务升级1.4.211→1.6.0，全部61个源ID保持一致。随后同一旧库/旧下载断言仍通过；`aex05-host-catalog-upgrade-reopen-art.log` 再次重启后通过。运行标记分别为 `AEX05_CATALOG_UPGRADE_OK` 和 `AEX05_HOST_UPGRADE_OK`。最新测试APK SHA-256 `eb22eef44dfa37fd2bdb4b2a7377b4f84b2784b34e7b89a452078325464e573e`。
- 重放顺序：在固定旧工作目录以init脚本构建并安装旧宿主；当前工作目录以同一init脚本构建宿主及androidTest；只先安装测试APK，运行 `HistoricalHostSeedInstrumentationTest`；覆盖安装当前宿主，运行 `HostUpgradePreservationInstrumentationTest`；再带 `-e aex05CatalogUpgrade true` 运行一次；force-stop后不带该参数复验。runner为 `app.mihon.aex05.dev.test/androidx.test.runner.AndroidJUnitRunner`，只用专用 `emulator-5580`。两个跨宿主测试对其他应用身份跳过，不能把跳过当验收通过；旧数据seed不允许覆盖现存fixture。
- 保留隔离实例及历史构建目录用于后续跨签名验证。尚未宣称迁移过程中强杀恢复、跨签名迁移或R8/ARM通过；AEX-05未勾选，批末独立审查尚未执行。

### 2026-09-16 AEX-05 恢复取消边界

- `BackupRestorerBehaviorTest` 使用固定历史备份、真实解码及恢复编排，在漫画恢复期间取消父任务。`aex05-restore-cancel-red` 正确失败：未完成的漫画仍触发 `showRestoreProgress(..., 1, 1, ...)`；不是声称整体完成通知也被触发。
- 漫画恢复分支现在单独重抛 `CancellationException`，不把取消当作普通条目失败继续计数。`aex05-restore-cancel-green` 定向3项及真实格式检查通过；`aex05-restore-cancel-regression` 相关备份12项全部通过，failures/errors/skipped均0，git diff --check通过。
- 此证据仅覆盖协程取消，不等价于进程强杀中的数据库迁移恢复；其他取消分支仍需批末核对。AEX-05保持未完成、未提交，后续跨签名迁移及独立审查不省略。用户已拔出安卓实机：ARM实机验收统一留到最后，不等待或轮询设备，继续不依赖实机的工作。

### 2026-09-16 AEX-05 跨签名安全迁移

- 新增 `CrossSignatureBackupInstrumentationTest`，仅允许专用模拟器及 `app.mihon.aex05.dev`，显式 `aex05MigrationPhase=export/prepare/restore/verify`。导出使用生产BackupCreator（关闭应用设置和非书架条目，保留源设置/仓库），恢复使用生产BackupRestorer/默认DI/真实数据库。没有复制数据库或私有扩展安装信任。该测试无产品行为改动。
- `aex05-cross-signature-build` 构建当前隔离debug宿主和测试APK；首轮导出因测试未先创建目标文件失败，已核对手动备份接口要求既有文档，补 `createNewFile()` 后 `aex05-cross-signature-file-build` 测试构建及真实无hook格式检查通过。该失败是测试输入不符，不当作产品RED。
- 临时测试证书 `CN=AEX05 Migration Test Only`，SHA-256 `711ddfc2146068b4ebda82b6d40f2421b22897fd3f868e60560e6a36924a7d04`；与原debug证书不同。通过apksigner对宿主/测试APK重签，不修改应用代码或manifest。测试密钥位于忽略的 `app/build/tmp/aex05-cross-signature/test-only.p12`，公开测试口令 `aex05-test-only`，不得用于正式发布；用户授权的新fork发布密钥仍属AEX-06未完成项。
- `aex05-cross-signature-rejection.log` 明确返回 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；随后 `aex05-cross-signature-rejected-preservation-art.log` 原宿主升级保全测试1项通过，书架和生产ChapterLoader旧下载读取未受影响。
- `aex05-cross-signature-export-final-art.log` 1项通过。库备份SHA-256 `a938d8854431fb82a545ebfd291ccfc6e59fd8c33cacab089e3ef9c045e5d068`；独立下载ZIP `17ce65bf501bbfe2b033ea87c540fb5768c69ee7343e4fadc591e52a0dc1135d`。三份传输文件（含页图哈希）先adb pull至 `app/build/tmp/aex05-cross-signature/transfer`，核对设备与电脑哈希一致后，才卸载专用宿主及其测试包；原 `app.mihon.dev` 未操作。
- 安装换签名宿主后，`aex05-cross-signature-prepare-art.log` 确認无旧数据，推回已验证的备份/ZIP，再运行 `aex05-cross-signature-restore-art.log`（1项通过/0.1s）；force-stop后 `aex05-cross-signature-reopen-art.log`（1项通过/0.025s）。源ID、书架标题/笔记、分类、已读/书签/第7页、源设置和仓库指纹均保留。独立下载图片SHA一致；原seed receipt、私有安装信任均不存在，SourceManager确实缺源，未把扩展/信任直接迁移冒充新安装。
- 换签名宿主SHA-256 `00588f771ace3cf7faf77f3ea16e37b999bd5e44404e19345b8e81dc1cc582fe`；测试APK `9d1bb081a689eac87345f94137a61559cf1f4c05b21c5dfbcdad2fa2c2d50cd3`。当前模拟器隔离实例为此测试证书安装，不再是原debug签名；普通debug APK不能直接覆盖它。重放须从历史播种流程开始，不能在现有换签名实例上误跑export或restore。
- 下载恢复边界：备份不携带图片；本测试另外复制专用下载目录并验证字节，未设置新实例下载目录或重装扩展，因此不宣称换签名后已能直接阅读。用户迁移需单独保留下载目录、选择存储位置，并通过正常仓库安装/信任扩展；正式发布仍需实际下载/阅读运行验收。AEX-05剩余故障矩阵收口及独立审查，仍未勾选或提交。

### 2026-09-16 AEX-05 批末收口（取代上文阶段内pending状态）

- 独立代理 `aex05_review` 一轮批审发现仓库恢复分支与漫画同类的取消遗漏。`aex05-repo-cancel-red` 参数化案例只在仓库分支因错误进度通知失败；补两行CancellationException重抛后，`aex05-repo-cancel-green` 共68项（备份13、Android API16、Manager12、安装安全27）全部无失败/错误/跳过，真实无hook格式检查通过。一次定向修复复审核对取消修复，并指出新增中断测试的默认运行门控问题；已改assumption并实际验证默认调用返回跳过（status -4，不计成功业务案例）。
- 故障证据：`ExtensionApiSharedCatalogTest` 实际MockWebServer→生产解析覆盖部分仓库失败、权威空目录、缓存、超时/取消、403/429/500及畸形响应；`ExtensionManagerTest` 覆盖失败仓库保全、obsolete重新发现和未知协议拒装；`AndroidExtensionInstallSecurityRollbackTest` 覆盖仓库签名绑定、metadata不匹配、损坏/拒绝和回滚。AEX-04已验收真实签名冲突/损坏升级保全与typed错误UI，本批相关回归保持绿；缺源恢复见真实历史及跨签名ART。不把相互独立的单元测试冒称为一次端到端操作。
- 新增 `InterruptedExtensionUpgradeInstrumentationTest`：复用原始签名1.4正常私有安装，在生产Manager发出1.6下载请求、测试server收到HTTP请求并持久化PID/信任哈希/source IDs时，由主机强杀，再以新进程核验旧APK/信任字节及61个源实例注册，最后正常重试1.6更新。仅在专用身份且显式 `aex05InterruptPhase=prepare/verify` 时运行。
- 中断测试装配失败如实保留：首轮因跨签名夹具遗留的同指纹仓库违反唯一约束，现仅在精确名称/指纹匹配且备份存在时删除该测试行；既有跨签名备份仍保存在应用外。第二轮串行工具等待错过网络超时，夹具自行失败清理，因此后续文件缺失不是产品数据丢失证据。仅清理该失败轮明确PID=3136的测试收据；没有清空数据库或触碰主实例。第三轮并行监视新收据和活PID，收到请求后2.22秒内强杀成功。
- `aex05-interrupted-upgrade-kill.log` 确认PID3485；`aex05-interrupted-upgrade-killed-art.log` 预期为进程被杀（不是通过的test）；`aex05-interrupted-upgrade-verify-final-art.log` 为1项通过/0.292s，运行标记 `oldPid=3485 newPid=3630`。重试后APK哈希对应固定1.6，源ID保持。测试APK最终SHA-256 `84fc27f4b02857260e47e43c911853911327e2d49a8cb6cbf00d6f7d14123a0b`，沿用跨签名测试证书；`aex05-interrupted-upgrade-gate/fixture` 构建及真实格式检查通过。
- 重放需在现有历史/跨签名流程后进行：以同一隔离身份编译测试包，并用当前实例证书签名；并行启动prepare runner与有界主机监视器。监视器读取仅该实例的 `shared_prefs/aex05-interrupted-upgrade.xml`，核对收据PID仍为 `pidof app.mihon.aex05.dev` 后force-stop；随后verify。不得等待prepare正常结束再强杀。完成后保留本次拥有的1.6扩展和本地夹具仓库用于后续验收；旧跨签名恢复测试不再可直接重复运行，须按各自前置重建。
- 证据边界：覆盖升级下载请求阶段进程中断与成功重试，不证明数据库迁移/文件提交每个指令边界的断电原子性。旧下载本地读取、跨签名图片复制、在线单页分别记录，不宣称本批完成正式release整章下载。正式密钥/R8、最低API、ARM及完整Windows/macOS验收仍是AEX-06硬门槛。
- 本批跨多个测试文件是同一历史升级/数据保全功能簇，production仅源枚举及两个取消分支；超出8文件/400行主要为真实ART夹具、设备步骤和既有文档证据。风险集中在隔离测试状态与签名，已用专用applicationId、显式阶段及精确fixture边界限制；不为行数拆开不可独立验收的迁移链。独立审查、相关验证和主模型结果核验完成，AEX-05/C10随本次功能提交关闭。

### 2026-09-16 AEX-06 发布前置（未完成）

- AEX-05提交 `dbf3f050a1a5cd112b9a3347285259f4d319e161`。本批按Android正式产物→Windows/macOS正式产物→最后ARM实机/证据总审推进；共享worktree重型Gradle仍仅主模型串行执行，Mac前置只读检查可并行，不把前置核对拆成独立交付提交。
- 新增显式启用的 `scripts/android-fork-release.init.gradle`，发布包名 `app.mihon.desktop.fork`、versionName `0.19.4-aex.1`、versionCode19。普通构建不使用该脚本时仍保留原身份；namespace不变。独立包名不能覆盖官方 `app.mihon`，迁移按备份/存储选择/正常扩展安装处理，绝不把新证书冒称官方签名。今后该fork更新必须继续同一包名/密钥并增加versionCode。
- `aex06-release-config` 以真实AGP DSL运行 `:app:verifyForkReleaseConfiguration` 通过（13秒），核对R8与资源压缩开启、不可调试、遥测/updater关闭、签名由Gradle外处理。校验任务随后挂入 `preReleaseBuild`，实际发布构建时再次强制检查；本轮未运行assembleRelease，不能以配置绿代替R8/ART验收。
- 按用户批准，新建正式4096位RSA/PKCS12密钥：`D:/Android/Signing/mihon-desktop-fork/release.p12`，alias `mihon-desktop-fork`，证书SHA-256 `bd8e3af75921fc4356deacabd44a3d491fda8439ffbc7d073c363974a648cae3`，不是AEX-05测试证书。一次性创建入口 `scripts/create-android-fork-release-key.ps1` 拒绝覆盖既有目录/密钥及写入仓库路径；生成后以keytool实际重读验证。目录ACL关闭继承且仅当前用户一条完整访问规则，检查通过。
- 随机密码仅保存在该目录的 `password.dpapi.xml`（Windows当前用户/机器DPAPI加密），未写入仓库、命令参数或日志；用于keytool的临时环境变量已清除。签名时应在受控本地进程解密至临时环境变量，使用apksigner的 `env:` 参数，并在finally清除；不得打印密码。密码文件不能直接跨机器解密，迁机前需安全离线备份密钥及另行妥善保存可恢复的密码；禁止为方便重建/轮换此密钥。当前只创建密钥，尚未签名交付APK。
- 子代理 `aex06_mac_preflight` 只读确认 `ssh -o BatchMode=yes -o ConnectTimeout=8 mbp-lan` 可用；macOS14.8.4/x86_64，脚本所需 `/Users/altair/.jdks/jdk-21.0.10+7/Contents/Home` 可运行，Python3.9.6，剩余磁盘约35GiB。`/Users/altair/Github/mihon` 停留旧提交 `c84ed331fa0b` 且有用户改动，不覆盖/清理；后续同步最终提交至隔离工作目录，仍通过 `scripts/build-desktop.sh`，谨慎处理现有 `/Applications/Mihon Desktop.app`。未启动远端构建或应用，不声称macOS发布验收已通过。
- 下一出口：执行上述init配置下真实R8 release构建，校验manifest/版本/证书/flags，隔离模拟器运行生产调用链；再集中最终全量与正式Desktop验收。最低API/ARM、正式APK、平台运行和parity收口证据仍缺失，AEX-06保持未勾选。

### 2026-09-16 AEX-06 首个签名R8候选产物（运行验收待完成）

- `aex06-r8-release` 离线构建48秒后因Compose映射工具及其依赖未缓存失败，不是产品/R8缺陷。按本机代理规则使用明确HTTP/HTTPS JVM代理重试一次，未改依赖版本；`aex06-r8-release-proxy` 2m1s通过，实际执行 `minifyReleaseWithR8`、desugaring及资源压缩/优化。`preReleaseBuild` 已实际执行fork身份校验；未传遥测、updater或disable-code-shrink参数。
- 生成BuildConfig确认DEBUG=false、包名 `app.mihon.desktop.fork`、versionName `0.19.4-aex.1`、versionCode19、TELEMETRY_INCLUDED=false、UPDATER_ENABLED=false。源码基线 `dbf3f050a1` 加本批未提交的发布配置，不冒称产物来自干净的已提交AEX-06版本。mapping/configuration/usage等真实压缩输出位于 `app/build/outputs/mapping/release/`；不读取或提交巨型mapping快照。
- R8输出AndroidX Window extensions/sidecar及jsoup RE2/J缺失类警告，但任务最终成功；没有添加dontwarn或关闭R8。[Android官方说明](https://source.android.com/docs/core/display/windowmanager-extensions)将前者描述为设备可选平台模块；[jsoup 1.22.1官方发布说明](https://jsoup.org/news/release-1.22.1)将RE2/J描述为需另加依赖启用的正则引擎。这仅解释可选性，实际折叠屏/HTML解析运行仍须验证，不能凭文档消除运行风险。
- 新增 `scripts/sign-android-fork-release.ps1`：先对实际APK执行身份/debuggable与zipalign检查，再从仓库外DPAPI文件在受控进程临时解密，使用apksigner `env:` 参数签名，finally清理密码，最后核验证书及输出SHA。`aex06-release-signing.log` v2/v3签名均通过、单一4096位RSA签名者，证书为上一节的新fork密钥。实测拒绝覆盖已有签名APK、拒绝debug/错误身份且不产生文件；未暴露密码。正式输出目录 `app/artifacts/` 加入Git忽略，不将二进制混入提交。
- 候选产物：[Mihon Fork 0.19.4-aex.1 rc1 universal APK](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.1-rc1/Mihon-Fork-0.19.4-aex.1-rc1-universal.apk)，67,339,965字节，SHA-256 `6f57c3a9e36b9e64d9643cb7b33a4dcbb18baf3bad1fbcb57fcaddbd40f7e281`。targetSdk36，含arm64-v8a/armeabi-v7a/x86/x86_64。`rc1` 是候选文件标记，不改变APK内versionName；未来修复产物必须另用候选路径，脚本拒绝静默覆盖。
- 本輪没有安装此包、没有运行实机或Desktop构建，没有进行最终独立审查/全量测试；此链接不是完成验收的发布承诺。下一步以该实际签名/R8 APK运行新版扩展搜索/更新/阅读/下载/重启及信任工作流，不能用debug结果替代。AEX-06仍未勾选、未提交。

### 2026-09-16 AEX-06 首轮最终回归与候选运行失败（未完成）

- 用户已拔出ARM实机；实机任务统一排在其他工作之后，不轮询、不等待手机连接。当前设备操作仅限专用 `emulator-5580`。
- `aex06-full-offline-regression` 执行 `testReleaseUnitTest jvmTest :test-desktop:test spotlessCheck --continue --max-workers=2 --no-parallel --no-daemon --offline`，7m24s失败：Android仓库生命周期测试JDBC driver未注册、Desktop全局搜索测试超时、parity当前源码行号失效，共3项失败；Desktop另有2项跳过。部分任务命中缓存/UP-TO-DATE，不能宣称全部重新执行；默认排除的联网/最终parity标签也不属于本命令的覆盖范围。
- 仓库生命周期测试按项目现有模式显式注册/清理SQLite JDBC driver；`aex06-regression-focused-diagnosis` 中该行为测试及Android格式检查通过，桌面超时仍独立复现。桌面夹具只模拟旧接口，而production已使用 `getMangaUpdate`；修正为新版接口并断言数据库最新标题/封面传入，仍验证原搜索对象未被修改、重复点击不重复导航或刷新，不延长超时。`aex06-regression-focused-green` 37秒通过，全局搜索2项及parity契约34项均无失败/跳过。
- parity仅修正26处当前角色证据行号，不改变能力状态。一次补丁误命中ID35冻结历史锚点，经对照HEAD发现并恢复；最终只读核验所有 `FIXED_ORIGINAL` 与HEAD一致，所有当前角色行号仍指向声明符号。未扩大为清单重写或更新历史基线。
- 子代理以实际rc1签名R8 APK完成首次启动、存储/权限设置、添加真实Keiyoushi仓库并显示MangaDex1.6.0；点击安装后出现损坏/不兼容提示，未进入系统安装确认，尚未完成源查询、阅读、下载或重启业务验证。直连和一次临时代理重试均失败；统一文案可对应MalformedData或NoResults，现有日志未暴露具体cause，不能认定为R8或网络根因。优先核对实际下载物解析、元数据与摘要验证；本地fixture不是此次下载物，不能替代证据。
- 已清理模拟器临时全局代理（`:0`后delete），并仅force-stop `app.mihon.desktop.fork`，无运行中的该宿主。子代理只读核对source-api consumer keep与有限裁剪条目，未取得外部必需ABI被裁剪的证据，没有放宽校验、关闭R8或重复构建。下一步先定位安装失败具体边界；最终审查、正式Windows/macOS运行、最低API/ARM及最终回归仍待完成，AEX-06保持未勾选，不以定向绿灯替代完整验收。

### 2026-09-16 AEX-06 正式运行安装身份缺陷修复（批次仍未完成）

- 真实新版目录 `repo.json → index.pb` 发布MangaDex包名 `eu.kanade.tachiyomi.extension.all.mangadex`、versionCode106000、versionName1.6.0、协议1.6。实际应用HTTP缓存APK与该目录下载APK的SHA-256相同：`35d220b64162cb9409da47af81fbb09ae96f170ed77eaa0ffb440add65f92f35`。临时Android只读探针确认系统及候选R8包中的metadata/gateway解析能识别包名、协议、版本、扩展feature及仓库签名；探针不是production wiring验收，不能替代真实应用运行。
- 新增固定校验拒绝原因日志，不记录URL、凭据或私有路径。`aex06-validation-log-red` 因缺少日志正确失败；随后28项安装安全/回滚测试通过，长行格式修正后 `aex06-diagnostic-r8` 通过。rc2新签名候选SHA-256 `e55cea28f4f929b972dfe6af6dd2d29a3c48a48c4d12f106db1d9a40a7287d37`，实际应用日志确认失败在APK/目录元数据一致性检查，而不是签名拒绝或类加载；记录 `.gradle-coordinator/aex06-rc2-validation-reason.log`。
- 已证实根因：Android `GetExtensionsByType` 的语言行投影把 `pkgName` 改成 `包名-源ID`，ScreenModel将此投影直接交给安装器，和真实APK包名不匹配。删除该包名改写，保留语言/源投影；现有UI行hash含源数据，仍区分不同语言行，安装/取消/错误状态统一使用真实包名。Desktop已有独立真实安装包名字段，没有把Android修复扩张到Desktop重构。
- `aex06-projection-identity-red` 中分类和实际ScreenModel安装入口均因收到 `pkg.bundle-7` 而失败（Gradle自动重试使2个逻辑失败记录为6次失败）；一行production修复后8项界面链路及28项安全/回滚测试通过。测试覆盖两个语言行独立展示、点击安装与取消传递真实包名、保留版本/下载地址；两处测试格式问题修正后 `aex06-identity-fixed-r8` 2m53s通过，包含上述36项测试、格式检查、真实R8及资源压缩构建。
- 新候选：[Mihon Fork 0.19.4-aex.1 rc3 universal APK](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.1-rc3/Mihon-Fork-0.19.4-aex.1-rc3-universal.apk)，SHA-256 `63e87ad7a5a959856fd6ca188fc7ee800192f16036cc60257c2583bcb492ad9a`，同一正式fork证书、独立包名、不可调试，旧rc1/rc2未覆盖。当前仍来自AEX-05基线加本批未提交差异，不冒称最终发布。
- 专用API36模拟器真实UI确认：扩展列表搜索MangaDex → 点击英文源行 → 出现系统安装确认 → 确认后显示Installed/Multi/1.6.0 → force-stop重启 → Sources中仍有MangaDex English → 打开Popular得到真实在线漫画列表。已明确确认本项安装bug修复；UI证据 `.gradle-coordinator/aex06-rc3-popular-ui.xml`。本轮未证明搜索、详情更新、阅读、完整下载或最低API/ARM兼容。
- 结束时临时HTTP代理已按`:0 → delete`清理，仅fork宿主force-stop，Gradle均已结束。MangaDex现为模拟器系统安装包，后续验证不得误以为不存在或无条件卸载。ARM任务仍最后，AEX-06未审查/提交/勾选；下一步复用rc3继续完整业务运行及其他平台验收。

### 2026-09-16 AEX-06 rc3真实阅读/下载/离线重启证据

- 复用上一节同一rc3，不修改代码、不重建；专用 `emulator-5580` 为API36/x86_64。实际已安装base.apk的SHA-256再次核对为 `63e87ad7a5a959856fd6ca188fc7ee800192f16036cc60257c2583bcb492ad9a`，与[rc3 APK](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.1-rc3/Mihon-Fork-0.19.4-aex.1-rc3-universal.apk)一致，记录 `.gradle-coordinator/aex06-rc3-installed-hash.log`。
- 真实UI：Browse → MangaDex English → 搜索 `The Story of a Girl Who Can Read Minds` → 返回单个目标结果 → 打开详情后载入作者/状态与5个章节 → 加入专用测试书库 → 打开第1章，实际显示页图并翻到第2页。搜索/详情XML记录 `aex06-rc3-search-ui.xml`、`aex06-rc3-details-ui.xml`；在线截图 `aex06-rc3-reader-online.png`、`aex06-rc3-reader-page2.png`，均位于忽略目录 `.gradle-coordinator/`，不是新的版本管理快照包。
- 仅下载第1章，不下载整部作品。生产下载目录 `/storage/emulated/0/ihonAex06Release/downloads/MangaDex (EN)/The Story of a Girl Who Can Read Minds/` 生成 `Scribe's Chamber_Ch.1 - The Story of How We Met_4a37f9.cbz`，8,509,461字节，SHA-256 `a214f4b57b33891c5139a4a898faea6a333ac0d49339885b3c042b4b9b2f4e16`。只读检查CBZ含19张非空JPG及ComicInfo.xml；下载副本仅在忽略的 `app/build/tmp/aex06-rc3-chapter.cbz`，不提交漫画或截图。
- 离线验证前记录Wi-Fi=1、mobile_data=1、airplane_mode=0；关闭专用模拟器Wi-Fi/移动数据，系统确认 `Active default network: none`，force-stop并重启fork宿主。从书库重开已下载第1章，定位并实际显示第18、19页（19/19），不是只复用先前浏览过的第1/2页；证据 `aex06-rc3-offline-network.log` 与 `aex06-rc3-reader-offline-last.png`。返回详情后阅读进度不再显示第2页的未完成状态。未对19页逐张视觉审查，不把该证据扩大为所有站点/章节验收。
- 结束时Wi-Fi/mobile_data恢复为1，临时HTTP代理`:0 → delete`后为null，仅fork宿主force-stop；书库、系统扩展和单章下载保留供后续验收。没有查询ARM手机，没有启动Gradle或代理。此证据补齐当前API36代表性真实扩展的搜索/详情更新/在线阅读/翻页/完整单章下载/离线重启链路，尚不满足最低API、ARM、正式release夹具runner、全部信任反例或Windows/macOS发布门槛；AEX-06仍未勾选、未提交。

### 2026-09-16 AEX-06 release夹具构建接线（运行仍未通过）

- `aex06-release-runner-preflight` 实际确认此前 `-Pmihon.testBuildType=release` 不生效，`:app:assembleReleaseAndroidTest` 不存在。`app/build.gradle.kts` 现显式读取此属性，只允许debug/release，默认debug不变；`aex06-release-runner-taskgraph` dry-run通过并出现release测试编译、R8和打包任务。此项仅证明配置接通，不是设备测试通过；默认/非法值的独立拒绝验证尚待执行。
- 新增 `ExtensionReleaseParityInstrumentationTest` suite，复用既有1.4/1.5/1.6 ABI、旧版安装、1.6生命周期、信任UI、失败反馈及升级回滚6个测试类，不复制业务测试。Suite的BeforeClass要求专用模拟器、fork包名、非debug标记、明确传入 `aex06ReleaseSha256` 与实际安装APK一致、实际签名为正式fork证书；缺参/错误hash不得静默跳过。此guard目前编译通过但尚未成功执行，不能宣称它已通过反例验收。
- 签名脚本新增显式 `-Instrumentation`，只允许 `app.mihon.desktop.fork.test` 且manifest的targetPackage为 `app.mihon.desktop.fork`；普通宿主校验仍保持不可调试/指定版本/包名，正则中的点改为字面匹配。实测拒绝旧AEX-05 debug测试包及把正常rc3宿主误当测试包，均不产生输出；随后对真实release测试APK签名成功。未改变密钥或把密码写入Gradle。
- `aex06-release-test-build` 因新增日志长行格式失败；修正后 `aex06-release-test-package` 52秒通过（`:app:assembleReleaseAndroidTest :app:assembleRelease :app:spotlessCheck`，带fork init与release测试属性）。宿主R8/打包UP-TO-DATE，再签名到忽略的验证路径所得SHA仍为rc3的 `63e87ad7a5a959856fd6ca188fc7ee800192f16036cc60257c2583bcb492ad9a`，因此没有冒称或另建新宿主候选。测试输入实际名为 `app-release-androidTest.apk`，不是推测的`-unsigned.apk`。
- 已签名测试产物：[rc3 release instrumentation APK](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.1-rc3/Mihon-Fork-0.19.4-aex.1-rc3-release-tests.apk)，SHA-256 `5273a4d52dc8af534514954874891b64363dacc6e2b9dc4754707a20be9ca02b`，同fork证书，已仅安装在 `emulator-5580`。用全零hash运行Suite的预期反例，却在AndroidJUnitRunner.onCreate先崩溃：`NoClassDefFoundError: androidx.tracing.Trace`；没有进入guard或业务测试。日志 `aex06-release-hash-guard-negative.log`、`aex06-release-runner-trace-failure.log`。adb退出0/`INSTRUMENTATION_CODE: 0`不表示通过，必须检查进程崩溃及测试结果。
- 这是release测试框架依赖被宿主优化后的边界问题，非rc3普通UI链路失败。[官方当前Runner源码](https://raw.githubusercontent.com/android/android-test/main/runner/android_junit_runner/java/androidx/test/runner/AndroidJUnitRunner.java)的onCreate仍调用Trace，不能猜测升级runner就解决。[AOSP历史同名异常修复](https://android.googlesource.com/platform/frameworks/support/+/5f46cf5b34082a0efcbb7d32ef43873ad0300696)针对API17主DEX缺类，与当前API36证据不同，未照搬multidex修复。尚未添加宽泛keep或关闭R8；下一步限定到测试入口实际依赖，并重新执行错误hash反例，再执行真实hash套件。
- Suite中的真实MangaDex安装夹具拒绝覆盖既有系统扩展；当前rc3手动验收留下的系统MangaDex仍存在。正向套件运行前须明确保存并处理这一个自有测试安装，不能盲跑或卸载其他应用。真实release签名由外部脚本完成，所以设备步骤应使用指定序列号的 `adb install`/`am instrument -w -r -e class ... -e aex06ReleaseSha256 <actual-sha> app.mihon.desktop.fork.test/androidx.test.runner.AndroidJUnitRunner`；原表中无签名/hash参数的connected命令仍不是可用验收证据。ARM最后，不接触手机。AEX-06未完成、未审查/提交。

### 2026-09-16 AEX-06 release测试边界与最低API环境

- `aex06-release-trace-entry` 一次2m20s构建/格式通过。仅保留Runner外部调用的 `androidx.tracing.Trace` 公共static入口，普通release与受测release共用规则，未关闭R8。rc4宿主SHA-256 `3a7b54e303f4226e422bd982d85cf5b4c6c69a1d6bc8b4e3641ec6eb4e009afb`，测试APK仍为 `5273a4d52dc8af534514954874891b64363dacc6e2b9dc4754707a20be9ca02b`；产物保存在 `app/artifacts/android/0.19.4-aex.1-rc4/`。错误SHA实际在Suite BeforeClass拒绝，0项业务/1项guard失败是预期反例，不算业务通过；日志 `aex06-rc4-release-hash-guard-negative.log`。
- 主线程先导出自有系统MangaDex，核对SHA仍为 `35d220b64162cb9409da47af81fbb09ae96f170ed77eaa0ffb440add65f92f35`，才临时卸载这一个测试扩展。正确SHA套件真实执行17项、16失败，日志 `aex06-rc4-release-suite.log`；结束后已从已核验副本恢复系统扩展，书库和下载未清除。未操作其他实例或实机。
- 区分两种边界：`BasePreferences.extensionInstaller` 被删除、协程 `TestScopeImpl` 覆盖已final的 `JobSupport.toString`，是白盒夹具依赖宿主内部优化前结构；`Source.getPageList` 缺失则是公开动态扩展ABI缺陷。releaseAndroidTest配置实际含宿主 `-applymapping`，不是遗漏mapping；R8 usage与ART错误共同证实接口方法被删除。现有consumer规则只匹配Source子类型，[官方规则说明](https://developer.android.com/topic/performance/app-optimization/add-keep-rules)明确不包含基接口本身。
- 后续验证按边界分层：内部生命周期/Compose夹具仍在debug执行；release Suite只运行公开ABI契约并保留身份、SHA与证书guard，正式生命周期/安全反馈仍须通过同一发布产物的外部UI验收。删除不适用于R8内部结构的Suite注册不等于免除release行为要求；不引入Keeper或批量内部keep来制造绿灯。公开Source ABI修复及复验正在进行，当前未宣称修复完成。
- 最低支持版本由 `AndroidConfig.MIN_SDK=26` 确认。SDK Manager通过规定代理安装官方 `system-images;android-26;google_apis;x86_64` revision16，正常退出；创建 `D:/Android/Avd/mihon-aex-api26.avd`。avdmanager打印devices.xml警告，但AVD实际生成，`emulator -list-avds`识别且成功启动，不把警告误判为创建失败。
- 为控制内存，先关闭本任务API36/5580，再以2核/1536MiB启动API26/5582。实际 `sys.boot_completed=1`、SDK26/x86_64；rc4安装Success，MainActivity `Status: ok`，PID4441处于resumed，UI XML `aex06-api26-startup.xml`确认显示Welcome初始设置页。启动早期有system_server NetworkPolicyManager异常，随后启动完成；不是宿主崩溃证据。此项仅证明最低API安装/首屏，不证明扩展业务、最终rc5或ARM通过。模拟器日志位于 `.gradle-coordinator/aex06-api26-emulator.log/.err.log`。

#### 正式release复验命令

公开ABI后续绿证据：consumer规则明确保留 `Source` 与 `Source$DefaultImpls` 的public成员，不放开内部应用API。`aex06-release-source-abi` 2m16s构建/格式通过；首次尝试notClass仍运行17项，其中4项ABI各自通过、其余13项失败，不计Suite通过。精简Suite注册后 `aex06-release-abi-suite-only` 仅49秒，宿主R8为UP-TO-DATE，测试APK重建及app/source-api格式通过。主线程独立核对 `.gradle-coordinator/aex06-rc5-api26-release-abi-suite.log`：numtests=4、`OK (4 tests)`，以及 `aex06-rc5-api26-guard.log` 中真实 `AEX06_RELEASE_VERIFIED`、API26/x86_64和宿主hash。因此已明确确认公开Source.getPageList被R8删除的bug修复；不宣称其他release门槛完成。

该证据产物：[rc5 R8宿主](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.1-rc5/Mihon-Fork-0.19.4-aex.1-rc5-universal.apk)，SHA-256 `8de7a74f576e7d72cd749ca59664f7409d860b02953aab08ea310e3e72ac63f3`；[rc5公开ABI测试APK](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.1-rc5/Mihon-Fork-0.19.4-aex.1-rc5-release-abi-tests.apk)，SHA-256 `e51374d6d20af8c725a80a894bfec009dac96b5ece1c0bf2877aa70c0015d2d6`。二者由主线程重新计算hash吻合，使用同一正式fork证书。当前API26模拟器5582保留、宿主force-stop；API36模拟器5580已关闭（AVD数据保留），所有Gradle终止，无实机操作。rc5的API36/完整UI链路、Windows/macOS最终产物、最终全量及独立批审、ARM实机仍待验证，AEX-06不勾选、不做部分提交。

本节替代矩阵中历史未接线的connected命令；先确认协调器空闲。设置仓库要求的UTF-8、JDK及SDK环境后：

```powershell
python scripts/gradle-coordinator.py foreground --key aex06-release-final --timeout-seconds 900 -- .\gradlew.bat -I scripts/android-fork-release.init.gradle :app:assembleRelease :app:assembleReleaseAndroidTest :app:spotlessCheck :source-api:spotlessCheck '-Pmihon.testBuildType=release' --max-workers=2 --no-parallel --no-daemon --offline
```

构建成功后，用 `scripts/sign-android-fork-release.ps1` 分别签名 `app/build/outputs/apk/release/app-universal-release-unsigned.apk` 与 `app/build/outputs/apk/androidTest/release/app-release-androidTest.apk`；后者必须传 `-Instrumentation`，两个输出必须指定不存在的正式候选文件路径，不覆盖历史产物。对输出执行Get-FileHash，签名脚本核对正式证书；指定专用模拟器 `adb -s <serial> install -r <signed-apk>` 依次安装宿主/测试包，再运行：

```text
adb -s <dedicated-emulator-serial> shell am instrument -w -r -e class eu.kanade.tachiyomi.extension.ExtensionReleaseParityInstrumentationTest -e aex06ReleaseSha256 <signed-host-sha256> app.mihon.desktop.fork.test/androidx.test.runner.AndroidJUnitRunner
```

占位值必须取实际产物与本任务专用模拟器，不能复制为实机指令。以guard证书/SHA通过、当前5项公开ABI全部执行且0失败/跳过为该门槛的标准（原4项加正式宿主Zstd/JNI真实解压回归）。旧17项Suite的 `-e notClass` 实测不排除Suite内部子类，必须使用当前注册后重建的测试APK，不能用旧APK或零散成功status冒充全绿信封。此命令不验收系统/私有安装生命周期、信任UI、真实查询/阅读/下载或跨进程恢复；这些仍需单独按矩阵在相同正式宿主上取得外部UI/运行证据。签名输出、构建版本或宿主行为变化后，不自动继承旧候选运行结论。

### 2026-09-16 AEX-06 修复后完整回归与取消夹具稳定性

- `aex06-final-regression-repaired` 实际运行 `testReleaseUnitTest jvmTest :test-desktop:test spotlessCheck --continue --max-workers=2 --no-parallel --no-daemon --offline`，6m26s返回BUILD SUCCESSFUL，373 tasks中19执行、10缓存、344 UP-TO-DATE。Android原始XML为367条记录/1次失败：安装取消参数化案例自动重试后通过，所以不能只凭Gradle退出0宣称无失败；上一轮JDBC、全局搜索和manifest锚点三个问题未再失败。
- Desktop XML 3021项、0失败/错误、2跳过：`MacOsNativeSharePortTest` 的真实JXA用例标注仅macOS，后续须在Mac执行；`LibraryPageCompositionTest` 的“explicit non release build”要求 `BuildInfo.IS_NON_RELEASE_BUILD`，当前release配置不满足，不能算release功能失败或非release功能通过。默认integration/live-network/network-survey/final-parity/parity-governance标签仍排除，另行验收，未把默认完整回归扩大解释。
- 其他模块XML核对：Source API Android/JVM各19、domain Android370/JVM435、data Android12/JVM138、core/common Android10/JVM56、test-desktop52，均0失败/错误/跳过；部分为UP-TO-DATE/缓存证据，不声称全部重新执行。
- 取消用例根因：真实Default dispatcher可在测试执行 `runCurrent()` 前完成回滚/清理，原测试没有明确阻塞清理却断言terminal未完成；同次重试一失败一通过。仅在该用例委托真实AndroidInstallGateway，给delete清理入口加入显式latch，等待实际清理到达→断言terminal和active transaction仍未结束→放行清理→断言最终Idle与生命周期清空；finally必放行，未改production取消语义、未去掉等待清理的断言或添加睡眠。
- `aex06-cancellation-fixture-stable` 定向该类及app格式检查，1m11s通过；主线程读取XML确认28项、0失败/错误/跳过，未触发重试。该测试文件在本轮app测试已结束后修改，不能把前一完整命令当成此修改的执行证据；本定向结果补齐变化部分。不改正式rc5宿主，不为测试同步另建产品候选或独立提交。

### 2026-09-16 AEX-06 API26正式rc5业务链路

- 专用 `emulator-5582`、API26/x86_64，沿用SHA `8de7a74f576e7d72cd749ca59664f7409d860b02953aab08ea310e3e72ac63f3` 的正式rc5。真实UI完成首次设置、SAF目录 `/sdcard/MihonAex06Api26`、本fork未知来源安装许可（原OFF→系统UI允许）、真实Keiyoushi仓库添加、新索引、MangaDex1.6.0下载和系统INSTALL确认。English Sources搜索 `The Story of a Girl Who Can Read Minds`，载入详情5章，打开Ch1真实页图1/19。主线程目视核对 `aex06-rc5-api26-ui-reader.png`；安装/详情XML为同前缀 `ui-install.xml` / `ui-detail.xml`。上述搜索/阅读直连成功。
- 直连下载失败如实保留：点击下载及一次Resume后Paused1、无CBZ；同一宿主PID5966的DownloadJob约12ms即FAILURE，通知为“No network connection available”。系统连接但WIFI/CELLULAR无VALIDATED，生产 `NetworkStateTracker.isOnline=isConnected&&isValidated`、DownloadJob先检查网络再调用downloaderStart，故此次在下载前置检查短路，不能归因于尚未发生的漫画HTTP或SAF写入。证据 `ui-download-failure.log`、`ui-network-state.log`、`ui-notification.xml`。
- 一次有界临时HTTP代理 `10.0.2.2:10808` 对照，未改校验地址或禁用校验：原系统HTTP/HTTPS验证均204，network102获得VALIDATED，日志 `ui-proxy-network.log`。随后真实UI Resume一次，队列清空并生成Ch1 CBZ；不是仅curl代理成功。文件8,509,461字节，SHA-256 `8c2da3190fcadadcd6db3a41c79a0c11a0fd5ef6aa64f5b7fc6a71a0aecfcbf1`，主线程独立读取忽略副本 `aex06-rc5-api26-ui-ch1.cbz`，确认19个非空JPG+ComicInfo.xml。不提交漫画数据。
- 断网并force-stop冷启动，从已下载章节定位此前未在线浏览的末页19/19，实际显示页图。主线程目视复核 `aex06-rc5-api26-ui-offline-last-page.png`，并读取 `ui-offline-network.log` 的 `Active default network: none`。这是API26正式rc5下载/离线重启证据，不能写成直连下载通过，也不证明所有19页逐张视觉检查。
- 结束时飞行模式0、Wi-Fi/mobile_data均1，代理按`:0 → delete`清除，主线程确认http_proxy=null且宿主PID为空；保留该fork书库/系统扩展/一章下载。清代理后系统网络可再次无VALIDATED，这属于已记录测试网络限制。API36最新rc5、ARM实机、正式Windows/macOS产物、其余release安全UI门槛仍未借此关闭，AEX-06继续未勾选。

### 2026-09-16 AEX-06 显式parity门槛

- `aex06-explicit-parity-gates` 51秒：finalParityAudit 1项通过；parityGovernanceCheck 7项中1项失败。错误是旧治理预期与已存在的当前Reader证据不一致，不是Reader功能测试失败。ID45漏列decode concurrency/512MiB cache两个已执行测试；补充在当前readerCoreMigration预期中，不改task3冻结历史基线。
- 后续真实失败保留：`aex06-parity-governance-aligned` 17秒在任务选择阶段失败，`:app-desktop:spotlessCheck`不存在（命令错误，不计测试结果）；`aex06-parity-governance-aligned-task` 32秒发现ID47引用已改名方法；一次只读当前behaviorMethods核对发现两处，分别同步为双重重试保留页身份/force refresh，以及章节边界不激活且不保留viewport反馈。第二项与既有statusDecision及当前真实行为一致，没有在本任务改变Reader语义。`aex06-governance-current-methods` 30秒继续发现ID53漏列已通过的双页进度契约，随后补齐当前预期。
- `aex06-governance-reader-evidence` 35秒最终通过，主线程XML核对7项、0失败/错误/跳过。相关新名称均对应真实执行通过的domain/Desktop测试，不以方法文本存在冒充行为验收。manifest仅更新两处当前方法引用；与HEAD比较所有capability状态变更数为0，未提升状态。git diff --check通过；Desktop模块本身无spotlessCheck任务，不声称该任务通过。
- 这些是本批最终回归的证据/测试修正，不作独立产品迭代、单独提交或新建治理工具。仍需后续正式平台产物、最终diff相关验证及独立AEX-06批审；full parity JVM门槛不等价于发布runtime全部通过。

### 2026-09-16 AEX-06 Windows候选正式构建

- 在前述完整Desktop JVM及定向治理修复证据后，按规定执行 `scripts/build-desktop.sh build-only`，由协调器 `aex06-windows-release` 独占本地Gradle。脚本分配BUILD33→34，版本 `0.11.19.34.dbf3f05`，约3m06s正常结束；不额外重跑完整JVM。此产物来自AEX-05 HEAD加本批未提交变更，版本hash不是声称AEX-06已提交。本轮未改Desktop产品逻辑，治理与测试同步修正不影响正式产品字节；分配版本的机械修改保留在本批。
- 脚本实际发布运行时的独立profile验收通过：正常安装真实签名旧漫画柜1.4.28 APK，加载源ID `7057750772596492765`；日志明确 `Extension runtime acceptance passed`，没有用系统JDK或旁路loader代替发布运行时。未发现正在运行的用户桌面实例，未触碰正常书库。
- 已核对日志 `Final unpacked EXE:` 指向且文件存在：[Mihon Desktop 0.11.19.34.dbf3f05](D:/Shell/Github/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.34.dbf3f05-unpacked/Mihon%20Desktop.exe)。[Windows ZIP](D:/Shell/Github/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.34.dbf3f05-windows.zip) SHA-256 `ff3f57c5dc53dc033b04d8f13fee2a94e5380008359b7298a1fefbf3451be33c`，主线程重算与旁边.sha256一致。只交付artifacts路径，临时tmp产物不作为地址。
- 额外启动同一EXE的Test Mode时，工具策略在命令执行前拒绝了Start-Process操作；预定独立profile目录实际不存在，未启动进程或改动环境。已异步询问用户是否方便手动启动，不通过包装脚本/换壳绕过拒绝；此额外runtime门槛仍待执行，内置旧APK验收不能替代全部JAR-first、Authors、FlareSolverr与Reader场景。
- Mac只读二次核验：mbp-lan可达，旧工作树仍dirty且HEAD `c84ed331fa0b`；旧app259MB存在、本次未运行，35GiB可用，指定JDK21.0.10可用。候选隔离目录 `/Users/altair/Github/mihon-aex06-release` 和公共 `/private/tmp/mihon-dist` 尚不存在。后续须独立同步、保护旧应用、协调同版本输入及串行使用公共分发目录，尚未部署或构建Mac。
- API26验证已结束，Windows编译前内存趋紧时关闭本任务 `emulator-5582`，保留AVD数据。没有查询实机。AEX-06仍未审查/提交/勾选，当前Windows候选成功不等价于整个发布批次完成。
- 随后实际执行roadmap所列 `scripts/desktop-smoke-test.sh`（协调器 `aex06-desktop-smoke-script`）：3m02s、47个任务全部执行，主线程XML核对94项、0失败/错误/跳过。脚本强制rerun，属于JVM冒烟而非已发布EXE的额外Test Mode，不能替代被策略拒绝的运行项。当前jvmTest XML已由这次过滤运行更新，完整3021项结果的历史命令/记录见上节，不能把现有94项报告冒充全量。
- Mac同步的只读核对确认旧提交 `c84ed331fa0b7851b62dc44a66a8602bb3f60876` 是当前HEAD祖先，可以使用增量bundle加当前源文件覆盖到新隔离checkout；不要传整份约6.6GiB Git历史、用户testfile或签名密钥。Mac普通构建也增加BUILD，须从同一BUILD33输入形成BUILD34，或使用满足提交前置的evidence入口，不能把Windows的BUILD34直接再加一。尚未实际执行同步，不能把此方案写成已构建。

### 2026-09-16 AEX-06 发布入口缺口与Test Mode边界

- rc8/API36正式私有MangaDex已补证：先从专用模拟器导出原系统APK，SHA `35d220b64162cb9409da47af81fbb09ae96f170ed77eaa0ffb440add65f92f35` 与既有样本一致；通过UI卸载单一系统扩展、选Private、从原仓库正常安装1.6.0，Installed·Private且系统包不存在。English Sources实际搜索The Story of a Girl Who Can Read Minds→唯一结果→In library/5 chapters→此前未读且有Download按钮的Ch.2在线页图1/9。force-stop冷启后从原书库仍能打开Ch.2。root目视核对 `.gradle-coordinator/aex06-rc8-md-reader.png` / `aex06-rc8-md-cold-reader.png`，不是只核对页码；冷启可能使用缓存，不冒称第二次重新下载。
- 临时私有MangaDex已通过UI卸载，再用先前验证的APK恢复系统安装；root独立计算备份和恢复后再次导出的APK，两者SHA均为上述35d220…值。UI恢复PackageInstaller，MangaDex无Private、MangaPlus仍Private；原书库关联与Ch.1下载未删除。恢复副本在 `.gradle-coordinator/aex06-rc8-mangadex-system-backup.apk` / `aex06-rc8-mangadex-system-restored.apk`，卸载内容可由这些副本恢复。本簇没有新增下载/离线验收，不能将缓存当新下载。
- 为定位泛化Unknown error，新增release-only显式opt-in `ExtensionLiveReleaseQueryInstrumentationTest`：默认assumption跳过且不在离线ABI Suite；只有 `-e aex06LiveMangaPlus true` 并通过正式host/emulator/hash/cert guard才加载已通过UI安装的私有样本，使用production ChildFirstPathClassLoader/public Source默认筛选与真实搜索，45秒上限，不自行拼HTTP/替换parser、不安装或信任代码。仅输出异常类型、HTTP code（若有）及堆栈，不打印任意异常message/响应/凭据。首次43s构建被一处格式换行拦截，按实际diff修正后46s构建/格式通过，未改宿主行为。
- [rc8显式诊断测试APK](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.1-rc8/Mihon-Fork-0.19.4-aex.1-rc8-live-query-tests.apk) SHA `c99b4c1fb2e50abd8e1240e7e318e40bee028e018d633f84a7264464199adaf5`，正式签名，宿主仍6808a79b…rc8。单次诊断 `aex06-rc8-mangaplus-query-diagnostic.log` 2.77s、1项1失败；root核对脱敏帧，扩展map_id51a199…的c0.t→c0.u→c0.q→f1抛出java.lang.Exception，没有HTTP状态，未再出现缺类/native崩溃。现有页面将多类AppError统一显示Unknown，所以截图不能用于归因；本结果把失败定位至扩展内部异常，但仍不足以证明外部站点或宿主无关。MangaDex通过不能掩盖该失败，继续保留待诊断。设备/Gradle均已释放，代理实际host空/port0，宿主停止。
- rc8真实MangaPlus复验未闭环：保留的私有扩展在Sources进入并搜索One Piece不再发生原缺类/native崩溃，但UI显示Unknown error，无搜索列表、详情或阅读成功。直连与一次临时HTTP代理对照均同反馈；现有进程日志仅证明libzstd-kmp加载，不足以区分HTTP/TLS、站点响应或扩展处理错误，不能断言外部网络根因。主线程目视核对 `.gradle-coordinator/aex06-rc8-private-search.png`，保留失败，不以“无崩溃”替代业务通过。后续须取得具体错误证据，私有整链仍未完成。
- JNI第二层依据固定版本[官方源码](https://github.com/square/zstd-kmp/blob/parent-0.4.0/zstd-kmp/native/ZstdKmp.cpp)：初始化按名称查找ZstdCompressor/ZstdDecompressor，两类各读取inputBytesProcessed/outputBytesProcessed整数域；主线程独立读取确认。rc7映射显示类被删除、域迁移至子类，与真实native异常吻合。依赖AAR无consumer规则，最小补充精确保留这两个类及四个字段（不允许类合并/字段优化），未保留整个包。
- `aex06-zstd-jni-green-build` 2m24s构建/格式通过。[rc8 R8宿主](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.1-rc8/Mihon-Fork-0.19.4-aex.1-rc8-universal.apk) SHA `6808a79bd13cd18d257eb5ea47b71446db247478e21ec9787426ae75fbd9e4f0`；[rc8测试APK](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.1-rc8/Mihon-Fork-0.19.4-aex.1-rc8-release-tests.apk) SHA `6d18e9bb2ef897d7c6b613dfef050ad133aef75e425216ce42616d5c165fc359`，与rc6/rc7失败时相同测试字节，正式证书保持不变。API36/5580 `aex06-zstd-rc8-green-suite.log` 0.319s、OK(5 tests)，主线程核对终态和双APK哈希；真实解压GREEN，不只是类存在。原MangaPlus业务路径还须单独复验，未借此宣布整个AEX-06完成。
- Zstd根因第一层已证实为R8：rc6的map_id与崩溃一致，usage删除OkioZstd整类，mapping将其标为REMOVED并把宿主decompress调用内联；依赖AAR中原类存在，正式APK含四种ABI的libzstd-kmp.so。新增release-only `ExtensionZstdReleaseAbiInstrumentationTest`，先确认测试APK及split不包含com.squareup.zstd实现，再按精确公开类/方法反射调用，实际解压固定Zstd帧为hello，不让AGP映射改写或测试自带依赖制造假绿；纳入正式Suite，当前应执行5项。rc6测试日志 `aex06-zstd-rc6-red.log` 为1项1失败、明确ClassNotFoundException，guard与测试dex检查先通过。首次构建因DexFile不实现Closeable失败19s，不算产品RED；改try/finally后仅测试构建46s通过。
- 仅保留OkioZstd公开静态接口后，`aex06-zstd-green-build` R8构建2m27s及格式通过。新 [rc7宿主](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.1-rc7/Mihon-Fork-0.19.4-aex.1-rc7-universal.apk) SHA `8a798d33000377732ca45e64ac90acbb25db7b9b3ea6846ada1e5ede867ea0d1`；[rc7测试APK](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.1-rc7/Mihon-Fork-0.19.4-aex.1-rc7-release-tests.apk) SHA `6d18e9bb2ef897d7c6b613dfef050ad133aef75e425216ce42616d5c165fc359`，与rc6 RED测试包完全一致。两者正式证书不变，root签名重算hash，历史候选保留。
- **rc7不是通过候选**：API36前4项通过，第5项实际解压触发native SIGABRT，精确新异常为JNI无法找到 `com.squareup.zstd.ZstdCompressor`；主线程核对 `.gradle-coordinator/aex06-zstd-rc7-crash.log` 的01:01:51新崩溃及新map_id，不把同一crash缓冲里的旧OkioZstd异常混入。公开入口恢复后暴露native ABI裁剪边界，尚需核对JNI消费规则再最小修复；未重复真实源查询，未宣称修复完成。新增产品缺陷超出已完成接线复审范围，已请求用户追加一次5–10分钟定向只读审查，尚待答复；不擅自重开全仓审计。
- 上述两项P2已由原独立审查者完成唯一一轮定向复审并通过：确认release源集隔离、所有资产merge依赖、真实APK夹具内容及正式guard未减弱；API26实际UI/ABI终态另由主线程核对通过。此审查结论仅覆盖当前已审diff/接线修复，不替代后续Zstd修复的定向审查或剩余正式运行门槛；不提前勾选/提交AEX-06。
- 独立AEX-06批审发现两项P2测试接线缺陷：release资产合并未依赖fixture同步任务、release-only测试混入默认debug源集。主线程以真实Gradle任务图/Java源目录验证，`aex06-wiring-debug-red-scoped` 19s按预期拒绝默认debug发现release类，`aex06-wiring-release-red-scoped` 14s按预期拒绝缺sync依赖。修正为所有merge*AndroidTestAssets依赖sync，两个正式专用测试放入 `app/src/releaseAndroidTest/java`，仅显式release变体加载，身份/证书/SHA硬检查未放宽。最终 `aex06-wiring-debug-explicit-green` 19s、`aex06-wiring-release-explicit-green` 14s通过任务图核验；dry-run不计设备行为通过。
- 验证装配失败如实保留：首条命令误用Windows批处理不接受的`./gradlew.bat`；随后init脚本错误作用于buildSrc，修为仅目标app。初次用assets.srcDir(taskProvider)未建立AGP任务依赖，debug/release GREEN尝试仍失败；最终显式依赖才通过。没有把这些失败算产品RED。将旧生成目录精确移动到 `.gradle-coordinator/aex06-assets-before-clean-proof` 保留可恢复副本，实际从无生成目录执行 `aex06-release-tests-clean-assets`，48s构建及格式通过，日志确实执行sync，主线程核对APK包含固定1.4/1.5/1.6样本与备份。
- 测试产物更新但正式rc6宿主不变：[rc6接线修正测试APK](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.1-rc6/Mihon-Fork-0.19.4-aex.1-rc6-release-tests-wiring.apk)，SHA `3fd773d8571a68dd124c5059fbd3333bdf19423d41cc0ed7ec94e2ef09056d6f`，同正式证书。API26/5582最初4项ABI通过，UI复验在Advanced同名标题无可点击祖先失败；仅将测试findText限制可点击祖先，保留有界条件等待，不增加固定sleep。更新测试APK后 `aex06-rc6-api26-private-settings-clickable.log` 15.032s、OK(1 test)，`aex06-rc6-api26-clean-assets-abi.log` 0.501s、OK(4 tests)，均通过rc6 hash/证书guard。最终宿主force-stop，原安装器由UI测试恢复。
- API36正式rc6真实私有安装MANGA Plus by SHUEISHA1.6.66成功，显示Installed·Private并注册英文源，原系统MangaDex/书库下载保留。搜索时主进程6663的明确主异常为 `NoClassDefFoundError: com.squareup.zstd.okio.OkioZstd`，私有扩展ClassLoader路径在完整日志 `.gradle-coordinator/aex06-rc6-private-primary-crash.log`；error_handler的WorkManager异常是另一个二次异常，不能当原始根因。搜索/详情/阅读尚未通过。恢复PackageInstaller并force-stop，私有样本保留以供复现；未root导出其字节，不能虚构样本SHA。代理始终实际host空/port0，没有为缺类错误重试网络。依赖缺口需补行为回归后修复，不借已安装成功关闭整链。
- 私有安装入口修复已GREEN：`aex06-private-settings-green-build` 2m47s正常结束，app格式及SecurityRollback28/SessionLifecycle28共56项通过，主线程核对原始XML零失败/错误/跳过。正式rc6在API36/5580运行同一真实UI测试，`aex06-private-settings-green.log` 为17.7s、OK(1 test)，选择Private后离开重开仍选中，再恢复原安装器；`aex06-rc6-api36-release-abi-suite.log` 同一宿主4项ABI通过。主线程读取两份终态日志；无跳过、未使用内部偏好注入。此证据只关闭“入口与选择持久化”缺口，私有扩展实际安装和其余发布业务链仍须验证。
- 当前最新候选为 [rc6 R8 APK](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.1-rc6/Mihon-Fork-0.19.4-aex.1-rc6-universal.apk)，SHA `ff4d2124a25f0e9b08427f73b3f4557b79310217fe35db8233e5fb2794c48d26`；[rc6测试APK](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.1-rc6/Mihon-Fork-0.19.4-aex.1-rc6-release-tests.apk)，SHA `249db44adb0b41957796fec3efa0d720a4a4900fc65701489496302d4966074a`。正式fork证书/身份/版本/flags不变，主线程签名并重算hash，未覆盖rc5。模拟器系统MangaDex与数据保留、宿主已停止、Gradle结束。API26/ARM及其余同产物门槛不自动继承rc5结果，AEX-06仍待批审/收口提交。
- 已新增 `ExtensionInstallerSettingsReleaseInstrumentationTest`，使用Android系统UiAutomation走正式MainActivity/设置导航，不调用被R8优化的内部偏好方法，不依赖Compose test runtime。先只构建/签名测试APK，在原rc5上执行，`aex06-private-settings-red.log` 为1项1失败，确切断言 `The real release Installer dialog must offer Private`；不是编译/导航/权限失败。测试APK SHA `7d4b1d3c8b9acfa83f441513d17b70587da1935a1ac5165c7ad968d029fa956d`，复用既有正式证书，未替换密钥。随后才移除SettingsAdvancedScreen的release选项过滤及unused import；原安装安全校验未修改。修复后的R8构建/定向回归由 `aex06-private-settings-green-build` 串行执行，未取得GREEN前不声明修复完成。
- Mac隔离入口核对：普通启动默认DesktopPreferenceStore使用全局Preferences.userRoot()/mihon；backup、source preferences、兼容SharedPreferences、credential/reader/app legacy路径还有直接userRoot调用，单改user.home或只替换DI一个store不能证明全部隔离。已有extension runtime acceptance仅独立注入偏好节点用于扩展验收，不是完整Test Mode隔离能力。已异步请求用户选择专用Mac账户或授权Test Mode profile扩展；未擅自新增账户/偏好架构，Android独立工作继续。
- 已读取实际Mac JVM原始XML并核对对应production测试：AuthorsProductionWiringTest 4、AuthorDetailBehaviorTest 8、FlareSolverrClientTest 2、DesktopExtensionLoaderTest 15、JvmExtensionArtifactAdapterTest 1、DesktopExtensionArtifactAuthenticityTest 11，均0失败/错误/跳过。Authors覆盖真实挂载UI/业务接线（仓库依赖受控），FlareSolverr为真实客户端对MockWebServer契约；这些证明对应JVM回归，不宣称正式包真实外部FlareSolverr服务/GUI运行已通过。复用已执行报告，没有再次运行全量。
- 主线程在同一正式rc5/API36模拟器5580执行 More → Settings → Advanced → Extensions → Installer，实际弹窗只有Legacy、PackageInstaller、Shizuku，没有Private；UI证据 `.gradle-coordinator/aex06-rc5-release-installer-options.xml`。只打开/关闭弹窗，未改变安装设置。源码 `SettingsAdvancedScreen.getExtensionsGroup` 明确在isReleaseBuildType时过滤PRIVATE；不能以debug测试直接设置偏好代替用户入口。下一步必须补真实设置接线失败测试并最小修复，再对新候选验证；当前不能宣称正式版双安装路径完整可用。原TODO所指URL处理边界也须核对，不盲目暴露尚不安全的能力。
- Mac现有final-parity客户端尝试使用独立profile与FileSystemPreferencesFactory，正式应用在health前因 `UnsatisfiedLinkError: FileSystemPreferences.chmod` 退出1。主线程读取远端日志确认栈经过DesktopPreferenceStore/production DI；这是所选偏好隔离方式失败，未证明默认产品运行故障。日志 `/Users/altair/Github/mihon-aex06-release-input/runtime-final-parity/app.log`；无客户端执行/summary，无Reader场景通过，不移除隔离后冒险使用普通偏好。该应用已退出，没有重建。
- 通用inventory里的既有authors-entry=partial不构成本任务新增Authors能力的授权。验收须保护现有Authors/FlareSolverr/Reader行为，不用静态family映射冒充真实场景，也不因全清单封存失败扩展产品范围。Windows手动隔离启动仍待用户回复；ARM仍最后。

### 2026-09-16 AEX-06 Mac终态与设备排程

- rc8/API26代表性系统升级/拒绝黑盒通过：先导出原系统MangaDex并确认SHA `35d220b64162cb9409da47af81fbb09ae96f170ed77eaa0ffb440add65f92f35`，从仓库UI复制并记录原入口 `https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json`。仅本专用实例正常UI删除仓库、卸载扩展，书库/下载不动；添加回环固定v2仓库。临时Python服务仅绑定127.0.0.1:18089，adb reverse仅5582；启动时核验旧/新/冲突签名fixture固定SHA，不改任何APK metadata或签名。
- 从真实扩展列表安装旧MangaDex1.4.211并经Android INSTALL确认。仓库切换到声明1.6.0的既有冲突签名样本后，刷新/Update：UI显示完整性/签名拒绝，实际已安装APK仍为旧SHA `eff4ee157380f0cd4f19a2150f93220ca7a9bcd4e5d570736f639230ef338236`。损坏APK再经刷新/Retry被拒，UI明确damaged or incompatible，导出的旧APK SHA再次不变。服务会话84565实际记录GET wrong.apk/broken.apk各200，不能把未下载候选或缓存旧错误当证据。对应XML `aex06-rc8-upgrade-wrong-ui.xml` / `-broken-ui.xml`，对应导出 `aex06-rc8-after-wrong.apk` / `-after-broken.apk`。
- 正确同签名新版刷新/Retry进入Android“update existing application”确认，INSTALL后为1.6.0；实际导出 `aex06-rc8-after-upgrade.apk` SHA恢复为上述35d220…，UI `aex06-rc8-upgrade-system-success.xml`。最后正常UI删除本地测试仓库并恢复原Keiyoushi旧入口，成功显示Keiyoushi；记录 `aex06-rc8-restored-repo.xml`。冷启原书库仍有漫画，旧Ch1 CBZ导出SHA `8c2da3190fcadadcd6db3a41c79a0c11a0fd5ef6aa64f5b7fc6a71a0aecfcbf1` 与验收前一致。已移除本轮adb reverse、停止fork和仅本轮服务PID41324，核对进程不存在/转发列表为空。无Gradle/实机访问；不冒称此次重跑了全部私有升级反例或验证每个内部偏好键。

- rc8/API36真实信任UI：事前确认 `aex00.external.v16.controlled` 未安装，固定APK SHA `34c21ef4c3a5b60b789cd5dce95a78f638ba9875007f19d094cb3e344df4e182`。adb安装仅作为“外部安装、不受信”前置，不冒称应用内安装验收。正式Browse→Extensions显示AEX-00 v1.6 controlled为UNTRUSTED；打开风险提示后Back取消，Sources完整可见列表无测试源。再次点击Trust并明确确认后扩展不再UNTRUSTED，Sources出现 `AEX-00 v1.6 en fixture`；force-stop冷启再进入Sources仍可见，原MangaDex/MANGA Plus仍在。证据 `aex06-rc8-trust-before.xml`、`-dialog.xml`、`-rejected-sources.xml`、`-confirmed.xml`、`-cold-sources.xml`。这证明指定源UI准入及重启，不以列表观察断言所有内部信任键或同签名其他包均已检验。
- 收尾通过该扩展info→Uninstall→Android OK正常卸载，pm path为空，停止fork。只移除本轮安装的受控测试扩展，未删书库/已有扩展；固定APK可恢复。模拟器可能保留此fixture的信任偏好，不声称完整还原其信任存储，也不为此全局重置信任。无构建/源码修改/实机访问。最终rc8同签名升级与错误候选拒绝仍需证据，不借本次信任流程关闭整个发布安全出口。

- rc8/API36新增下载与离线冷启通过：原系统MangaDex1.6.0、原书库，事前下载目录仅Ch1；真实UI为Ch2点击Download后队列Paused1，直连网络缺VALIDATED。一次临时代理10.0.2.2:10808后网络100实际IS_VALIDATED，点击Resume，见临时001–005.jpg逐步生成，最终队列No downloads、生成Ch2 CBZ。主线程拉取忽略副本 `aex06-rc8-api36-ch2.cbz`，4,019,702字节，SHA `1f340563b1a3679c7ba7296c56780e75b91eecf4162a13a450b8f690f6a99bca`，实际ZIP含9个非空JPG+ComicInfo.xml；不是沿用旧下载或仅看队列。
- 随后清代理、关闭Wi-Fi/mobile data，确认实际network none后force-stop冷启，Library→原漫画→Ch2，显示1/9，再翻至9/9实际页图。主线程目视核对 `aex06-rc8-api36-ch2-offline.png` / `aex06-rc8-api36-ch2-last.png`，日志 `aex06-rc8-api36-ch2-offline-network.log`，末页时再次确认none。未把9页逐张截图作为完成条件；此为正式rc8新增下载和离线冷启证据，不宣称直连下载成功。结束Wi-Fi1/mobile_data1、实际proxy host空/port0、network105、fork PID为空；原数据保留，实机未访问。

- MangaPlus真实响应边界：opt-in观察模式绑定固定私有APK SHA，在测试进程中把该扩展客户端复制后仅增加最外层只读观察器，保留全部原有interceptors、proxy、TLS、cache，finally恢复原lazy引用；不改发布宿主、不替换响应、不输出正文或凭据。一次实际查询得到 `status=200 encoding=none peekBytes=4 firstByte=18 network=true cache=true`，随后仍走c0.t缺success异常。首字节18是长度分隔的字段2，匹配固定协议error字段；可区分于缺类/JNI直接失败，但没有完整error内容，也因network/cache均参与而不能声称这次是远端全新正文。日志 `aex06-rc8-response-access-result.log` / `-frames.log`，该查询仍失败，不算业务通过。
- 观察器装配成本：首次20s编译失败（误用OkHttp内部Builder属性），公开interceptors()修正后52s构建/格式通过；首次设备运行因未显式反射访问final字段失败，查询尚未发生。明确启用测试内反射访问后46s构建/格式通过，才得到上述一次真实响应。最终测试APK `Mihon-Fork-0.19.4-aex.1-rc8-response-access-tests.apk` SHA `5862fdfa1344db8a409cea083e7875e7196261b454877fed1dc6d0ac839ca98b`。宿主rc8未重建，测试结束停止fork；没有实机访问。不继续同条件联网重试。

- MangaPlus离线解码分界验证：固定APK自带d2.a流式protobuf解码器（不是直接用宿主ProtoBuf.decodeFromByteArray），已用固定字段1/success与字段2/error空嵌套消息 `0a00` / `1200` 调用真实扩展serializer和decoder。测试要求私有APK SHA精确匹配，再引用该版本混淆符号；在rc8正式宿主API36上 `OK (1 test)`、0.076s，无网络请求，分别确认预期字段非null、另一字段null。`aex06-mangaplus-codec-only`测试构建/格式46s通过；测试APK `Mihon-Fork-0.19.4-aex.1-rc8-codec-tests.apk` SHA `94aee9ce206c8497abe38bd7df1066dfa40437acc382b2bfebce91b3ef72be91`。日志 `aex06-rc8-mangaplus-codec-result.log` / `-frames.log`。仅排除了这两个固定消息的基础解码/字段识别故障，不证明真实响应正确、所有protobuf结构兼容或站点查询通过；下一步须观察实际响应边界，而非继续同条件查询。宿主未重建，无产品改动或实机访问。

- MangaPlus字节身份后续已闭合：opt-in发布诊断在targetContext流式计算私有扩展SHA，实际为 `e9511110525f81f30139704bda07b0a910e5100e42326570c5c9870a7529f94b`，与上述官方APK完全相同。`aex06-live-query-identity`仅测试APK构建及格式53s通过，未重建rc8宿主；签名测试APK `Mihon-Fork-0.19.4-aex.1-rc8-query-identity-tests.apk` SHA `b7c0d5730d1d42bfafc3ad7d3be72c1dcf0c7f88fe2fdd3ba6bc6c1f47d287dc`。一次API36诊断仍1项失败，日志 `aex06-rc8-query-identity-result.log` / `aex06-rc8-query-identity-frames.log`；宿主hash/cert guard通过，固定脱敏关键词仅unknown=true，其余update/maintenance/region/rate_limit=false，堆栈与前次相同。关键词不是服务端错误码；没有记录原始message/响应/凭据，没有再次换代理重试。剩余缺口是响应结构与宿主解码是否一致，不能凭通用错误认定站点故障。宿主已停止，未访问实机。

- rc8 API26补验：专用5582，标准发布ABI套件5项通过（0.683s，`aex06-rc8-api26-abi.log`），真实安装器设置1项通过（15.035s，`aex06-rc8-api26-settings.log`）。本次主线程重读终态日志，没有重建或重跑套件。关闭实际网络、force-stop冷启、Library→已有漫画→Ch1，实际显示1/19页；截图 `aex06-rc8-api26-offline-verified.png` 与 `aex06-rc8-api26-offline-verified-network.log`，阅读前后均确认 `Active default network: none`。复用先前rc5下载的数据，不宣称本次新增下载或全部19页逐页验收。
- 断网准备失败记录：直接广播飞行模式被系统权限拒绝，未绕过；改正常系统设置UI。首次快速切换后网络重新连接为102，旧 `aex06-rc8-api26-offline-reader.png` 不能作为离线证据。重新明确关闭Wi-Fi并再冷启才取得上述通过证据。结束恢复飞行模式0、Wi-Fi1、mobile_data1，实际代理host空/port0、网络103，停止fork；未查询实机。
- MangaPlus诊断补充：官方固定仓库提交f672d80f44f299c21a0f6c73055e1ce022d96f97关联release1fbc35e的1.6.66 APK，主线程重算SHA `e9511110525f81f30139704bda07b0a910e5100e42326570c5c9870a7529f94b`。其DEX map ID与设备异常51a199…一致，SDK dexdump中c0.q:16在allV2响应protobuf解析后调用c0.u:7→c0.t:34；后者在success为空时构造Exception，主线程读取构造指令确认。不是新的缺类/JNI直接异常；HTTP状态、具体error内容及设备私有样本完整SHA尚未验证，不能归因限流或网络，也不能以另一个源通过关闭此问题。证据 `aex06-mangaplus-public-dexdump.log`；没有新增产品修改。

- 用户已拔出安卓实机：全部ARM实机验收留到最后，不轮询设备、不等待连接；模拟器、桌面端和审查工作继续。实机证据缺失仍保持未完成，不以x86_64结果替代。
- Mac协调器 `aex06-macos-release` 正常结束，一次完整JVM测试6m47s、正式打包41s；主线程经SSH独立解析395份XML，确认3021项、0失败/错误、7跳过（6项Windows限定、1项非release限定）。没有重新跑全量。正式应用 `/Applications/Mihon Desktop.app`，版本 `0.11.19.34.dbf3f05`；主线程确认launcher存在，SHA `e9a9c74b1f59f964438d6f1dee86185a6ae17e284cadb55f10b274629dbe2fa7` 仅标识launcher，不是整个app包的摘要。
- 两次正式应用独立profile运行均退出0。主线程读取 `/Users/altair/Github/mihon-aex06-release-input/runtime-mangadex-jar/result.json` 与 `runtime-manhuagui-apk/result.json`，确认同版本、success=true，真实MangaDex1.6.0 JAR注册61个源、真实漫画柜1.4.28 APK注册源7057750772596492765。这覆盖production安装/加载，不代表GUI阅读、外部站点或完整Test Mode全部通过。
- 原Mac dirty工作树与普通书库未修改；旧app备份保留在release-input/previous-app。代理回执确认构建、临时SSH代理及验收应用已结束。AEX-06尚需剩余发布运行门槛、独立批审与最终实机验收，未提交/勾选。

### 2026-09-16 AEX-06 用户重连 ARM 实机后的验收

- 用户重新连接 SM-S9280，ARM64/API36。仅新装隔离包 `app.mihon.desktop.fork` 与其测试包，不覆盖 `app.mihon`，不卸载或升级系统扩展。宿主仍为 rc8 SHA `6808a79bd13cd18d257eb5ea47b71446db247478e21ec9787426ae75fbd9e4f0`。新测试入口 `ExtensionArmReleaseAbiInstrumentationTest` 只注册 4 项固定 ABI 与 1 项 Zstd 解压，不注册安装器/信任/数据库夹具；原模拟器 Suite 的硬件限制保留。所有入口仍核对 fork 身份、非 debug、实际 APK SHA 与正式证书。
- `aex06-arm-abi-runner` 测试 APK 构建及 app 格式检查 1m8s 通过，无宿主重建。[ARM ABI 测试 APK](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.1-rc8/Mihon-Fork-0.19.4-aex.1-rc8-arm-abi-tests.apk) SHA `8b3417bbfcceff659fe89295f5e39528e1d8ef20321d35552a65b48324d031c0`。实机 `aex06-rc8-arm64-abi.log` 为 `OK (5 tests)`、0.293s，无跳过。错误 SHA 与误用模拟器 Suite 分别在 BeforeClass 拒绝，0 项业务执行，见 `aex06-rc8-arm64-hash-negative.log` / `aex06-rc8-arm64-emulator-guard-negative.log`；这些失败是保护的反例，不算业务失败。
- 正常初始设置创建独立 `/storage/emulated/0/MA06ARAEX06ARM`，通过生产 add-repo URI 确认添加原 Keiyoushi 入口；选择 Private 后正常安装 MANGA Plus 1.6.66，UI 显示 Installed/Private，系统 pm path 无该包。实机打开其 English 图源仍显示未知错误，证据 `aex06-arm-mangaplus-live.xml`，未因 ABI 通过而关闭此业务失败。
- 新发现迁移缺口：系统 MangaDex 1.4.202 与仓库新版均为证书 `9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2`；仅在 fork 中信任旧包，尝试私有升级后收到通用信任校验错误，刷新后重试同样失败。代码路径对无来源记录的旧安装构造空 trust record，策略返回 ConfirmationRequired，但安装端转成 Authentication 错误，当前 UI 无对应来源确认闭环。旧包 SHA `1dadd0391066e33e3d433eff08f251c29b46923a4aa38b5d44185cf71c2072c2` 前后相同；不得靠卸载用户旧包或放宽签名校验绕过。后续需针对该路径补 RED、最小修复与验收；此项并非已经修复。
- 旧系统 MangaDex 1.4.202 在 rc8 上完成真实搜索、详情/5 章更新、加入隔离书库、新下载 Ch.2 与实际阅读。CBZ 4,019,702 字节，SHA `18bb8d0d3cfc53aa29fd89d1d2c28d57933d0aeba85bd3305f718163232e2697`，含 9 张非空 JPG 与 ComicInfo.xml，副本 `aex06-arm-ch2.cbz`。临时 Wi-Fi/data 关闭，实际 default network 为 none 后 force-stop 冷启，从书库打开 Ch.2 显示真实第 1 页；主线程目视核对 `aex06-arm-offline.png` 与 `aex06-arm-offline-last.png`，两张均为 1/9，后者文件名不代表末页，不能宣称末页或进度恢复通过。快速点击造成缩放/异步页码，不作为翻页验收证据。
- 结束恢复 Wi-Fi=1、mobile_data=1、airplane=0，未修改 VPN/代理；停止 fork。独立书库、私有扩展及新增单章下载保留供复验，原系统 MangaDex 哈希再次相同。手机当前无需持续占用；ARM 新版业务完整门槛仍未关闭。
- 同期获准的一轮 Zstd/诊断独立审查已通过，无 finding；该结论仅覆盖保留规则、宿主 ABI、固定样本诊断与 Suite wiring，不覆盖之后新增 ARM 入口或 Desktop profile。桌面隔离先写测试，`aex06-profile-red` 50s、8 项中 2 项按预期失败：未限制 profile 参数、隔离启动仍调用 URI 注册。收到手机重连消息后保留 RED 现场，尚未开始隔离 production 实现。

### 2026-09-16 Desktop profile 定向审查与正式运行

- 用户批准额外一轮只读隔离审查，复用原代理；发现 P2：Base64 偏好节点目录在大小写不敏感文件系统上碰撞。focused RED 38s 复现，改用小写 SHA-256 目录及 UTF-8 节点名；43s、26 项 GREEN，覆盖跨进程持久化、长 Unicode 节点、独立删除及真实 main 接线。Mac 同组 26 项全绿，39s。修复未追加独立复审。
- Windows 完整 Desktop 回归 5m49s：3027 项、1 失败、2 跳过；唯一失败为 parity manifest 当前树证据行号过期，更正后契约 focused 通过。test-desktop 客户端未修改，任务 UP-TO-DATE。没有把原完整调用描述为 exit 0。定向 ktlint 最终 28s 通过。
- 双平台按 `scripts/build-desktop.sh build-only` 构建 `0.11.19.35.dbf3f05`：Windows 2m26s 后 production APK 安装运行验收通过，实际产物 `app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.35.dbf3f05-unpacked/Mihon Desktop.exe`；ZIP SHA `92a3f912799f47c9d77162e34ef880b489d2107330d70ccba6a5c8a0ac31e415`。Mac 32s，部署 `/Applications/Mihon Desktop.app`，原应用备份保留。以上仍为未提交候选。
- Mac 使用独立 profile `/Users/altair/Github/mihon-aex06-release-input/profile-035-runtime`，DB、日志与 Java Preferences 落在隔离目录。SSH 直接启动因 HeadlessException 退出，系统 open 启动成功。PowerShell stdin 末尾 CR 导致端口回落到 8080，经 PID 19449/lsof 核对归属后才执行客户端；不是对其他实例验收。
- Reader 客户端 downloaded_directory 首项失败：180 页 fixture 已产生 OPEN_READER_INTENT、PAGE_LIST_READY、OPEN_PAGE、DECODE，但缺 FIRST_PAGE_PRESENTED；关闭请求后 productionClosed=false。原始状态保存在 Mac 输入目录 `profile-035-reader-failure.json`。其余四类未执行，不用静态 inventory 摘要替代运行证据。用户随后确认 Mac 已解锁、未休眠、窗口为空书架；空书架符合隔离数据，但不能据此定位首屏/关闭事件缺失原因。Windows 完整 GUI Test Mode 已请求用户手动启动，不绕过先前工具策略拒绝。
- 用户确认桌面状态后，使用同一进程、同一 production Python Reader 客户端单独复测 downloaded_directory，真实首屏 115.323ms，通过；没有修改代码，首次失败原因仍未证实。随后关闭成功，再顺序执行五类：downloaded_directory 121.749ms、downloaded_cbz 187.052ms、local_archive 115.714ms、online 273.321ms、partial_download 145.231ms，全部通过首屏/I/O 契约且各自 productionClosed=true。这证明 Mac 正式 GUI 五类 fixture 阅读链路，不代表真实远程站点或全部 13 类能力运行通过。末尾请求正常 Test Mode shutdown，不保留后台验收进程。
- AEX-06 仍未提交/勾选：Windows GUI 验收、实机旧扩展来源确认升级闭环与 MANGA Plus live 失败仍待处理，不因部分运行、构建和单测通过关闭所有出口。

### 2026-09-16 AEX-06 rc5 API36补验与Mac隔离同步

- 冷启动原专用API36/5580，覆盖安装同一rc5宿主及公开ABI测试APK（沿用API26已验证的签名/hash，不重建）。首次guard明确报告API36/x86_64、宿主SHA `8de7a74f576e7d72cd749ca59664f7409d860b02953aab08ea310e3e72ac63f3`；4项中2项真实本地HTTP返回503，日志 `aex06-rc5-api36-release-abi-suite.log`。这是HTTP失败，不是缺方法。
- 只读确认 `http_proxy=null`，但 `global_http_proxy_host=10.0.2.2`、port10808仍在。执行 `http_proxy=:0` 后先等待实际host为空、port0，再删除legacy主键并force-stop宿主；同一APK/测试立即 `OK (4 tests)`、0跳过，日志 `aex06-rc5-api36-release-abi-direct.log`。这修正了测试环境代理残留，没有修改产品。旧的“主键null即清理完成”证据不足，今后清理必须核对实际字段并用production请求确认；不把可能的异步处理时序直接写成已证实根因。
- Mac输入实际生成并上传：增量bundle13,420,826字节，SHA `92e4d15e561f385a0001d9b92fbfd40a9a5797fe9caa6c7e5bf8c92c1722608a`；覆盖包482,590字节，SHA `d3c41bee866207948cf8cd68fd911342b690d69d8f7bd2f90374c3c9eb3068bd`。双端SHA一致，仅本批源文件/测试/脚本与文档，不含用户testfile、签名密钥或机器local.properties；AppVersion特意保留基线BUILD33，让Mac脚本分配为34。
- Mac已在 `/Users/altair/Github/mihon-aex06-release` 独立clone、fetch bundle并检出dbf3f050a1，覆盖本批文件；原dirty树未修改。旧 `/Applications/Mihon Desktop.app` 已ditto备份到 `/Users/altair/Github/mihon-aex06-release-input/previous-app/Mihon Desktop.app`，diff -qr内容一致后才进入构建。协调器 `aex06-macos-release` 通过规定 `scripts/build-desktop.sh` 默认模式执行；此条仅记录构建启动，终态和运行验收须另记，不提前称Mac通过。
- 变体接线补验：`aex06-default-debug-variant` 17秒dry-run，未提供属性时真实选中 `assembleDebugAndroidTest`；不把SKIPPED任务图当行为测试。非法值第一次因PowerShell未引用 `-Pmihon.testBuildType=bogus` 被拆成错误任务名（14秒），不算配置guard成功。引用完整参数后 `aex06-invalid-test-variant-quoted` 10秒按预期拒绝，确切错误 `mihon.testBuildType must be debug or release`；与已实际构建/执行的release variant形成默认、合法、非法三种证据。文档PowerShell复验命令已同步加引号，没有重新构建APK或修改产品。


### 2026-09-16 旧来源确认闭环与最终候选补验

- Android 沿用安装协调器，在 APK 身份、仓库签名、摘要及版本校验通过后，只对旧来源/摘要记录缺失发布当前事务的来源确认。浏览→插件→更新会展示扩展版本、仓库名称、完整仓库 URL 和签名指纹；确认继续原事务，取消不写信任或替换包。已有来源改变、不同签名、损坏候选不进入此许可路径。确认后再次核对候选字节、已安装拓扑与旧包字节，防止等待期间变化；信任仅随成功安装保存，不使用全局信任代替仓库绑定。
- 确认请求及 UUID 仅保存在安装器内存，Manager→ScreenModel→弹窗使用同一请求；答复不迁移到重试，取消/结束通过 finally 清除请求。既有安装协调器、原版加载信任和平台 adapter 均保留，不新增信任系统，也不修改共享 TrustPolicy。
- TDD：`aex06-origin-red` 1m2s，正确复现旧系统包缺来源记录时被终止；`aex06-origin-green` 1m56s 的桥接测试预期漏写 declaredLibVersion，非产品失败；`aex06-origin-green-fixed` 52s 因新增页面测试漏传 declaredSha256 编译失败。修正后 `aex06-origin-green-verified` 安全31、生命周期28、页面wiring9，共68项/0失败及 app Spotless 通过。独立窄审查1轮无阻塞发现，未重审既有 AEX06 实现。
- `aex06-origin-final-full` 6m10s：Android 369项/0失败/0跳过；Desktop 3027项/1失败/2跳过。唯一失败明确为 ID34 CURRENT_ANDROID 安装器证据行120过期；同文件 ID36 行650也已移至672。只更新两个 roleEvidence 行号（141、672），不改变 capability 状态；修正后的契约验收记录另附，不将本次全量命令记为成功。
- 用户明确授权自行启动后，Windows 正式 0.11.19.35 GUI 成功启动；已核对正式 EXE、专用 profile、启动器44128及监听子进程7776。`aex06-windows-035-reader-evidence.json` 五类 downloaded_directory / downloaded_cbz / local_archive / online / partial_download 均通过既有客户端的真实首屏事件和 I/O 断言，首屏分别320.3228/194.0689/142.4061/309.4371/128.5321ms，全部 productionClosed=true。online 仅回环 fixture；Mac 已有效的五类证据继续复用。未重新构建 Desktop、未扩大其他 family 或互联网诊断。
- ARM升级前复核原系统MangaDex字节 SHA-256仍为 `1dadd0391066e33e3d433eff08f251c29b46923a4aa38b5d44185cf71c2072c2`，UI显示1.4.202。只操作独立fork；新版来源确认、升级与业务结果以下续记，不能用此事前核对代替完成。

- `aex06-origin-rc9-release` 2m57s：修正后的 Desktop parity 契约、正式 R8 构建和 app Spotless 通过。最终 [rc9 universal APK](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.1-rc9/Mihon-Fork-0.19.4-aex.1-rc9-universal.apk)，SHA-256 `12f83d907ac6866c9fc6a321025bb78cb4945b8e1077f0d0274b007bff74671d`；既有证书 `bd8e3af75921fc4356deacabd44a3d491fda8439ffbc7d073c363974a648cae3`，fork包/versionCode19/版本0.19.4-aex.1、R8/resource shrink启用、非debug、telemetry/updater关闭。构建来源为交接HEAD `852d0d796c` 加本批产品diff，不把该HEAD当产品完成提交。签名日志 `aex06-rc9-sign.log`，未新建密钥。
- 同一rc9覆盖安装至ARM64/API36与专用API26/5582，仅fork包；复用既有发布测试APK并传入上述实际SHA guard。`aex06-rc9-arm64-abi.log` 0.335s与`aex06-rc9-api26-abi.log` 0.723s均 `OK (5 tests)`，包含真实Source ABI与Zstd解压。未把模拟器专用入口用于实机。
- 本批跨Android发布、已有Desktop隔离、签名脚本和必要文档，超过8文件/400行；它们共同支撑同一AEX06正式产物验收，保留历史已审改动并只审新增来源确认。主要风险为发布裁剪与安装信任边界，分别由正式ABI及安装事务/实机链验证；不为行数限制拆开可交付批次。

- 本次停止条件：用户首次解锁后，构建等待期间手机再次锁屏；rc9已安装并通过ARM ABI，第二次解锁请求尚未得到响应，后续只读UI仍为锁屏。没有尝试绕过设备锁，没有关闭网络或修改系统代理。已完成产品改动先随本批提交保存，AEX06及第7节未关闭项保持未勾选；不是完整兼容性完成提交。继续时直接从rc9实际来源确认的取消/接受→私有1.6升级→查询/更新→阅读/新下载→断网冷启开始，保留原系统包并核验字节；无需再次全量或重建。Windows五类Reader已完成，当前测试实例仍运行。


### 2026-09-16 rc9 ARM最终业务验收完成

用户再次解锁后，直接复用已安装rc9；未修改产品代码、重建宿主、重跑全量或新增代理。再次从设备实际APK计算SHA `12f83d907ac6866c9fc6a321025bb78cb4945b8e1077f0d0274b007bff74671d`，与已签名产物一致。产品源提交为 `b3d81b34dc9e13f470281a8892392164fc797daa`；APK内部构建HEAD仍是852d0d796c加同一产品diff，不冒称提交后重建。

| 必需行为 | 实际结果与证据（`.gradle-coordinator/`） |
|---|---|
| 浏览→插件→MangaDex更新→来源确认→取消 | `aex06-rc9-origin-dialog.xml` 展示1.6.0、Keiyoushi、完整仓库URL及证书指纹9add655…；`aex06-rc9-origin-cancel.xml`仍为1.4.202，系统APK SHA不变；再次更新重新提示，见`aex06-rc9-origin-confirm-repeat.xml` |
| 确认信任→私有升级→冷启 | `aex06-rc9-origin-upgraded.xml`及`aex06-rc9-origin-cold.xml`均为1.6.0 Private，无UNTRUSTED；原系统1.4.202保持不变 |
| 真实1.6源搜索→详情刷新 | `aex06-rc9-search.xml`搜到原书架漫画；详情菜单明确点击刷新，仍有5章，见`aex06-rc9-detail-updated.xml`；未将缓存详情观察冒称flags调用次数测试，flags/memo复用共享契约与既有整链 |
| 未下载Ch.3在线阅读/翻页 | 事前下载目录只有旧Ch.2；在线首屏`aex06-rc9-online-reader.png`实际解码并显示1/9，`aex06-rc9-online-page2.xml`为2/9 |
| 新下载Ch.3 | `aex06-rc9-arm-ch3.cbz` 8,474,057字节，SHA `851d7b682ff7ba0aa62242d45e9b230ae89f0c86ed0ec7129bf0a51b91ad8a65`；8 PNG、1 JPG和ComicInfo，zip完整性检查无错误 |
| 实际断网→强制停止→书架→新下载 | 初次关闭网络仍有默认网络167，未把它当离线；待`Active default network: none`再冷启。`aex06-rc9-offline-reader.xml/png`恢复2/9并显示实际图片；连续翻页至`aex06-rc9-offline-last.xml/png`的9/9。阅读前后网络none证据分别为`aex06-rc9-offline-network.log`及`-after.log` |
| 旧包/旧下载保全与环境恢复 | 系统MangaDex SHA仍为`1dadd0391066e33e3d433eff08f251c29b46923a4aa38b5d44185cf71c2072c2`；旧Ch.2导出SHA仍为`18bb8d0d3cfc53aa29fd89d1d2c28d57933d0aeba85bd3305f718163232e2697`。恢复Wi-Fi=1、mobile_data=1、airplane=0，实际默认网络169；未改VPN/系统代理，fork已force-stop |

第7节收紧后的必需出口已关闭。源码及测试、独立审查、正式ABI和Windows/macOS证据沿用前轮有效结果，本轮补齐缺失ARM业务并提交运行验收记录。MangaPlus仍未验证live查询成功，原因未知；Mac历史网络提示仍缺明确触发信息，二者不被本次MangaDex及fixture成功改写为已修复或全站兼容。
