# Android 新版扩展系统完整兼容 Roadmap

- 日期：2026-09-12
- 状态：**IN_PROGRESS，2026-09-15 主模型接续实施；AEX-00/AEX-01/AEX-02/AEX-03A 已验收，下一批 AEX-03B**
- 父计划：[Android / macOS / Windows 正式 Roadmap](./2026-06-30-mihon-desktop-refactor-roadmap.md)
- 专项代码基线：`6d6263dcfeffdfbd5f810a54fa7a3d24c0a6ba50`
- 协议对齐基线：Mihon `v0.20.4`，实际 commit `df6507256acce8e7f3660783a3db6dbd1a31b6b5`

2026-09-12 首次审阅调整（历史记录）：解除早期组件测试对 AEX-04 安装/版本放行的反向依赖；AEX-03 拆为查询与更新数据闭环两个行为批次；AEX-00 增加可执行测试矩阵的强制关闭门槛。当时仅规划；后续已按用户授权开始 AEX-00，当前进度以下文实施证据为准。

本文件是本专项唯一计划与进度记录。实施进度仅从第 5 节第一个未勾选的批次推导；不增加 `active-task`。用户于 2026-09-15 明确要求主模型完成全部剩余工作，停止使用 Luna 技能，并授权按成本收益选择子代理。当前执行规则以本文件末尾的 2026-09-15 接续约定为准；此前的技能、等待批准和暂停描述保留为历史记录。规划、开始执行和子代理自测均不等于兼容性完成证据。

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
| [ ] | AEX-03B 统一更新与 memo | 更新、持久化、重启、备份恢复及阅读/下载数据传递闭环 | AEX-01、AEX-03A | 1.5–2.5 日 |
| [ ] | AEX-04 Android 安装与整链集成 | 1.6 APK 经版本/信任准入、安装加载、源注册到业务流程全部通过 | AEX-02、AEX-03A、AEX-03B | 1.5–3 日 |
| [ ] | AEX-05 历史升级与故障验收 | 旧用户数据迁移、真实端到端与恢复路径通过 | AEX-04 | 1–2 日 |
| [ ] | AEX-06 正式产物与跨平台收口 | R8 Android、Windows/macOS 发布及完整证据 | AEX-05 | 1–2 日 |

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

### AEX-05：历史升级、部分失败与真实端到端验收

- 从本仓库旧版本数据夹具开始；若用户提供实际旧 Mihon 版本/备份，再增加对应迁移路径。分别记录原地升级与跨签名备份迁移，不能互相替代。
- 场景：旧 index.min.json 仓库 → 应用升级 → 刷新新版目录 → 旧扩展不误报废弃 → 安装/更新 1.6 → 保留 source ID 与书架 → 浏览/阅读/下载 → 退出重启 → 再更新。
- 故障：一仓库成功一仓库失败、真实空目录、索引签名变化、未知版本、断网/限流、安装拒绝、旧源暂时不可用、升级中断后再次启动。用户需能区分仓库故障、协议不兼容、安装失败和网站失败。
- 数据：漫画/章节 memo、进度、分类、自定义字段、源设置、信任状态、旧下载关联与新版备份 round-trip；明确旧应用恢复新版数据的限制。
- 先用本地受控 HTTP 与固定二进制跑全产品流程，再选代表性真实 1.6 APK/站点做有界联网验收。任何新发现行为 bug 先加失败测试，再做最小修复并按批次审查。
- 网络按仓库代理约定，失败检查代理后最多重试一次；不能用 curl 成功替代 APK 的 production 网络调用。外部站点不可用时记录，不把它臆断为兼容性失败；也不得将未完成的必要真实验收勾为通过。
- UI：无需新增页面；用现有入口完成第 7 节流程，故障应有可操作反馈。
- 关闭条件：正常升级、跨签名安全迁移和故障恢复边界明确；无全量误标 obsolete、数据丢失或签名绕过。

### AEX-06：正式发布、跨平台回归与证据收口

- Android：对最终 diff 跑相关模块完整单元/集成测试、格式检查；构建 R8 开启的目标 release variant，明确 updater/telemetry flags，不因验收自动启用。确认版本标识属于本 fork、versionCode 可合法升级目标实例，不冒充官方 v0.20.4 签名或版本。
- 在 AEX-00 确认的最低/当前受支持 Android 系统及代表性 ABI 上，以可安装的正式 release 产物执行关键 fixture/代表性真实扩展的搜索、更新、阅读、下载、重启与信任验收。签名材料或设备缺失必须明确阻塞发布门槛。
- R8/反射/资源/类加载问题以该 APK 的真实 ART 行为验证；debug、仅 JVM 或系统 JDK 结果不能替代。记录 APK 路径、SHA-256、签名指纹、版本、构建 flags、设备和结果。
- Desktop：共享改动必须通过完整 Desktop JVM 测试、Test Mode 及 Windows/macOS 正式构建和运行验收；特别覆盖 JAR-first、旧 APK、Source ABI、Authors、FlareSolverr 和既有 Reader 行为。
- Desktop 构建只能走 `scripts/build-desktop.sh`。同一最终 diff 已有等价完整 Desktop 测试时可用 `build-only` 避免重复全量测试；仍需正式 runtime 验收。Windows 交付路径必须来自日志 `Final unpacked EXE:` 且确认存在，不用 tmp/build 目录。
- 无关已有失败与本次回归分别记录；不能凭“可能是旧问题”忽略。无法证明不相关的失败作为收口阻塞。
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
| 发布产物 / AEX-06 | 最终 R8 APK 与正式 Windows/macOS runtime | 待新增 `app/src/androidTest/java/eu/kanade/tachiyomi/extension/ExtensionReleaseParityInstrumentationTest.kt`；默认 telemetry/updater 关闭：`python scripts/gradle-coordinator.py run --key aex06-android-release -- .\gradlew.bat :app:assembleRelease`；当前未配置 `testBuildType=release`，`connectedReleaseAndroidTest` 不是现成 task，待实现 `app/build.gradle.kts` 的 `mihon.testBuildType=release` 选择并校验 release test APK 的 R8/签名/hash（属性、wiring、校验和 runner 均待实现，现有 debug ART 不替代）后执行 `python scripts/gradle-coordinator.py run --key aex06-android-release-device -- .\gradlew.bat :app:connectedAndroidTest '-Pmihon.testBuildType=release' '-Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.extension.ExtensionReleaseParityInstrumentationTest'`；Desktop：`scripts/build-desktop.sh` 后 `scripts/desktop-smoke-test.sh`；均记录正式产物 hash、报告及 runner 设备 | 本批次是发布集成门槛，不虚构必须先失败；若产物失败，定位后对相关行为补 RED 并修复；最终产物与受测 hash/版本一致、必要场景不跳过 |

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
| 发布 APK/Desktop runtime / AEX-06 | 最终 R8 release APK 与正式 Windows/macOS runtime；不把 debug/临时目录产物当发布证据 | 待新增 `app/src/androidTest/java/eu/kanade/tachiyomi/extension/ExtensionReleaseParityInstrumentationTest.kt`；默认 telemetry/updater 关闭：`python scripts/gradle-coordinator.py run --key aex06-android-release -- .\gradlew.bat :app:assembleRelease`；当前未配置 `testBuildType=release`，`connectedReleaseAndroidTest` 不是现成 task，待实现 `app/build.gradle.kts` 的 `mihon.testBuildType=release` 选择并校验 release test APK 的 R8/签名/hash（属性、wiring、校验和 runner 均待实现，现有 debug ART 不替代）后执行 `python scripts/gradle-coordinator.py run --key aex06-android-release-device -- .\gradlew.bat :app:connectedAndroidTest '-Pmihon.testBuildType=release' '-Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.extension.ExtensionReleaseParityInstrumentationTest'`；Desktop：`scripts/build-desktop.sh` 后 `scripts/desktop-smoke-test.sh`；均记录正式产物 hash、报告及 runner 设备 | 发布产物不匹配受测 hash、R8/平台行为差异或必要场景跳过 | 版本、签名、hash、设备/API/ABI、报告和用户路径全部可追溯；Android/Desktop 必需场景无跳过 |

上述 AEX-00 矩阵保留当时的探针和拟新增名称，不是当前进度快照。AEX-01 已验收，AEX-02 已有部分未提交测试；实际结果以第 8/9 节为准。AEX-03A 至 AEX-06 尚未实施。所有 runner 仍按协调器串行执行，新增套件应更新实际落点而非重复建立计划中的占位类。

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

以下均未执行，不能据此声称功能已完成。每项完成后关联第 8 节对应设备/自动化证据。

- [ ] 旧安装数据升级 → 原仓库、扩展信任、书架、进度和源设置保留；版本/签名不允许覆盖时使用明确的备份迁移流程，不卸载主实例试错。
- [ ] 浏览 → 扩展 → 刷新原 Keiyoushi 仓库 → 能看到兼容的新扩展；不会因旧目录下线或一次请求失败全体变成已废弃。
- [ ] 扩展仓库 → 分别输入旧 index.min.json 和新版索引/deeplink → 正确识别同一仓库；重复输入不产生重复项；重启后仍能刷新。
- [ ] 扩展列表 → 安装真实 1.6 APK → 系统/私有安装各自成功后显示可用；进入源能搜索和打开漫画。
- [ ] 扩展更新 → 同签名新版成功；不同签名、损坏文件或未知协议版本被拦截并说明原因；不会自动扩大信任。
- [ ] 不受信任扩展 → 查看并确认信任 → 仅指定扩展/签名变为可信；拒绝后不加载其代码。
- [ ] Source-only 测试源 → 热门/搜索/筛选/可用时最新 → 分页正常；全局搜索能找到并进入详情，不依赖 CatalogueSource 类型。
- [ ] 漫画详情/书架更新/元数据更新 → getMangaUpdate flags 和调用次数符合请求；已读章节、下载对应关系及自定义元数据不被误改。
- [ ] 测试扩展返回 memo → 退出重启、再次更新、备份恢复后再次更新 → 漫画和章节 memo 保真传入；旧备份仍可恢复。
- [ ] 新版源 → 阅读章节、翻页、下载并离线打开 → 正式 release APK 正常；失败有明确反馈，不呈现空白成功。
- [ ] 断网/限流/一仓库失败/安装取消/安装拒绝 → 保留可用旧数据和正确状态，恢复后可重试；成功的空目录与请求失败可区分。
- [ ] 最低/当前支持 Android 系统和代表性 ABI → 同一发布配置的 R8 产物通过关键工作流；在此项补充可点击 APK 绝对路径、hash、版本和设备证据。
- [ ] Windows/macOS 正式 Desktop → 原 JAR 优先、旧 APK 兼容、Authors、FlareSolverr、源查询和阅读/下载行为无回归；在此项补充实际发布产物链接与 Test Mode 证据。
- [ ] 受影响单元/集成/wiring、最终全量与格式检查 → 全部通过或明确未通过门槛；每项 capability 状态与机器权威一致。

## 8. 实施证据与风险记录

本节是本专项唯一实施证据记录。AEX-00 经独立审查、定向修复及用户批准的补修复验后，代码、样本和 runner 已通过主模型验收。2026-09-13 用户明确允许本批次豁免 Luna 目标回执校验，依据主模型独立证据提交并继续 AEX-01；该例外仅限 AEX-00，不豁免产品测试，也不把有缺口的子 goal 回执改写为通过。下文保留历史失败、修复和验证边界。

| 批次 | RED 与失败原因 | GREEN/回归命令和结果 | 独立审查 | 产物/运行环境 | 提交 |
| --- | --- | --- | --- | --- | --- |
| AEX-00 | `source-api` 首次 RED：`FilterList()` 实际 size=0 而测试断言 1，确认为测试输入错误并修正，非产品 RED；外部 fixture 首次 plugin marker 未缓存、首次导出把 `--tests` 传给 Jar task、ART 首次未引用参数被 PowerShell 拆分，均为 runner/命令配置故障并留存日志；ART 离线首次另因 AndroidX Test/Compose AAR 未缓存失败，未执行用例 | `:source-api:jvmTest --tests "eu.kanade.tachiyomi.source.SourceApiCurrentBaselineTest"` exit0，XML tests=2 skipped=0 failures=0 errors=0；`:source-api:testReleaseUnitTest --tests "eu.kanade.tachiyomi.source.SourceApiCurrentBaselineTest"` exit0，XML tests=2/0/0/0；`:app:testReleaseUnitTest --tests "eu.kanade.tachiyomi.extension.api.ExtensionApiSharedCatalogTest"` exit0，XML tests=8/0/0/0；`:app-desktop:jvmTest --tests "mihon.desktop.extension.DesktopExtensionApiSharedCatalogTest" --tests "mihon.desktop.extension.DesktopExtensionLoaderTest" --tests "mihon.desktop.extension.DesktopExtensionArtifactAuthenticityTest"` exit0，XML catalog=11/0/0/0、loader=15/0/0/0、authenticator=8/0/0/0；外部 v1.5 fixture test exit0，XML tests=1/0/0/0；外部 v1.6 static probe fixture test exit0，XML `app-desktop/tmp/aex00-external-v16/build/test-results/test/TEST-aex00.external.v16.V16ContractTest.xml` tests=1 skipped=0 failures=0 errors=0；v1.6 JAR export exit0，unsigned hash `50628427539f0b2839d251dd2c3af773456ff07763aabef188f2ec3737cd1d9c`，签名 JAR hash `e623be999c1c6b7c9a5383253645f6dc3d496de44d44a4fa1cd1a4fe020c33c4`，受控 v1.5 APK hash `caf80d849e2eb5ad8f0be5121f914d9cee1ee06c15653d15f33321602183a316`、v1.6 APK hash `34c21ef4c3a5b60b789cd5dce95a78f638ba9875007f19d094cb3e344df4e182`；在线依赖重试后 `BrowseSourceUiWiringTest` ART exit0，专用 `emulator-5580` API36/x86_64 XML tests=4 skipped=0 failures=0 errors=0；签名后 Desktop loader/authenticator 亦已 exit0（上述 XML loader=15/0/0/0、authenticator=8/0/0/0） | 主模型已核验 C1–C3；C4 的代码/范围审查通过，目标回执按用户本批次豁免关闭；见本节独立复验 | `source-api/src/commonTest/resources/aex00/fixture-manifest.json`；固定索引；真实 1.4/1.6 APK/JAR；签名受控 v1.5 Source-only JAR+APK 及 provenance；签名受控 v1.6 SourceFactory/memo/flags/error JAR+APK 及 provenance；外部 v0.19.4/v0.20.4 独立源码/依赖生成器；设备报告 `app/build/outputs/androidTest-results/connected/debug/TEST-mihon-aex-api36(AVD) - 16-_app-.xml`；1.5 历史第三方发布物未找到，受控样本不冒称历史 provenance；C3 尚未执行 1.6 ABI/正常安装整链 | 与本行同一 AEX-00 提交；提交主题 `test: freeze AEX-00 extension fixtures and add foreground verification` |
| AEX-01 | 有效 RED：`aex01-desktop-red` 为固定外部 probe 调用缺失 `Source.getMangaUpdate`；`aex01-art-page-red3` 为真实 ComicFury 缺失 Android Uri 形态 Page 构造器；`aex01-review-image-override-red` 为零偏移新重载绕过旧 override；`aex01-art-final5/6` 与 `aex01-network-red` 为 Android 默认压缩链不兼容。编译错误、测试输入错误与中间绕过诊断不计产品 RED | 主模型最终 `aex01-main-final-verification` exit0（2026-09-13 11:46:58 UTC）：Source API JVM 17、Android release unit 17、core Android network 2、Desktop 21、API36/x86_64 ART 4，共 61 项，0 failure/error/skip；三个相关模块 spotlessCheck 通过，git diff --check 通过。此前模块完整 `aex01-source-phase-final` 两 target 各 19 项通过 | C5、A1–A4 与本批 C4 经首审及一次定向修复复验通过；仅 API/加载组件，不包含版本准入和安装整链 | 日志 `.gradle-coordinator/aex01-main-final-verification.log/.json`；各模块 test-results XML 与 Android connected/debug XML。7 个本批固定 APK/JAR 大小/hash 复核未变；Page Android Uri/JVM Object、旧 Authors 页图和 child-first 回归保留；详情见第 9 节最终复验 | 测试、production 与本次 checkoff 随同一功能提交；提交号见本文件 git 历史 |
| AEX-02 | 接续新增真实 RED 覆盖目录跟随/二进制响应/有界读取、显式地址、失败快照、不兼容与所属仓库判断；复审另发现 protobuf/JSON 判别、未知 meta、改名缓存及指纹等价缺口，均已修复。继承的先改后测和测试装配错误不改写为产品 RED | 主模型核验原始 XML：domain JVM 424、Android 359；data JVM 136、Android 10；app focused 50；Desktop focused 81；ART 3，共 1063 项，全部实际执行且 failure/error/skip=0。平台证据 `aex02-final-green-art1`（该轮 data 尚失败）；data fixture 修正后 `aex02-final-data-fixtures-green1` exit0，1m4s。app/domain/data/i18n 无 hook 真实 spotlessCheck 与 git diff --check 通过；Desktop 无 Spotless 插件，人工检查受影响格式，不声称执行不存在的 task | 共享协议一次独立审查及一次定向修复复审通过；主模型独立核对平台、存储、备份、UI、连带 fixture 差异及最终日志/XML。C6 和本批 C4 通过 | 专用 `emulator-5580` / `mihon-aex-api36` / API36 x86_64；上述日志位于 `.gradle-coordinator/`，XML 位于各模块 test-results 与 app connected/debug。Android loader 1.6 准入尚属 AEX-04；Desktop 失败警告沿用即时投影，不新增持久状态机 | 测试、production、必要迁移 fixture 与本次 checkoff 同一功能提交，hash 见本文件 git 历史 |
| AEX-03A | `aex03a-discovery-red2` 为 Android 真实 manager 漏 Source-only；`aex03a-query-red` 为 Android 筛选项应 1 实为 0、Desktop 真实浏览页从旧投影找不到源。首轮泛型编译错误、MockK getter 混淆和 ART Proxy 参数错误均为装配问题，不计产品 RED | `aex03a-validation` exit0：domain JVM 46、Android 6，app 23，Desktop 136，ART 4，相关 app/data/domain 格式通过。定向 `aex03a-review-verification` 中 Desktop 2 类 5 项通过，整轮因 ART 装配失败为 exit1；最终 `aex03a-art-final` exit0，真实数据库导航及模式按钮对照在内 ART 6 项通过，app 格式和 debug 编译通过。各最终目标 failure/error/skip=0；主模型核验原始日志和当前 XML，未重复计算替代轮次 | 主模型一次独立审查及定向复验完成；补齐两端 latest 入口可见性和 Android 真库结果导航，不接受固定 ID Proxy 作持久化证据。C7 和本批 C4 通过 | Android API36 x86_64、专用 emulator-5580。Desktop 离屏真实导航/HTTP parser、Authors、本地标记和旧源查询回归通过；不冒称本地磁盘重验或完整 APK 安装。日志 `.gradle-coordinator/aex03a-*.log/.json`；共享新契约 `tachiyomi.domain.source.service.SourceOnlyQueryContractTest` 在 domain JVM/Android 两 target 执行 | 测试、production、必要 UI 提取与 checkoff 同一功能提交，hash 见本文件 git 历史 |
| AEX-03B | — | — | — | — | — |
| AEX-04 | — | — | — | — | — |
| AEX-05 | — | — | — | — | — |
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
| C4 / 全批次 | 审核 diff、红绿日志、未提交状态和平台行为保留 | 仅授权文件；无用户数据/签名绕过/无关重构/捐赠或遥测引入；行为改动均有真实 production/wiring 测试；待验收项不勾选；模型和目标隔离如实报告 | 主模型检查实际 diff 与定向测试；本仓库规则和实际目标工具结果；历史 Luna 要求按后续用户取消指令处理 | 主模型 / AEX-00 通过（历史目标回执为用户明确豁免）；AEX-01、AEX-02、AEX-03A 通过；AEX-03B 起逐批 pending |
| C5 / AEX-01 | 外部二进制调用新旧 Source API、桥接及加载基础组件 | 真实 ABI 与语义符合固定规范；Android ART/JVM 和 Desktop 契约通过；完整 APK 准入仍不冒称完成 | 第 6 节 ABI 矩阵、各 target focused tests 和 ART 组件测试 | 主模型 / pass（2026-09-13，第 9 节最终复验） |
| C6 / AEX-02 | 从旧/新仓库地址进入两端 production HTTP、数据与 UI 链路，注入成功/异常/冲突 | v2 形式、版本/分级/语言、身份迁移、签名和状态正确；失败保留可用数据；Android 未放行版本不可误装 | MockWebServer、真实数据库迁移/备份恢复、两端仓库及列表集成 | 主模型 / 2026-09-15 通过，见第 8 节 |
| C7 / AEX-03A | 受控 Source-only 与旧源通过真实 manager、查询、ScreenModel 和导航 | 源可见，浏览/搜索/分页/筛选/最新能力/取消/错误反馈符合契约；无类型强转回归 | 双平台共享查询场景、真实 UI/DI/导航与 HTTP 集成 | 主模型 / 2026-09-15 通过，见第 8 节 |
| C8 / AEX-03B | 详情/后台作业更新，持久化后重建服务，生产备份恢复再调用并阅读/下载 | flags/次数/章节同步正确；漫画/章节 memo、进度、自定义数据与下载关联保真；失败不静默成功 | 双平台更新契约、真实 SQL 迁移、生产 backup/restore、Reader/下载集成 | 主模型 / pending |
| C9 / AEX-04 | 正常签名真实 APK 经系统/私有安装、信任、loader、源注册到全部业务及重启 | 1.6 真正可用；错误版本/签名/损坏/拒绝/取消安全反馈；不靠 Source 注入或 shell 权限替代普通用户流程 | Android ART 设备测试、JVM 安全生命周期及普通权限操作证据；Desktop 共享回归 | 主模型 / pending |
| C10 / AEX-05 | 旧版本数据升级、跨签名备份迁移与断网/部分失败/中断恢复 | 旧用户状态保全、错误可区分和恢复；选定真实站点有实际 production 验收，缺失不伪报 | 固定旧数据 fixture 的 E2E 与有界真实联网；测试设备和可用外部站点 | 主模型 / pending |
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
