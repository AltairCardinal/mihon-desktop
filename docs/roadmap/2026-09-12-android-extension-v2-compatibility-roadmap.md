# Android 新版扩展系统完整兼容 Roadmap

- 日期：2026-09-12
- 状态：**PLANNED，仅完成规划，尚未授权实施**
- 父计划：[Android / macOS / Windows 正式 Roadmap](./2026-06-30-mihon-desktop-refactor-roadmap.md)
- 专项代码基线：`6d6263dcfeffdfbd5f810a54fa7a3d24c0a6ba50`
- 协议对齐基线：Mihon `v0.20.4`，实际 commit `df6507256acce8e7f3660783a3db6dbd1a31b6b5`

2026-09-12 审阅调整：解除早期组件测试对 AEX-04 安装/版本放行的反向依赖；AEX-03 拆为查询与更新数据闭环两个行为批次；AEX-00 增加可执行测试矩阵的强制关闭门槛。仍仅规划，未开始实现。

本文件是本专项唯一计划与进度记录。实施进度仅从第 5 节第一个未勾选的批次推导；不增加 `active-task`。本次不激活父计划、不恢复其他暂停计划、不修改产品代码，也不把规划当成兼容性完成证据。

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
- 父计划本次继续 `active-child-plan: none`。用户授权实施时，才在首个工作批次中激活本子计划；本专项的 Android 实际发布和设备验收属于必要门槛，不适用父计划早期“Android 发布延期不阻塞”的方针。
- `app-desktop/src/test/resources/parity/parity-manifest.json` 仍是 capability 机器权威。实施时仅针对实际受影响条目补充可复现证据；未验收项不得标为 VERIFIED，不批量改写历史状态。

## 5. 有序交付批次

默认串行执行；表中估算按一名开发者、已有构建环境计算，不包含外部签名、设备、网络和 macOS 资源等待。总计约 **10–19 个工程日**，AEX-00 后根据 ABI 实测收窄估算。文件数/行数只作审查提示，不据此机械拆分不可独立验收的功能。

保留 7 个宏观阶段，实际有 8 个独立验收/提交批次：阶段 03 仅由 AEX-03A、AEX-03B 两行表示，不再设置另一条 AEX-03 勾选项。两批合计仍使用初稿的 2–4 日粗估，不因拆分宣称工作量减少；测试基础设施成本在 AEX-00 核实后重新评估。

| 进度 | 批次 | 可独立验证的结果 | 前置 | 估算 |
| --- | --- | --- | --- | --- |
| [ ] | AEX-00 协议与验收基线 | 固定 fixture、可执行测试矩阵和已验证的 runner | 本计划获准实施 | 0.5–1 日 |
| [ ] | AEX-01 Source ABI 与旧版桥接 | 外部二进制执行真实 API/加载基础组件契约；不含 APK 版本准入整链 | AEX-00 | 2–4 日 |
| [ ] | AEX-02 共享仓库协议与迁移 | Android/Desktop 使用同一正确 v2 catalog，旧地址和仓库数据可迁移 | AEX-00、AEX-01 | 2–3 日 |
| [ ] | AEX-03A Source 查询与导航 | Source-only 的源发现、浏览、搜索及 UI wiring 可用 | AEX-01、AEX-02 | 0.5–1.5 日 |
| [ ] | AEX-03B 统一更新与 memo | 更新、持久化、重启、备份恢复及阅读/下载数据传递闭环 | AEX-01、AEX-03A | 1.5–2.5 日 |
| [ ] | AEX-04 Android 安装与整链集成 | 1.6 APK 经版本/信任准入、安装加载、源注册到业务流程全部通过 | AEX-02、AEX-03A、AEX-03B | 1.5–3 日 |
| [ ] | AEX-05 历史升级与故障验收 | 旧用户数据迁移、真实端到端与恢复路径通过 | AEX-04 | 1–2 日 |
| [ ] | AEX-06 正式产物与跨平台收口 | R8 Android、Windows/macOS 发布及完整证据 | AEX-05 | 1–2 日 |

### 测试边界与依赖规则

- AEX-01 测真实 Source API、兼容桥及 production 使用的加载基础组件。Android 可执行受控外部二进制的 ART 组件测试；直接调用这些组件不等于已经验证 PackageManager、版本准入、信任或完整 ExtensionLoader。
- AEX-03A/03B 通过既有依赖注入/平台 port 提供受控 Source，执行真实 SourceManager、ScreenModel、用例、网络解析、数据库和备份实现。允许控制外部源响应，不允许 mock 被验收的 parser、更新用例、持久化或 UI wiring；这些批次不以成功安装 1.6 APK 为前提。
- AEX-04 用未改写 metadata 的签名 APK，通过正常安装/信任/加载入口注册源，再串联 AEX-03A/03B 的业务场景。前期组件测试和后期整链测试必须分别记录，不能互相替代。
- AEX-05 复用已通过的整链场景加入旧版本数据及故障恢复；AEX-06 在最终发布产物上回归。两者不是首次补做早期批次应有的业务测试，也不是每批都重跑全部 E2E。
- AEX-02 暂保持一个批次：地址识别、store 身份、信任连续性、持久化迁移与两端 catalog wiring 共同完成同一仓库能力，不先交付未被产品使用的 parser。其内部按测试矩阵逐组红绿，不按测试类拆提交。

### AEX-00：冻结协议、fixture 与验收环境

- 输入：本节固定 commit、现有权威基线、仓库/Source/安装生产调用点。
- 交付：将第 6 节测试归属表具体化为本文件内的可执行矩阵；逐项填写“行为 → 生产入口 → 现有/拟新增测试类与 source set → 所属批次 → 首个预期失败原因 → Gradle task/filter 或设备 runner → 通过标准”。确认支持集合、metadata 冲突规则、索引形式、Source API 差异和所需依赖 ABI，不只抄 release notes。
- fixture：取得固定 commit 对应的 Keiyoushi 索引样本、可合法保留的真实 1.4/历史 1.5/1.6 APK 与 Desktop 对应 JAR；记录来源、版本、包名、哈希、签名和许可。不能把可变 live URL 作为 CI 唯一输入，也不将 APK blob 无限制塞进仓库。
- 另备基于冻结外部 API 独立构建的受控扩展：Source-only、SourceFactory、多语言、分级、memo、getMangaUpdate flags、错误路径；包含对应 APK/JAR。记录组件调用方式与完整 APK 安装方式，后者在 AEX-04 执行生产 loader。本批次只要求 fixture 来源/构建可核验、测试入口可运行，不要求当前尚不支持的 1.6 APK 已加载成功；不在 host 测试内重新定义接口来掩盖 ABI 差异。
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

### 测试归属与可执行矩阵

下表是测试责任分配，不是已经通过的测试记录。拟新增测试名称仅为计划名称，不声称文件或 task 已存在。AEX-00 必须把每行具体化到 source set、准确 task/filter 或设备 runner、fixture、预期 RED 原因及通过标准；一个行为的首次实现/RED/GREEN 只归一个批次，后续批次记录更高层集成或回归。

| 行为 / 所属批次 | 被测生产入口与边界 | 测试落点及复用 / 拟新增套件 | 首个预期 RED 与通过标准 |
| --- | --- | --- | --- |
| Source ABI / AEX-01 | source-api、旧版桥接、production 加载基础组件；不含 APK 准入整链 | 拟新增 SourceApiBinaryContractTest：共享契约在 Android/JVM 两个 target 执行；app/src/androidTest 的 ART 组件入口在 AEX-00 确认 | 外部二进制调用真实方法出现 ABI/行为差异；GREEN 时新旧调用结果正确且两平台实际执行，不能以版本拒绝作为 ABI 失败 |
| 目录协议 / AEX-02 | 两端 ExtensionApi → 共享 decoder/catalog → 领域对象 | 扩充 Android/Desktop ExtensionApiSharedCatalogTest；新增共享 v2 协议契约，绑定对应平台网络入口 | 显式 lib、分级、语言、列表或错误映射不符；真实 HTTP 成功/失败矩阵通过且失败保留成功快照 |
| 仓库生命周期 / AEX-02 | deeplink/仓库 ScreenModel → Create/Replace/Delete → HTTP → 真实数据库与备份恢复 | 保留 ExtensionRepoServiceContractTest 的动作映射；拟新增 ExtensionRepoPersistenceIntegrationTest，复用数据迁移测试及两端仓库 UI 测试入口 | 新索引地址不能创建、身份/信任或迁移后定位丢失；旧/新输入、重复、冲突、删除、重启及恢复通过，不能 mock 创建用例或数据库后声称整链通过 |
| 目录兼容状态 / AEX-02 | 平台能力与共享状态 → 列表呈现及安装入口禁用 | 扩充 ExtensionPresentationWiringTest / Desktop 对应呈现测试，绑定真实 catalog 与平台能力 adapter | 1.6 条目被错误过滤或 Android 尚未放行却显示可安装；GREEN 时条目可解释、不可错误操作，Desktop 既有可用状态不退化 |
| Source 查询 / AEX-03A | 真实 SourceManager → 查询服务/ScreenModel → 导航；外部 Source 为受控输入 | 扩充 AndroidSourceManagerInitializationTest、BrowseSourceScreenModelBehaviorTest；拟新增 SourceOnlyQueryContractTest 与全局搜索/导航集成，Android/Desktop 共用场景 | Source-only 被过滤、没有调用源或参数/导航错误；源可见，分页/筛选/可用时最新/取消和失败反馈正确；DI 可解析只是附加检查 |
| 更新与 memo / AEX-03B | 详情/后台作业 → 统一更新/同步 → 数据库 → 备份/恢复 → 再调用及页图/下载消费 | 拟新增 SourceUpdateMemoContractTest 与平台调用方集成；复用 LibraryUpdateJobSharedLifecycleIntegrationTest、数据迁移、BackupCreator/RestorerBehaviorTest 及 Desktop 备份/Reader 测试 | flags/次数错误或任一数据环节丢失 memo/进度；真实存储重开和生产备份恢复后重新调用正确，旧数据不损坏；codec 自身 round-trip 不足以关闭 |
| APK 准入、安装与整链 / AEX-04 | 真实 PackageManager/installer → ExtensionLoader/ART → SourceManager → 查询/更新/阅读/下载 | 扩充 Android 安全/生命周期测试；拟新增 ExtensionV16LifecycleInstrumentationTest，复用现有 SystemExtensionRollbackInstrumentationTest 的设备基础设施 | 真实 1.6 APK 被版本/metadata 拒绝或源注册/业务调用失败；同一签名 APK 经正常产品入口完成全部步骤；无 Source 注入、metadata 改写或信任后门 |
| 历史升级与恢复 / AEX-05 | 旧版本数据/备份进入已完成的产品整链 | 拟新增 ExtensionUpgradeWorkflowTest；复用 AEX-04 场景加入升级 fixture、故障与恢复 | 新发现行为缺陷归属到相应组件先补 RED；随后升级/中断恢复全流程通过，独立记录旧数据版本及结果 |
| 发布产物 / AEX-06 | 最终 R8 APK 与正式 Windows/macOS runtime | Android release 设备 runner/人工操作证据；Desktop 既有 Test Mode 和正式构建脚本 | 本批次是发布集成门槛，不虚构必须先失败；若产物失败，定位后对相关行为补 RED 并修复；最终产物与受测 hash/版本一致、必要场景不跳过 |

runner 分层与命令约束：

- source-api 当前未显式配置测试 source set 依赖，不能假定新增契约会自动运行。AEX-00 必须明确最小测试配置、外部二进制资源接入和两端 runner；候选 `:source-api:jvmTest`、`:source-api:testReleaseUnitTest` 必须以实际配置核实，不能把候选名称当成已验证命令。
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
python scripts/gradle-coordinator.py run --key aex-catalog -- .\gradlew.bat :app:testReleaseUnitTest --tests eu.kanade.tachiyomi.extension.api.ExtensionApiSharedCatalogTest

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

本节供实施时维护，不创建第二份进度权威。本次证据仅为第 2 节的文档/源码对照；**尚无本专项实现、测试通过、APK 或设备验收结论**。

| 批次 | RED 与失败原因 | GREEN/回归命令和结果 | 独立审查 | 产物/运行环境 | 提交 |
| --- | --- | --- | --- | --- | --- |
| AEX-00 | 待实施；基线批次按实际内容注明适用性 | — | — | — | — |
| AEX-01 | — | — | — | — | — |
| AEX-02 | — | — | — | — | — |
| AEX-03A | — | — | — | — | — |
| AEX-03B | — | — | — | — | — |
| AEX-04 | — | — | — | — | — |
| AEX-05 | — | — | — | — | — |
| AEX-06 | — | — | — | — | — |

关键风险及停止边界：

1. **二进制兼容范围大于表面接口差异**：外部 APK/JAR 实测出现依赖或桥接缺失时，先定位到冻结 API/依赖符号；必要的兼容修复留在本专项。若需要平台/依赖体系重构，先报告估算与范围变化。
2. **数据与备份迁移**：失败必须可重试且不破坏原数据；采用测试数据验证，实际用户迁移前备份。不得在未说明数据兼容边界时承诺旧版本可直接降级打开新数据库。
3. **签名、设备及发布身份未知**：AEX-00 记录真实状态。没有官方私钥不能原地覆盖官方安装；无设备可先做非设备测试，但 AEX-06 不得勾选完成。
4. **外部网络/站点变化**：离线 fixture 证明协议和调用；实时验证证明选定时点、选定站点和产物，不泛化为全仓库所有站点。需要的实时验证无法执行时保留待验收状态。
5. **共享代码影响 Desktop**：必须用同一契约与正式 runtime 回归；不因当前目标是 Android 而跳过 Desktop 安全/ABI/数据验收，也不借机删除其独有能力。
6. **依赖其他暂停计划**：只使用已存在的稳定实现和本专项最小必要接口，不隐式推进非 Reader、Authors 或 Reader 相邻工作。若无法在该边界交付，先说明具体依赖并请求重新排期。
7. **完成标准失真**：catalog 成功、安装成功、类加载成功、业务成功和正式产物成功分别记录；缺任一必需层次，最终状态只能是部分完成。
