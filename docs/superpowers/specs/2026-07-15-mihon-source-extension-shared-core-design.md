---
comet_change: align-sources-extensions
role: technical-design
canonical_spec: openspec
---

# Mihon 源与扩展共享核心技术设计

## 目标

让 Android 与 Desktop 在源列表、单源浏览、全局搜索、扩展发现、版本判断、安全信任、安装/更新事务和错误反馈上使用同一套业务语义。原始 Mihon 语义的唯一 authority 是 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`；当前 `app/` 只是 fork 后的 Android consumer，shared 代码只是迁移输出，二者都不能自证为 authority。Desktop 只保留操作系统与 JVM 强制不同的适配：扩展目录、ClassLoader、APK→JAR、浏览器会话、文件工具和系统网络集成。

本次迁移保留 Desktop 已有的宽屏布局、APK→JAR、扩展文件信息/定位、键鼠交互、FlareSolverr 显式后备和 Test Mode。它们作为共享核心之上的产品增强存在，不得反向进入共享业务规则。

## 现状与复用判断

### 可直接复用

- `SourceMangaSearchService` 已位于 domain common，能够统一 popular/latest/search 的源调用。
- `AppError` 已提供跨端错误模型，网络层已有 403、429、服务端错误和解析错误映射基础。
- `ExtensionRepoRepository`、`ExtensionRepoService` 与仓库模型已位于 domain common。
- Desktop 已有 `ApkToJarConverter`、`DesktopExtensionLoader`、`DesktopCookieJar`、原子文件替换、扩展 metadata、FlareSolverr client 和现有 UI 入口。
- 固定 main 中的 `ExtensionApi`、`ExtensionManager`、`ExtensionLoader`、Sources/GlobalSearch/Extensions ScreenModel 提供原始行为与 fixture 来源；当前同名 Android consumer 必须另行比较、分类差异。

### 应抽取后共用

- 源查询的加载/空/成功/失败状态、分页游标和错误到用户动作的映射。
- 扩展目录条目、兼容性结果、安装任务状态、信任判断、事务编排与回滚结果。
- 仓库刷新中的逐仓库成功/失败聚合，避免 Desktop 目前把异常吞成空列表。
- 源 preference schema 的平台无关描述；Android PreferenceFragment 与 Desktop Compose 只负责渲染。

### 必须独立适配

- Android：PackageManager、PackageInstaller、签名读取、WebView/CookieManager。
- Desktop：文件目录、JAR ClassLoader、DEX→JAR 转换、浏览器会话、Explorer/Finder、OS cookie 交接。
- 这些 adapter 不决定版本是否可更新、错误如何分类或事务何时完成。

## 方案比较

1. **共享业务核心 + 薄平台适配器（采用）**：业务规则只有一份，平台差异通过接口注入；改动较广，但直接清除目标技术债。
2. **共享 façade、保留两套 manager**：可以快速统一 UI 类型，却仍让版本、安全与回滚规则漂移，不能满足“完全向原版对齐”。
3. **直接移植 Android manager**：会把 Android 包管理和 WebView 假装成通用 API，兼容层成本高且无法在 Desktop 上真实验证。

## 架构

### 1. 共享源查询核心

在 domain common 扩展现有 `SourceMangaSearchService`，形成以下稳定边界：

- `SourceQuery`：Popular、Latest、Search(query, filters)。
- `SourcePageRequest`：sourceId、query、page、generation；generation 用于丢弃旧请求结果。
- `SourcePageResult`：Content(items, hasNextPage)、Empty、Failure(AppError, recoveryAction)。
- `SourceRecoveryAction`：Retry、OpenLogin、OpenSettings、InstallExtension；只表达动作，不导航。
- `SourceCatalogState`：加载中、成功项、逐源/逐仓库错误、刷新状态。

Android 与 Desktop adapter 都把真实 `CatalogueSource` 传给同一服务。UI ScreenModel 负责取消旧 generation 和组合展示状态，不再自行决定分页终止、空列表或异常字符串。

多仓库刷新采用部分成功语义：成功仓库的目录继续显示，失败仓库保留 `AppError` 和重试入口。只有所有仓库都成功且条目为空时才显示真正空状态。

### 2. 共享扩展目录与兼容性

共享模型不持有 `PackageInfo`、`File` 或 `ClassLoader`：

- `ExtensionArtifact`：packageName、versionName/code、libVersion、language、NSFW、source descriptors、repository identity、declared digest、download location。
- `InstalledExtensionRecord`：packageName、version、origin、repository identity、artifact digest、installed timestamp、source descriptors。
- `ExtensionCompatibility`：Compatible、UnsupportedLib、MissingPlatformApi、CorruptArtifact、UntrustedArtifact，并携带稳定诊断字段。
- `ExtensionCatalogEntry`：Available、Installed、UpdateAvailable、Installing、Failed、TrustRequired。

Android `ExtensionApi` 与 Desktop `DesktopExtensionApi` 的 index DTO 解析迁到共享 serializer/mapper；两端只提供 HTTP body 和平台安装 adapter。版本范围、更新可用性和错误分类不再重复。

### 3. 共享安装事务

`ExtensionInstallCoordinator` 编排以下阶段：

1. `Prepare`：下载到唯一临时位置，记录任务与取消令牌。
2. `Validate`：校验 ZIP/APK/JAR 结构、package、lib/API 兼容性、仓库身份和摘要。
3. `Commit`：由平台 adapter 原子提交候选产物，但保留旧产物与 metadata 快照。
4. `Reload`：平台 loader 加载候选并至少解析一个声明 source；若扩展声明零 source，则按 fixture 中的合法类型验证。
5. `Complete`：写入新 metadata，删除快照并发布 Installed。
6. `Rollback`：任何提交后失败都恢复旧产物和旧 metadata，重新加载旧版本后发布 Failed；恢复本身失败则产生单独的 `RollbackFailed` 高优先级错误。

平台接口分为 `ArtifactPreparer`、`ArtifactValidator`、`ArtifactCommitter` 和 `ExtensionRuntimeLoader`，便于在 JVM 测试用临时目录与 fake loader 精确制造每个失败点。Desktop 的 APK→JAR 是 Prepare/Validate 之间的转换步骤；Android 的 PackageInstaller 是 Commit adapter。

并发规则：同一 package 同时只能有一个事务；更新全部可并行处理不同 package，但结果逐项发布。取消在 Commit 前删除候选并结束，在 Commit 开始后等待原子提交完成并立即回滚，避免半安装。

### 4. 信任与安全边界

原始兼容核心与安全增强必须分层报告。固定 main 提供版本/更新、已安装/可用/不受信任展示、安装/更新/取消/trust 基础，以及 Android 签名和 PackageInstaller 语义。仓库 fingerprint 连续性、声明与下载 SHA-256 连续性、snapshot、rollback、runtime restore 是保留的跨平台安全/可靠性增强，不得声称固定 main 已有；结果表述为“原始兼容核心 + 跨平台安全增强”。

信任检查依次使用：

1. 仓库配置中的身份/公钥指纹；
2. index 声明摘要（如果仓库提供）；
3. 下载后实际 SHA-256；
4. 已安装记录的仓库身份与来源连续性；
5. Android 平台可用时的 APK 签名结果。

Desktop 没有 APK 签名证据时只声明“仓库身份与摘要连续”，不得显示“签名已验证”。旧 sidecar 缺少身份时进入一次性 TrustRequired 迁移，用户能看到旧来源、incoming 来源和摘要；拒绝后旧版本保持可用。

parity provenance 使用结构化 completion gate：固定 `upstreamRef`、逐项 `upstreamSymbols(path, symbol)`、显式 `sharedImplementationPaths`、逐项存在的 `currentAndroidConsumerPaths` / `desktopConsumerAdapterPaths`，以及逐项带允许 classification 和说明的 `deviations`。旧 `authoritativeImplementation` / `desktopImplementation` 只兼容既有读取方，不参与 authority 判定。

### 5. compat stub 治理

建立机器可校验清单，每个 compat API 必须记录：

- API 符号；
- 真实代表性扩展 fixture 与版本；
- 加载/调用测试；
- 对应 JVM 实现或明确 unsupported 结果；
- 删除条件。

架构测试扫描 compat 包的 public surface，清单外新增符号直接失败。没有真实扩展调用且没有产品保护测试的 stub 在确认无生产引用后删除。QuickJS 或 Android-only AAR 不通过堆叠空 stub“支持”，而是显示明确兼容性错误。

### 6. 浏览器登录与 Cookie 回传

共享层定义 `SourceLoginRequest`、`SourceLoginState` 与 `AuthenticatedSession`。Desktop `BrowserLoginAdapter` 负责打开受控浏览器会话、观察目标域 Cookie，并在以下任一条件结束：

- 获取到要求的 Cookie/登录完成信号：一次性提交到 `DesktopCookieJar`，随后重试原请求；
- 用户取消：返回 Cancelled，不写 Cookie；
- 超时：返回 TimedOut，保留可重试入口；
- 浏览器不可用：显示手动 Cookie 导入与可选 FlareSolverr 后备。

FlareSolverr 不自动启动、不静默接管正常请求。用户必须在设置中启用且在失败界面显式选择后备。Cookie 日志、诊断复制和 Test Mode 状态都必须脱敏。

## UI 与用户可见行为

### 源列表

- 入口：Browse → Sources。
- 加载：保留现有内容并显示刷新进度，不用全屏空白替换。
- 空状态：仅在所有已启用仓库/扩展成功且确无 source 时显示；提供“管理扩展”。
- 错误：逐仓库/逐 source 显示原因与 Retry；403 可提供 Login，缺扩展提供 Install。

### 单源浏览与全局搜索

- 页面展示共享 Content/Empty/Failure；翻页失败保留已加载内容。
- 新查询取消旧 generation，晚到结果不得覆盖当前查询。
- 403/挑战错误提供“登录/验证”，429 显示可重试提示，解析错误显示可复制的脱敏诊断。

### 扩展列表与详情

- 显示已安装、可更新、安装中、失败、信任确认和不兼容状态。
- 安装/更新显示阶段和取消；失败显示逐项原因与 Retry，旧版本仍可用时明确说明。
- 详情保留 source 列表、偏好入口、仓库链接、SHA-256、Explorer/Finder 定位和卸载确认。
- 缺权限、只读目录或磁盘不足时不尝试静默降级，显示可执行的修复提示。

### 挑战登录

- 页面或对话框显示目标域、进度、取消、超时与后备选项。
- Cookie 只有成功完成后写入；取消/失败不改变已有会话。

所有本 change 触达的业务文案迁入 i18n；至少基础资源必须完整，其他 locale 按项目既有 fallback 规则运行，但不得在 Kotlin 中新增硬编码业务提示。

## TDD 与验证策略

### RED 契约

- domain：分页成功、空、取消、旧 generation、403/429/500/畸形、多仓库部分失败。
- extension core：版本比较、信任连续性、摘要不符、任务状态、取消、commit/reload 失败回滚、rollback 失败。
- Desktop adapter：JAR、APK→JAR、损坏 ZIP、错误 package、不兼容 API、临时文件清理、metadata 恢复。
- compat：真实 fixture 调用和 public surface 清单。

### GREEN 集成

- MockWebServer 从真实 index/source HTTP 响应到共享领域结果，不 mock parser。
- Android/Desktop manager production wiring 测试，确保没有继续调用旧规则。
- Screen 实例化、Voyager 导航类型、DI 全解析、i18n 缺 key、Test Mode action/state。
- 双轨比较迁移期保持同 fixture 结果一致，切换后删除旧路径与双轨开关。

### 运行时验收

- Android：自行启动 API 36 模拟器，安装当前 ABI APK 与代表性扩展，验收扩展发现/安装、源列表、单源浏览、搜索、错误/登录反馈。
- Windows：只用 `scripts/build-desktop.sh` 生成新 BUILD，启动固定未打包 EXE，验收安装/更新/回滚、源浏览、挑战登录与文件工具。
- macOS：通过 `ssh mbp` 在安全临时 clone 运行相关测试、构建部署 `/Applications/Mihon Desktop.app` 并启动验证平台 adapter。

## 分批迁移与回滚

1. 权威 fixture 与产品保护网。
2. 共享源查询与错误状态，双轨接线后删除 Desktop 查询规则。
3. 共享扩展目录、版本和信任模型，保留旧 installer adapter。
4. 共享事务协调器接入 Desktop/Android adapter，验证 reload 回滚后删除旧编排。
5. 浏览器登录/Cookie 回传与 UI wiring。
6. compat/i18n 去重、parity 更新和跨平台运行时验收。

每批独立提交、独立审查。若共享路径未通过对应平台生产 wiring 与回归测试，该批不删除旧路径；旧路径只作为迁移回滚点存在，不接受新功能。
