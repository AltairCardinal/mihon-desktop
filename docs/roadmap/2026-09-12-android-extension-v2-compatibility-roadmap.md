# Android 新版扩展系统完整兼容 Roadmap

- 日期：2026-09-12
- 状态：**PLANNED，仅完成规划，尚未授权实施**
- 父计划：[Android / macOS / Windows 正式 Roadmap](./2026-06-30-mihon-desktop-refactor-roadmap.md)
- 专项代码基线：`6d6263dcfeffdfbd5f810a54fa7a3d24c0a6ba50`
- 协议对齐基线：Mihon `v0.20.4`，实际 commit `df6507256acce8e7f3660783a3db6dbd1a31b6b5`

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
- AEX-01 可以先建立 ABI 与兼容桥，但 Android 1.6 安装/加载支持开关须在 AEX-03 的调用方与数据链完成后，才在 AEX-04 放开，避免出现“可安装但不能用”的中间交付。

### 3.4 更新、memo 与数据保全

- 新版统一更新必须传入正确的既有章节及 fetchDetails/fetchChapters flags；按冻结规范处理返回值，复用本仓库已有同步、去重、进度和下载保留逻辑。
- 漫画/章节 memo 按 JSON 数据保真传递，不重新解释第三方内容；覆盖新增、更新、空值、重启、备份恢复及再次调用。不把 memo 或其中潜在凭据写入普通日志。
- 新字段由现有共享 SQLDelight schema 与备份格式演进承载；保留旧备份读取能力，明确新版备份回到旧应用可能损失新字段。禁止声称任意数据库降级可逆。
- 不因新版调用合并而覆盖用户自定义元数据、误清章节阅读状态、重排下载对应关系或改变既有 Source ID。

## 4. 与现有计划的交接

- [非 Reader 上游核心路线图](./2026-08-02-mihon-desktop-non-reader-upstream-core-roadmap.md) 保持 PAUSED；本专项只承接 `EX-01` 中 Android v2/1.6 所必需的共享协议、安装状态与真实调用切片，不代表 `EX-01`、`BR-01` 或整条非 Reader 主线完成。
- 不等待完整 BR-01 大重构才修复兼容性：AEX-00/AEX-03 在现有查询边界中冻结本专项必需的 Source 接口与 adapter，后续 BR-01/EX-01 必须复用这些实现和 fixture，不能再维护另一套规则。
- [Reader adapter 重构计划](./2026-08-27-desktop-reader-upstream-semantics-adapter-refactor-roadmap.md) 不重新打开；仅回归其真实 source 输入和 reader/downloader 链路。发现无关 Reader 缺陷另行记录，不扩大本专项。
- 父计划本次继续 `active-child-plan: none`。用户授权实施时，才在首个工作批次中激活本子计划；本专项的 Android 实际发布和设备验收属于必要门槛，不适用父计划早期“Android 发布延期不阻塞”的方针。
- `app-desktop/src/test/resources/parity/parity-manifest.json` 仍是 capability 机器权威。实施时仅针对实际受影响条目补充可复现证据；未验收项不得标为 VERIFIED，不批量改写历史状态。

## 5. 有序交付批次

默认串行执行；表中估算按一名开发者、已有构建环境计算，不包含外部签名、设备、网络和 macOS 资源等待。总计约 **10–19 个工程日**，AEX-00 后根据 ABI 实测收窄估算。文件数/行数只作审查提示，不据此机械拆分不可独立验收的功能。

| 进度 | 批次 | 可独立验证的结果 | 前置 | 估算 |
| --- | --- | --- | --- | --- |
| [ ] | AEX-00 协议与验收基线 | 不可变的协议、二进制 fixture 和发布验收矩阵 | 本计划获准实施 | 0.5–1 日 |
| [ ] | AEX-01 Source ABI 与旧版桥接 | 新旧扩展二进制可在共享 API/平台 adapter 上执行契约 | AEX-00 | 2–4 日 |
| [ ] | AEX-02 共享仓库协议与迁移 | Android/Desktop 使用同一正确 v2 catalog，旧地址和仓库数据可迁移 | AEX-00、AEX-01 | 2–3 日 |
| [ ] | AEX-03 新 Source 生产调用与状态 | 浏览、搜索、更新、阅读、下载及 memo 数据链闭合 | AEX-01、AEX-02 | 2–4 日 |
| [ ] | AEX-04 Android 安装与扩展管理 | 新 APK 安全安装/更新/信任/重启可用，错误状态可操作 | AEX-02、AEX-03 | 1.5–3 日 |
| [ ] | AEX-05 历史升级与故障验收 | 旧用户数据迁移、真实端到端与恢复路径通过 | AEX-04 | 1–2 日 |
| [ ] | AEX-06 正式产物与跨平台收口 | R8 Android、Windows/macOS 发布及完整证据 | AEX-05 | 1–2 日 |

### AEX-00：冻结协议、fixture 与验收环境

- 输入：本节固定 commit、现有权威基线、仓库/Source/安装生产调用点。
- 交付：在本文件第 8 节记录协议条目到生产入口/测试的对应关系；确认支持集合、metadata 冲突规则、索引形式、Source API 差异和所需依赖 ABI，不只抄 release notes。
- fixture：取得固定 commit 对应的 Keiyoushi 索引样本、可合法保留的真实 1.4/历史 1.5/1.6 APK 与 Desktop 对应 JAR；记录来源、版本、包名、哈希、签名和许可。不能把可变 live URL 作为 CI 唯一输入，也不将 APK blob 无限制塞进仓库。
- 另备基于冻结外部 API 独立构建的受控扩展：Source-only、SourceFactory、多语言、分级、memo、getMangaUpdate flags、错误路径；包含对应 APK/JAR。它必须执行生产 loader，不在 host 测试内重新定义接口来掩盖 ABI 差异。
- 确认 Android 最低/当前支持系统、代表性 ABI、现有 androidTest runner、SDK、可用设备/模拟器、release variant/R8/signing 配置和已有 app ID/versionCode。核对测试设备身份，不卸载或覆盖用户主实例。
- 输出基线只记录已测事实；当前手机版本未知不阻塞制定 fixture，但真实“从用户版本原地升级”须取得其版本及安装身份后验证。
- 验证：先运行最小现有相关用例确认环境；新增行为测试在所属实现批次进入 RED，不能在本阶段以文本扫描充当行为测试。缺任一不可替代 fixture/设备/签名时记录具体门槛与替代验证能证明的范围。
- UI：本批次无新增 UI，只冻结后续入口与反馈矩阵。

### AEX-01：补齐共享 Source ABI 与兼容 adapter

- 范围：source-api、必要 core/network 依赖与平台 adapter；复用现有模块，不另建 Android/桌面接口副本。
- RED：外部编译 1.6 fixture 在当前 host 上暴露缺失方法/模型/桥接；分别覆盖 Source-only、HttpSource、SourceFactory、Rx 旧扩展及模型。失败必须来自真实执行，不是源码字符串断言。
- GREEN：按冻结 API 补齐 Source 查询、SMangaUpdate/getMangaUpdate、模型 memo、默认实现和旧接口桥接；核对共享/平台 API 可见性和扩展实际调用的依赖符号。只升级必要依赖。
- 重构：将新旧分派收敛到一处；为不可删除的旧 ABI adapter 留下原因和测试。AEX-03 结束时删除只为迁移调用方设置的临时桥，保留真正面向旧扩展的兼容桥。
- 验证：共享契约在 Android/Desktop 对应 target 执行；真实 Android loader/ART 与 Desktop production loader 执行外部 fixture；ABI 失败、构造失败和源初始化失败都有可识别诊断。
- 关闭条件：接口和 adapter 可执行，新旧扩展契约通过；尚不对用户开放 Android 1.6 安装能力，不宣称阅读链已完成。
- UI：无独立新入口；保护既有源列表与错误反馈，不改变 Desktop Authors 扩展接口。

### AEX-02：共享 v2 store/catalog、仓库 CRUD 与持久化迁移

- 范围：既有 domain/data extensionrepo、catalog、共享 DTO/decoder 与两端网络 adapter；仓库设置页和现有添加仓库 deeplink。
- RED：旧 JSON、v2 JSON/protobuf、压缩/非压缩、内联/远程列表、未知字段、缺失字段、MIXED、多语言、显式 extensionLib 与 versionName 不一致等契约；原始 HTTP 到领域对象完整执行。
- 同时覆盖：403/429/500、超时、取消、空/畸形响应、签名不符、失败不丢旧 catalog、多个仓库部分失败、地址归一化/去重/删除、旧数据库迁移和重启。
- GREEN：共享解析与 store 定位；Android `ExtensionApi` 和 Desktop `DesktopExtensionApi` 消费同一结果。保存新版索引定位及必要 store 元数据，映射实际 APK URL 和 Desktop JAR 资源，版本/分级/语言按规范判断。
- 仓库字段涉及备份时同步调整现有备份/恢复映射；数据迁移由共享 SQLDelight schema 单点维护。
- UI：现有“扩展仓库”入口支持粘贴旧/新地址及 deeplink；添加结果、重复仓库、签名冲突、刷新失败都有反馈。Android 未完成 AEX-04 前，1.6 可在目录中明确显示“当前运行时不兼容”，不能假称可安装。
- 重构：删除两端重复 catalog 协议判断；必要旧 JSON parser 作为共享兼容分支保留。保住 Desktop JAR-first/APK fallback 和现有信任校验。
- 关闭条件：两端从相同 HTTP fixture 得到相同协议决策；Android 不再只请求旧目录；生产 DI/仓库 ScreenModel 与刷新入口均被集成测试覆盖。

### AEX-03：接通新 Source 查询、统一更新与 memo

- 范围：现有 source manager、浏览/全局搜索、远端用例、详情、LibraryUpdateJob、MetadataUpdateJob、章节同步、共享数据/备份与相关 Desktop 调用方。
- RED：Source-only 在源列表可见且可浏览/搜索；不支持 latest 的源正确隐藏入口；分页、筛选、取消、部分源失败与旧 CatalogueSource 行为；移除真实 wiring 后测试必须失败。
- 更新矩阵：仅详情、仅章节、二者同时、不请求未选内容、空/异常返回、已有章节顺序、重复同步；memo 首次返回 → 保存 → 重启 → 再次传入 → 备份/恢复后再调用。
- GREEN：将产品查询门槛从旧类型假设调整为真实 Source 能力；共享调用 getMangaUpdate 并复用当前合并/同步规则。避免详情和章节各自重复取数；保留阅读进度、自定义元数据和下载关联。
- Android 与 Desktop、本地源、旧扩展、Authors 等扩展能力都走各自受支持路径；不能用“全部改成 HttpSource”代替适配。
- UI：浏览 → 源 → 热门/搜索/筛选/最新；全局搜索 → 详情；详情刷新、书架更新、元数据更新均有加载/完成/错误状态。阅读器与下载入口沿用现有 UI，错误不能表现为无意义空页或静默成功。
- 验证：共享契约、真实 ScreenModel/DI、MockWebServer、数据库迁移和备份 round-trip；从 fixture 目录到详情、章节、页图、下载的 production 集成。受控 APK/JAR 的结果一致，平台特殊能力有独立断言。
- 关闭条件：外部 1.6 fixture 走通业务和重启数据链；旧源仍可用；不遗留只在测试中可调用、UI/后台作业未接入的新方法。

### AEX-04：Android metadata、安装、信任与管理 UI

- 范围：ExtensionLoader、ExtensionManager、现有 ExtensionInstaller/AndroidInstallPort、共享版本/状态决策及扩展列表/详情/信任入口。
- RED：新旧 metadata、显式协议版本、未来未知版本、名称/分级、SourceFactory、多源；1.6 安装前拒绝/完成适配后可用；历史 1.5 不因连续区间改集合而无声丢失。
- 安全矩阵：首次安装、可信更新、签名冲突、错误哈希/损坏 APK、下载取消、安装拒绝、缺失包、加载失败、卸载和重启；分别覆盖系统/私有安装及现有安装生命周期。
- GREEN：AEX-01/AEX-03 前置验收满足后正式放开 1.6；复用现有 coordinator、信任和签名能力，不另建安装引擎。发现兼容版本但加载失败时保留可诊断结果。
- UI：扩展列表显示可安装/已安装/有更新/不受信任/不兼容/确实废弃的正确状态；安装/更新/信任/卸载有进度、结果及必要确认。失败可查看原因并重试；不诱导“卸载全部再试”。
- 回滚边界：下载/校验/提交前失败不得损坏可用旧版本；私有安装沿用安全替换恢复。系统安装已经成功提交后的降级受 Android 限制，不能保证自动回滚；此时明确反馈并提供安全恢复指引。
- 验证：真实 PackageManager/安装路径、ART 加载、重启与签名检查；JVM 安全/生命周期测试加设备证据。Desktop 同步回归共享信任/更新决策，保持每个 JAR payload 的真实性校验。
- 关闭条件：新版目录项能经真实生产入口安装并执行 AEX-03 工作流；版本放行、安装成功、加载成功、业务成功分别有证据。

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

### 本次规划预算与产物

- 技能：无专门技能触发；按仓库文档与源码审查执行。
- 固定步骤：读现有计划/架构与源码 → 对照固定上游 → 编写本计划并添加父计划/EX-01 入口 → 一轮只读核验 → 文档提交。
- 子代理：0；无并行实现或独立 agent 审查。
- 审查：1 轮计划只读核验；验证：文档结构/链接 1 次、git diff 1 次。产品 focused tests、全量测试、构建均为 0，因为本次不改产品行为。
- 交付物：本 roadmap；必要衔接修改仅限父计划与 EX-01。过程产物：不另建计划、报告、逐文件快照或大型 diff 包。
- 预计墙钟：10–20 分钟；主要成本是协议/代码对照与计划撰写。网络不可用时标注未核实内容，不把实现或环境安装加入本轮。

### 未来实施预算

- 每个有行为变化的批次按 RED → 最小 GREEN → 重构 → 相关测试/格式 → 1 轮独立审查 → 必要时至多 1 轮修复复审 → 提交执行。执行前另报该批次流程预算；本计划不是无限测试、审查或子代理授权。
- 默认单工作流，重型 Gradle 串行；不为占槽启动子代理。如后续确有不共享写入的独立验证工作，届时说明成本并遵循现有授权上限。
- 红绿循环只跑当前 focused tests；批次末跑相关单元、集成、wiring 和格式检查；阶段末跑对应模块完整测试。最终全量 Android/Desktop 与正式平台验收集中在 AEX-06，各一轮，不在每个批次重复。
- 测试失败允许在同一行为范围修复；若新增未预算的迁移、依赖大升级、工具安装或产品范围，应先说明证据、成本与替代方案，取得新方向后推进。
- 一功能批次原则上一个提交，含测试、production、必要证据与 checkoff；审查修复至多增加一个。保护用户已有改动，不混入无关文件。

现有测试入口优先扩展，而非另建相同测试框架：

| 领域 | 现有入口 | 本专项增加的实际断言 |
| --- | --- | --- |
| 仓库/目录 | ExtensionRepoServiceContractTest、Android/Desktop ExtensionApiSharedCatalogTest | 完整 v2 形式、metadata 映射、刷新/迁移、安全失败和 production wiring |
| 安装 | ExtensionInstallCoordinatorWiringTest、ExtensionInstallSessionLifecycleTest、AndroidExtensionInstallSecurityRollbackTest | 1.6 metadata、真实 APK/ART、系统与私有安装的安全边界 |
| Android UI/来源 | AndroidSourceManagerInitializationTest、SourceSharedQueryWiringTest、BrowseSourceScreenModelBehaviorTest、ExtensionPresentationWiringTest、ExtensionReposScreenModelWiringTest | Source-only、安装状态、仓库输入和实际 ScreenModel/DI |
| Desktop artifact | DesktopExtensionArtifactAuthenticityTest、DesktopExtensionApiSharedCatalogTest、既有真实加载 fixture | JAR-first/旧 APK、签名与共享 Source ABI 不回退 |
| 新增必要覆盖 | 外部编译 1.6 ABI fixture、更新 flags/memo 契约、Android release/device 集成 | 在 AEX-00 确定真实 runner/模块后新增；目前不声称这些测试已存在或通过 |

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
| AEX-03 | — | — | — | — | — |
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
